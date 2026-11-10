# radiomic: living implementation plan

Status: DRAFT for review (2026-11-10). No code until reviewed.
Working name `radiomic` (rename freely). Public repo, no church-specific data: hosts, tokens, stream names and event data live only in user-made profiles on the device or in the private deployment repo.

Legend: [V] verified from web-radio-relay README at d39f6d9 (code not yet read); [U] unverified, needs a check or experiment.

## 0. Scope decisions (Juha, 2026-11-10)

- MVP = capture + mix + sound library + AAC publish + profiles + operator UI + status. **Speech-to-text and captions are NOT MVP** (no Whisper in MVP). Only an STT interface and the mic-only PCM tap are built in MVP so STT drops in later without redesign.
- Kotlin, native, minSdk 29 (Android 10; needed for `AudioPlaybackCapture`-free design we do not use, but 29 gives reliable `setPreferredDevice` routing and the 14+ FGS rules are simpler to reason about). [U: confirm minSdk 26 vs 29 against the target phone.]
- Android app independent of Pi hardware. Capture, mix, encode and publish live at the phone; HLS, archive, listener delay and caption fan-out stay in web-radio-relay.

## A. Existing architecture assessment

From web-radio-relay README [V]:

| Fact | Consequence for the app |
|---|---|
| Legacy ingest: nginx-rtmp `rtmp://<host>:1935/live/<key>`, key 1-32 chars of `a-z 0-9 _ -`; `on-publish.sh` starts ffmpeg (HLS + MP3 archive). | Stream-name validation regex for profiles: `^[a-z0-9_-]{1,32}$`. |
| Managed lifecycle API: `POST /api/v1/streams/{name}/start`, `/stop`, `GET .../lifecycle`; Bearer `RELAY_API_TOKEN`; relay pulls `RADIO_SOURCE_URL_TEMPLATE` (e.g. `rtsp://mediamtx:8554/{name}`); source URL is never accepted from the client. | Pull path from MediaMTX is the right fit: phone publishes to MediaMTX, relay pulls. The token is admin-grade, so it must never be in the APK. |
| README warns: do not publish via nginx RTMP **and** API-manage the same stream name. | Pick one path per stream name. Recommended: MediaMTX path + managed lifecycle. |
| Captions: `POST /api/v1/streams/{name}/captions`, body `{segments:[{lang,text,startedAt,endedAt?}]}` up to 100, epoch ms on sender clock (NTP-synced with relay), 202. Released to listeners at `startedAt + delay`. No partial/final distinction, no ids, no dedupe key. | Later phase: send finals only, `startedAt` = audio capture time. Gaps listed in section D. |
| Listener delay: `PUT /api/v1/streams/{name}/delay` (0..`DELAY_MAX_SECONDS`, 60), HLS 4 s segments with `PROGRAM-DATE-TIME`; sync good to about a second. | App needs no delay logic; it only needs a correct wall clock (NTP) for caption timestamps. |
| Auth: one Bearer token for `/api/v1/*`, 600 req/min/IP; listener routes public. | No scoped or per-stream credential exists. Control plane needed (section D). |
| OpenAPI 3.1 at `server/openapi.json`; zero-dep CLI generated from it. | New relay endpoints go through openapi.json first. |
| Pi ecosystem: pigateway already pulls captions from an SSE events API and holds token-gated control APIs; MediaMTX on Pi has RTSP 8554, RTMP 1935; the VPS receives the Pi radio stream over a tunnel (project memory). | Do not reuse pigateway for the phone: it lives on the church LAN. The control plane must be reachable from mobile data (VPS). |

