# android-field-radio: living implementation plan

**Status:** DESIGN FROZEN, P1 IMPLEMENTATION COMPLETE (2026-11-13). 

**Implemented:** ~2000 LOC Kotlin across core-audio, core-profile, core-publish (RTMP), core-session, core-stt, core-library, and app. Single-screen Compose UI with live MIC toggle, status, level meters, library sound buttons, and profile editor. All modules JVM-testable; full unit test suite (mixer, resampler, RTMP protocol, session reconnect). Ready for real device field test pending aidos SSH tunnel integration.

Working name `android-field-radio` (rename freely). Public repo, no church-specific data: hosts, tokens, stream names and event data live only in user-made profiles on the device or in the private deployment repo.

Legend: [V] verified from web-radio-relay README at d39f6d9 (code not yet read); [U] unverified, needs a check or experiment.

## 0. Scope decisions (Juha, 2026-11-10)

- MVP = capture + mix + sound library + AAC publish + profiles + operator UI + status. **Speech-to-text and captions are NOT MVP** (no Whisper in MVP). Only an STT interface and the mic-only PCM tap are built in MVP so STT drops in later without redesign.
- Kotlin, native. Build targets the same as aidos (read from `D:\aidos\agent\androidapp\build.gradle.kts`): minSdk 26, compileSdk/targetSdk 34, JVM toolchain 21. Features above API 26 (Android 14 FGS rules, `setCommunicationDevice` 31+, on-device recognizer 31+) are gated at runtime.
- Licence: EUPL-1.2 (Juha, 2026-11-10), same as aidos/fffleet.
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
1. [U] (project memory 2026-11-01 says the VPS relay uses nginx-rtmp ingest bound to 127.0.0.1 with no public auth, reachable only via the Pi tunnel, and MediaMTX is on the Pi; the VPS agent is checking for MediaMTX on the VPS) Is MediaMTX actually deployed on the VPS next to the relay and reachable from the internet? If not, publish target is the relay's nginx-rtmp (legacy path) and the managed lifecycle is not needed.
2. [U] `server/index.js` and `on-publish.sh`: actual stream-name validation, behaviour when the same key reconnects within seconds (HLS discontinuity, archive file split).
3. [U] MediaMTX version, auth method configured (`internal`/`http`/`jwt`), which protocols are open (RTMP/RTMPS, SRT, WHIP).
4. [U] Whether the VPS Traefik exposes TCP (RTMPS/SRT/UDP) at all. Traefik is HTTP-centric; TCP/UDP entrypoints or a direct port on the host are needed.
5. [U] Archive behaviour for a stream that pauses (phone drops) and resumes.

**VPS findings (VPS agent, read-only, 2026-11-10) [V]:** no MediaMTX on the VPS. Ingest is nginx-rtmp 1.23.1 in container `web-radio-relay-nginx` beside `web-radio-relay-api` (`/opt/apps/web-radio-relay/docker-compose.yml`), only `rtmp://.../live/<name>`, bound to 127.0.0.1:1935. No RTMPS, SRT, WHIP or UDP. No publish auth (`on-publish.sh` checks the name against `[a-z0-9_-]{1,32}` only). Traefik has only :80, :443, :8082 (no TCP/UDP entrypoints). `POST /api/v1/streams/{name}/start` answers 503 in production because `RADIO_SOURCE_URL_TEMPLATE` is unset (managed lifecycle unused). Captions via `POST /api/v1/streams/{name}/captions` (bearer, 1-100 segments).
**Consequence:** the SSH tunnel to loopback RTMP (C1b) is the only publish path today and is the MVP transport. The MediaMTX/JWT control plane (D.1-D.2) is deferred until MediaMTX exists on the VPS; relay lifecycle API is not used in the MVP (stream appears when the publisher connects, as legacy ingest does). Remaining check: sshd port/user for restricted keys.

## B. Proposed architecture

