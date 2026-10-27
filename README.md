# Android SMS Gateway System

Turns an Android phone into a programmatic SMS gateway. A Node.js/Express/SQLite
backend queues outbound messages; the phone polls the backend, sends messages
through its own SIM via `SmsManager`, and reports delivery status back.

```
Your code --POST /api/sms/send--> Server (SQLite queue) <--GET /api/sms/pending-- Android app
                                        ^                                    |
                                        +---------POST /api/sms/status-------+
```

## Repo layout

- `server/` — Node.js + Express + SQLite backend (`npm install && npm start`)
- `android/` — Android Studio project (Kotlin), builds to an APK

## 1. Server deployment

```bash
cd server
npm install
cp .env.example .env
```

Edit `.env`:

- `API_KEYS` — comma-separated list of strong random keys for whoever calls
  `/api/sms/send`, `/api/devices/register`, `/api/devices`, `/api/sms/:id`.
  Generate one with `openssl rand -hex 32`.
- `PORT`, `DB_PATH`, rate-limit settings — defaults are fine to start.

```bash
npm start
```

The server listens on `PORT` (default 3000) and creates the SQLite file at
`DB_PATH` on first run. `GET /health` returns `{"ok":true}` once it's up.

Deploy it anywhere that can reach the internet over HTTPS (a VM, a small
cloud box, etc.) and put a TLS-terminating reverse proxy (Caddy, nginx +
certbot, or a platform's managed HTTPS) in front of it — the Android app
refuses non-HTTPS server URLs by design.

### Generating a device token

Each phone needs a device token, issued by the server:

```bash
curl -s -X POST https://your-server.example.com/api/devices/register \
  -H "X-API-Key: YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"name":"Pixel 7a - front desk"}'
```

Response:

```json
{"device_id":"...", "name":"Pixel 7a - front desk", "token":"6a2e84...593e02"}
```

**Copy the `token` now** — only its hash is stored server-side, so it cannot
be retrieved again. This is what you'll paste into the app's Settings screen
as "Device token".

List registered devices and their last-seen poll time:

```bash
curl -s https://your-server.example.com/api/devices -H "X-API-Key: YOUR_API_KEY"
```

## 2. Building and sideloading the Android app

Open the `android/` folder as a project in Android Studio (Hedgehog or
newer). On first open, Android Studio will offer to regenerate the Gradle
wrapper jar (this repo ships `gradle-wrapper.properties` but not the binary
wrapper jar) — accept that, or run `gradle wrapper` once yourself if you have
a system Gradle install. Then:

1. Let Gradle sync (downloads Android Gradle Plugin, Kotlin, Retrofit, Room, etc.)
2. `Build > Build Bundle(s) / APK(s) > Build APK(s)`
3. The APK lands under `android/app/build/outputs/apk/debug/app-debug.apk`

To sideload onto a phone:

```bash
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

or copy the APK to the phone and open it (enable "Install unknown apps" for
whatever app you use to open it — Files, a browser, etc.).

> **Note on this deliverable:** this environment has no Android SDK/emulator,
> so the Kotlin sources, Gradle config, manifest, and resources here are
> complete and internally consistent, but the APK itself was not compiled or
> run on a device from this session. Build it in Android Studio as above
> before relying on it.

### Granting permissions on first launch

1. Launch the app. It shows an explanation dialog, then the system "Allow
   SMS" permission prompt — accept it.
2. It then offers to exempt the app from battery optimization (so Android
   doesn't kill the background service); accept that too.
3. Open **Settings** (button on the main screen) and fill in:
   - **Server URL** — `https://your-server.example.com` (plain `http://` is
     rejected at validation time)
   - **Device token** — the token from `/api/devices/register` above
   - **Polling interval** — how often (seconds) the phone checks for new
     messages; default 20
   - **Choose SIM** — only shown/useful on dual-SIM phones; picks which SIM
     slot sends outgoing messages
4. Save, go back to the main screen, and tap **Start**. The persistent
   notification "SMS Gateway running — N sent today" confirms the foreground
   service is active.

## 3. End-to-end test

With the server running, a device registered, and the app configured and
started on a real phone with a SIM:

