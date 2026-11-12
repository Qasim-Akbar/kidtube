#!/usr/bin/env bash
# Proves a backup can be restored: loads a dump into a SCRATCH database, checks it, and drops the scratch database.
# It never touches the real `kidtube` database.
#
#   sudo -u postgres /usr/local/sbin/kidtube-restore-test                      # newest dump in /var/backups/kidtube
#   sudo -u postgres /usr/local/sbin/kidtube-restore-test /path/to/some.dump
set -euo pipefail
cd /   # the caller's working directory (often a home directory) may not be readable by postgres

SCRATCH="@KIDTUBE_DB_NAME@_restore_test"   # fixed on purpose: this is the only database this script ever drops
DEST="${KIDTUBE_BACKUP_DIR:-@KIDTUBE_BACKUP_DIR@}"
PGPORT="${PGPORT:-@KIDTUBE_PG_PORT@}"
export PGPORT

dump="${1:-}"
if [ -z "$dump" ]; then
    # newest by name (names contain a UTC timestamp)
    dump="$(find "$DEST" -maxdepth 1 -type f -name 'kidtube-*.dump' | sort | tail -n 1)"
fi
[ -n "$dump" ] && [ -s "$dump" ] || { echo "FAIL: no dump file found (looked in $DEST)" >&2; exit 1; }
echo "Restoring $dump into scratch database $SCRATCH ..."

# Never drop something we did not create: a pre-existing database with this name is somebody else's.
if [ "$(psql --no-psqlrc -tA -d postgres -c "select 1 from pg_database where datname='$SCRATCH'")" = 1 ]; then
    echo "FAIL: a database named $SCRATCH already exists; drop it yourself if it is a leftover (dropdb $SCRATCH)" >&2
    exit 1
fi
created=0
cleanup() { if [ "$created" -eq 1 ]; then dropdb --if-exists "$SCRATCH" > /dev/null 2>&1 || true; fi; }
trap cleanup EXIT
createdb --encoding=UTF8 --template=template0 "$SCRATCH"
created=1

# --no-owner: the scratch database does not need the application role's ownership.
pg_restore --no-owner --exit-on-error --dbname="$SCRATCH" "$dump"

q() { psql --no-psqlrc --tuples-only --no-align --quiet --dbname="$SCRATCH" -c "$1"; }
tables="$(q "select count(*) from information_schema.tables where table_schema='public'")"
revision="$(q "select version_num from alembic_version")"
echo "tables: $tables, alembic revision: $revision"
for t in families parents children devices videos plans plan_items events; do
    printf '  %-12s %s rows\n' "$t" "$(q "select count(*) from \"$t\"")"
done

if [ "$tables" -lt 9 ] || [ -z "$revision" ]; then
    echo "FAIL: restored database looks incomplete" >&2
    exit 1
fi
echo "PASS: dump restores cleanly (scratch database dropped on exit)"
