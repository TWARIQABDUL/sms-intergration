const { db } = require('../db');
const { hashToken } = require('../util/crypto');

const VALID_API_KEYS = new Set(
  (process.env.API_KEYS || '')
    .split(',')
    .map((k) => k.trim())
    .filter(Boolean)
);

function requireApiKey(req, res, next) {
  const key = req.header('x-api-key');
  if (!key || !VALID_API_KEYS.has(key)) {
    return res.status(401).json({ error: 'missing or invalid X-API-Key header' });
  }
  next();
}

async function requireDeviceToken(req, res, next) {
  const token = req.header('x-device-token');
  if (!token) {
    return res.status(401).json({ error: 'missing X-Device-Token header' });
  }
  try {
    const tokenHash = hashToken(token);
    const result = await db.execute({
      sql: 'SELECT id, name FROM devices WHERE token_hash = ?',
      args: [tokenHash],
    });
    const device = result.rows[0];
    if (!device) {
      return res.status(401).json({ error: 'invalid device token' });
    }
    req.device = device;
    next();
  } catch (err) {
    next(err);
  }
}

module.exports = { requireApiKey, requireDeviceToken, VALID_API_KEYS };