Needs verification before design freeze (read code, do not trust README):
1. [U] Is MediaMTX actually deployed on the VPS next to the relay and reachable from the internet? If not, publish target is the relay's nginx-rtmp (legacy path) and the managed lifecycle is not needed.
2. [U] `server/index.js` and `on-publish.sh`: actual stream-name validation, behaviour when the same key reconnects within seconds (HLS discontinuity, archive file split).
3. [U] MediaMTX version, auth method configured (`internal`/`http`/`jwt`), which protocols are open (RTMP/RTMPS, SRT, WHIP).
4. [U] Whether the VPS Traefik exposes TCP (RTMPS/SRT/UDP) at all. Traefik is HTTP-centric; TCP/UDP entrypoints or a direct port on the host are needed.
5. [U] Archive behaviour for a stream that pauses (phone drops) and resumes.

## B. Proposed architecture

```
Phone (radiomic)                                   VPS
 AudioRecord(mic) --+--> [mic tap] --> STT (later)
                    v
 Sound library --> decoder --> Mixer --> Limiter --> AAC (MediaCodec) --> publisher
        |                         ^                                          |
        +--> local monitor <------+ (same PCM, no acoustic loop)             v
                                                                         MediaMTX path <name>
 Control client ---HTTPS---> radiomic-control (new, small)                    |
   1. start session (event PIN / invite)                                      v
   2. receives short-lived publish credential for ONE path          web-radio-relay pulls rtsp://mediamtx:8554/<name>
   3. control calls relay start/stop with the admin token it holds  (HLS, MP3 archive, delay, listener page)
```

Responsibilities
- **App**: capture, mix, encode, publish, profiles, library, UI, (later) STT and caption send.
- **MediaMTX**: ingest and per-path auth. 
- **web-radio-relay**: unchanged lifecycle, HLS, archive, delay, captions.
- **radiomic-control** (new, tiny, on the VPS, own container): holds `RELAY_API_TOKEN` and MediaMTX admin secret; exposes a narrow OpenAPI: create session, refresh credential, end session. Could later be a module inside web-radio-relay instead; decide after reading its code. Keep OpenAPI-first in this repo (`api/control.openapi.yaml`) so either host can implement it.

## C. Technical decisions

### C1. Publish protocol
| Option | For | Against |
|---|---|---|
| RTMP/RTMPS to MediaMTX | Simple, well supported by Android libs, nginx-rtmp-compatible fallback. | TCP head-of-line blocking on bad mobile links; RTMPS needs TLS on MediaMTX; credentials in URL query. |
| SRT to MediaMTX | Built for lossy links, latency tunable, passphrase + streamid. | Needs UDP port open; fewer Android libs; AAC-in-MPEG-TS audio-only edge cases [U]. |
| WHIP (WebRTC) to MediaMTX | HTTPS-friendly, Opus, bearer-token auth via HTTP header, good with Traefik. | Heavier client; Android WebRTC lib size; stats/reconnect more complex. |

Recommendation: **RTMPS to MediaMTX for MVP** (it is the contract in the original brief and the legacy relay speaks it), SRT as phase-2 experiment for bad coverage. [U: confirm RTMPS is exposed.]

### C2. Publisher library
Use `RootEncoder` (pedroSG94, Apache-2.0) RTMP/SRT client for transport and write **our own** capture/mix/AAC stage (`MediaCodec` AAC-LC), feeding encoded frames to its sender. Reason: its stock microphone source cannot take our mixed PCM and would compete for the mic. [U: verify its API allows injecting pre-encoded AAC frames or a custom audio source; fall back to a small own RTMP muxer only if not.]

### C3. Audio format
48 kHz, 16-bit, **mono** (speech-first, halves bitrate), AAC-LC 64 kbps default (profile option 96/128; stereo later for music-heavy events). Single internal rate 48 kHz; library files resampled on decode. Mic captured at 48 kHz where supported, else resample.

