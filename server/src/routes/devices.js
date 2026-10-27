const express = require('express');
const db = require('../db');
const { newId, newDeviceToken, hashToken } = require('../util/crypto');
const { isNonEmptyString } = require('../util/validators');
const { requireApiKey } = require('../middleware/auth');
const { apiKeyLimiter } = require('../middleware/rateLimit');

const router = express.Router();

// POST /api/devices/register - admin/API-key-authenticated call that
// provisions a new phone. The plaintext token is returned exactly once;
// only its hash is ever persisted server-side.
router.post('/register', apiKeyLimiter, requireApiKey, (req, res) => {
  const { name } = req.body || {};
  if (!isNonEmptyString(name, 100)) {
    return res.status(400).json({ error: 'name is required (max 100 chars)' });
  }

  const id = newId();
  const token = newDeviceToken();
  const now = new Date().toISOString();

  db.prepare(
    `INSERT INTO devices (id, name, token_hash, created_at) VALUES (?, ?, ?, ?)`
  ).run(id, name, hashToken(token), now);

  res.status(201).json({ device_id: id, name, token });
});

// GET /api/devices - basic admin view of registered devices.
router.get('/', apiKeyLimiter, requireApiKey, (req, res) => {
  const devices = db
    .prepare('SELECT id, name, created_at, last_seen_at FROM devices ORDER BY created_at DESC')
    .all();
  res.json({ devices });
});

module.exports = router;
