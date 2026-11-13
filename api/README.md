# API Design and Control Plane

## Overview

The control plane (`field-radio-control`) is a small, stateless service running on the VPS alongside web-radio-relay.
It authenticates operators via event-scoped invite codes and mints short-lived credentials for:

1. **Publish credential**: one-time use token for MediaMTX RTMPS ingest (per stream, per session, per-path auth)
2. **Caption token** (post-MVP): scoped bearer token for `/api/v1/streams/{name}/captions` (relay API)

This design ensures:
- No admin token (`RELAY_API_TOKEN`) in the APK or profiles
- Per-stream, time-bounded credentials
- Duplicate-session prevention (409 when a stream name is already live)
- Scalable to multiple concurrent broadcasts (one per stream name)

## Architecture

```
Phone (android-field-radio)
  |
  +--HTTPS---> field-radio-control
  |              (mints credential)
  |
  +--RTMPS---> MediaMTX
                (publish with credential)
                  |
                  v
              web-radio-relay
                  (HLS, archive, listener page)
```

### Authentication Model

**Invite Codes** (operator-generated, event-scoped):
- Pre-created in control plane DB or config (no self-serve signup)
- Format: random 256-bit hex (example: `f3a7c2...`)
- Lifetime: hours to days (event duration)
- Revocable: delete = no new sessions
- Passed in header: `X-Invite-Code: <code>`

**Admin Key** (VPS operator only):
- Used by automation, relay management, invite creation
- Never in APK or profiles
- Passed in header: `X-Admin-Key: <key>`

## OpenAPI Spec

See `control.openapi.yaml` (3.1.0). Key endpoints:

| Endpoint | Method | Auth | Purpose |
|---|---|---|---|
| `/v1/control/sessions` | POST | invite or admin | Create session → publish credential + stream name |
| `/v1/control/sessions/{id}` | DELETE | invite or admin | End session (revoke credential, tell relay to stop) |
| `/v1/control/sessions/{id}/refresh` | POST | invite or admin | Refresh credential before expiry (long broadcasts) |
| `/v1/control/status` | GET | none | Health check + hints (MediaMTX URL, relay URL, TTL) |

## Credential Flow (P1: MVP, SSH tunnel)

In P1, credentials are minimal (the session ID is enough to track the stream, no JWT):

1. **Phone user taps "Start broadcast"**
2. **Phone calls** `POST /v1/control/sessions { profileHint: "...", inviteCode: "..." }`
3. **Control returns:**
   ```json
   {
     "sessionId": "550e8400-...",
     "streamName": "zenterpres2",
     "publishUrl": "rtmp://127.0.0.1:1935",
     "publishCredential": "zenterpres2",
     "expiresAt": "2026-11-13T10:00:00Z",
     "relay": { "apiUrl": "https://relay.example.church/api/v1" }
   }
   ```
4. **Phone opens SSH tunnel** to VPS (`127.0.0.1:1935` locally → VPS `127.0.0.1:1935`)
5. **Phone publishes RTMP** to `rtmp://127.0.0.1:1935/live/zenterpres2` (the stream name from step 3)
6. **Relay receives RTMP**, starts ffmpeg for HLS/archive

**Session end:**
1. Phone calls `DELETE /v1/control/sessions/550e8400-...`
2. Control marks session as ended
3. (Optional) Control tells relay to stop the stream (if managed API is used)
4. Phone closes SSH tunnel
5. RTMP stream ends, relay stops ffmpeg

## Credential Flow (P3+: With MediaMTX and JWT)

Once MediaMTX is on the VPS with JWT auth enabled:

1–3. Same as P1 (POST /sessions)

4. **Control returns JWT in `publishCredential`:**
   ```json
   {
     "publishCredential": "eyJhbGc...",
     "publishUrl": "rtmps://media.example.church/live",
     "expiresAt": "2026-11-13T10:00:00Z"
   }
   ```
   JWT claims:
   - `exp`: credential expiry (Unix seconds)
   - `mediamtx_permissions`: `[{ action: "publish", path: "zenterpres2" }]`
   - `sub`: session ID (for logging)

5. **Phone publishes RTMPS** to `rtmps://media.example.church/live` with JWT in header or stream name query
6. **MediaMTX validates JWT**, checks `path` matches the stream name, allows publish

Session end same as P1.

## Implementation Notes

### Deployment
- Standalone Node.js/Deno service or a Kotlin module inside the relay codebase
- Stateless: credential minting is deterministic (JWT signed with a key)
- Invite code storage: Redis, PostgreSQL, or in-memory with periodic reload
- Credential TTL: typically 2–4 hours (configurable)

### Invite Code Management
No self-service signup in this API. Invites are created out-of-band:
- Operator creates invite in a web UI or CLI tool (separate from this API)
- Invite is stored and has an expiry
- When expired or revoked, control returns 401 on refresh/delete

### Duplicate-Session Prevention
- Track active sessions by stream name
- `POST /sessions` returns 409 if `streamName` is already in use
- `DELETE /sessions/{id}` removes the session entry
- Stale sessions (credential expired, no refresh for > TTL) are garbage-collected

### Caption Token (Post-MVP)
When captions are implemented (P6):
- Control can optionally mint a separate `captionToken` (scoped to POST captions only)
- Token includes claim `caption_stream: "zenterpres2"`
- Relay validates the token on each caption POST
- Keeps caption writes decoupled from publish credential

---

## Testing

**Local development:**
```bash
# Start control on :9100 with in-memory DB and a test invite
export INVITE_CODE="test123"
export MEDIAMTX_URL="rtmps://localhost:9443/live"
export RELAY_URL="http://localhost:8080/api/v1"
node control.js

# Create session
curl -X POST http://localhost:9100/v1/control/sessions \
  -H "X-Invite-Code: test123" \
  -H "Content-Type: application/json" \
  -d '{}'

# End session
curl -X DELETE http://localhost:9100/v1/control/sessions/<sessionId> \
  -H "X-Invite-Code: test123"
```

**Integration test:**
- Create invite code
- Create session, check stream name is available
- Second create for same stream → 409
- Refresh credential, check new expiry
- Delete session
- Create new session with same invite → succeeds (old entry is gone)

---

## Future Enhancements

1. **Invite code UI**: web dashboard for operators to generate, view, revoke codes
2. **Session logging**: audit trail (who created/used/ended which sessions, when)
3. **Per-stream webhook**: notify control when relay stream starts/stops (for monitoring)
4. **Credential rotation**: automated refresh before expiry (if broadcast runs > TTL)
5. **SRT support**: SRT passphrase minting alongside RTMPS JWT
6. **Caption-only token**: separate cred for caption submission (already designed, deferred to P6)

---

## References

- `control.openapi.yaml`: full API contract (OpenAPI 3.1)
- Plan section D1–D2: control plane design decisions
- Plan section C1b: SSH tunnel MVP transport (does not need full JWT; works with invite codes only)