### C4. Mixer
- Single mic `AudioRecord` (source `VOICE_RECOGNITION` default, `MIC` option, `UNPROCESSED` where supported; AEC/NS off: no speaker in the mix path) -> ring buffer; one reader for mixer, a second read-only tap for STT later.
- Library playback: `MediaCodec`/`MediaExtractor` decode (mp3, aac/m4a, ogg, wav, flac) -> PCM float -> per-source gain.
- Mix in float, soft limiter (look-ahead 5 ms, -1 dBFS ceiling) before 16-bit conversion; clipping counter exposed to UI.
- Transition policy per profile: `STOP` | `FADE_OUT(ms)` | `DUCK(dB, release ms)` | `KEEP`. Default `FADE_OUT 400 ms`. Mic on => library handled by policy; mic off => mic muted in mix **and tap closed**.
- Mixer clock = output sample count, not wall clock; the encoder is pulled by a single audio thread so mic/library never drift against the stream. Silence is encoded when nothing plays so the stream never stalls.
- Local monitor: the same mixed PCM (or library-only, configurable) to `AudioTrack` on the preferred device. Rule: **never monitor the live mic on the phone speaker**; mic monitoring only on wired/Bluetooth headphones, off by default (feedback and latency).

### C5. Android platform [U: confirm each against target phone]
- Foreground service, type `microphone` (+ `mediaPlayback` for the library), notification with Stop and MIC toggle. Android 14+ requires the type declared and `RECORD_AUDIO` granted before start; 14+ also forbids starting a mic FGS from the background, so the service starts from an on-screen tap only.
- `POST_NOTIFICATIONS` (13+), `WAKE_LOCK`, `WifiLock` n/a (mobile data), request battery-optimisation exemption (educate, do not force).
- Audio focus: request focus for monitoring only; on loss (phone call) mute mic, keep stream alive with silence, show warning, require operator tap to resume mic.
- Bluetooth: SCO/LE headsets give 8-16 kHz mono and add latency; support as "input device" via `AudioManager.setCommunicationDevice` (31+) / `setPreferredDevice`; warn on narrowband. USB mic via `AudioDeviceInfo`. Device list refreshed on `AudioDeviceCallback`.

### C6. STT (post-MVP; plan only)
Abstraction: `interface SpeechEngine { start(lang, sampleRate); fun feed(pcm16: ShortArray, captureTimeNs: Long); val events: Flow<SttEvent> /* Partial | Final(text, startMs, endMs) */; stop() }` fed only from the mic tap (mic-only, never the mix).

Candidates, all [U] until measured on the target phone with Finnish speech:
| Engine | Offline? | Continuous PCM in? | Finnish | Notes |
|---|---|---|---|---|
| `SpeechRecognizer` (default) | Often cloud; `createOnDeviceSpeechRecognizer` (31+) is on-device but needs model per language | **No**: it owns the microphone (competes with our `AudioRecord`). Android 13+ has `EXTRA_AUDIO_SOURCE` to feed a pipe [U] | Language pack availability varies by device/OEM | Cheapest to try; hard to guarantee offline |
| ML Kit GenAI speech recognition | On-device (Gemini Nano class) [U] | Mic or file mode [U] | Language list must be checked; likely limited device set | Device support narrow; verify before relying |
| Vosk | Yes, fully | Yes, PCM streaming | Finnish model exists [U: check size and WER] | Apache-2.0; small RAM; WER weaker than Whisper |
| whisper.cpp (JNI) | Yes | Chunked (not truly streaming); needs VAD segmentation | Good Finnish with `small`/`medium` but slow on phones | MIT; models 75 MB-1.5 GB; CPU/battery heavy. **Out of MVP.** |

Experiment E4 decides: record 10 minutes of real Finnish speech (invented/neutral text, no names committed), run Vosk and whisper.cpp small on the target phone while publishing, compare WER, real-time factor, temperature and battery drain.

## D. API and protocol plan

Existing (use as is): relay lifecycle start/stop/status; `POST .../captions`; delay; listener routes.

Proposed extensions (all OpenAPI-first, nothing implemented yet):
1. **Control plane** `radiomic-control` (or a relay module):
   - `POST /v1/sessions` `{profileHint?}` auth: event invite code (long random, pre-created by an admin, expiring, revocable) -> `{sessionId, streamName, publishUrl, publishCredential, expiresAt, captionToken?}`.
   - `POST /v1/sessions/{id}/refresh`, `DELETE /v1/sessions/{id}` (also stops relay stream).
   - Server chooses/validates the stream name from the invite (client cannot pick arbitrary names, cannot pass source URLs).
   - One active session per stream name (409 otherwise) = duplicate-session prevention.
