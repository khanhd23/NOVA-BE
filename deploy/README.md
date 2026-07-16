# VPS Deployment

This folder contains the minimal files needed to run the backend on a Linux VPS with systemd and Nginx.

Assume the full repository is checked out at `/opt/nova`, so the backend project lives at `/opt/nova/backend`.

## Files

- `systemd/nova-backend.service` - systemd unit that runs the packaged jar.
- `nginx/nova-backend.conf` - reverse proxy config with WebSocket support and upload limits.

## Quick setup

1. Copy `backend/.env.example` to `backend/.env` in the repository checkout and edit the values.
1. Build the jar:

```bash
./gradlew -p backend bootJar
```

1. Keep the repo checkout at `/opt/nova` or update the paths in the service and Nginx files to match your location.
1. Install the systemd unit:

```bash
sudo cp backend/deploy/systemd/nova-backend.service /etc/systemd/system/nova-backend.service
sudo systemctl daemon-reload
sudo systemctl enable nova-backend
sudo systemctl start nova-backend
```

1. Install the Nginx site config and reload Nginx:

```bash
sudo cp backend/deploy/nginx/nova-backend.conf /etc/nginx/sites-available/nova-backend.conf
sudo ln -s /etc/nginx/sites-available/nova-backend.conf /etc/nginx/sites-enabled/nova-backend.conf
sudo nginx -t
sudo systemctl reload nginx
```

## Notes

- Keep `client_max_body_size` at least as large as `NOVA_UPLOAD_MAX_REQUEST_SIZE`.
- If you switch from the bundled H2 file to PostgreSQL, only the `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` values need to change.
- The backend exposes health at `GET /api/v1/health`.
- WebSocket traffic goes through `/ws/realtime`, so the proxy must forward `Upgrade` headers.
