#!/usr/bin/env bash
# Daily PostgreSQL backup of the KidTube database (custom format), keeping the last 14 days.
# Installed as /usr/local/sbin/kidtube-backup and run as the `postgres` OS user (peer authentication: no password
# anywhere). Prints the path of the new dump as its last line of output.
#
# Dumps contain every table (parent e-mail addresses and password hashes, children, events): they are written with
# mode 600 into a directory only postgres and root can read. A backup on the same disk does not protect against losing
# the VPS: copy /var/backups/kidtube off the machine too (DEPLOY.md, "Backups").
set -euo pipefail
umask 077
cd /   # the caller's working directory (often a home directory) may not be readable by postgres

DB="${KIDTUBE_DB:-kidtube}"
DEST="${KIDTUBE_BACKUP_DIR:-/var/backups/kidtube}"
KEEP_DAYS="${KIDTUBE_BACKUP_KEEP_DAYS:-14}"

[[ "$KEEP_DAYS" =~ ^[0-9]+$ && "$KEEP_DAYS" -ge 1 ]] || { echo "KIDTUBE_BACKUP_KEEP_DAYS must be a positive integer" >&2; exit 2; }
[ -d "$DEST" ] || { echo "backup directory $DEST does not exist (deploy.sh creates it)" >&2; exit 2; }

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
final="$DEST/kidtube-$stamp.dump"
partial="$final.partial"
trap 'rm -f "$partial"' EXIT

pg_dump --format=custom --compress=6 --file="$partial" --dbname="$DB"
# The archive must be non-empty and readable by pg_restore before it replaces anything.
test -s "$partial"
pg_restore --list "$partial" > /dev/null
mv "$partial" "$final"

# Prune only after a successful backup, so a failing dump never eats the old ones.
find "$DEST" -maxdepth 1 -type f -name 'kidtube-*.dump' -mmin +"$((KEEP_DAYS * 1440))" -delete

echo "$final"
