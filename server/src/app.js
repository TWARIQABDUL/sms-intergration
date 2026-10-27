const express = require('express');
const requestLogger = require('./middleware/logger');
const smsRoutes = require('./routes/sms');
const deviceRoutes = require('./routes/devices');

const app = express();

app.use(express.json({ limit: '32kb' }));
app.use(requestLogger);

app.get('/health', (req, res) => res.json({ ok: true }));

app.use('/api/sms', smsRoutes);
app.use('/api/devices', deviceRoutes);

// 404 fallback
app.use((req, res) => res.status(404).json({ error: 'not found' }));

// Error handler - never leak stack traces or request bodies to the client/logs.
app.use((err, req, res, next) => {
  console.log(JSON.stringify({ ts: new Date().toISOString(), level: 'error', message: err.message }));
  res.status(500).json({ error: 'internal server error' });
});

module.exports = app;