```bash
curl -s -X POST https://your-server.example.com/api/sms/send \
  -H "X-API-Key: YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"to":"+15551234567","body":"Hello from the SMS gateway"}'
```

Response: `{"id":"<message-id>"}`.

Within one polling interval, the phone should pick this up and send it — you
should see the SMS arrive on `+15551234567`, and the app's status screen log
show a `delivered` (or `sent` → `delivered`) entry.

Confirm server-side status:

```bash
curl -s https://your-server.example.com/api/sms/<message-id> -H "X-API-Key: YOUR_API_KEY"
```

```json
{"id":"...", "to":"+15551234567", "status":"delivered", "device_id":"...", ...}
```

If the carrier doesn't send delivery reports for a given route, `status` may
stay at `sent_to_device` even though the SMS was actually delivered — that's
a carrier limitation, not a bug in this system.

## API Reference

Everything you'd need to build an admin/dashboard UI against this backend.
All endpoints are JSON in, JSON out, over HTTPS.

### Auth

Two separate header-based schemes — a dashboard typically only ever needs
the first one.

| Scheme | Header | Who holds it | Used by |
|---|---|---|---|
| API key | `X-API-Key: <key>` | your dashboard / server-side callers | `/api/sms/send`, `/api/sms/:id`, `/api/devices/register`, `/api/devices` |
| Device token | `X-Device-Token: <token>` | the Android app, one per phone | `/api/sms/pending`, `/api/sms/status` |

- API keys are static values from the `API_KEYS` env var (comma-separated —
  you can issue more than one, e.g. one per dashboard/integration, and
  revoke one by removing it from the list and redeploying).
- Device tokens are minted by `POST /api/devices/register` and only ever
  shown once in that response; the server stores just a SHA-256 hash.
- Missing/invalid credentials → `401 {"error": "..."}`.

### Rate limiting

Every endpoint is rate-limited, keyed by the credential used (API key or
device token; falls back to IP if neither is present). Limits are
configurable via env vars (`RATE_LIMIT_WINDOW_MS`,
`RATE_LIMIT_MAX_PER_API_KEY`, `RATE_LIMIT_MAX_PER_DEVICE_TOKEN`). Exceeding
the limit → `429` with standard `RateLimit-*` response headers and
`{"error": "rate limit exceeded for this API key|device token"}`.

### Error shape

Every error response is `{"error": "<human-readable message>"}` with a
matching HTTP status (`400` validation, `401` auth, `403` forbidden, `404`
not found, `429` rate limited, `500` unexpected server error). `500`
responses never include request details — see the logging note below.

### Message lifecycle

```
pending --(device polls)--> sent_to_device --(device reports outcome)--> delivered
                                            \-> failed
```

A message can also sit at `sent_to_device` indefinitely if the carrier
never sends a delivery confirmation — that's a carrier limitation, not a
stuck state in this system (the SMS likely did arrive).

### Endpoints

#### `GET /health`
No auth. Liveness check (useful for Render's health check / dashboard
"is the backend up" indicator).

```json
{"ok": true}
```

---

#### `POST /api/devices/register`
Auth: `X-API-Key`. Provisions a new phone and issues its device token.

Request body:
```json
{"name": "Pixel 7a - front desk"}
```
- `name`: required, non-empty string, max 100 chars.

