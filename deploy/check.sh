#!/usr/bin/env bash
# READ-ONLY pre-flight: prints what a KidTube install on THIS machine would conflict with. Changes nothing
# (no file is written, no service is touched; it only reads config files and asks `ss`, `systemctl is-active`, `psql` SELECTs).
#
#     deploy/check.sh            # as a normal user: PostgreSQL and some nginx files may be unreadable ("could not check")
#     sudo deploy/check.sh       # complete answer
#
# Settings: deploy/deploy.env. Exit status: 0 = no conflict, 1 = at least one CONFLICT.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy/lib.sh
. "$SCRIPT_DIR/lib.sh"
load_config

conflicts=0
ok() { printf 'OK        %s\n' "$*"; }
note() { printf 'NOTE      %s\n' "$*"; }
skip() { printf 'UNKNOWN   %s\n' "$*"; }
conflict() { printf 'CONFLICT  %s\n' "$*"; conflicts=$((conflicts + 1)); }

IS_ROOT=0
[ "$(id -u)" -eq 0 ] && IS_ROOT=1
INSTALLED=0
[ -f "$KIDTUBE_DIR/VERSION" ] && INSTALLED=1
echo "KidTube pre-flight for $KIDTUBE_DOMAIN (user $KIDTUBE_USER, port $KIDTUBE_PORT, dir $KIDTUBE_DIR, database $KIDTUBE_DB_NAME)"
if [ "$INSTALLED" -eq 1 ]; then
    echo "A previous KidTube deployment exists ($KIDTUBE_DIR/VERSION): items that belong to it are reported as NOTE, not CONFLICT."
fi
echo

# ---- port -----------------------------------------------------------------------------------------------------------
listener="$(ss -ltnH "sport = :$KIDTUBE_PORT" 2> /dev/null)"
if [ -z "$listener" ]; then
    ok "port $KIDTUBE_PORT is free"
elif [ "$INSTALLED" -eq 1 ] && systemctl is-active --quiet kidtube.service 2> /dev/null; then
    note "port $KIDTUBE_PORT is used by the running kidtube service (expected on a re-run)"
else
    conflict "port $KIDTUBE_PORT is already in use: $(awk '{print $4}' <<< "$listener" | tr '\n' ' ') (choose another KIDTUBE_PORT)"
fi

# ---- OS user, directories, units ---------------------------------------------------------------------------------------
if id "$KIDTUBE_USER" > /dev/null 2>&1; then
    if [ "$INSTALLED" -eq 1 ]; then note "user $KIDTUBE_USER exists (previous KidTube install)"; else conflict "user $KIDTUBE_USER already exists (used by something else? choose another KIDTUBE_USER)"; fi
else
    ok "user $KIDTUBE_USER does not exist yet (install.sh creates it, no login shell)"
fi
if [ -e "$KIDTUBE_DIR" ] && [ "$INSTALLED" -eq 0 ]; then conflict "$KIDTUBE_DIR already exists and is not a KidTube deployment"; else ok "$KIDTUBE_DIR is free or ours"; fi
for u in kidtube.service kidtube-backup.service kidtube-backup.timer; do
    if [ -e "/etc/systemd/system/$u" ] && [ "$INSTALLED" -eq 0 ]; then conflict "/etc/systemd/system/$u already exists"; fi
done
if [ -e "$KIDTUBE_ENV_FILE" ] && [ "$INSTALLED" -eq 0 ]; then note "$KIDTUBE_ENV_FILE exists: install.sh will keep it and generate nothing new"; fi

