#!/usr/bin/env bash
# Host firewall: allow SSH, HTTP and HTTPS in; deny everything else in. PostgreSQL (5432) is never opened.
# Run as root, over an SSH session you can afford to lose once: if the SSH rule is wrong you lock yourself out.
#   sudo deploy/firewall/ufw.sh              # SSH on port 22 (uses ufw's OpenSSH profile)
#   sudo SSH_PORT=2222 deploy/firewall/ufw.sh
# Idempotent: re-running adds nothing twice.
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)" >&2; exit 1; }
command -v ufw > /dev/null || { echo "ufw is not installed: sudo apt install ufw" >&2; exit 1; }

if ! grep -qs '^IPV6=yes' /etc/default/ufw; then
    echo "WARNING: IPV6 is not enabled in /etc/default/ufw; IPv6 traffic would bypass these rules." >&2
fi

ufw default deny incoming
ufw default allow outgoing

# The SSH rule goes in BEFORE enabling the firewall.
if [ -n "${SSH_PORT:-}" ]; then
    ufw allow "${SSH_PORT}/tcp" comment 'SSH'
else
    ufw allow OpenSSH
fi
ufw allow 80/tcp comment 'HTTP (redirect + certbot)'
ufw allow 443/tcp comment 'HTTPS (API)'
# No rule for 5432: PostgreSQL listens on localhost only, and the default policy would drop it anyway.

ufw --force enable
ufw status verbose
