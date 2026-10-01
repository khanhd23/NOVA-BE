# VPS Deployment

This folder contains the minimal files needed to run the backend on a Linux VPS with systemd and Nginx.

Assume the repository is checked out at `/opt/NOVA-BE`.

## Files

- `systemd/nova-backend.service` - systemd unit that runs the packaged jar.
- `nginx/nova-backend.conf` - reverse proxy config with WebSocket support and upload limits.
- `coturn/turnserver.conf`, `coturn/setup-turn.sh` - TURN relay for voice/video calls.

## Quick setup

1. Copy `.env.example` to `.env` in the repository checkout and edit the values.
1. Build the jar:

```bash
./gradlew bootJar
```

1. Keep the repo checkout at `/opt/NOVA-BE` or update the paths in the service and Nginx files to match your location.
1. Install the systemd unit:

```bash
sudo cp deploy/systemd/nova-backend.service /etc/systemd/system/nova-backend.service
sudo systemctl daemon-reload
sudo systemctl enable nova-backend
sudo systemctl start nova-backend
```

1. Install the Nginx site config and reload Nginx:

```bash
sudo cp deploy/nginx/nova-backend.conf /etc/nginx/sites-available/nova-backend.conf
sudo ln -s /etc/nginx/sites-available/nova-backend.conf /etc/nginx/sites-enabled/nova-backend.conf
sudo nginx -t
sudo systemctl reload nginx
```

## TURN server for calls (coturn)

Without TURN, calls fail for users behind strict NATs (many 4G networks, office Wi-Fi). coturn relays the media in those cases. It can run on the same VPS as the backend.

1. Install and configure coturn (Ubuntu/Debian):

```bash
sudo bash deploy/coturn/setup-turn.sh
```

The script detects the public IP, generates a shared secret, writes `/etc/turnserver.conf` from `deploy/coturn/turnserver.conf`, opens the ports in `ufw` if it is active, and prints the lines to add to `.env`.

1. Add the printed `NOVA_WEBRTC_TURN_SERVERS` and `NOVA_WEBRTC_TURN_SECRET` to `.env` and restart the backend:

```bash
sudo systemctl restart nova-backend
```

1. Open these ports in the cloud provider firewall (security group) as well: `3478/udp`, `3478/tcp`, `49152-65535/udp`.

1. Check that the backend returns the TURN server: `GET /api/v1/realtime/config` (with a logged-in token) should list a `turn:` URL with a `username` like `1767225600:u-12` and a `credential`.

To test the relay itself, open https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/, enter the `turn:` URL with that username and credential, and click "Gather candidates". A candidate of type `relay` means TURN works.

The backend signs short-lived credentials (`NOVA_WEBRTC_TURN_TTL_SECONDS`, default 24 hours) with the shared secret, so no password is baked into the app. The app fetches fresh credentials before every call.

## Notes

- Keep `client_max_body_size` at least as large as `NOVA_UPLOAD_MAX_REQUEST_SIZE`.
- If you switch from the bundled H2 file to PostgreSQL, only the `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` values need to change.
- The backend listens on `8080` by default, so Nginx should proxy to `127.0.0.1:8080`.
- The backend exposes health at `GET /api/v1/health`.
- WebSocket traffic goes through `/ws/realtime`, so the proxy must forward `Upgrade` headers.
