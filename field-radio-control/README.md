# field-radio-control

Session management control plane for android-field-radio broadcasts. 

## Features (P3 MVP)

- **Session lifecycle**: Create, refresh, and revoke broadcast sessions
- **Invite code auth**: Event-scoped pre-created codes (time-bound, revocable)
- **Credential minting**: Short-lived RTMP credentials (2-hour TTL)
- **Duplicate prevention**: One stream name → one active session
- **Admin API** (dev): Create invites, list sessions, manual revocation

## Architecture

```
Android App  →  field-radio-control  →  MediaMTX/RTMP relay
  (invite)        (POST /sessions)      (publish credential)
```

1. **Operator** pre-creates invite codes via admin API
2. **App** presents invite code UI (P3 feature)
3. **App** exchanges code for session → RTMP credential
4. **App** publishes to RTMP URL in the credential
5. **App** calls `/refresh` before credential expires (P5)
6. **Operator** clicks STOP → `/delete` → credential revoked

## Installation

```bash
npm install
ADMIN_KEY=dev-secret npm start
```

Server listens on `:9100` by default (configurable via `PORT`).

## API Reference

See `../api/control.openapi.yaml` for full OpenAPI spec.

### POST /v1/control/sessions

Create a broadcast session with an invite code.

```bash
curl -X POST http://localhost:9100/v1/control/sessions \
  -H "Content-Type: application/json" \
  -H "X-Invite-Code: c3fa22e5-48d5-42e4-8f3d-7a5f6c9e2b1a" \
  -d '{"profileHint": "Sunday service"}'
```

Response (201):
```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "streamName": "zenterpres2",
  "publishUrl": "rtmp://127.0.0.1:1935/live",
  "publishCredential": "rtmp://127.0.0.1:1935/live/zenterpres2",
  "captionToken": null,
  "expiresAt": "2026-11-13T10:30:00Z",
  "createdAt": "2026-11-13T09:30:00Z"
}
```

### POST /v1/control/sessions/:sessionId/refresh

Extend credential lifetime (before expiry).

```bash
curl -X POST http://localhost:9100/v1/control/sessions/550e8400-e29b-41d4-a716-446655440000/refresh \
  -H "X-Invite-Code: c3fa22e5-48d5-42e4-8f3d-7a5f6c9e2b1a"
```

Response (200):
```json
{
  "publishCredential": "rtmp://127.0.0.1:1935/live/zenterpres2",
  "expiresAt": "2026-11-13T11:30:00Z",
  "captionToken": null
}
```

### DELETE /v1/control/sessions/:sessionId

End a broadcast session (revoke credential).

```bash
curl -X DELETE http://localhost:9100/v1/control/sessions/550e8400-e29b-41d4-a716-446655440000 \
  -H "X-Invite-Code: c3fa22e5-48d5-42e4-8f3d-7a5f6c9e2b1a"
```

Response (204): No content.

### Admin Endpoints (Dev Only)

**Create invite code**:
```bash
curl -X POST http://localhost:9100/v1/control/admin/invites \
  -H "Content-Type: application/json" \
  -H "X-Admin-Key: dev-secret" \
  -d '{"streamName": "zenterpres2"}'
```

Response (201):
```json
{
  "inviteCode": "c3fa22e5-48d5-42e4-8f3d-7a5f6c9e2b1a",
  "streamName": "zenterpres2",
  "expiresAt": "2026-11-14T09:30:00Z"
}
```

**List active sessions**:
```bash
curl http://localhost:9100/v1/control/admin/sessions \
  -H "X-Admin-Key: dev-secret"
```

## P3 Next Steps

1. Add **ControlPlane client** to app (core-session module) to call these endpoints
2. Add **EncryptedSharedPreferences** to store credentials securely (never log/export)
3. Integrate invite code input into **Settings UI** (Compose)
4. Add **credential refresh loop** to BroadcastSession (auto-refresh before expiry)

## P4+ Roadmap

- **PostgreSQL backend** (replace in-memory Map)
- **Rate limiting** (60 req/min per IP)
- **Webhook notifications** to relay on session lifecycle (so relay can pre-cache)
- **Caption token** minting (scoped JWT for `/api/v1/streams/{name}/captions`)
- **Audit logging** (all session actions)
- **HA deployment** (multiple control-plane instances, shared PostgreSQL)

## Testing

```bash
npm test
```

Dev testing:
```bash
ADMIN_KEY=dev-secret npm run dev
# In another terminal:
curl http://localhost:9100/v1/control/status
```