# ---- nginx -------------------------------------------------------------------------------------------------------------
if command -v nginx > /dev/null 2>&1; then
    ok "nginx found: $(nginx -v 2>&1)"
    # grep the configuration files instead of running nginx (a pure read).
    if [ -r /etc/nginx ]; then
        # Our own file (a re-run) is skipped; comment lines never match.
        hits="$(grep -rEn "^[[:space:]]*server_name[^;]*[[:space:]]$KIDTUBE_DOMAIN[[:space:];]" /etc/nginx 2> /dev/null | grep -v '/kidtube\.conf:' | grep -v '\.bak:')"
        if [ -n "$hits" ]; then
            conflict "server_name $KIDTUBE_DOMAIN is already present in: $(cut -d: -f1 <<< "$hits" | sort -u | tr '\n' ' ')"
        else
            ok "no other nginx config uses server_name $KIDTUBE_DOMAIN"
        fi
        if [ -e /etc/nginx/sites-enabled/kidtube.conf ] || [ -e /etc/nginx/sites-available/kidtube.conf ]; then
            if [ "$INSTALLED" -eq 1 ]; then note "kidtube.conf already in sites-available/enabled (will be replaced after a diff + nginx -t)"; else conflict "/etc/nginx/sites-*/kidtube.conf already exists"; fi
        fi
        if grep -rqE 'zone=kidtube_(auth|pair|conn)|zone=kidtube_tls' /etc/nginx 2> /dev/null && [ ! -e /etc/nginx/sites-available/kidtube.conf ]; then
            conflict "an nginx rate-limit zone named kidtube_* is already defined elsewhere (duplicate zone names make nginx -t fail)"
        fi
        def="$(grep -rEn 'listen[^;#]*default_server' /etc/nginx 2> /dev/null | grep -v '#' | head -3)"
        if [ -n "$def" ]; then note "existing default_server (left alone, our blocks never use it): $(cut -d: -f1,2 <<< "$def" | tr '\n' ' ')"; fi
    else
        skip "/etc/nginx is not readable (re-run with sudo to check server_name conflicts)"
    fi
    if ss -ltnH 2> /dev/null | awk '{print $4}' | grep -qE '(:|\])80$'; then ok "something listens on :80 (nginx), as expected"; else note "nothing listens on :80 yet"; fi
    if ss -ltnH 2> /dev/null | awk '{print $4}' | grep -qE '(:|\])443$'; then ok "something listens on :443"; else note "nothing listens on :443 yet"; fi
else
    conflict "nginx is not installed (this runbook adds a site to an existing nginx: sudo apt install nginx)"
fi

# ---- DNS -----------------------------------------------------------------------------------------------------------------
resolved="$(getent ahostsv4 "$KIDTUBE_DOMAIN" 2> /dev/null | awk '{print $1}' | sort -u | tr '\n' ' ')"
if [ -z "$resolved" ]; then
    conflict "$KIDTUBE_DOMAIN does not resolve (create the DNS A record first; certbot needs it)"
else
    ok "$KIDTUBE_DOMAIN resolves to: $resolved(compare with this server's public IP; local addresses: $(hostname -I 2> /dev/null))"
fi

# ---- certificates --------------------------------------------------------------------------------------------------------
if command -v certbot > /dev/null 2>&1; then ok "certbot found"; else note "certbot not installed (sudo apt install certbot; install.sh will stop if it is missing)"; fi
if [ -d "$CERT_DIR" ]; then note "a certificate for $KIDTUBE_DOMAIN already exists in /etc/letsencrypt (install.sh reuses it)"; fi
if [ -e "$KIDTUBE_WEBROOT" ] && [ "$INSTALLED" -eq 0 ] && [ -n "$(ls -A "$KIDTUBE_WEBROOT" 2> /dev/null)" ]; then conflict "$KIDTUBE_WEBROOT exists and is not empty"; fi

# ---- PostgreSQL ----------------------------------------------------------------------------------------------------------
if command -v pg_lsclusters > /dev/null 2>&1; then note "clusters: $(pg_lsclusters --no-header 2> /dev/null | awk '{printf "%s/%s:%s(%s) ", $1, $2, $3, $4}')"; fi
if ! command -v psql > /dev/null 2>&1; then
    conflict "PostgreSQL client tools not found (sudo apt install postgresql)"
