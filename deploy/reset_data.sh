#!/usr/bin/env bash
# KidTube: START OVER WITH EMPTY DATA. Run ON THE VPS by an admin, from a checkout of this repository:
#
#     sudo deploy/reset_data.sh [--dry-run]
#
# Use it to throw away test accounts/children/videos/events before the real family starts. It is interactive and
# DESTRUCTIVE for the KidTube database only:
#   1. shows what it will do and asks you to TYPE the domain (KIDTUBE_DOMAIN) to confirm;
#   2. takes a pg_dump backup first (kidtube-backup) and stops if that fails;
#   3. stops the kidtube service;
#   4. drops and recreates ONLY the database KIDTUBE_DB_NAME (owner KIDTUBE_DB_USER, same locked-down privileges as
#      install.sh); no other database, role, site, nginx or PostgreSQL setting is touched;
#   5. runs the migrations (alembic upgrade head, as the service user);
#   6. sets ALLOW_REGISTRATION=true in the env file and starts the service, so the real parent account can be created;
#   7. tells you to CLOSE registration again once that account exists.
#
# --dry-run prints every command it would run and changes nothing (no root needed, nothing is asked).
# Settings come from deploy/deploy.env (see deploy.env.example), like the other deploy scripts.
set -Eeuo pipefail
umask 022

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STAGE="starting"
BACKUP_FILE="(none yet)"
die() {
    printf 'ERROR: %s\n' "$*" >&2
    if [ "${DESTRUCTIVE_STARTED:-0}" -eq 1 ]; then print_recovery; fi
    exit 1
}
# shellcheck source=deploy/lib.sh
. "$SCRIPT_DIR/lib.sh"

DRY_RUN=0
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        -h | --help) sed -n '2,19p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "unknown option: $arg (see --help)" >&2; exit 2 ;;
    esac
done

load_config

APP_USER="$KIDTUBE_USER"
SRC_DIR="$KIDTUBE_DIR/src"
VENV="$KIDTUBE_DIR/venv"
ENV_FILE="$KIDTUBE_ENV_FILE"
BACKUP_BIN="/usr/local/sbin/kidtube-backup"
HEALTH_URL="http://127.0.0.1:$KIDTUBE_PORT"
DESTRUCTIVE_STARTED=0

# Safety net: this script may only ever drop the application database named in deploy.env, never a system one.
case "$KIDTUBE_DB_NAME" in
    postgres | template0 | template1) die "KIDTUBE_DB_NAME is '$KIDTUBE_DB_NAME', a PostgreSQL system database: refusing" ;;
esac

print_recovery() {
    {
        echo
        echo "RESET FAILED during: $STAGE"
        echo "  Backup taken before the reset: $BACKUP_FILE"
        echo "  The kidtube service may be stopped and the database may be empty or half-created."
        echo "  Look at:  journalctl -u kidtube -n 50     and fix the cause, then run this script again"
        echo "  (it is safe to repeat), or restore the backup:"
        echo "     sudo -u postgres dropdb -p $KIDTUBE_PG_PORT $KIDTUBE_DB_NAME"
        echo "     sudo -u postgres createdb -p $KIDTUBE_PG_PORT -O $KIDTUBE_DB_USER $KIDTUBE_DB_NAME"
        echo "     sudo -u postgres pg_restore -p $KIDTUBE_PG_PORT -d $KIDTUBE_DB_NAME --no-owner --role=$KIDTUBE_DB_USER '$BACKUP_FILE'"
        echo "     sudo systemctl start kidtube"
    } >&2
}

