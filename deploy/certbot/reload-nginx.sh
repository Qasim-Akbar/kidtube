#!/bin/sh
# Install as /etc/letsencrypt/renewal-hooks/deploy/reload-nginx (mode 755).
# certbot runs it after every SUCCESSFUL renewal so nginx starts serving the new certificate.
set -e
nginx -t
systemctl reload nginx
