# Backend Architecture

This backend is a Spring Boot modular monolith.

## Layers

1. `config`
2. `common`
3. Feature modules
4. Persistence, runtime stores, and seed data

## Design rules

- Controllers stay thin.
- Services own business rules.
- Shared account data is the source of truth for other modules.
- Current persistence is JDBC-backed via `schema.sql`, with H2 as the default file database and PostgreSQL available through `DATABASE_URL`.
- Mutable runtime state is persisted in `module_state`, `auth_sessions`, `device_tokens`, `profile_follows`, and `accounts`.
- Uploaded files live under `storage.upload-dir`; the backend creates the directory if it does not exist.
- In-memory collections are only used for module-local caches and seeded demo content.
- Later replacement points are:
  - account store -> stricter relational schema plus migrations
  - session store -> Redis or another transient store if needed
  - media store -> object storage
  - notifications -> queue/worker
  - commerce orders -> PostgreSQL + payment provider callbacks

## UI alignment

The backend mirrors the current Android UI:

- auth/onboarding -> `auth`
- profile/settings/premium -> `account`
- home/discover/feed -> `content`
- chat/call/notifications -> `social`
- premium/VIP/diamonds/orders/payments -> `commerce`
- community/events -> `community`
- safety/admin -> `safety`

## Deployment notes

- The backend is ready to run behind a reverse proxy on port 8081 by default.
- The proxy should forward `X-Forwarded-*` headers and WebSocket upgrades for `/ws/realtime`.
- Keep the upload body limit at or above `NOVA_UPLOAD_MAX_REQUEST_SIZE`.
- Use a writable `NOVA_UPLOAD_DIR` on the VPS, preferably outside the application source tree.
