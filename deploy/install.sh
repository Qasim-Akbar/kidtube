#!/usr/bin/env bash
# KidTube one-time setup on a VPS that ALREADY runs nginx (and maybe PostgreSQL) for other sites.
#
#     deploy/check.sh                  # read-only: look for conflicts first
#     sudo deploy/install.sh           # asks before EVERY system change; answer n to skip a step
#     sudo deploy/install.sh --dry-run # only prints what each step would do
#
# Idempotent: every step checks what exists and skips what is already done, so it is safe to run again.
# Settings: deploy/deploy.env (deploy.env.example documents them).
#
# What it never does: restart nginx or PostgreSQL (nginx is only RELOADED, and only after `nginx -t` passed; a failing test
# restores the previous site file and leaves nginx untouched), edit nginx.conf, any other site, pg_hba.conf, postgresql.conf,
# the firewall, or any existing database or role.
#
# Steps: 1 user + directories | 2 database role, database, env file | 3 code, venv, systemd units, migrations (deploy.sh)
#        | 4 nginx site over HTTP | 5 certificate (certbot, this domain only) | 6 nginx site over HTTPS
set -Eeuo pipefail
umask 022

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"
# shellcheck source=deploy/lib.sh
. "$SCRIPT_DIR/lib.sh"

DRY_RUN=0
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        -h | --help) sed -n '2,15p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "unknown option: $arg (see --help)" >&2; exit 2 ;;
    esac
done

load_config
[ "$(id -u)" -eq 0 ] || [ "$DRY_RUN" -eq 1 ] || die "run as root: sudo $0"

# ask "what is about to change"  ->  returns 0 only when the user typed y/yes
ask() {
    printf '\n--> %s\n' "$1"
    if [ "$DRY_RUN" -eq 1 ]; then echo "    [dry-run] skipped"; return 1; fi
    local answer
    read -r -p "    Do this? [y/N] " answer || answer=""
    [[ "$answer" =~ ^[yY]([eE][sS])?$ ]]
}

SKIPPED=()
skipped() { SKIPPED+=("$1"); echo "    skipped: $1"; }

# ------------------------------------------------------------------------------------------------ pre-flight
log "checking for conflicts first (deploy/check.sh, read-only)"
if ! "$SCRIPT_DIR/check.sh"; then
    if ! ask "check.sh reported conflicts (above). Continue anyway? Say n unless you understand each one"; then
        die "stopped because of conflicts. Fix them or change deploy/deploy.env"
    fi
fi

# ------------------------------------------------------------------------------------------------ 1. user + directories
if id "$KIDTUBE_USER" > /dev/null 2>&1 && [ -d "$KIDTUBE_DIR" ] && [ -d "$(dirname "$KIDTUBE_ENV_FILE")" ]; then
    log "step 1: user and directories already exist"
elif ask "Step 1: create system user '$KIDTUBE_USER' (no login shell, no home), $KIDTUBE_DIR and $(dirname "$KIDTUBE_ENV_FILE")"; then
    id "$KIDTUBE_USER" > /dev/null 2>&1 || useradd --system --user-group --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin "$KIDTUBE_USER"
    install -d -m 755 -o root -g root "$KIDTUBE_DIR"
    install -d -m 750 -o root -g root "$(dirname "$KIDTUBE_ENV_FILE")"
else
    skipped "step 1 (user and directories)"
fi

# ------------------------------------------------------------------------------------------------ 2. database + env file
# SCRAM-SHA-256 verifier computed here, so the server (and its logs, if log_statement is 'ddl') only ever sees the hash,
# and the password never appears on a command line.
scram_verifier() {
    python3 -I -c '
import base64, hashlib, hmac, os, sys
pw = sys.stdin.read().strip().encode()
salt, it = os.urandom(16), 4096
salted = hashlib.pbkdf2_hmac("sha256", pw, salt, it)
stored = hashlib.sha256(hmac.new(salted, b"Client Key", "sha256").digest()).digest()
server = hmac.new(salted, b"Server Key", "sha256").digest()
b = lambda x: base64.b64encode(x).decode()
print("SCRAM-SHA-256$%d:%s$%s:%s" % (it, b(salt), b(stored), b(server)))'
}
pg_exists() { [ "$(psql_admin -d postgres -tA -c "$1")" = 1 ]; }