elif [ "$IS_ROOT" -eq 1 ] && id postgres > /dev/null 2>&1; then
    q() { (cd / && runuser -u postgres -- psql -X -tA -p "$KIDTUBE_PG_PORT" -d postgres -c "$1" 2> /dev/null); }
    if [ "$(q 'select 1')" != 1 ]; then
        conflict "cannot connect to PostgreSQL on port $KIDTUBE_PG_PORT as the postgres user (wrong KIDTUBE_PG_PORT? see pg_lsclusters)"
    else
        ok "PostgreSQL answers on port $KIDTUBE_PG_PORT ($(q 'show server_version'))"
        la="$(q 'show listen_addresses')"
        case "$la" in
            "*" | *0.0.0.0* | *::*) note "listen_addresses = '$la': PostgreSQL accepts non-local connections (existing setting, NOT changed by KidTube; make sure the firewall blocks $KIDTUBE_PG_PORT)" ;;
            *) ok "listen_addresses = '$la' (local only)" ;;
        esac
        if [ "$(q "select 1 from pg_database where datname='$KIDTUBE_DB_NAME'")" = 1 ]; then
            if [ "$INSTALLED" -eq 1 ]; then note "database $KIDTUBE_DB_NAME exists (previous KidTube install)"; else conflict "database $KIDTUBE_DB_NAME already exists (choose another KIDTUBE_DB_NAME)"; fi
        else ok "database $KIDTUBE_DB_NAME does not exist"; fi
        if [ "$(q "select 1 from pg_roles where rolname='$KIDTUBE_DB_USER'")" = 1 ]; then
            if [ "$INSTALLED" -eq 1 ]; then note "role $KIDTUBE_DB_USER exists (previous KidTube install)"; else conflict "role $KIDTUBE_DB_USER already exists (choose another KIDTUBE_DB_USER)"; fi
        else ok "role $KIDTUBE_DB_USER does not exist"; fi
        # Is a password login from 127.0.0.1 for the new role/database going to be accepted by the existing pg_hba rules?
        rule="$(q "select line_number||': '||type||' '||database::text||' '||user_name::text||' '||coalesce(address,'')||' '||auth_method from pg_hba_file_rules where error is null and type in ('host','hostssl') and (address in ('127.0.0.1','localhost') or netmask is not null) and auth_method in ('scram-sha-256','md5') and ('all'=any(database) or '$KIDTUBE_DB_NAME'=any(database)) and ('all'=any(user_name) or '$KIDTUBE_DB_USER'=any(user_name)) order by line_number limit 1")"
        if [ -n "$rule" ]; then
            ok "pg_hba.conf already lets $KIDTUBE_DB_USER connect to $KIDTUBE_DB_NAME from 127.0.0.1 with a password (line $rule): nothing to add"
        else
            note "no pg_hba.conf line matches a password login from 127.0.0.1; install.sh will print the one line to add (it never edits pg_hba.conf itself)"
        fi
    fi
else
    skip "PostgreSQL role/database/pg_hba checks need root (run: sudo deploy/check.sh)"
fi

# ---- tooling -------------------------------------------------------------------------------------------------------------
for c in python3 rsync curl systemd-run openssl; do
    command -v "$c" > /dev/null 2>&1 && ok "$c found" || conflict "$c is missing (sudo apt install python3 python3-venv rsync curl openssl)"
done
python3 -c 'import ensurepip, venv' 2> /dev/null || conflict "python3-venv is missing (sudo apt install python3-venv)"
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' 2> /dev/null || conflict "Python >= 3.10 is required"

echo
if [ "$conflicts" -eq 0 ]; then
    echo "No conflicts found. Next: sudo deploy/install.sh"
    exit 0
fi
echo "$conflicts conflict(s). Fix them (or change the values in deploy/deploy.env) and run this again."
exit 1
