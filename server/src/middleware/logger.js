// Structured request logger. Deliberately logs only metadata (method, path,
// status, duration, auth identity, message id when relevant) and NEVER the
// request body, so SMS content never ends up in console/log output.
function requestLogger(req, res, next) {
  const startedAt = Date.now();

  res.on('finish', () => {
    const entry = {
      ts: new Date().toISOString(),
      method: req.method,
      path: req.path,
      status: res.statusCode,
      duration_ms: Date.now() - startedAt,
      api_key: maskSecret(req.header('x-api-key')),
      device_token: maskSecret(req.header('x-device-token')),
      message_id: req.params.id || req.body?.id || res.locals.messageId || undefined,
    };
    // Remove undefined fields for cleaner output.
    Object.keys(entry).forEach((k) => entry[k] === undefined && delete entry[k]);
    console.log(JSON.stringify(entry));
  });

  next();
}

function maskSecret(value) {
  if (!value) return undefined;
  if (value.length <= 8) return '***';
  return `${value.slice(0, 4)}...${value.slice(-4)}`;
}

module.exports = requestLogger;