create_env_file() { # create_env_file DB_PASSWORD
    local tpl jwt
    tpl="$(render "$SCRIPT_DIR/kidtube.env.example")"
    jwt="$(python3 -I -c 'import secrets; print(secrets.token_urlsafe(48))')"
    tpl="${tpl//CHANGE_ME_JWT_SECRET/$jwt}"
    tpl="${tpl//CHANGE_ME_DB_PASSWORD/$1}"
    (umask 077; printf '%s\n' "$tpl" > "$KIDTUBE_ENV_FILE.new")
    chown root:root "$KIDTUBE_ENV_FILE.new"
    chmod 600 "$KIDTUBE_ENV_FILE.new"
    mv "$KIDTUBE_ENV_FILE.new" "$KIDTUBE_ENV_FILE"
}

db_ready=0
if [ -f "$KIDTUBE_ENV_FILE" ] && ! grep -v '^[[:space:]]*#' "$KIDTUBE_ENV_FILE" | grep -q CHANGE_ME; then
    log "step 2: $KIDTUBE_ENV_FILE already exists with real values; the database step is skipped (nothing is regenerated)"
    chown root:root "$KIDTUBE_ENV_FILE"; chmod 600 "$KIDTUBE_ENV_FILE"
    db_ready=1
elif ask "Step 2: in PostgreSQL (port $KIDTUBE_PG_PORT) create role '$KIDTUBE_DB_USER' (LOGIN only: no superuser/createdb/createrole) and database '$KIDTUBE_DB_NAME' owned by it, then write $KIDTUBE_ENV_FILE (mode 0600, generated JWT secret + database password, ALLOW_REGISTRATION=true for now). Existing databases and roles are not modified"; then
    command -v psql > /dev/null || die "psql not found (sudo apt install postgresql)"
    psql_admin -d postgres -tA -c 'select 1' > /dev/null || die "cannot connect to PostgreSQL port $KIDTUBE_PG_PORT as the postgres user (check KIDTUBE_PG_PORT, pg_lsclusters)"
    db_pw="$(openssl rand -hex 24)"
    verifier="$(printf '%s' "$db_pw" | scram_verifier)"
    if pg_exists "select 1 from pg_roles where rolname='$KIDTUBE_DB_USER'"; then
        ask "Role '$KIDTUBE_DB_USER' ALREADY EXISTS. Its password will be REPLACED (anything else using this role breaks). Only say y if this is a leftover of an earlier KidTube attempt" \
            || die "stopped: choose another KIDTUBE_DB_USER in deploy/deploy.env"
        printf "ALTER ROLE \"%s\" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '%s';\n" "$KIDTUBE_DB_USER" "$verifier" | psql_admin -d postgres
    else
        printf "CREATE ROLE \"%s\" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '%s';\n" "$KIDTUBE_DB_USER" "$verifier" | psql_admin -d postgres
    fi
    if pg_exists "select 1 from pg_database where datname='$KIDTUBE_DB_NAME'"; then
        echo "    database $KIDTUBE_DB_NAME already exists: left as it is"
    else
        psql_admin -d postgres -c "CREATE DATABASE \"$KIDTUBE_DB_NAME\" OWNER \"$KIDTUBE_DB_USER\" ENCODING 'UTF8' TEMPLATE template0"
    fi
    # Only this database and its own public schema are changed: nobody but the owner may connect or create objects.
    psql_admin -d postgres -c "REVOKE ALL ON DATABASE \"$KIDTUBE_DB_NAME\" FROM PUBLIC" -c "GRANT CONNECT ON DATABASE \"$KIDTUBE_DB_NAME\" TO \"$KIDTUBE_DB_USER\""
    psql_admin -d "$KIDTUBE_DB_NAME" -c 'REVOKE ALL ON SCHEMA public FROM PUBLIC' -c "GRANT ALL ON SCHEMA public TO \"$KIDTUBE_DB_USER\""
    create_env_file "$db_pw"
    unset db_pw verifier
    log "wrote $KIDTUBE_ENV_FILE (0600 root:root)"
    db_ready=1
    # Prove the password login the service will use (127.0.0.1, password) works with the existing pg_hba.conf.
    # PGPASSWORD is taken from the file we just wrote, never typed or printed.
    url="$(grep '^DATABASE_URL=' "$KIDTUBE_ENV_FILE" | cut -d= -f2-)"
    pw_check="${url#*://*:}"; pw_check="${pw_check%%@*}"
    if PGPASSWORD="$pw_check" psql -X -tA -h 127.0.0.1 -p "$KIDTUBE_PG_PORT" -U "$KIDTUBE_DB_USER" -d "$KIDTUBE_DB_NAME" -c 'select current_user' > /dev/null 2>&1; then
        log "password login over 127.0.0.1 works"
    else
        cat >&2 <<EOF