# ---- helpers: everything that changes the machine goes through these, so --dry-run can print instead of run ----------
act() { # act DESCRIPTION COMMAND [ARGS...]
    local desc="$1"
    shift
    if [ "$DRY_RUN" -eq 1 ]; then
        printf '  [dry-run] %s\n              %s\n' "$desc" "$*"
    else
        log "$desc"
        "$@"
    fi
}
sql_admin() { # sql_admin DATABASE SQL   (as the postgres OS user, peer authentication)
    local db="$1" sql="$2"
    if [ "$DRY_RUN" -eq 1 ]; then
        printf '  [dry-run] psql (port %s, database %s): %s\n' "$KIDTUBE_PG_PORT" "$db" "$sql"
    else
        psql_admin -d "$db" -c "$sql"
    fi
}
run_as_app() { # same environment as the service: its user, env file and working directory
    systemd-run --quiet --wait --collect --pipe \
        -p User="$APP_USER" -p EnvironmentFile="$ENV_FILE" -p WorkingDirectory="$SRC_DIR" \
        -p NoNewPrivileges=yes -p PrivateTmp=yes -E PYTHONDONTWRITEBYTECODE=1 "$@"
}

# ---- preflight (read-only) ----------------------------------------------------------------------------------------------
STAGE="preflight checks"
if [ "$DRY_RUN" -eq 0 ]; then
    [ "$(id -u)" -eq 0 ] || die "run as root: sudo deploy/reset_data.sh"
    command -v psql > /dev/null || die "psql not found"
    [ -f "$ENV_FILE" ] || die "$ENV_FILE does not exist: run sudo deploy/install.sh first"
    [ -x "$BACKUP_BIN" ] || die "$BACKUP_BIN is not installed (deploy/deploy.sh installs it): refusing to reset without a backup"
    [ -d "$SRC_DIR" ] && [ -x "$VENV/bin/alembic" ] || die "the application is not installed in $KIDTUBE_DIR: run sudo deploy/deploy.sh first"
    psql_admin -d postgres -tA -c 'select 1' > /dev/null || die "cannot connect to PostgreSQL port $KIDTUBE_PG_PORT as the postgres user"
    # The service must be using exactly the database we are about to drop (read from the env file, the password is never printed).
    url="$(grep '^DATABASE_URL=' "$ENV_FILE" | head -n1 | cut -d= -f2-)"
    url_db="${url##*/}"
    url_db="${url_db%%\?*}"
    unset url
    [ "$url_db" = "$KIDTUBE_DB_NAME" ] || die "DATABASE_URL in $ENV_FILE points at database '$url_db' but KIDTUBE_DB_NAME is '$KIDTUBE_DB_NAME': refusing to guess which one to wipe"
    if ! psql_admin -d postgres -tA -c "select 1 from pg_database where datname='$KIDTUBE_DB_NAME'" | grep -q 1; then
        log "database $KIDTUBE_DB_NAME does not exist yet: it will simply be created"
    fi
else
    log "DRY RUN: nothing below is executed, and nothing is asked"
fi

cat << EOF

This will ERASE ALL KidTube DATA (parents, children, plans, videos, events, pairing codes) in:

    database   $KIDTUBE_DB_NAME   (owner $KIDTUBE_DB_USER, PostgreSQL port $KIDTUBE_PG_PORT)
    domain     $KIDTUBE_DOMAIN

Other databases, other sites, nginx and PostgreSQL's configuration are not touched.
A backup is taken first. Phones keep their local data: after the reset they must sign in again (and kid phones pair again).

EOF

if [ "$DRY_RUN" -eq 0 ]; then
    printf 'To continue type the domain (%s) exactly: ' "$KIDTUBE_DOMAIN"
    read -r answer || die "no answer (not an interactive terminal?): nothing was changed"
    [ "$answer" = "$KIDTUBE_DOMAIN" ] || die "that is not the domain: nothing was changed"
fi

# ---- 1. backup ---------------------------------------------------------------------------------------------------------------
STAGE="backup"
if [ "$DRY_RUN" -eq 1 ]; then
    printf '  [dry-run] pg_dump backup first\n              runuser -u postgres -- %s   (aborts the reset if it fails)\n' "$BACKUP_BIN"
    BACKUP_FILE="$KIDTUBE_BACKUP_DIR/kidtube-<timestamp>.dump"
else
    log "taking a pg_dump backup before touching anything"
    BACKUP_FILE="$(runuser -u postgres -- "$BACKUP_BIN" | tail -n 1)" || die "the backup failed: nothing was changed"
    [ -s "$BACKUP_FILE" ] || die "the backup file $BACKUP_FILE is missing or empty: nothing was changed"
    log "backup written: $BACKUP_FILE"
