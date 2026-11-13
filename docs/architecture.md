# android-field-radio: Architecture Overview

**Version:** 1.0 (2026-11-13)  
**Status:** Design freeze pending P1 (PoC capture→AAC→RTMP) field test  

---

## High-Level System

```
Phone                            VPS
┌─────────────────────┐         ┌────────────────────────────┐
│  android-field-     │         │   web-radio-relay (existing)
│  radio              │         │   ├─ nginx-rtmp ingest
│                     │         │   ├─ relay API
│  ┌─────────────┐    │         │   ├─ ffmpeg (HLS + archive)
│  │ Mic Capture │    │         │   └─ Listener page
│  └──────┬──────┘    │         └────────────────────────────┘
│         │           │         
│  ┌──────▼──────────┐ │         ┌────────────────────────────┐
│  │ Mixer (float)   │ │         │  MediaMTX (planned)
│  │ + Limiter       │ │         │  ├─ RTMPS public endpoint
│  └──────┬──────────┘ │         │  └─ per-path JWT auth
│         │            │         └────────────────────────────┘
│  ┌──────▼──────────┐ │         ┌────────────────────────────┐
│  │ AAC Encoder     │ │         │  field-radio-control
│  │ (MediaCodec)    │ │         │  ├─ Session mgmt (invite)
│  └──────┬──────────┘ │         │  ├─ Credential minting
│         │            │         │  └─ Duplicate prevention
│  ┌──────▼──────────┐ │         └────────────────────────────┘
│  │ RTMP Publisher  │ │
│  │  (RtmpPublisher)│─┼─SSH Tunnel (P1) ──────→ 127.0.0.1:1935
│  │  or SRT (P2)    │ │         or RTMPS (P3+)  ─→ MediaMTX
│  └─────────────────┘ │
│                      │
│  [STT tap] ──────────┤─→ SpeechEngine (Noop MVP, real P6)
│            (mic only)│
│                      │
│  ┌─────────────────┐ │         ┌────────────────────────────┐
│  │ Operator UI     │ │         │  HTTP/HTTPS: control plane │
│  │ (Compose)       │─┼─────────→ status, session mgmt       
│  │ • MIC toggle    │ │         │  
│  │ • Profile picker│ │         │  Relay API: listener routes
│  │ • Library +     │ │         │  (public, no auth)
│  │   playback      │ │         │
│  │ • Settings      │ │         
│  └─────────────────┘ │         
│                      │
│  [Monitor] ─────────→ AudioTrack (local playback, wired/BT only)
└─────────────────────┘
```

### MVP Transport: SSH Tunnel to Loopback RTMP

1. **Phone opens SSH local forward:** `-L 127.0.0.1:local → VPS 127.0.0.1:1935`
   - Uses aidos SSH module (RFC-0108 phase 4, Ed25519 key in Keystore)
   - VPS user: `radiopub` (restricted, port-forwarding only)

2. **Phone publishes RTMP** to `rtmp://127.0.0.1:local/live/{streamName}`
   - Stream name from control plane session creation
   - No auth at nginx level (validation by name format only)

3. **Relay receives RTMP,** starts ffmpeg
   - HLS output for listeners
   - MP3 archive file
   - Stream URL: `https://relay.example.church/live/{streamName}`

**Advantage:** No public RTMP port needed; reuses existing nginx-rtmp ingest.  
**Limitation:** Any tunnelled phone can publish any stream name (mitigated: one key per event).

### P3+ Transport: Direct RTMPS to MediaMTX

Once MediaMTX is deployed on VPS (planned):

1. **Phone gets JWT from control plane:**
   ```json
   {
     "exp": 1698234000,
     "mediamtx_permissions": [{ "action": "publish", "path": "zenterpres2" }],
     "sub": "session-uuid"
   }
   ```

2. **Phone publishes RTMPS** to public MediaMTX endpoint
   - JWT in stream name query or header (MediaMTX auth method)
   - Per-path, time-bounded credentials

3. **Relay pulls from MediaMTX via RTSP** (or HTTP if available)
   - Same HLS/archive/listener flow

**Advantage:** Public, scalable, per-path auth, shorter credential lifetime.  
**Timeline:** Post-MVP, after MediaMTX deployment.

---

## Module Responsibilities

### JVM Modules (testable without Android SDK)

