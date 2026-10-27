const crypto = require('crypto');

function newId() {
  return crypto.randomUUID();
}

// Device tokens are high-entropy bearer secrets. We store only a SHA-256
// hash server-side (never the plaintext token) so a DB leak doesn't expose
// usable credentials directly.
function newDeviceToken() {
  return crypto.randomBytes(32).toString('hex');
}

function hashToken(token) {
  return crypto.createHash('sha256').update(token, 'utf8').digest('hex');
}

module.exports = { newId, newDeviceToken, hashToken };
