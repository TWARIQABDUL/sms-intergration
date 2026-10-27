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
