const rateLimit = require('express-rate-limit');

const windowMs = Number(process.env.RATE_LIMIT_WINDOW_MS) || 60_000;
const maxPerApiKey = Number(process.env.RATE_LIMIT_MAX_PER_API_KEY) || 60;
const maxPerDeviceToken = Number(process.env.RATE_LIMIT_MAX_PER_DEVICE_TOKEN) || 30;

// Rate limit by API key (falls back to IP if the header is missing so
// unauthenticated spam still gets throttled before the auth middleware
// rejects it with a 401).
const apiKeyLimiter = rateLimit({
  windowMs,
  max: maxPerApiKey,
  standardHeaders: true,
  legacyHeaders: false,
  keyGenerator: (req) => req.header('x-api-key') || req.ip,
  message: { error: 'rate limit exceeded for this API key' },
});

// Rate limit by device token.
const deviceTokenLimiter = rateLimit({
  windowMs,
  max: maxPerDeviceToken,
  standardHeaders: true,
  legacyHeaders: false,
  keyGenerator: (req) => req.header('x-device-token') || req.ip,
  message: { error: 'rate limit exceeded for this device token' },
});

module.exports = { apiKeyLimiter, deviceTokenLimiter };
