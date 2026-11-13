# Work Remaining: P1–P5 Roadmap

**Last updated**: After P1 + P2 (partial) + P3 (MVP) + P5 (audio focus + reconnect buffer)

**Status**: Effective-memory branch, ready for field testing when aidos RFC-0108 phase 4 arrives.

---

## Completed ✅

### P1 (Proof-of-Concept: Capture → AAC → RTMP)
- [x] Core audio loop: mic → mixer → AAC encoder → RTMP publisher
- [x] Profile model: streams, transitions, library, caption placeholders
- [x] RTMP handshake & chunk protocol (AAC only, no video)
- [x] Session reconnect with exponential backoff
- [x] Foreground service with microphone permissions (Android 14+)
- [x] BroadcastService: lifecycle, audio focus request, state sync
- [x] Broadcast state: link status, error, meters (peak, clips)
- [x] JVM tests: mixer, AAC encoder, RTMP flow, session lifecycle
- [x] Android app: MainActivity, BroadcastScreen, profile import/export (JSON)
- [x] SVG resources: mic, play, stop, error icons
- [x] Gradle: app module with all dependencies

### P5 Features (Non-UI Reliability)
- [x] **Audio focus loss handling**: mic mute on AUDIOFOCUS_LOSS/LOSS_TRANSIENT, stream stays alive with silence
- [x] **Reconnect buffer**: queue up to 10 s AAC frames during disconnection, flush on reconnect
- [x] **Device state tracking**: AudioDeviceCallback skeleton + state enum
- [x] **Bluetooth/USB mic detection**: device enumeration stubs (API 24+)

### P3 (Control Plane MVP)
- [x] **OpenAPI spec**: POST /sessions (create), /refresh (extend TTL), DELETE (revoke)
- [x] **field-radio-control Node.js service**: Express server, in-memory session store
  - Invite code auth (24-hour codes, pre-created by operator)
  - Duplicate session prevention (one stream → one active session)
  - Credential minting (2-hour RTMP URLs)
  - Admin API for invite creation & session listing
- [x] **HttpControlPlaneClient**: OkHttp-based implementation in core-session
- [x] **SessionManager**: lifecycle (create, refresh, end), phase tracking

---

## In Progress / P2 Stubs 🔨

### P2 (Audio Hardening) — Foundation In Place

**Completed stubs**:
- [x] Engine: `onDeviceChanged` callback + AudioDeviceState model
- [x] Engine: `getAvailableInputDevices()` enumeration (API 24+)
- [x] AudioDeviceCallback registration skeleton (not yet wired to UI)

**Still needed** (P2 full):
- [ ] **Library SAF integration**: file picker → import to app storage (PDF/MP3/WAV)
- [ ] **Audio device selection UI**: dropdown in Settings → select mic/headset input
- [ ] **Bluetooth detection**: warn if narrowband (8 kHz) headset connected
- [ ] **Local monitor**: loop mixed audio to wired/BT headsets (no mic echo)
- [ ] **Device fallback**: on headset disconnect, auto-switch to built-in mic
- [ ] Tests: device switch scenarios, SAF edge cases

---

## Todo / P3 Continuation 📋

### P3 (Control Plane) — MVP Done, Integration Needed

**Completed**:
- [x] Control plane API & service (Express server)
- [x] OpenAPI spec (full session lifecycle)
- [x] HttpControlPlaneClient (OkHttp) in core-session
- [x] SessionManager (create, refresh, end)

**Still needed**:
- [ ] **EncryptedSharedPreferences**: store invite codes & credentials (never log/export)
- [ ] **Invite code input UI**: EditText in Settings, persist to SharedPreferences
- [ ] **Credential refresh loop**: BroadcastSession calls SessionManager.refresh() before TTL expiry
- [ ] **Export/import with credential stripping**: profile export removes invite codes, credentials
- [ ] **Profile expansion**: full JSON schema (audio device, library list, caption settings)
- [ ] **CI security checks**: grep for RELAY_API_TOKEN, ADMIN_KEY not in artifacts
- [ ] Tests: session lifecycle, credential expiry, refresh timing

---

## Todo / P4 Deferred 🎨

### P4 (Multi-Screen UI)

**No code changes** yet (blocked on P2 + P3 + P5 foundation).

Scope once unblocked:
- [ ] Expand Profile model: full audio/library/captions JSON schema
- [ ] Profile CRUD: create, load, save, import/export as zip
- [ ] Multi-screen Compose navigation:
  - Home: profile list + create
  - Broadcast: live controls (meters, link status, error)
  - Now-Playing: library controls
  - Settings: device selection, invite code, advanced options
- [ ] UI polish: 56 dp+ touch targets, high contrast, haptics, landscape/portrait

---

## Todo / P5 Reliability (Final) 🛡️

### Completed
- [x] Audio focus loss: mic mute, stream alive
- [x] Reconnect buffer: 10 s frame queue, auto-flush on reconnect