2. **MediaMTX auth**: per-path publish permission via JWT (`authMethod: jwt`, claim `mediamtx_permissions: [{action: publish, path: <name>}]`, short expiry, JWKS served by control) or `authMethod: http` callback to control. Pick after C1/A.3 findings. Read access stays closed to the public; the relay pulls internally.
3. **Caption endpoint hardening** (later): `POST /api/v1/streams/{name}/captions` currently takes only the admin token. Needs either a per-stream scoped caption token minted by control, or captions relayed via control. Also wanted: optional `id`/`sessionId` for idempotent retries (relay dedupes), documented ordering rule. Propose as relay OpenAPI change, do not invent a parallel protocol.
4. Phone clock: use `SystemClock`/NTP offset check against server `Date` header of control API; warn if skew > 1 s (captions only).

Security rules (non-negotiable): no `RELAY_API_TOKEN` in APK or in profiles; credentials only short-lived and path-scoped; stream names validated client and server; HTTPS/RTMPS only outside dev; credentials in Android Keystore-backed `EncryptedSharedPreferences`/DataStore; profile export strips credentials.

## E. Android application design

Modules (Gradle, Kotlin, Compose):
- `app` UI + DI. `core-audio` (capture, mixer, limiter, resampler, meters, no Android UI deps, JVM unit-testable). `core-publish` (AAC encoder, transport interface `Publisher` with `RtmpPublisher`, later `SrtPublisher`). `core-session` (control-plane client generated from OpenAPI, reconnect state machine). `core-library` (Room + SAF file import into app storage). `core-profile` (profile model, JSON import/export). `core-stt` (interface + `NoopSpeechEngine` in MVP; real engines later). `service` (foreground service owning engine lifetime; UI binds to it).

Profile (JSON, versioned): `{schema, name, eventName, server:{controlUrl, inviteCode?}, streamName, audio:{input, sampleRate:48000, bitrateKbps:64, micGainDb, playbackGainDb, transition:{mode, fadeMs, duckDb}, limiter}, library:{items[], folders[]}, captions:{enabled:false, lang:"fi", engine:"none"}, monitor:{enabled, device}}`. Stored locally; export/import as a zip (profile + audio files) via share sheet. No cloud sync (later, optional).

Operator UI (one screen, landscape/portrait, sun-readable high contrast, 56 dp+ targets, haptics, keep-screen-on toggle):
top: profile name + status chip (OFFLINE / CONNECTING / LIVE / RECONNECTING) + timer + bitrate. Centre: giant MIC toggle (red when live, shows input level bar). Below: now-playing card with play/pause/stop/next and a fade-now button, and a 2x3 grid of pinned sound buttons. Bottom: START/STOP broadcast (press-and-hold to stop, confirm). Banner area: errors/warnings (permission, Bluetooth lost, clipping, skew). Settings behind a lock icon (profile, library, device).

Reliability matrix
| Failure | Automatic | Operator |
|---|---|---|
| Network drop / publisher error | Yes: keep mixing, buffer up to ~10 s of AAC then drop oldest, exponential reconnect with the same session | If credential expired and refresh fails (offline > TTL): re-auth |
| Bluetooth/USB mic disconnect | Yes: fall back to built-in mic, warn | Confirm to re-select |
| Phone call / audio focus loss | Mute mic, keep stream (silence) | Tap MIC to resume |
| App/process killed | Foreground service restarts with last profile, **does not auto-open mic** | Tap START (Android 14 rule) |
| RECORD_AUDIO revoked | Service stops mic, shows error | Re-grant |
| Second device same stream | Control returns 409 | Take over explicitly |
| Low battery / thermal | Warn at 20 %/10 % | Charge |

## F. Roadmap (small, testable phases)