fi

# ---- 2. stop the service, drop and recreate the database ----------------------------------------------------------------------
DESTRUCTIVE_STARTED=1
STAGE="stopping the kidtube service"
act "stopping the kidtube service" systemctl stop kidtube.service

STAGE="dropping and recreating database $KIDTUBE_DB_NAME"
sql_admin postgres "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$KIDTUBE_DB_NAME' AND pid <> pg_backend_pid()"
sql_admin postgres "DROP DATABASE IF EXISTS \"$KIDTUBE_DB_NAME\""
sql_admin postgres "CREATE DATABASE \"$KIDTUBE_DB_NAME\" OWNER \"$KIDTUBE_DB_USER\" ENCODING 'UTF8' TEMPLATE template0"
# Same lock-down as install.sh: only the owner may connect or create objects in this database.
sql_admin postgres "REVOKE ALL ON DATABASE \"$KIDTUBE_DB_NAME\" FROM PUBLIC"
sql_admin postgres "GRANT CONNECT ON DATABASE \"$KIDTUBE_DB_NAME\" TO \"$KIDTUBE_DB_USER\""
sql_admin "$KIDTUBE_DB_NAME" "REVOKE ALL ON SCHEMA public FROM PUBLIC"
sql_admin "$KIDTUBE_DB_NAME" "GRANT ALL ON SCHEMA public TO \"$KIDTUBE_DB_USER\""

# ---- 3. migrations ------------------------------------------------------------------------------------------------------------
STAGE="alembic upgrade head"
act "creating the empty tables (alembic upgrade head, as $APP_USER)" run_as_app "$VENV/bin/alembic" upgrade head

# ---- 4. registration open + start ---------------------------------------------------------------------------------------------
STAGE="opening registration for the first account"
if [ "$DRY_RUN" -eq 1 ]; then
    printf '  [dry-run] set ALLOW_REGISTRATION=true in %s (replacing the line, or adding it)\n' "$ENV_FILE"
else
    if grep -q '^ALLOW_REGISTRATION=' "$ENV_FILE"; then
        sed -i 's/^ALLOW_REGISTRATION=.*/ALLOW_REGISTRATION=true/' "$ENV_FILE"
    else
        printf 'ALLOW_REGISTRATION=true\n' >> "$ENV_FILE"
    fi
    chown root:root "$ENV_FILE"
    chmod 600 "$ENV_FILE"
    log "ALLOW_REGISTRATION=true is set in $ENV_FILE"
fi

STAGE="starting the kidtube service"
act "starting the kidtube service" systemctl start kidtube.service

STAGE="waiting for the service to answer"
if [ "$DRY_RUN" -eq 1 ]; then
    printf '  [dry-run] wait up to 30 s for %s/health, then check it answers "ok"\n' "$HEALTH_URL"
else
    healthy=0
    for _ in $(seq 1 30); do
        if curl -fsS --max-time 2 -o /dev/null "$HEALTH_URL/health" 2> /dev/null; then healthy=1; break; fi
        sleep 1
    done
    [ "$healthy" -eq 1 ] || die "the service did not answer on $HEALTH_URL/health within 30 s"
    systemctl is-active --quiet kidtube.service || die "kidtube is not active"
fi
DESTRUCTIVE_STARTED=0

cat << EOF

$([ "$DRY_RUN" -eq 1 ] && echo "DRY RUN finished: nothing was changed." || echo "DONE: $KIDTUBE_DB_NAME is empty and the service is running.")

NEXT (do this now, in this order):
  1. On your parent phone: sign out / clear the app's data if it still shows the old test account, then
     open the app, choose "Create account" and create the REAL parent account (https://$KIDTUBE_DOMAIN/).
  2. THEN CLOSE REGISTRATION AGAIN, or anyone who finds the address can create an account:
         sudo sed -i 's/^ALLOW_REGISTRATION=.*/ALLOW_REGISTRATION=false/' $ENV_FILE && sudo systemctl restart kidtube
  Backup of the old data: $BACKUP_FILE
EOF