| Module | Responsibility | Lifecycle |
|---|---|---|
| **core-audio** | Mic capture (ring buffer), mixer (float PCM), limiter (-1 dBFS), transition policy (stop/fade/duck/keep), resampler (48 kHz). Metering: input level, clipping count. | P1 (PoC-1) |
| **core-profile** | Profile model (JSON serializable): server config, stream name, audio settings, library folders, captions engine choice (noop/android-standard/android-ondevice/whisper-bundled≥2.0), monitor device. Validation: stream name regex, STT engine compatibility. Export: zip with profile + audio files, no credentials. | P1 (PoC-1) |
| **core-publish** | RTMP/SRT publisher (`Publisher` interface), `RtmpPublisher` implementation (RootEncoder lib). Transport seam (`Route.Direct`, `Route.ViaTunnel`). `BroadcastSession`: reconnect state machine, duplicate-session guard, silent AAC fill. | P1 (PoC-1) |
| **core-stt** | `SpeechEngine` interface (start, feed PCM, events: Partial/Final). Engines: `NoopSpeechEngine` (MVP), `AndroidStdSpeechEngine` (P6), `AndroidOnDeviceSpeechEngine` (P6), `WhisperSpeechEngine` (post-MVP, if selected). | MVP |
| **core-session** | Control plane client interface, `NoopControlPlaneClient` for dev, `SessionManager` for lifecycle. Tracks active session, credential refresh on expiry. | P2–P3 |
| **core-library** | `LibraryDao` interface for managing audio file list. `InMemoryLibraryDao` for MVP. Android module adds Room + SAF importer. | P2 |

### Android App Modules

