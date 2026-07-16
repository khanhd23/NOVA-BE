# NOVA Backend

Spring Boot backend service for the NOVA app.

## Goals

- Keep the backend easy to read from the UI layer.
- Support social login via Google/Facebook.
- Expose stable API modules for auth, account, content, social, community, and safety.
- Expose stable commerce APIs for premium tiers, diamond packs, orders, and payment webhooks.
- Persist core identity/session/profile/device-token data in the database.
- Persist the mutable content, community, safety, social, and commerce runtime state in the database as module snapshots.

## Run

From the repo root:

```bash
./gradlew bootRun
```

Build a jar for VPS deployment:

```bash
./gradlew bootJar
```

Run tests:

```bash
./gradlew test
```

## Module map

- `auth` - social login, session issuing, refresh/logout.
- `account` - profile, settings, badges, wallet, premium entitlements.
- `content` - home feed, discovery, media catalog.
- `social` - chat threads, messages, calls, notifications.
- `social` now supports chat list, one-side delete, message recall, read state, and typing.
- `community` - community topics, posts, events.
- `safety` - reports, blocks, admin metrics.
- `commerce` - VIP tiers, diamond packs, orders, checkout, payment callbacks.

## Strategy

- Use Google/Facebook only for identity proof.
- Convert provider identity into an internal `userId`.
- Keep API responses screen-oriented so the app can evolve without breaking the backend shape.
- Prefer provider abstraction for payments. `DEMO` works locally; `MOMO` and `ZALOPAY` are the best candidates for a real VN payment flow later.
- For messaging, keep REST as the source of truth and add FCM + WebSocket/SSE when you move to realtime.

## Server deployment

Run the packaged jar on a VPS:

```bash
java -jar build/libs/nova-backend-0.1.0-SNAPSHOT.jar
```

For a real VPS, prefer copying `.env.example` to `.env` and loading it from systemd. The deploy files live in `deploy/`.

Recommended environment variables:

```bash
export SERVER_PORT=8081
export DATABASE_URL=jdbc:h2:file:./data/nova-db;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1
export DATABASE_USERNAME=sa
export DATABASE_PASSWORD=
export NOVA_UPLOAD_DIR=./uploads
export NOVA_UPLOAD_MAX_FILE_SIZE=50MB
export NOVA_UPLOAD_MAX_REQUEST_SIZE=60MB
export NOVA_GOOGLE_WEB_CLIENT_ID=655774214440-31qsc49ov49bemf24c1uoqunenebto31.apps.googleusercontent.com
export NOVA_ALLOW_DEV_TOKENS=false
export NOVA_FIREBASE_ENABLED=false
export NOVA_FIREBASE_PROJECT_ID=
export NOVA_FIREBASE_SERVICE_ACCOUNT_PATH=
export NOVA_WEBRTC_ICE_SERVERS=stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302
export NOVA_WEBRTC_TURN_SERVERS=
export NOVA_WEBRTC_TURN_USERNAME=
export NOVA_WEBRTC_TURN_CREDENTIAL=
export NOVA_WEBRTC_MAX_RESTART_ATTEMPTS=2
export NOVA_WEBRTC_RESTART_BACKOFF_MS=1000
```

The backend stores accounts, auth sessions, device tokens, and module runtime state in the database. By default it uses a local H2 file database so the jar can run without a separate DB service. If you want PostgreSQL on the VPS, change `DATABASE_URL` to a PostgreSQL JDBC URL and set the username/password accordingly.

The backend also persists uploaded media on disk. Make sure `NOVA_UPLOAD_DIR` points to a writable directory on the VPS, and set your reverse proxy `client_max_body_size` to at least `NOVA_UPLOAD_MAX_REQUEST_SIZE`.

For call stability on real mobile networks, set `NOVA_WEBRTC_TURN_SERVERS` together with `NOVA_WEBRTC_TURN_USERNAME` and `NOVA_WEBRTC_TURN_CREDENTIAL`. Without TURN, WebRTC may still work on many networks, but it will be less reliable behind strict NATs.

If you put the backend behind Nginx or another reverse proxy, keep HTTPS in front of the app and leave `server.forward-headers-strategy=framework` enabled so forwarded headers are handled correctly.

For a copy-paste VPS baseline, use:

- `deploy/systemd/nova-backend.service`
- `deploy/nginx/nova-backend.conf`
- `.env.example`

## Android client config

When you build the Android app against a VPS or public API, pass the backend URL as a Gradle property:

```bash
./gradlew -PbackendBaseUrl=https://api.your-domain.com assembleDebug
```

If you do not pass it, the app falls back to `http://10.0.2.2:8081` for emulator testing.

## Social login behavior

- `GOOGLE` accepts a real Google ID token from the Android app.
- `dev:*` tokens only work while `NOVA_ALLOW_DEV_TOKENS=true` for local testing.
- New Google users are created with `profileRequired=true` so the app routes them into profile setup before home.
