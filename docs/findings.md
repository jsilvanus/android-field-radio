# android-field-radio: Verification Findings

**Date:** 2026-11-10 (VPS agent, Juha review)  
**Status:** VERIFIED, constraints applied to MVP scope

## P0 Verification: Relay Infrastructure

### Findings Summary

**MediaMTX deployment [PLANNED]**
- **Finding (2026-11-10):** No MediaMTX on the VPS. Ingest is nginx-rtmp 1.23.1 in container `web-radio-relay-nginx` beside `web-radio-relay-api`, bound to `127.0.0.1:1935` only.
- **Update (2026-11-13):** MediaMTX will be added to the VPS.
- **Impact:** Once deployed, the managed lifecycle API (`POST /api/v1/streams/{name}/start`) and per-path MediaMTX/JWT auth (D1-D2 in plan) become available.
- **MVP strategy:** 
  - **Phase P1-P2:** SSH tunnel to loopback RTMP (MVP) for immediate field testing; does not depend on MediaMTX deployment.
  - **Phase P3+:** Direct RTMPS to public MediaMTX + JWT auth when MediaMTX goes live on the VPS. Control plane and managed lifecycle become operational.

**RTMP ingest**
- Nginx version: 1.23.1 (container `web-radio-relay-nginx`)
- Listen: `127.0.0.1:1935` (loopback only, not public)
- Stream-name validation: `[a-z0-9_-]{1,32}` in `on-publish.sh` (no additional auth)
- Public exposure: None via nginx directly. Accessible only via SSH tunnel (C1b).

**Traefik exposure**
- Ingress: :80, :443, :8082 (HTTP/HTTPS only)
- No TCP/UDP entrypoints for RTMPS, SRT, WHIP, or raw RTMP
- Consequence: RTMP must tunnel (the existing Pi radio-tunnel uses reverse tunnelling; phone needs local-forward)

**HLS/archive/listener interface**
- Relay pulls from `RTSP://...` source (MediaMTX path, or fallback to nginx when no source URL)
- `on-publish.sh` starts ffmpeg for HLS + MP3 archive
- Stream validation regex: `^[a-z0-9_-]{1,32}$` (matches plan A1)
- Captions API: `POST /api/v1/streams/{name}/captions` (bearer auth, 202, up to 100 segments, 1-100 epoch ms each)
- Listener delay: `PUT /api/v1/streams/{name}/delay` (0..60 s, HLS 4 s segments with PROGRAM-DATE-TIME)

**Existing SSH tunnel (Pi radio-tunnel)**
- Direction: VPS client → Pi server (reverse tunnel)
- Pi listens: `radio@Pi:1337`
- VPS tunnel spec: `-R 127.0.0.1:11935:127.0.0.1:1935`
- Consequence: Pi-to-VPS tunnel already exists. Phone needs separate inbound entry (not reusable).

**SSH access for phone keys (proposed, not applied)**
- sshd on VPS: stock config, 0.0.0.0:22, password auth enabled
- Proposed new user: `radiopub` (nologin)
- Proposed match rules: `AllowTcpForwarding local`, `PermitTTY no`, `X11Forwarding no`, `permitopen="127.0.0.1:1935"`
- Status: Awaiting deployment approval from Juha (requires `D:\aidos` RFC-0108 phase 4 delivery first)

---

## Detailed Findings

### A1. nginx-rtmp ingest (verified)
Located in container `/opt/apps/web-radio-relay/docker-compose.yml` (`web-radio-relay-nginx`).
- Stream name validation: accepts `[a-z0-9_-]{1,32}` only; rejects others before forwarding to `on-publish.sh`
- No publish-time authentication (validation is name-format only)
- Bindings: `127.0.0.1:1935` (localhost only, not accessible from the internet without a tunnel)

**Implications for MVP:**
- Stream names must be validated client-side to `^[a-z0-9_-]{1,32}$` (already done in `core-profile`)
- Client auth per stream name is not possible at nginx level; must be enforced at relay API level (post-MVP)
- SSH tunnel is required for any publish from outside the VPS network

### A2. Relay API (`/api/v1/...`)
- Endpoint: `POST /api/v1/streams/{name}/start`, `/stop`, `GET .../lifecycle` (managed mode, awaiting MediaMTX)
- Endpoint: `POST /api/v1/streams/{name}/captions` (scoped token via bearer auth, 202 Accepted)
- Endpoint: `PUT /api/v1/streams/{name}/delay` (0..60 s, for HLS segment-level delay)
- Auth token: `RELAY_API_TOKEN` (admin-grade, never in APK or profiles)
- Rate limit: 600 req/min/IP