NOTE: a password login over 127.0.0.1 as $KIDTUBE_DB_USER did NOT work. pg_hba.conf has no matching rule. This script
never edits pg_hba.conf. Add this ONE line ABOVE any broader rule that would reject it (it only covers this role and
this database, from this machine):

    host    $KIDTUBE_DB_NAME    $KIDTUBE_DB_USER    127.0.0.1/32    scram-sha-256

then reload (not restart):   sudo systemctl reload postgresql     (check: sudo -u postgres psql -c 'select pg_reload_conf()')
Run this script again afterwards; the migration step needs the login.
EOF
    fi
    unset url pw_check
else
    skipped "step 2 (database and env file)"
fi

# ------------------------------------------------------------------------------------------------ 3. app
if [ "$db_ready" -eq 1 ] && ask "Step 3: copy the code to $KIDTUBE_DIR/src, build the virtualenv, install the systemd units (kidtube.service, daily backup timer), run migrations as '$KIDTUBE_USER', start the service on 127.0.0.1:$KIDTUBE_PORT (deploy/deploy.sh; only the kidtube service is restarted)"; then
    "$SCRIPT_DIR/deploy.sh"
else
    skipped "step 3 (deploy the app)"
fi

# ------------------------------------------------------------------------------------------------ nginx helpers
NGINX_RELOADED=0
# nginx_install TEMPLATE  -> writes the site file, links it, runs `nginx -t`; on failure restores the previous state and exits.
# Reloads (never restarts) only after the test passed.
nginx_install() {
    local tpl="$1" new prev="" had_link=0
    command -v nginx > /dev/null || die "nginx is not installed"
    new="$(mktemp)"
    render "$tpl" > "$new"
    if [ -f "$NGINX_SITE_AVAILABLE" ]; then
        if cmp -s "$new" "$NGINX_SITE_AVAILABLE"; then log "$NGINX_SITE_AVAILABLE is already up to date"; fi
        prev="$(mktemp)"; cp -a "$NGINX_SITE_AVAILABLE" "$prev"
        echo "    changes to the existing $NGINX_SITE_AVAILABLE:"; diff -u "$NGINX_SITE_AVAILABLE" "$new" | sed 's/^/    | /' || true
    fi
    [ -L "$NGINX_SITE_ENABLED" ] && had_link=1
    install -m 644 -o root -g root "$new" "$NGINX_SITE_AVAILABLE"
    [ -L "$NGINX_SITE_ENABLED" ] || ln -s "$NGINX_SITE_AVAILABLE" "$NGINX_SITE_ENABLED"
    rm -f "$new"
    if nginx -t; then
        systemctl reload nginx
        NGINX_RELOADED=1
        log "nginx reloaded (not restarted). Roll back: sudo rm $NGINX_SITE_ENABLED && sudo nginx -t && sudo systemctl reload nginx"
    else
        echo "ERROR: nginx -t failed: restoring the previous state; nginx was not reloaded and keeps its running configuration." >&2
        if [ -n "$prev" ]; then cp -a "$prev" "$NGINX_SITE_AVAILABLE"; else rm -f "$NGINX_SITE_AVAILABLE"; fi
        if [ "$had_link" -eq 0 ]; then rm -f "$NGINX_SITE_ENABLED"; fi
        rm -f "$prev"
        exit 1
    fi
    rm -f "$prev"
}