Response `201`:
```json
{"device_id": "uuid", "name": "Pixel 7a - front desk", "token": "64-hex-char secret"}
```
`token` is shown **only here** — the server only ever stores its hash.
A dashboard that needs to re-issue a token for a phone should call this
again with the same name (it creates a new device record with a new id;
there's currently no "rotate token for existing device id" endpoint).

---

#### `GET /api/devices`
Auth: `X-API-Key`. Lists registered devices — the natural data source for
a dashboard's device table.

Response `200`:
```json
{
  "devices": [
    {"id": "uuid", "name": "Pixel 7a - front desk", "created_at": "ISO-8601", "last_seen_at": "ISO-8601 | null"}
  ]
}
```
`last_seen_at` updates every time that device successfully calls
`GET /api/sms/pending` — it's the field to watch for "is this phone still
polling" in a dashboard.

---

#### `POST /api/sms/send`
Auth: `X-API-Key`. Queues a new outbound message. This is the endpoint a
dashboard's "send message" form submits to.

Request body:
```json
{"to": "+15551234567", "body": "Hello"}
```
- `to`: required, E.164-ish (`+` optional, 8–15 digits, first digit 1-9).
- `body`: required, 1–1600 chars (≈10 concatenated SMS segments).

Response `201`:
```json
{"id": "uuid"}
```
Save this `id` — it's what you poll with `GET /api/sms/:id`.

---

#### `GET /api/sms/:id`
Auth: `X-API-Key`. Full status of one message — what a dashboard's message
detail view / status poller calls.

Response `200`:
```json
{
  "id": "uuid",
  "to": "+15551234567",
  "status": "pending | sent_to_device | delivered | failed",
  "device_id": "uuid | null",
  "error": "string | null",
  "created_at": "ISO-8601",
  "sent_to_device_at": "ISO-8601 | null",
  "delivered_at": "ISO-8601 | null",
  "failed_at": "ISO-8601 | null",
  "updated_at": "ISO-8601"
}
```
Note: this does **not** return `body` (message text isn't echoed back by
design — see the logging/privacy note below; if a dashboard needs to show
the original text, it should keep its own copy at send time).

404 if the id doesn't exist.

There's currently no "list all messages" endpoint — a dashboard that wants
a message history/table would need to either keep its own record of every
`id` it got back from `/api/sms/send`, or that's the first thing to add
server-side (`GET /api/sms?status=&limit=&before=`) before building that view.

---

#### `GET /api/sms/pending`
Auth: `X-Device-Token`. Called by the Android app, not a dashboard. Atomically
claims up to 20 oldest `pending` messages for that device (marks them
`sent_to_device` so no other poll — same device or another — refetches them).

Response `200`:
```json
{"messages": [{"id": "uuid", "to": "+15551234567", "body": "Hello"}]}
```

---

#### `POST /api/sms/status`
Auth: `X-Device-Token`. Called by the Android app to report an outcome, not
a dashboard. A device can only update messages it was itself assigned.

Request body:
```json
{"id": "uuid", "status": "delivered", "error": "optional string, only meaningful when status is failed"}
```

Response `200`:
```json
{"ok": true}
```
`403` if the message wasn't assigned to the calling device's token.

### Gaps to fill before building a full dashboard

The above covers everything the current backend exposes. A real dashboard
would likely also want (none of this exists yet — flagging it for later):
- `GET /api/sms` — paginated/filterable message list (by status, device, date range)
- A way to revoke/rotate a single device's token without re-registering
- A way to revoke a single API key without redeploying with a new `API_KEYS` list
- Webhooks or SSE/WebSocket push instead of polling, if a dashboard wants live updates

## Design notes / deviations worth knowing about

- **Foreground service type**: there is no official Android foreground
  service type literally named `phone`. The closest correct type for an app
  that forwards/sends messages on behalf of a server is `remoteMessaging`
  (`android:foregroundServiceType="remoteMessaging"` +
  `FOREGROUND_SERVICE_REMOTE_MESSAGING` permission), which is what's used
  here.
- **Message IDs** are server-generated UUIDs (`crypto.randomUUID()`), not
  sequential integers, so they can't be enumerated/guessed by a party who
  only knows one id.
- **Device tokens** are high-entropy random bearer tokens; the server stores
  only a SHA-256 hash of each one, never the plaintext. The Android app
  stores its copy in `EncryptedSharedPreferences`.
- **Retry queue**: failures that never reach a delivery confirmation (no
  SIM, radio off/airplane mode, generic `SmsManager` failure) are queued in
  a local Room database and retried with exponential backoff (30s, 60s,
  120s, 240s, 480s), capped at 5 attempts, after which the server is told
  `status: "failed"` and the local record is dropped.
- **Multipart SMS**: a message over 160 characters is split with
  `SmsManager.divideMessage()`/`sendMultipartTextMessage()`. The app tracks
  all parts' `SMS_SENT`/`SMS_DELIVERED` callbacks and only reports
  `delivered` once every part confirms delivery; any part failing fails the
  whole message.
- **Logging**: the server's structured request logger never logs
  `req.body`, so SMS text never reaches console/log output — only metadata
  (method, path, status, truncated API key/device token, message id).