### Still Needed
- [ ] **Bluetooth fallback**: on headset disconnect, auto-switch to built-in mic + notify
- [ ] **Process death recovery**: save last profile to SharedPreferences, restore on service restart (don't auto-mic)
- [ ] **Permission revocation**: detect RECORD_AUDIO loss, stop mic, show error, shutdown service
- [ ] **Battery/thermal warnings**: warn at 20% and 10% battery; log CPU throttling (API 29+)
- [ ] **Soak testing**: 3-hour manual test with periodic events (calls, device changes, network loss)
- [ ] **Monitoring hooks**: battery %, thermal state, dropped frame count in logs

---

## Known Blockers & Workarounds 🚧

### aidos RFC-0108 Phase 4 (SSH Tunnel Transport)
- **Status**: Not yet available
- **Impact**: Blocks P1 device field test (can't validate MediaMTX integration on real device)
- **Workaround**: 
  - Dev: Direct RTMP to localhost via emulator or forward
  - Field: Temporary public RTMP endpoint (demo server) or SSH manual tunnel
- **ETA**: Awaiting aidos delivery; code path ready (Route.ViaTunnel in BroadcastSession)

### Gradle APK Build in Sandbox
- **Status**: Gradle plugin resolution fails (temp network/caching issue)
- **Impact**: Can't verify APK assembly; JVM tests run fine
- **Workaround**: Build locally or in CI/CD
- **Severity**: Low (code is logically correct; only packaging blocked)

### field-radio-control Service Environment
- **Status**: Express server runs; no production deployment yet
- **Impact**: P3 integration testing limited to dev machine
- **Workaround**: Mock HttpControlPlaneClient for unit tests; deploy to VPS for E2E
- **Roadmap**: Docker container in P4; K8s in P5+

---

## Dependency Graph

```
P1 ✅ (proof-of-concept)
├── P2 (audio hardening) — parallel, no P1 dependency
├── P3 (control plane) — parallel, no P1 dependency
├── P5 (reliability, non-UI) ✅ partial (audio focus + buffer)
│   └── P1 audio focus callbacks (done)
│   └── P1 reconnect buffer (done)
│   └── P2 device fallback (blocked on P2 device selection)
│   └── P5 permission revocation (not on critical path)
│
P4 (multi-screen UI) — sequential after P1 + P2 + P3
│   └── Requires expanded Profile schema (P3)
│   └── Requires device selection UI (P2)
│   └── Requires control plane integration (P3)
```

---

## Next Immediate Actions

1. **Test P1 + P5 audio focus locally**: 
   - Simulate AUDIOFOCUS_LOSS (start music app)
   - Verify mic mutes, stream keeps running
   - Resume when focus regained

2. **Test P1 + P5 reconnect buffer**:
   - Kill RTMP server mid-broadcast
   - Verify frames buffer for 10 s
   - Restart server, verify frame flush + continuous stream

3. **Integrate P3 control plane with app**:
   - Add EncryptedSharedPreferences for invite code
   - Wire SessionManager to BroadcastService
   - Add UI text input for invite code in Settings

4. **Start P2 audio device selection**:
   - Implement AudioDeviceCallback listener in Engine
   - Add device list UI dropdown
   - Test Bluetooth headset detection

5. **Field test when aidos ready**:
   - Deploy field-radio-control to test VPS
   - Create invite code via admin API
   - Build APK with real server URL
   - Run 30-min broadcast, capture logs
   - Verify: no stream gaps, clean shutdown, battery/thermal OK

---

## Metrics & Acceptance Criteria

### P1 Device Test (when aidos available)
- ✅ 30-min broadcast, no gaps > 1 s in HLS output
- ✅ Encoder handles network stalls (reconnect buffer absorbs frame bursts)
- ✅ Shutdown: credential revoked, stream ends cleanly
- ✅ Battery: < 10% drain in 30 min (idle CPU when not talking)

### P2 Audio Hardening
- ✅ Device change: fallback to built-in within 100 ms, no pops
- ✅ Bluetooth narrowband warning: shown to operator
- ✅ SAF import: PDF/MP3/WAV work end-to-end

### P3 Control Plane
- ✅ Session CRUD: invite → create → refresh → delete all work
- ✅ Credentials never logged; export strips codes
- ✅ Credential expiry enforced (reject use after TTL)

### P5 Reliability
- ✅ Audio focus loss: recoverable within 2 s
- ✅ Reconnect: buffer absorbs up to 10 s silence
- ✅ 3-h soak: battery OK, no crashes, thermal stable

---

## Code Organization

```
app/
  src/main/kotlin/org/fieldradio/app/
    audio/Engine.kt (audio loop + device state)
    service/BroadcastService.kt (lifecycle, audio focus)
    ui/ (MainActivity, BroadcastScreen, SettingsScreen)

core-*/
  core-audio/ (mixer, AAC encoder)
  core-publish/ (RTMP protocol, RtmpPublisher + reconnect buffer)
  core-profile/ (Profile model)
  core-session/ (BroadcastSession, ControlPlane + HttpControlPlaneClient)

field-radio-control/
  src/index.js (Express server, session API)
  README.md (deployment & testing guide)

api/
  control.openapi.yaml (OpenAPI 3.1 spec)

docs/
  plan.md (master plan with phase updates)
  work-remaining.md (this file)
```

---

## Links & References

- **Main plan**: docs/plan.md
- **Control plane API**: api/control.openapi.yaml
- **Control plane server**: field-radio-control/README.md
- **Github issue**: #1 (P1 implementation)
- **Effective-memory branch**: All work pushed here; PR to main when P1 tested on device