- **P0 Verify (no app code)**: read relay `server/index.js`, `on-publish.sh`, nginx and MediaMTX config; test publish with ffmpeg over RTMPS/RTMP from a laptop on mobile hotspot; decide C1/D2. *Accept:* A.1-A.5 answered in `docs/findings.md`.
- **P1 PoC-1 capture->AAC->RTMP**: bare app, one screen. *Accept:* 30 min screen-off publish on a real phone, relay HLS plays, no gaps > 1 s, battery/temperature logged.
- **P2 PoC-2/3 mixer + library + MIC switch**: decode file, mix, local monitor, fade policy. *Accept:* intro -> MIC -> music -> MIC with stream never restarting; offline test with a golden WAV (mixer unit tests: gain, fade curve, limiter, no clipping).
- **P3 Control plane + auth**: OpenAPI, `radiomic-control`, MediaMTX JWT/http auth, invite codes, 409 duplicate. *Accept:* APK contains no admin token (CI grep), expired/foreign-path credentials are rejected, revocation kills a live publish within TTL.
- **P4 Profiles + library + operator UI**: *Accept:* a non-developer starts a broadcast from a saved profile in under 30 s in a field test.
- **P5 Reliability hardening**: reconnect, focus, Bluetooth, process death, soak 3 h. *Accept:* failure matrix rows pass in manual test script.
- **P6 (post-MVP) STT + captions**: E4 engine bake-off, mic-tap feeding, finals to relay with scoped token, caption sync test with relay delay > 0. *Accept:* caption appears within +-1.5 s of speech in the delayed listener page.
- **P7 (later)**: SRT publisher, stereo/music profile, remote monitoring of the relay stream, multi-profile playlists, scheduled cue points.

## G. Risk register (rank, mitigation)

1. Mic + STT contention / mic permission model on Android 14+ (high, post-MVP) -> single `AudioRecord`, own STT feed.
2. Mobile-data reliability of RTMP (high) -> reconnect buffer, SRT experiment, silent-fill.
3. Background/battery kill on OEM ROMs (high) -> FGS, battery exemption, soak tests on the actual phone.
4. Traefik/MediaMTX not exposing a publish port with auth (high) -> P0 first.
5. Credential leakage / admin token exposure (high impact) -> control plane, short TTL, no tokens in APK/profile export.
6. Same-name double publish colliding with relay HLS/archive (med) -> 409 session lock, single path.
7. Library decode/licensing of user music (med) -> user-supplied files; nothing bundled; broadcast rights are the operator's responsibility (STEF/Teosto note in README).
8. Bluetooth narrowband quality (med) -> warn, prefer wired.
9. Caption timing precision limited by 4 s HLS segments (low) -> accepted, documented.
10. Finnish offline STT quality on-device (med, post-MVP) -> E4 measurement before choosing.

## H. Repository plan

```
radiomic/
  docs/plan.md  docs/findings.md  docs/architecture.md  docs/operations.md
  api/control.openapi.yaml   api/README.md
  android/                (Gradle multi-module, Kotlin)
  control/                (Node 24 ESM, zero/low-dep, tiny control service; Dockerfile)
  profiles/examples/      (invented example profile, no real hosts)
  LICENSE (decide: MIT or EUPL-1.2 like fffleet) 
```
Docs updated with every change; `docs/plan.md` stays the source of truth. Per the ecosystem rule, record the role of this repo in `piecosystem` docs (`projects-catalog.md`, `inter-service-matrix.md`) when it exists.

## I. Open questions (not answerable from code)

1. Target phone model and Android version for the PoC (decides minSdk, BT/USB behaviour, ML Kit support).
2. Is the first real event soon (e.g. a date to plan P0-P4 against)? Determines whether to ship an RTMP-only MVP using a hand-minted, short-lived MediaMTX credential before the control plane exists.
3. Licence for the repo (MIT vs EUPL-1.2).
4. Music rights for pre-recorded material (organisation's responsibility; confirm no in-app licence handling is wanted).
