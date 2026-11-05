#!/usr/bin/env bash
# KidTube deploy / update. Run ON THE VPS by an admin, from a checkout of this repository:
#
#     git pull                       # as yourself, in the checkout (or use --pull below)
#     sudo deploy/deploy.sh [--pull] [--skip-backup]
#
# Idempotent: safe to run again after any failure. Stops at the first error. Never prints secrets: it does not read
# the contents of the env file except to test for placeholders, and it passes no secret on any command line.
#
# What it does: user + directories -> env file check -> pre-deploy database backup -> copy code to /opt/kidtube/src ->
# venv + pinned dependencies + non-editable install -> systemd units -> alembic upgrade head -> restart ->
# smoke check on http://127.0.0.1:8000. Nginx, certbot, ufw, fail2ban and PostgreSQL setup are one-time manual steps
# (DEPLOY.md).
set -Eeuo pipefail
umask 022

APP_USER="kidtube"
APP_HOME="/opt/kidtube"
SRC_DIR="$APP_HOME/src"
VENV="$APP_HOME/venv"
ENV_DIR="/etc/kidtube"
ENV_FILE="$ENV_DIR/kidtube.env"
BACKUP_DIR="/var/backups/kidtube"
BACKUP_BIN="/usr/local/sbin/kidtube-backup"
RESTORE_BIN="/usr/local/sbin/kidtube-restore-test"
HEALTH_URL="http://127.0.0.1:8000"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"

DO_PULL=0
DO_BACKUP=1
for arg in "$@"; do
    case "$arg" in
        --pull) DO_PULL=1 ;;
        --skip-backup) DO_BACKUP=0 ;;
        -h | --help) sed -n '2,13p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "unknown option: $arg (see --help)" >&2; exit 2 ;;
    esac
done

log() { printf '==> %s\n' "$*"; }

PREV_REVISION="(unknown: first deploy or database not reachable)"
BACKUP_FILE="(none taken)"
HAD_PREVIOUS_CODE=0
CHANGED=0   # becomes 1 once this script starts modifying /opt/kidtube
STAGE="starting"

print_rollback() {
    {
        echo
        echo "DEPLOY FAILED during: $STAGE"
        if [ "$CHANGED" -eq 0 ]; then
            echo "Nothing on the server was changed yet. Fix the problem above and run this script again."
            return
        fi
        echo
        echo "Check the state:  systemctl status kidtube"
        echo "Logs (read them yourself, they may contain request details):  journalctl -u kidtube -n 100 --no-pager"
        echo
        echo "HOW TO ROLL BACK"
        if [ "$HAD_PREVIOUS_CODE" -eq 1 ]; then
            echo "  1. Previous code (kept by this script):"
            echo "       sudo rm -rf $SRC_DIR && sudo mv $SRC_DIR.prev $SRC_DIR"
            echo "       sudo $VENV/bin/pip install --no-deps --force-reinstall $SRC_DIR"
        else
            echo "  1. There was no previous deployment, so there is no code to restore. Fix the problem and run deploy.sh again."
        fi
        echo "  2. Database. Only needed if 'alembic upgrade head' already ran. Schema revision before this deploy: $PREV_REVISION"
        echo "     Either step it back:"
        echo "       sudo systemd-run --wait --collect --pipe -p User=$APP_USER -p EnvironmentFile=$ENV_FILE \\"
        echo "           -p WorkingDirectory=$SRC_DIR $VENV/bin/alembic downgrade <revision above>"
        echo "     or restore the backup taken before this deploy: $BACKUP_FILE   (DEPLOY.md, 'Restore')."
        echo "  3. sudo systemctl restart kidtube && curl -fsS $HEALTH_URL/health"
    } >&2
}

die() {
    printf 'ERROR: %s\n' "$*" >&2
    print_rollback
    exit 1
}

on_error() {  # unexpected command failure (set -e)
    local code=$?
    print_rollback
    exit "$code"
}
trap on_error ERR

# ------------------------------------------------------------------------------------------------ preflight
STAGE="preflight checks"
[ "$(id -u)" -eq 0 ] || die "run as root: sudo $0"
for cmd in python3 rsync curl systemctl systemd-run install find; do
    command -v "$cmd" > /dev/null || die "missing command: $cmd (sudo apt install python3 python3-venv rsync curl)"
