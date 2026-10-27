const fs = require('fs');
const path = require('path');
const { createClient } = require('@libsql/client');

// In production (Render), point TURSO_DATABASE_URL/TURSO_AUTH_TOKEN at a
// Turso database so data survives the host's ephemeral filesystem. Locally,
// with those unset, we fall back to an embedded SQLite file via libSQL's
// "file:" URL scheme - same API, no Turso account needed for dev.
const url = process.env.TURSO_DATABASE_URL || `file:${process.env.DB_PATH || path.join(__dirname, '..', '..', 'data', 'sms-gateway.db')}`;
const authToken = process.env.TURSO_AUTH_TOKEN;

if (url.startsWith('file:')) {
  fs.mkdirSync(path.dirname(url.slice('file:'.length)), { recursive: true });
}

const db = createClient({ url, authToken });

async function migrate() {
  await db.batch(
    [
      `CREATE TABLE IF NOT EXISTS devices (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        token_hash TEXT NOT NULL UNIQUE,
        created_at TEXT NOT NULL,
        last_seen_at TEXT
      )`,
      `CREATE TABLE IF NOT EXISTS messages (
        id TEXT PRIMARY KEY,
        to_number TEXT NOT NULL,
        body TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'pending',
        device_id TEXT,
        error TEXT,
        created_at TEXT NOT NULL,
        sent_to_device_at TEXT,
        delivered_at TEXT,
        failed_at TEXT,
        updated_at TEXT NOT NULL,
        FOREIGN KEY (device_id) REFERENCES devices(id)
      )`,
      `CREATE INDEX IF NOT EXISTS idx_messages_status ON messages(status)`,
    ],
    'write'
  );
}

module.exports = { db, migrate };
