const express = require('express');
const { db } = require('../db');
const { newId, newDeviceToken, hashToken } = require('../util/crypto');
const { isNonEmptyString } = require('../util/validators');
const { requireApiKey } = require('../middleware/auth');
const { apiKeyLimiter } = require('../middleware/rateLimit');
const asyncHandler = require('../util/asyncHandler');

const router = express.Router();

// POST /api/devices/register - admin/API-key-authenticated call that
// provisions a new phone. The plaintext token is returned exactly once;
// only its hash is ever persisted server-side.
router.post('/register', apiKeyLimiter, requireApiKey, asyncHandler(async (req, res) => {
  const { name } = req.body || {};
  if (!isNonEmptyString(name, 100)) {
    return res.status(400).json({ error: 'name is required (max 100 chars)' });
  }

  const id = newId();
  const token = newDeviceToken();
  const now = new Date().toISOString();

  await db.execute({
    sql: 'INSERT INTO devices (id, name, token_hash, created_at) VALUES (?, ?, ?, ?)',
    args: [id, name, hashToken(token), now],
  });

  res.status(201).json({ device_id: id, name, token });
}));

// GET /api/devices - basic admin view of registered devices.
router.get('/', apiKeyLimiter, requireApiKey, asyncHandler(async (req, res) => {
  const result = await db.execute('SELECT id, name, created_at, last_seen_at FROM devices ORDER BY created_at DESC');
  res.json({ devices: result.rows });
}));

module.exports = router;
