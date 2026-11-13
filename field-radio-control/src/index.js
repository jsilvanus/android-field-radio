/**
 * field-radio-control: Session management control plane
 * P3 implementation for android-field-radio
 * 
 * Mints short-lived RTMP credentials, manages session lifecycle,
 * prevents duplicate streams, and enforces credential expiry.
 * 
 * Storage: In-memory for MVP; implement PostgreSQL in P4+
 */

const express = require('express');
const { v4: uuidv4 } = require('uuid');
const NodeCache = require('node-cache');

const app = express();
const PORT = process.env.PORT || 9100;

// In-memory storage: sessionId -> { session object }
const sessions = new Map();

// Invite code cache: inviteCode -> { used, createdAt, expiresAt }
const inviteCodes = new NodeCache({ stdTTL: 7200 }); // 2 hours default

// Stream name -> sessionId index for duplicate prevention
const streamSessions = new Map();

app.use(express.json());

// Middleware: Auth with invite code or admin key
function authMiddleware(req, res, next) {
  const inviteCode = req.headers['x-invite-code'] || req.headers['x-admin-key'];
  
  if (!inviteCode) {
    return res.status(401).json({
      code: 'unauthorized',
      message: 'Missing X-Invite-Code or X-Admin-Key header'
    });
  }
  
  // Admin key bypass for dev/testing (env var)
  if (inviteCode === process.env.ADMIN_KEY && process.env.ADMIN_KEY) {
    req.auth = { type: 'admin', key: inviteCode };
    return next();
  }
  
  // Validate invite code
  const invite = inviteCodes.get(inviteCode);
  if (!invite) {
    return res.status(401).json({
      code: 'invalid_invite',
      message: 'Invite code not found or expired'
    });
  }
  
  if (new Date() > new Date(invite.expiresAt)) {
    inviteCodes.del(inviteCode);
    return res.status(401).json({
      code: 'invite_expired',
      message: 'Invite code has expired'
    });
  }
  
  req.auth = { type: 'invite', code: inviteCode, streamName: invite.streamName };
  next();
}

// POST /v1/control/sessions - Create session
app.post('/v1/control/sessions', authMiddleware, (req, res) => {
  const { profileHint } = req.body;
  
  // Generate stream name from invite (or use hint as fallback for admin)
  let streamName;
  if (req.auth.type === 'invite') {
    streamName = req.auth.streamName;
  } else if (req.auth.type === 'admin') {
    streamName = profileHint || `stream_${Date.now()}`;
  }
  
  // Check for duplicate active session
  if (streamSessions.has(streamName)) {
    return res.status(409).json({
      code: 'stream_name_taken',
      message: `Stream '${streamName}' already has an active session`
    });
  }
  
  // Create session
  const sessionId = uuidv4();
  const createdAt = new Date();
  const expiresAt = new Date(createdAt.getTime() + 2 * 60 * 60 * 1000); // 2 hours
  const publishCredential = `rtmp://127.0.0.1:1935/live/${streamName}`; // MVP: direct RTMP
  
  const session = {
    sessionId,
    streamName,
    publishUrl: `rtmp://127.0.0.1:1935/live`,
    publishCredential,
    captionToken: null,
    expiresAt: expiresAt.toISOString(),
    createdAt: createdAt.toISOString(),
    profileHint,
    authType: req.auth.type
  };
  
  sessions.set(sessionId, session);
  streamSessions.set(streamName, sessionId);
  
  // If invite code, mark as used
  if (req.auth.type === 'invite') {
    const invite = inviteCodes.get(req.auth.code);
    if (invite) invite.used = true;
  }
  
  res.status(201).json(session);
});

// DELETE /v1/control/sessions/:sessionId - End session
app.delete('/v1/control/sessions/:sessionId', authMiddleware, (req, res) => {
  const { sessionId } = req.params;
  const session = sessions.get(sessionId);
  
  if (!session) {
    return res.status(404).json({
      code: 'session_not_found',
      message: `Session ${sessionId} not found`
    });
  }
  
  // Revoke: clear the session and stream name index
  sessions.delete(sessionId);
  streamSessions.delete(session.streamName);
  
  res.status(204).send();
});

