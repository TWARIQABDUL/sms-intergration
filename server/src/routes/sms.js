const express = require('express');
const db = require('../db');
const { newId } = require('../util/crypto');
const { isValidE164, normalizePhone, isNonEmptyString } = require('../util/validators');
const { requireApiKey, requireDeviceToken } = require('../middleware/auth');
const { apiKeyLimiter, deviceTokenLimiter } = require('../middleware/rateLimit');

const router = express.Router();

const MAX_BODY_LEN = 1600; // ~10 concatenated SMS segments of 160 chars
const PENDING_BATCH_SIZE = 20;

function toPublicMessage(row) {
  return {
    id: row.id,
    to: row.to_number,
    status: row.status,
    device_id: row.device_id || null,
    error: row.error || null,
    created_at: row.created_at,
    sent_to_device_at: row.sent_to_device_at || null,
    delivered_at: row.delivered_at || null,
    failed_at: row.failed_at || null,
    updated_at: row.updated_at,
  };
}

// POST /api/sms/send - queue a new outbound message.
router.post('/send', apiKeyLimiter, requireApiKey, (req, res) => {
  const { to, body } = req.body || {};

  if (!isValidE164(to)) {
    return res.status(400).json({ error: 'to must be a valid E.164-ish phone number, e.g. +15551234567' });
  }
  if (!isNonEmptyString(body, MAX_BODY_LEN)) {
    return res.status(400).json({ error: `body must be a non-empty string up to ${MAX_BODY_LEN} characters` });
  }

  const id = newId();
  const now = new Date().toISOString();

  db.prepare(
    `INSERT INTO messages (id, to_number, body, status, created_at, updated_at)
     VALUES (?, ?, ?, 'pending', ?, ?)`
  ).run(id, normalizePhone(to), body, now, now);

  res.locals.messageId = id;
  res.status(201).json({ id });
});

// GET /api/sms/pending - device polls for work. Atomically claims up to
// PENDING_BATCH_SIZE pending messages so concurrent/duplicate polls from the
// same or another device never fetch the same row twice.
router.get('/pending', deviceTokenLimiter, requireDeviceToken, (req, res) => {
  const now = new Date().toISOString();

  const claimed = db
    .prepare(
      `UPDATE messages
       SET status = 'sent_to_device', device_id = ?, sent_to_device_at = ?, updated_at = ?
       WHERE id IN (
         SELECT id FROM messages WHERE status = 'pending' ORDER BY created_at LIMIT ?
       )
       RETURNING id, to_number, body`
    )
    .all(req.device.id, now, now, PENDING_BATCH_SIZE);

  db.prepare('UPDATE devices SET last_seen_at = ? WHERE id = ?').run(now, req.device.id);

  res.json({
    messages: claimed.map((row) => ({ id: row.id, to: row.to_number, body: row.body })),
  });
});

// POST /api/sms/status - device reports the outcome of a send attempt.
router.post('/status', deviceTokenLimiter, requireDeviceToken, (req, res) => {
  const { id, status, error } = req.body || {};

  if (!isNonEmptyString(id, 200) || !['delivered', 'failed'].includes(status)) {
    return res.status(400).json({ error: "id is required and status must be 'delivered' or 'failed'" });
  }

  const message = db.prepare('SELECT * FROM messages WHERE id = ?').get(id);
  if (!message) {
    return res.status(404).json({ error: 'message not found' });
  }
  if (message.device_id !== req.device.id) {
    return res.status(403).json({ error: 'message was not assigned to this device' });
  }

  const now = new Date().toISOString();
  res.locals.messageId = id;

  if (status === 'delivered') {
    db.prepare(
      `UPDATE messages SET status = 'delivered', delivered_at = ?, error = NULL, updated_at = ? WHERE id = ?`
    ).run(now, now, id);
  } else {
    db.prepare(
      `UPDATE messages SET status = 'failed', failed_at = ?, error = ?, updated_at = ? WHERE id = ?`
    ).run(now, isNonEmptyString(error, 500) ? error : null, now, id);
  }

  res.json({ ok: true });
});

// GET /api/sms/:id - caller checks on the status of a previously sent message.
router.get('/:id', apiKeyLimiter, requireApiKey, (req, res) => {
  const message = db.prepare('SELECT * FROM messages WHERE id = ?').get(req.params.id);
  if (!message) {
    return res.status(404).json({ error: 'message not found' });
  }
  res.json(toPublicMessage(message));
});

module.exports = router;