done
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' || die "Python >= 3.10 required (Ubuntu 24.04 has 3.12)"
python3 -c 'import ensurepip, venv' 2> /dev/null || die "python3-venv is missing: sudo apt install python3-venv"
[ -f "$REPO_DIR/backend/pyproject.toml" ] || die "run this from a KidTube checkout (expected $REPO_DIR/backend/pyproject.toml)"
[ -f "$SCRIPT_DIR/constraints.txt" ] || die "deploy/constraints.txt is missing"

if [ "$DO_PULL" -eq 1 ]; then
    STAGE="git pull"
    [ -n "${SUDO_USER:-}" ] && [ "$SUDO_USER" != root ] || die "--pull needs to be run with sudo from a normal user (it pulls as that user)"
    log "git pull --ff-only (as $SUDO_USER)"
    sudo -u "$SUDO_USER" git -C "$REPO_DIR" pull --ff-only
fi
REV="$(git -c safe.directory="$REPO_DIR" -C "$REPO_DIR" rev-parse --short HEAD 2> /dev/null || echo 'no-git')"
log "deploying revision $REV from $REPO_DIR"

# ------------------------------------------------------------------------------------------------ user + directories
STAGE="creating user and directories"
if ! id "$APP_USER" > /dev/null 2>&1; then
    log "creating system user $APP_USER"
    useradd --system --user-group --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin "$APP_USER"
fi
install -d -m 755 -o root -g root "$APP_HOME"
install -d -m 750 -o root -g "$APP_USER" "$ENV_DIR"
# Backups: only meaningful once PostgreSQL (and its `postgres` user) exist.
if id postgres > /dev/null 2>&1; then
    install -d -m 700 -o postgres -g postgres "$BACKUP_DIR"
fi

# ------------------------------------------------------------------------------------------------ env file
STAGE="checking $ENV_FILE"
if [ ! -f "$ENV_FILE" ]; then
    install -m 640 -o root -g "$APP_USER" "$SCRIPT_DIR/kidtube.env.example" "$ENV_FILE"
    echo "Created $ENV_FILE from the example. Edit it now (JWT_SECRET, database password), then run this script again:" >&2
    echo "    sudo nano $ENV_FILE" >&2
    exit 3
fi
chown root:"$APP_USER" "$ENV_FILE"
chmod 640 "$ENV_FILE"
if grep -v '^[[:space:]]*#' "$ENV_FILE" | grep -q 'CHANGE_ME'; then
    die "$ENV_FILE still contains CHANGE_ME placeholders (JWT_SECRET / database password). Edit it and run again."
fi
grep -qx 'APP_ENV=prod' "$ENV_FILE" || die "$ENV_FILE must contain the line APP_ENV=prod"
grep -q '^JWT_SECRET=.\{32,\}' "$ENV_FILE" || die "JWT_SECRET in $ENV_FILE is missing or shorter than 32 characters"
if grep -qx 'ALLOW_REGISTRATION=true' "$ENV_FILE"; then
    echo "NOTE: ALLOW_REGISTRATION=true. Once the first parent account exists, set it to false and restart (DEPLOY.md)." >&2
fi

# ------------------------------------------------------------------------------------------------ state before the change
run_as_app() { # run a command with the service's user, environment file and working directory (same parser as systemd)
    systemd-run --quiet --wait --collect --pipe \
        -p User="$APP_USER" -p EnvironmentFile="$ENV_FILE" -p WorkingDirectory="$SRC_DIR" \
        -p NoNewPrivileges=yes -p PrivateTmp=yes -E PYTHONDONTWRITEBYTECODE=1 "$@"
}
if [ -x "$VENV/bin/alembic" ] && [ -d "$SRC_DIR" ]; then
    STAGE="reading the current schema revision"
    PREV_REVISION="$(run_as_app "$VENV/bin/alembic" current 2> /dev/null | awk 'NF && $1 !~ /^INFO/ {print $1; exit}')" || PREV_REVISION=""
    PREV_REVISION="${PREV_REVISION:-(none)}"
    log "schema revision before deploy: $PREV_REVISION"
fi

if [ "$DO_BACKUP" -eq 1 ] && [ -x "$BACKUP_BIN" ] && [ -d "$SRC_DIR" ]; then
    STAGE="pre-deploy database backup"
    log "backing up the database before changing anything"
    BACKUP_FILE="$(runuser -u postgres -- "$BACKUP_BIN" | tail -n 1)"
    log "backup written: $BACKUP_FILE"
elif [ "$DO_BACKUP" -eq 1 ]; then
    log "no pre-deploy backup (first deploy, or the backup script is not installed yet)"
fi