**Implications:**
- Captions require per-stream scoped tokens (post-MVP work, needs control plane D1-D2)
- App must not store or transmit `RELAY_API_TOKEN` (handled at control-plane level)
- Listener delay is relay-managed; app needs only correct NTP clock for caption timestamps

### A3. Archive and HLS
- Triggered by `on-publish.sh` when stream starts
- Duration: same as publish stream (RTMP connection lifetime)
- Archive format: MP3 (`.mp3` file per stream)
- HLS segments: 4 s intervals with `PROGRAM-DATE-TIME` (enables listener delay)
- On reconnect: ffmpeg is restarted, HLS continues (no published discontinuity visible to listeners if latency allows)

**Implications:**
- Multiple publishers to the same stream name will clobber archive and HLS (1 stream = 1 publisher rule enforced by control plane, see E reliability matrix)
- App is responsible for NOT reconnecting with the same stream name if the control-plane session is lost before it's explicitly ended

### A4. Captions timeline  
- Relay accepts segments via `POST /api/v1/streams/{name}/captions` with epoch millisecond timestamps
- Field `startedAt` (required), `endedAt?` (optional), `text`, `lang` per segment
- Relay releases to listeners at `startedAt + delay`
- Segment limit: up to 100 per request
- Deduplication: none (no segment ID or idempotency key; relay keeps all)

**Implications (post-MVP):**
- Caption app must mint short-lived scoped tokens via control plane
- Timestamps must be synchronized with relay (app checks NTP skew, warn if > 1 s)
- Finals only (no partials) recommended to avoid duplicate renders
- Optional work: relay enhancement for per-caption idempotency key

---

## Roadblocks and Mitigations

| Issue | Blocker? | Mitigation | Phase |
|---|---|---|---|
| No public MediaMTX | No (use SSH tunnel MVP) | SSH tunnel ingest, post-MVP MediaMTX deployment | P3 (post-MVP) |
| No per-stream publish auth at nginx | No (trust SSH key restrictions) | Control plane enforces 1 key per event, revocation at hand | P3 |
| Captions without scoped token | No (skip in MVP) | Post-MVP: control plane mints short-lived caption tokens | P2+ |
| Phone shares Pi's reverse tunnel? | No (separate entry) | New SSH user `radiopub` on VPS (awaiting Juha + RFC-0108 phase 4) | P1 prep |
| sshd not hardened | No (standard Linux) | `Match User radiopub` rules, key-only auth, restricted permissions | P1 prep |

---

## Preconditions for MVP Phases

**P0 Complete ✓**
- Relay ingest is nginx-rtmp 1.23.1 on loopback
- SSH tunnel is the only MVP transport
- Stream-name validation is `^[a-z0-9_-]{1,32}$`

**P1 Ready (given aidos RFC-0108 phase 4)**
- VPS sshd configured with `radiopub` user and SSH key restrictions
- App has access to aidos SSH tunnel component (`TunnelTransport`)

**P2 Ready**
- All of P1
- Core audio/profile/publish modules complete
- Engine threading model settled (single audio thread)

**P3 Ready**
- All of P2
- `field-radio-control` service deployed on VPS
- MediaMTX deployed on VPS (if upgrading from SSH tunnel to managed)

---

## Open Questions for Juha

1. Is the first real event date confirmed? (Decides whether to ship MVP SSH-tunnel-only or wait for MediaMTX.)
2. Can the VPS sshd be configured with the `radiopub` user and key restrictions before P1 testing starts?
3. Is aidos RFC-0108 phase 4 (phone-side SSH local forward + Keystore) scheduled before the app's first field test?
4. Music rights for pre-recorded library: is any in-app licensing/detection wanted, or is it user responsibility?

---

## References

- VPS infrastructure: `/opt/apps/web-radio-relay/` (Docker Compose, nginx-rtmp, ffmpeg, relay API)
- Pi radio-tunnel: systemd unit `radio-tunnel`, user `radiotunnel`, reverse-tunnel SSH to Pi:1337
- Plan section A (Existing architecture assessment) for full relay README extraction
- Plan section C1b (SSH-tunnel transport decision) for MTV/auth postponement
