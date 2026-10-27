require('dotenv').config();
const app = require('./app');
const { VALID_API_KEYS } = require('./middleware/auth');
const { migrate } = require('./db');

if (VALID_API_KEYS.size === 0) {
  console.error('No API_KEYS configured. Set API_KEYS in your .env file before starting the server.');
  process.exit(1);
}

const port = Number(process.env.PORT) || 3000;

migrate()
  .then(() => {
    app.listen(port, () => {
      console.log(JSON.stringify({ ts: new Date().toISOString(), level: 'info', message: `listening on :${port}` }));
    });
  })
  .catch((err) => {
    console.error(JSON.stringify({ ts: new Date().toISOString(), level: 'error', message: `migration failed: ${err.message}` }));
    process.exit(1);
  });
