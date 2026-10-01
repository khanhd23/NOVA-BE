#!/usr/bin/env bash
# Installs and configures coturn for NOVA calls on Ubuntu/Debian.
# Usage (on the VPS, from the repository checkout):
#   sudo bash deploy/coturn/setup-turn.sh
# Optional: PUBLIC_IP=1.2.3.4 REALM=turn.example.com sudo -E bash deploy/coturn/setup-turn.sh
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "Run as root: sudo bash $0" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PUBLIC_IP="${PUBLIC_IP:-$(curl -fsS https://api.ipify.org || true)}"
REALM="${REALM:-nova}"
TURN_SECRET="${TURN_SECRET:-$(openssl rand -hex 32)}"

if [[ -z "$PUBLIC_IP" ]]; then
  echo "Could not detect the public IP. Re-run with PUBLIC_IP=<your VPS IP>." >&2
  exit 1
fi

echo "Installing coturn..."
apt-get update -y
apt-get install -y coturn

echo "Writing /etc/turnserver.conf..."
sed -e "s|<PUBLIC_IP>|$PUBLIC_IP|" \
    -e "s|<TURN_SECRET>|$TURN_SECRET|" \
    -e "s|<REALM>|$REALM|" \
    "$SCRIPT_DIR/turnserver.conf" > /etc/turnserver.conf
chmod 640 /etc/turnserver.conf
chown root:turnserver /etc/turnserver.conf 2>/dev/null || true

# Debian/Ubuntu ship coturn disabled by default.
if [[ -f /etc/default/coturn ]]; then
  sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn
fi

if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  echo "Opening firewall ports with ufw..."
  ufw allow 3478/udp
  ufw allow 3478/tcp
  ufw allow 49152:65535/udp
fi

systemctl enable coturn
systemctl restart coturn

cat <<EOF

coturn is running.

1. Add these lines to the backend .env, then restart it (sudo systemctl restart nova-backend):

NOVA_WEBRTC_TURN_SERVERS=turn:$PUBLIC_IP:3478?transport=udp,turn:$PUBLIC_IP:3478?transport=tcp
NOVA_WEBRTC_TURN_SECRET=$TURN_SECRET
NOVA_WEBRTC_TURN_USERNAME=
NOVA_WEBRTC_TURN_CREDENTIAL=

2. If your cloud provider has its own firewall (security group), open:
   3478 UDP, 3478 TCP, 49152-65535 UDP.
EOF