| Module | Responsibility | Lifecycle |
|---|---|---|
| **service** | `BroadcastService` (foreground, type `microphone|mediaPlayback`). Owns `Engine`, `Publisher`, `BroadcastSession`, WakeLock. Lifecycle: user tap START → service started, mic opened (Android 14 requires on-screen tap, forbidden from background). Tap STOP or notif stop → shutdown (not auto-restart). | P1–P2 |
| **audio/MicCapture** | Android-specific `AudioRecord` source (INPUT source configurable, UNPROCESSED where supported, AEC/NS off). Ring buffer for mixer + STT tap. Device list + fallback logic. | P1 |
| **audio/LibraryPlayer** | `MediaExtractor` + `MediaCodec` decoder for mp3/aac/ogg/wav/flac. Feeds PCM to mixer. Controls playback state (play/pause/stop/next). Fade/duck logic applied by mixer, not player. | P2 |
| **audio/AacEncoder** | Android `MediaCodec` AAC-LC encoder (2–128 kbps bitrate, adjustable). Outputs `AacFrame`s (data + PTS in mixer time). Handles codec lifecycle (init/reconfigure/release). | P1 |
| **audio/Engine** | Orchestrates mic, library, mixer, encoder, publisher. Single audio thread (no drift between mic and library). Meters (input level, clipping count) updated per buffer. Tap reader opened/closed based on STT setting and mic state. | P1 |
| **ui/MainActivity** | Compose Operato UI: profile selector (load from storage), START/STOP broadcast buttons (press-and-hold to confirm STOP). MIC toggle (red when live), now-playing card (play/pause/next/fade buttons), pinned sound buttons. Status chip (OFFLINE/CONNECTING/LIVE/RECONNECTING), bitrate, timer. Settings icon (lock gated). | P4 |
| **ui/** (settings, profile editor, library import) | Deferred to P4 (post-MVP focus). | P4+ |

### VPS Services (External)

| Service | Responsibility |
|---|---|
| **field-radio-control** | Session minting, credential generation, duplicate-session prevention. Validates invite code (event-scoped, expiring, revocable). Mints `publishCredential` (string in MVP, JWT in P3+). Integrates with relay and MediaMTX when available. OpenAPI-first design (api/control.openapi.yaml). |
| **web-radio-relay** (existing) | Ingests RTMP stream, generates HLS, archive, listener page. Relay API: start/stop/status (managed mode when MediaMTX exists), captions endpoint (bearer auth). Unchanged for MVP. |
| **MediaMTX** (planned) | Public RTMPS endpoint, per-path JWT auth, pulls configured source (relay will pull from MediaMTX RTSP once it exists). Post-MVP ingest. |

---

## Reliability & Failure Handling

### Network Drop / Publisher Error
- **Automatic:** Mixer continues producing PCM, encoder fills buffer (up to ~10 s of AAC), excess frames dropped (FIFO). Reconnect with exponential backoff, same session ID.
- **Operator:** None (silent fill keeps broadcast alive for listeners). If credential expires and refresh fails offline, session is dead; operator must re-auth.

### Mic Disconnect (Bluetooth / USB)
- **Automatic:** Fall back to built-in mic, update `AudioRecord` source, warn in UI.
- **Operator:** Tap to re-select device if auto-fallback is undesired.

### Phone Call / Audio Focus Loss
- **Automatic:** Mute mic, keep stream (silence encoded), show warning banner.
- **Operator:** Tap MIC toggle to unmute and resume.

### Process Kill / System Restart
- **Automatic:** Service does NOT auto-restart mic (Android 14 rule). Last profile is loaded from disk.
- **Operator:** Tap START to open mic and begin broadcast again.

### Permission Revocation (RECORD_AUDIO)
- **Automatic:** Service stops mic, shows error.
- **Operator:** Re-grant permission via settings.

### Second Device / Duplicate Session
- **Automatic:** Control plane returns 409.
- **Operator:** Delete the old session or use a different stream name.

### Low Battery / Thermal Stress
- **Automatic:** Warn at 20% battery, 10% at critical. No throttling in MVP (post-MVP: optional bitrate reduction).
- **Operator:** Charge or cool device.

---

## Data Flow: Capture to Publish

### Audio Capture → Mix → Encode → Publish

```
MicCapture              Mixer               AacEncoder         RtmpPublisher
────────────           ───────             ──────────         ─────────────
1. AudioRecord     1. Poll mic ring    1. Dequeue AAC    1. Send to RTMP
   16-bit, 48kHz      buffer            frame (PTS)        stream (over SSH
   → ring buffer   2. Decode library    2. Encode 20ms    or RTMPS)
                      frame (if          of PCM            2. Handle backpressure:
2. Tap reader         playing)           3. Callback:      queue ≤ 100 frames
   (open if STT   3. Apply mix:         Publisher::       (≈2 s buffer)
   enabled)          gain, muting,       send(AacFrame)  3. Reconnect on error
                      balance        4. Handle bitrate:    (exponential backoff)
                   4. Apply limiter     AAC-LC 64–128   4. Emit state changes:
                      (look-ahead        kbps               IDLE→CONNECTING→LIVE
                      5ms, –1 dBFS)                        →RECONNECTING→...
                   5. Output to        (Callback from
                      encoder          mixer thread)
                   6. Output to
                      monitor
                      (AudioTrack,
                      no feedback)

Timing: All clocked by encoder output (mixer sample count, not wall clock).
Rate: 48 kHz mono → 1 frame every ~20 ms → ~50 frames/sec.
AAC frame size: ~160 bytes @ 64 kbps.
```

### Operator UI → Service → Engine

```
UI (Compose)          BroadcastService            Engine
────────────          ────────────────           ──────
1. User taps     1. onStartCommand          1. init(mic, encoder, publisher)
   START              gets profile           2. Start audio thread
2. ProfilePicker     from intent            3. Begin capture
   returns Profile 2. Creates Engine,      4. Poll mixer, encode AAC
3. Service.start()   Publisher, Session   5. Emit meters, state
4. Show profile  3. Starts foreground      6. On stop(): shut down
   name + LIVE       service notif         7. Release encoder,
   status            4. Engine.start()     mic, publisher
5. MIC toggle:   5. BroadcastController
   Engine.setMic()   updated with state   (BroadcastController is a
6. Play button:                            singleton; MainActivity
   Service.playItem()                      observes _state Flow)
7. STOP + hold:
   Service.stop()
```

---

## Session Lifecycle: Create → Publish → End

### P1 (SSH Tunnel, No MediaMTX)

```
Phone                    Control Plane           VPS SSH          Relay
──────                   ─────────────           ─────────        ─────
1. User input
   invite code, stream
   
2. POST /v1/control/
   sessions
   { inviteCode }     ──→ Validate code
                          Generate stream name
                          Record session
                          (In MVP: stream name
                           is hand-picked or
                           random)
                      ←── 201 SessionResponse
                          { sessionId,
                            streamName,
                            publishUrl:
                            "rtmp://127.0.0.1:1935",
                            expiresAt,
                            ... }
3. Open SSH tunnel
   from aidos
   ("-L 127.0.0.1:
    local → VPS
    127.0.0.1:1935")
   
4. Publish RTMP       ──→ nginx-rtmp ingest
   rtmp://127.0.0.1:
   local/live/
   {streamName}
                          on-publish.sh    ──→ ffmpeg starts
                                               HLS, archive
5. Stream live         Emit state →
   Status: LIVE             CONNECTING
                            LIVE
                            (bitrate meter)
                            
6. User taps STOP
   (or credential exp)
   
7. DELETE /v1/control/
   sessions/{id}      ──→ Mark session ended
                          (optional: tell relay
                           to stop stream)
                      ←── 204
                      
8. Close SSH tunnel
   
9. RTMP disconnect    ──→ nginx detects close
                          ffmpeg stops
                          ←── HLS archived
                               MP3 archived
```

### P3+ (Direct RTMPS, MediaMTX + JWT)

```
Phone                    Control Plane           MediaMTX            Relay
──────                   ─────────────           ────────            ─────
1–2. Same invite flow
   
3. POST /v1/control/
   sessions         ──→ Validate code
                        Generate stream name
                        Mint JWT:
                        {
                          exp: now+2h,
                          mediamtx_
                          permissions:
                          [{action:"publish",
                            path:"streamName"}]
                        }
                        Sign with private key
                      ←── 201 SessionResponse
                          { sessionId,
                            streamName,
                            publishUrl:
                            "rtmps://media...",
                            publishCredential:
                            "<JWT>",
                            ... }
                            
4. Publish RTMPS
   to MediaMTX
   credential: JWT    ──→ JWT auth check
   or streamid        (decode, verify sig,
                       check exp + path)
                        ←── Publish allowed
                            
5. Relay pulls
   from MediaMTX  ──→ RTSP pull (or HTTP)
                        ←── HLS → listeners
                             MP3 archive

6–9. End session: same as P1
```

---

## Thread Model

### Single Audio Thread

The app uses **one shared audio thread** owned by `Engine`:
- Pulls from mic ring buffer
- Polls library player for PCM
- Runs mixer (float PCM)
- Runs limiter
- Feeds encoder
- Calls publisher (async via callback, not blocking)

**No drift** between mic and library because:
- Both are clocked by the single encoder output (sample counter, not wall clock)
- If the mix underruns, silence is encoded (keeps stream alive)

**STT feed:**
- Separate read-only tap on the mic ring buffer (opened when STT is enabled, closed when mic is muted)
- STT engine runs on its own thread (async), fed PCM from the tap

**Monitor output (AudioTrack):**
- Same thread as mixer, writes the final mixed PCM
- **Never monitors live mic alone** (feedback risk); always monitors the mix or library-only (configured)

---

## Android Platform Specifics

### Foreground Service (API 26+)

- **Type:** `microphone` + `mediaPlayback` (Android 14+, declared in manifest)
- **Notification:** Started at service launch, shows profile + stream name
- **Permission:** `RECORD_AUDIO` (must be granted before service starts; Android 14 forbids granting after FGS is up)
- **Start rule:** Allowed only from UI thread (user tap); not from background/BroadcastReceiver (Android 14+)
- **Permissions:** `POST_NOTIFICATIONS` (13+), `WAKE_LOCK`

### Audio Focus & Device Management

- **Focus:** Request `AUDIOFOCUS_GAIN` for monitoring; on loss, mute mic, keep stream (silent fill)
- **Device selection:** `setCommunicationDevice` (31+) or `setPreferredDevice` (older); fallback to built-in mic
- **Bluetooth:** Support SCO/LE headsets (narrowband 8–16 kHz); warn user

### Battery & Thermal

- **WakeLock:** `PARTIAL_WAKE_LOCK` for the duration of the broadcast (CPU stays on)
- **Battery optimization:** Request exemption (educate user, do not force)
- **Thermal:** Warn at 85 °C, reduce bitrate if > 90 °C (post-MVP)

---

## Dependencies & Licenses

| Dependency | License | Purpose | Notes |
|---|---|---|---|
| RootEncoder | Apache-2.0 | RTMP/SRT client | Verify AAC-in-RTMP support; may need custom RTMP muxer if not |
| MediaCodec | AOSP | AAC encoding | Built-in Android API |
| MediaExtractor | AOSP | Library decode | Built-in Android API |
| Compose | Apache-2.0 | UI framework | androidx.compose.* |
| Room | Apache-2.0 | Library DB | Post-MVP (P2 onwards) |
| Ktor or OkHttp | Apache-2.0 | HTTP client | Control plane API calls |
| sshj | Apache-2.0 | SSH (if used) | Backup; aidos RFC-0108 provides the primary SSH implementation |
| ONNX Runtime | MIT | Whisper runner | aidos engine/stt module, post-MVP |

---

## Security Rules

1. **No `RELAY_API_TOKEN` in APK or profiles.** Token is admin-grade; only control plane holds it.
2. **Credentials are short-lived (2–4 hours).** Refresh on expiry or re-auth.
3. **Credentials are path-scoped (per stream name).** JWT includes `path` claim.
4. **Profile export strips all credentials.** Zip contains profile + audio files, no tokens.
5. **SSH key never exported; stored in Keystore (app-private, encrypted).** Only control plane and Android Keystore interact with the key.
6. **Stream names are validated client and server.** Regex: `^[a-z0-9_-]{1,32}$`.
7. **HTTPS/RTMPS only in production.** Dev/test can use HTTP/RTMP over localhost (SSH tunnel or loopback).

---

## Development Workflow

### Phases

| Phase | Goal | Acceptance | Blocker |
|---|---|---|---|
| **P0** | Verify relay infra | P0 findings documented | None (completed 2026-11-10) |
| **P1** | PoC capture→AAC→RTMP | 30 min screen-off on real phone, HLS plays | Java/Android SDK setup |
| **P2** | Mixer + library + transitions | Intro → MIC → music → MIC (stream never restarts) | Mixer unit tests + library decoder |
| **P3** | Control + auth | No admin token in APK, 409 duplicate, revoke kills publish | `field-radio-control` MVP on VPS |
| **P4** | Operator UI | Non-dev starts broadcast in < 30 s | Profile loader, library UI, START/STOP buttons |
| **P5** | Reliability hardening | Failure matrix passes (reconnect, focus, Bluetooth, process death, soak 3 h) | Long-duration field tests |
| **P6** | STT + captions | E4 engine bake-off (Vosk/whisper.cpp on target phone), finals to relay, ±1.5 s sync | STT engine selection; aidos Whisper module delivery |
| **P7** | SRT, stereo, scheduling | (future, post-MVP) | |

### Local Build & Test

```bash
# Prerequisites: JDK 21, Android SDK (API 34), gradle wrapper present

cd D:\android-field-radio

# Compile JVM modules (core-audio, core-profile, core-publish, core-stt, core-session, core-library)
.\gradlew.bat build

# Run unit tests
.\gradlew.bat test

# Build APK (debug)
.\gradlew.bat :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Build release APK (requires signing config)
.\gradlew.bat :app:assembleRelease
```

### Testing Strategy

- **JVM unit tests:** All core modules (mixer gain/fade/limiter, limiter, profile validation, RTMP handshake, session state machine, library DAO)
- **Android integration tests:** Mic capture, AAC encoding, publisher callback, foreground service lifecycle
- **E2E on device:** Real phone, 30 min screen-off broadcast, relay HLS player, network drop recovery, Bluetooth device disconnect

---

## Open Questions

1. **First real event date?** (Determines P0–P4 timeline.)
2. **Aidos RFC-0108 phase 4 delivery date?** (Phone SSH local-forward + Keystore key, blocks P1 field test.)
3. **VPS sshd config for `radiopub` user?** (Can be done anytime; needed before P1 testing.)
4. **MediaMTX deployment plan on VPS?** (P3 starts after; MVP works without it.)
5. **Music library file format support?** (MP3/AAC/OGG/WAV/FLAC confirmed via MediaExtractor/MediaCodec?)
6. **Preferred STT engine for P6?** (Vosk vs. whisper.cpp; E4 bake-off decides; aidos Whisper module is fallback.)

---

## Repository Layout

```
android-field-radio/
├── docs/
│   ├── plan.md                  (living implementation plan)
│   ├── findings.md              (P0 verification findings)
│   └── architecture.md          (this file)
├── api/
│   ├── control.openapi.yaml     (field-radio-control API spec)
│   └── README.md                (control plane design & credential flow)
├── core-audio/                  (JVM: mic, mixer, limiter, resampler)
├── core-profile/                (JVM: profile model, validation)
├── core-publish/                (JVM: RTMP/SRT publisher, session reconnect)
├── core-stt/                    (JVM: SpeechEngine interface)
├── core-session/                (JVM: control plane client, SessionManager)
├── core-library/                (JVM: LibraryDao interface)
├── app/                         (Android: UI, service, audio, encoder)
├── profiles/examples/           (example profiles, no real hosts)
├── gradle/wrapper/              (Gradle 9.3.0)
├── build.gradle.kts             (root)
├── settings.gradle.kts          (module includes)
├── gradle.properties            (Gradle config)
└── LICENSE                      (EUPL-1.2)
```

---

## Reference Implementations & Links

- **aidos RFC-0108:** SSH tunnel component (phase 4: phone local-forward + Keystore key)
- **aidos engine/stt:** Whisper.cpp on ONNX Runtime (post-MVP for P6)
- **web-radio-relay:** Relay API, HLS, archive, captions, delay
- **MediaMTX:** (planned VPS deployment; not yet used in MVP)

---

*Last updated: 2026-11-13*