# ------------------------------------------------------------------------------------------------ code
STAGE="copying the code"
CHANGED=1
if [ -d "$SRC_DIR" ]; then
    rm -rf "$SRC_DIR.prev"
    cp -a "$SRC_DIR" "$SRC_DIR.prev"
    HAD_PREVIOUS_CODE=1
fi
install -d -m 755 -o root -g root "$SRC_DIR"
rsync -a --delete \
    --exclude '.venv/' --exclude '__pycache__/' --exclude '*.egg-info/' --exclude 'build/' \
    --exclude '.pytest_cache/' --exclude '.env' --exclude '*.db' \
    "$REPO_DIR/backend/" "$SRC_DIR/"
install -m 644 -o root -g root "$SCRIPT_DIR/constraints.txt" "$APP_HOME/constraints.txt"
printf '%s %s\n' "$REV" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$APP_HOME/VERSION"
# Code is owned by root and read-only for the service user, so a bug in the app cannot rewrite its own code.
chown -R root:root "$SRC_DIR"
chmod -R a+rX,go-w "$SRC_DIR"   # readable by the service user whatever umask the checkout had

# ------------------------------------------------------------------------------------------------ virtualenv
STAGE="python virtualenv and dependencies"
if [ ! -x "$VENV/bin/python" ]; then
    log "creating virtualenv $VENV"
    python3 -m venv "$VENV"
fi
log "installing pinned dependencies and the project (non-editable)"
"$VENV/bin/pip" install --quiet --disable-pip-version-check --root-user-action=ignore \
    -c "$APP_HOME/constraints.txt" "$SRC_DIR"
# Same version number as before: force the project files themselves to be replaced.
"$VENV/bin/pip" install --quiet --disable-pip-version-check --root-user-action=ignore \
    --no-deps --force-reinstall "$SRC_DIR"
"$VENV/bin/pip" check > /dev/null || die "pip check found broken dependencies"

# ------------------------------------------------------------------------------------------------ systemd units
STAGE="installing systemd units"
install -m 644 -o root -g root "$SCRIPT_DIR/kidtube.service" /etc/systemd/system/kidtube.service
install -m 755 -o root -g root "$SCRIPT_DIR/backup/kidtube-backup.sh" "$BACKUP_BIN"
install -m 755 -o root -g root "$SCRIPT_DIR/backup/kidtube-restore-test.sh" "$RESTORE_BIN"
install -m 644 -o root -g root "$SCRIPT_DIR/backup/kidtube-backup.service" /etc/systemd/system/kidtube-backup.service
install -m 644 -o root -g root "$SCRIPT_DIR/backup/kidtube-backup.timer" /etc/systemd/system/kidtube-backup.timer
systemctl daemon-reload
systemctl enable kidtube.service > /dev/null 2>&1
if id postgres > /dev/null 2>&1; then
    systemctl enable --now kidtube-backup.timer > /dev/null 2>&1
else
    echo "NOTE: no postgres user on this machine, so the backup timer was not enabled." >&2
fi

# ------------------------------------------------------------------------------------------------ database migration
STAGE="alembic upgrade head"
log "applying database migrations"
run_as_app "$VENV/bin/alembic" upgrade head

# ------------------------------------------------------------------------------------------------ restart + smoke check
STAGE="restarting the service"
log "restarting kidtube"
systemctl restart kidtube.service

STAGE="waiting for the service to answer"
healthy=0
for _ in $(seq 1 30); do
    if curl -fsS --max-time 2 -o /dev/null "$HEALTH_URL/health" 2> /dev/null; then healthy=1; break; fi
    sleep 1
done
[ "$healthy" -eq 1 ] || die "the service did not answer on $HEALTH_URL/health within 30 s"

STAGE="smoke check"
body="$(curl -fsS --max-time 5 "$HEALTH_URL/health")"
[[ "$body" == *'"ok"'* ]] || die "unexpected /health answer: ${body:0:100}"
code="$(curl -sS -o /dev/null --max-time 5 -w '%{http_code}' "$HEALTH_URL/children" || true)"
[ "$code" = "401" ] || die "GET /children without a token answered $code (expected 401)"
code="$(curl -sS -o /dev/null --max-time 5 -w '%{http_code}' "$HEALTH_URL/docs" || true)"
[ "$code" = "404" ] || die "/docs answered $code (expected 404: is APP_ENV=prod?)"

systemctl is-active --quiet kidtube.service || die "kidtube is not active"
log "OK: revision $REV is running (health check, auth check and docs-off check passed)"
echo "Next: run deploy/smoke_test.sh against your public https URL."