# ------------------------------------------------------------------------------------------------ 4. nginx over HTTP
cert_ok=0
[ -f "$CERT_DIR/fullchain.pem" ] && cert_ok=1
if [ "$cert_ok" -eq 1 ]; then
    log "step 4/5: a certificate for $KIDTUBE_DOMAIN already exists; HTTP-only bootstrap and certbot are skipped"
else
    if ask "Step 4: add $NGINX_SITE_AVAILABLE (server_name $KIDTUBE_DOMAIN only, port 80, serves only the certbot challenge) + symlink in sites-enabled, run nginx -t, then RELOAD nginx. Creates $KIDTUBE_WEBROOT"; then
        install -d -m 755 -o root -g root "$KIDTUBE_WEBROOT"
        nginx_install "$SCRIPT_DIR/nginx/kidtube-bootstrap.conf"
    else
        skipped "step 4 (nginx HTTP site)"
    fi

    # -------------------------------------------------------------------------------------------- 5. certificate
    if [ -z "$LETSENCRYPT_EMAIL" ]; then
        skipped "step 5 (certificate): LETSENCRYPT_EMAIL is not set in deploy/deploy.env"
    elif ask "Step 5: request a Let's Encrypt certificate for $KIDTUBE_DOMAIN ONLY ($KIDTUBE_CERT_METHOD method; no other site is touched, no --redirect). Renewals reload nginx through a hook stored for THIS certificate only"; then
        command -v certbot > /dev/null || die "certbot is not installed (sudo apt install certbot)"
        hook='nginx -t && systemctl reload nginx'
        if [ "$KIDTUBE_CERT_METHOD" = webroot ]; then
            certbot certonly --webroot -w "$KIDTUBE_WEBROOT" -d "$KIDTUBE_DOMAIN" --cert-name "$KIDTUBE_DOMAIN" \
                --agree-tos -m "$LETSENCRYPT_EMAIL" --non-interactive --deploy-hook "$hook"
        else
            certbot certonly --nginx -d "$KIDTUBE_DOMAIN" --cert-name "$KIDTUBE_DOMAIN" \
                --agree-tos -m "$LETSENCRYPT_EMAIL" --non-interactive --deploy-hook "$hook"
        fi
        [ -f "$CERT_DIR/fullchain.pem" ] && cert_ok=1
    else
        skipped "step 5 (certificate)"
    fi
fi

# ------------------------------------------------------------------------------------------------ 6. nginx over HTTPS
if [ "$cert_ok" -eq 1 ] && ask "Step 6: replace the site file with the full HTTPS version (HTTP -> HTTPS redirect, proxy to 127.0.0.1:$KIDTUBE_PORT, security headers, own log files), run nginx -t, then RELOAD nginx"; then
    nginx_install "$SCRIPT_DIR/nginx/kidtube.conf"
else
    skipped "step 6 (nginx HTTPS site)"
fi

echo
if [ "${#SKIPPED[@]}" -gt 0 ]; then
    echo "Skipped: ${SKIPPED[*]}. Run this script again to do them."
fi
cat <<EOF
Next (DEPLOY.md):
  - create the first parent account while ALLOW_REGISTRATION=true, then close registration:
      sudo sed -i 's/^ALLOW_REGISTRATION=.*/ALLOW_REGISTRATION=false/' $KIDTUBE_ENV_FILE && sudo systemctl restart kidtube
  - from your workstation:  deploy/smoke_test.sh https://$KIDTUBE_DOMAIN
EOF