// POST /v1/control/sessions/:sessionId/refresh - Refresh credential
app.post('/v1/control/sessions/:sessionId/refresh', authMiddleware, (req, res) => {
  const { sessionId } = req.params;
  const session = sessions.get(sessionId);
  
  if (!session) {
    return res.status(404).json({
      code: 'session_not_found',
      message: `Session ${sessionId} not found`
    });
  }
  
  // Check expiry
  if (new Date() > new Date(session.expiresAt)) {
    sessions.delete(sessionId);
    streamSessions.delete(session.streamName);
    return res.status(401).json({
      code: 'credential_expired',
      message: 'Session credential has expired'
    });
  }
  
  // Refresh: new credential and extended expiry
  const oldExpiresAt = new Date(session.expiresAt);
  const expiresAt = new Date(Math.max(new Date().getTime() + 2 * 60 * 60 * 1000, oldExpiresAt.getTime()));
  const publishCredential = `rtmp://127.0.0.1:1935/live/${session.streamName}`;
  
  session.publishCredential = publishCredential;
  session.expiresAt = expiresAt.toISOString();
  
  res.status(200).json({
    publishCredential,
    expiresAt: expiresAt.toISOString(),
    captionToken: null
  });
});

// GET /v1/control/status - Health check
app.get('/v1/control/status', (req, res) => {
  res.status(200).json({
    status: 'ok',
    version: '1.0.0',
    mediaUrl: 'rtmp://127.0.0.1:1935',
    relayUrl: 'http://localhost:8080',
    credentialTtlSeconds: 7200,
    activeSessions: sessions.size,
    timestamp: new Date().toISOString()
  });
});

// Admin endpoint: List all active sessions (dev only)
app.get('/v1/control/admin/sessions', (req, res) => {
  if (req.headers['x-admin-key'] !== process.env.ADMIN_KEY || !process.env.ADMIN_KEY) {
    return res.status(401).json({ code: 'unauthorized', message: 'Admin key required' });
  }
  
  const sessionsList = Array.from(sessions.values()).map(s => ({
    sessionId: s.sessionId,
    streamName: s.streamName,
    createdAt: s.createdAt,
    expiresAt: s.expiresAt,
    profileHint: s.profileHint
  }));
  
  res.status(200).json({ sessions: sessionsList });
});

// Admin endpoint: Create invite code (dev only)
app.post('/v1/control/admin/invites', express.json(), (req, res) => {
  if (req.headers['x-admin-key'] !== process.env.ADMIN_KEY || !process.env.ADMIN_KEY) {
    return res.status(401).json({ code: 'unauthorized', message: 'Admin key required' });
  }
  
  const { streamName } = req.body;
  if (!streamName) {
    return res.status(400).json({ code: 'invalid_request', message: 'streamName required' });
  }
  
  const inviteCode = uuidv4();
  const expiresAt = new Date(new Date().getTime() + 24 * 60 * 60 * 1000); // 24 hours
  
  inviteCodes.set(inviteCode, { streamName, expiresAt: expiresAt.toISOString(), used: false });
  
  res.status(201).json({
    inviteCode,
    streamName,
    expiresAt: expiresAt.toISOString()
  });
});

// Error handler
app.use((err, req, res, next) => {
  console.error('Error:', err);
  res.status(500).json({
    code: 'internal_error',
    message: 'Internal server error',
    details: process.env.NODE_ENV === 'development' ? err.message : undefined
  });
});

// Start server
app.listen(PORT, () => {
  console.log(`field-radio-control listening on :${PORT}`);
  console.log(`Environment: ${process.env.NODE_ENV || 'development'}`);
  console.log(`Admin key configured: ${!!process.env.ADMIN_KEY}`);
});

module.exports = app;