```
Phone (android-field-radio)                                   VPS
 AudioRecord(mic) --+--> [mic tap] --> STT (later)
                    v
 Sound library --> decoder --> Mixer --> Limiter --> AAC (MediaCodec) --> publisher
        |                         ^                                          |
        +--> local monitor <------+ (same PCM, no acoustic loop)             v
                                                                         MediaMTX path <name>
 Control client ---HTTPS---> field-radio-control (new, small)                    |
   1. start session (event PIN / invite)                                      v
   2. receives short-lived publish credential for ONE path          web-radio-relay pulls rtsp://mediamtx:8554/<name>
   3. control calls relay start/stop with the admin token it holds  (HLS, MP3 archive, delay, listener page)
```

Responsibilities
- **App**: capture, mix, encode, publish, profiles, library, UI, (later) STT and caption send.
- **MediaMTX**: ingest and per-path auth. 
- **web-radio-relay**: unchanged lifecycle, HLS, archive, delay, captions.
- **field-radio-control** (new, tiny, on the VPS, own container): holds `RELAY_API_TOKEN` and MediaMTX admin secret; exposes a narrow OpenAPI: create session, refresh credential, end session. Could later be a module inside web-radio-relay instead; decide after reading its code. Keep OpenAPI-first in this repo (`api/control.openapi.yaml`) so either host can implement it.

## C. Technical decisions

### C1. Publish protocol
| Option | For | Against |
|---|---|---|
| RTMP/RTMPS to MediaMTX | Simple, well supported by Android libs, nginx-rtmp-compatible fallback. | TCP head-of-line blocking on bad mobile links; RTMPS needs TLS on MediaMTX; credentials in URL query. |
| SRT to MediaMTX | Built for lossy links, latency tunable, passphrase + streamid. | Needs UDP port open; fewer Android libs; AAC-in-MPEG-TS audio-only edge cases [U]. |
| WHIP (WebRTC) to MediaMTX | HTTPS-friendly, Opus, bearer-token auth via HTTP header, good with Traefik. | Heavier client; Android WebRTC lib size; stats/reconnect more complex. |

Recommendation (superseded by the VPS findings above: MVP = plain RTMP through an SSH tunnel, see C1b; the rest of this row applies once a public MediaMTX exists): **RTMPS to MediaMTX** (it is the contract in the original brief and the legacy relay speaks it), SRT as phase-2 experiment for bad coverage. [U: confirm RTMPS is exposed.]


### C1b. SSH-tunnel transport (Juha's proposal, 2026-11-10)
**Decision (Juha, 2026-11-10): the SSH tunnel is built in aidos as a modular component (RFC-0108); this repo does NOT implement SSH.** Seam in `core-publish`: `TunnelTransport` (state flow Idle/Connecting/Up(localHost,localPort)/Reconnecting/HostKeyChanged/AuthRefused, `open(ForwardSpec)`, `close()`), `Route.Direct | Route.ViaTunnel`, `Publisher`, and `BroadcastSession` (retry with backoff, needs-operator on host-key change/auth refusal, duplicate-start guard), all tested with fakes. Per RFC-0108: tunnel is in-process (sshj/MINA), Ed25519 key from the vault, pinned host key, keepalive and capped backoff owned by the aidos manager, public key shown as an `authorized_keys` line. **Gap:** RFC-0108 phase 3 is a desktop reverse tunnel (-R); the phone-side local forward (-L) and Android Keystore key storage are its phase 4, so aidos must deliver that form (or we adapt) before the phone can use it. The restricted-key line there uses `permitlisten`; ours needs `permitopen` (forward direction).

The phone opens an SSH local forward to the VPS (`127.0.0.1:<local> -> VPS 127.0.0.1:1935`) and publishes plain RTMP into it. This reuses the existing localhost-only nginx-rtmp ingest and needs no public RTMP port and no MediaMTX/JWT for the MVP. Fits the Pi radio-tunnel pattern.
- Key: one ed25519 key per phone, generated on the device, public key added to a restricted VPS user: `restrict,port-forwarding,permitopen="127.0.0.1:1935"`, no shell. Revoke = delete the line. Private key in app-private encrypted storage (never exported, not in profiles).
- Library: in-process SSH client (sshj, Apache-2.0, or Apache MINA SSHD) [U: verify on Android 8+], no Termux/root. `Publisher` gets a `TunnelTransport` wrapper; the profile gets `transport: "direct"|"ssh"` plus `ssh:{host,port,user}` (host/user are profile data, not code).
- Caveats: publishers are still not authenticated per stream name (any holder of a key can publish any name; acceptable for a trusted operator, mitigate with one key per event and revocation); RTMP-over-SSH is TCP-in-TCP so stalls after network loss are longer (tunnel and RTMP must reconnect together, keepalive 15 s); captions endpoint is separate HTTPS and still needs its own scoped token (post-MVP); the VPS sshd must be reachable from mobile networks [U: ask VPS agent which sshd port/user exists].
- Direction of the existing tunnel [V, VPS agent 2026-11-10]: the VPS systemd `radio-tunnel` (VPS user `radiotunnel`) is the SSH **client**, dialling `radio@Pi:1337` with `-R 127.0.0.1:11935:127.0.0.1:1935`; the Pi is the SSH server. It cannot be reused as an inbound phone account. So the Pi is the server in that tunnel and the VPS only accepts RTMP locally. The phone needs its own entry: (a) the VPS's own sshd with a restricted key (preferred, independent of the Pi), or (b) the Pi's sshd:1337 forwarding to 127.0.0.1:11935, which works only while the Pi is online and the tunnel is up (couples the phone to Pi uptime).
- **VPS entry (V, VPS agent):** sshd listens on 0.0.0.0:22 with stock config (password auth, TCP/X11 forwarding allowed). Proposed, not applied (VPS changes need Juha's OK): new user `radiopub` (nologin, no password); per-phone key line `restrict,port-forwarding,permitopen="127.0.0.1:1935",command="/usr/sbin/nologin" ssh-ed25519 ...`; `Match User radiopub` with `AllowTcpForwarding local`, `PermitTTY no`, `X11Forwarding no`, `ForceCommand /usr/sbin/nologin`; globally `PasswordAuthentication no` (check no other user relies on passwords first).
- **Stream-name gap:** any tunnelled phone can publish under any name, including the Pi's `keskuskirkko`. Mitigation options: an `on_publish` check in the relay's nginx (allow-list of names, or reject the Pi's reserved name for tunnel publishers; needs a relay change, left until Juha confirms), or a second loopback ingest port per key. Until then, one key per event and revocation.
- Verdict: recommended MVP transport if the VPS has no public MediaMTX; direct RTMPS/SRT stays the later option. Experiment E1 runs both from the same app.

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
**Settings decision (Juha, 2026-11-10):** the profile/app settings offer an STT engine choice with exactly three entries:
1. `android-standard`: normal Android `SpeechRecognizer` (may be cloud-backed; the UI must label it "may use the network").
2. `android-ondevice`: on-device Android recognition (`createOnDeviceSpeechRecognizer`, 31+, language pack required; hidden or disabled with a reason when unsupported).
3. `whisper-bundled`: bundled whisper.cpp, shown but **disabled until 2.0** (greyed, "coming in 2.0"). Profile value `whisper` is rejected by validation until then.
Default is `none` (captions off). Vosk is dropped from the settings list; it stays only as a bake-off candidate in E4. Since `SpeechRecognizer` owns the microphone, MVP+1 must verify whether it can run beside our `AudioRecord` (`EXTRA_AUDIO_SOURCE` pipe on 13+) or the engine must be started only while the mic is the sole capture path [U].

**Reuse from aidos (read on Bonum, `D:\aidos`, 2026-11-10):** aidos has no live audio capture, RTMP, SSH or mic foreground-service code (its manifests have `RECORD_AUDIO` commented out; FGS type is `dataSync`). It does have `engine/stt` (Kotlin, `commonMain` + `jvmAndAndroidMain`): Whisper on ONNX Runtime with log-mel, tokenizer and KV-cached greedy decode (`docs/stt.md`), with measured Finnish results on desktop CPU (int8): tiny CER 0.14 (garbled), small 0.04, large-v3-turbo 0.00; Android never compiled or timed. That is the natural implementation of the `whisper-bundled` entry in 2.0 (no whisper.cpp/JNI, no per-ABI native build) and also a way to reach it as a service: the aidos Engine's `POST /v1/audio/transcriptions` (base64 WAV, `language`, utterance-sized pieces, no timestamps). Constraints to remember: 30 s windows without timestamps, so captions would be utterance-level with our own capture times; small or larger needed for Finnish. Decision (Juha, 2026-11-10): take aidos's Whisper runner in 2.0; not before. the `SpeechEngine` interface stays engine-agnostic. Also noted: aidos RFC-0109 (ACP, BLE capture device) could later be an input source; out of scope.

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
1. **Control plane** `field-radio-control` (or a relay module):
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

Profile (JSON, versioned): `{schema, name, eventName, server:{controlUrl, inviteCode?}, streamName, audio:{input, sampleRate:48000, bitrateKbps:64, micGainDb, playbackGainDb, transition:{mode, fadeMs, duckDb}, limiter}, library:{items[], folders[]}, captions:{enabled:false, lang:"fi", engine:"none"|"android-standard"|"android-ondevice"|"whisper-bundled(2.0, disabled)"}, monitor:{enabled, device}}`. Stored locally; export/import as a zip (profile + audio files) via share sheet. No cloud sync (later, optional).

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

- **P0 Verify (complete ✅ 2026-11-10)**: Relay infrastructure verified (findings.md). SSH tunnel identified as MVP transport. VPS has nginx-rtmp on loopback only, no MediaMTX yet. No admin token exposure needed for MVP.
- **P1 PoC-1 capture->AAC->RTMP (complete ✅ 2026-11-13, code-ready for device test)**
  - **What's done:** ~2000 LOC Kotlin, UI, tests, single-screen app. Mic (48 kHz) → AAC (64 kbps) → RTMP publisher. Foreground service, level meters, library buttons, profile editor. Transports: Direct (for dev) and ViaTunnel (seam for aidos SSH module). RTMP protocol with full handshake, chunk encoding, reconnect state machine.
  - **What's pending:** (a) aidos RFC-0108 phase 4 (SSH TunnelTransport), (b) real device field test (30 min, no gaps > 1 s HLS).
  - **Accept:** 30 min screen-off publish on a real phone, relay HLS plays, no gaps > 1 s, battery/temperature logged. Device must have aidos SSH module and VPS sshd configured (see operations.md).
- **P2 PoC-2/3 mixer + library + MIC switch (starting now)**: decode file, mix, local monitor, fade policy, audio device selection, Bluetooth fallback. *Accept:* intro -> MIC -> music -> MIC with stream never restarting; offline test with a golden WAV (mixer unit tests: gain, fade curve, limiter, no clipping).
- **P3 Control plane + auth (starting now, parallel with P2)**: OpenAPI, `field-radio-control`, MediaMTX JWT/http auth (post-MVP), invite codes, 409 duplicate. *Accept:* APK contains no admin token (CI grep), expired/foreign-path credentials are rejected, revocation kills a live publish within TTL.
- **P4 Profiles + library + operator UI (after P1+P2+P3)**: Multi-screen UI, profile CRUD, import/export. *Accept:* a non-developer starts a broadcast from a saved profile in under 30 s in a field test.
- **P5 Reliability hardening (after P4)**: reconnect buffer, audio focus, Bluetooth fallback, process death, soak 3 h. *Accept:* failure matrix rows pass in manual test script.
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
android-field-radio/
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
3. ~~Licence~~ decided: EUPL-1.2.
4. Music rights for pre-recorded material (organisation's responsibility; confirm no in-app licence handling is wanted).
