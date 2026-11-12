#!/usr/bin/env bash
# Shared helpers for deploy/check.sh, deploy/install.sh and deploy/deploy.sh. Sourced, never run.
# shellcheck shell=bash

DEPLOY_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_ENV_FILE="${KIDTUBE_DEPLOY_ENV:-$DEPLOY_LIB_DIR/deploy.env}"

log() { printf '==> %s\n' "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }

if ! declare -F die > /dev/null; then
    die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
fi

# Settings the scripts understand (anything else in the file is an error: typos must not pass silently).
KIDTUBE_KEYS="KIDTUBE_DOMAIN LETSENCRYPT_EMAIL KIDTUBE_USER KIDTUBE_PORT KIDTUBE_DIR KIDTUBE_DB_NAME KIDTUBE_DB_USER
KIDTUBE_PG_PORT KIDTUBE_ENV_FILE KIDTUBE_CERT_METHOD KIDTUBE_WEBROOT KIDTUBE_NGINX_IPV6 KIDTUBE_BACKUP_DIR KIDTUBE_BACKUP_KEEP"

# Parse the env-style file WITHOUT executing it, then validate every value.
load_config() {
    [ -f "$DEPLOY_ENV_FILE" ] || die "$DEPLOY_ENV_FILE not found. Create it: cp $DEPLOY_LIB_DIR/deploy.env.example $DEPLOY_LIB_DIR/deploy.env and edit it."
    local line key val n=0
    while IFS= read -r line || [ -n "$line" ]; do
        n=$((n + 1))
        line="${line%$'\r'}"
        [[ "$line" =~ ^[[:space:]]*(#.*)?$ ]] && continue
        [[ "$line" =~ ^([A-Z0-9_]+)=(.*)$ ]] || die "$DEPLOY_ENV_FILE line $n: expected KEY=value"
        key="${BASH_REMATCH[1]}"
        val="${BASH_REMATCH[2]}"
        [[ " ${KIDTUBE_KEYS//$'\n'/ } " == *" $key "* ]] || die "$DEPLOY_ENV_FILE line $n: unknown setting $key"
        printf -v "$key" '%s' "$val"
    done < "$DEPLOY_ENV_FILE"

    : "${KIDTUBE_USER:=kidtube}" "${KIDTUBE_PORT:=8001}" "${KIDTUBE_DIR:=/opt/kidtube}" "${KIDTUBE_DB_NAME:=kidtube}"
    : "${KIDTUBE_DB_USER:=kidtube}" "${KIDTUBE_PG_PORT:=5432}" "${KIDTUBE_ENV_FILE:=/etc/kidtube/kidtube.env}"
    : "${KIDTUBE_CERT_METHOD:=webroot}" "${KIDTUBE_WEBROOT:=/var/www/kidtube-acme}" "${KIDTUBE_NGINX_IPV6:=auto}"
    : "${KIDTUBE_BACKUP_DIR:=/var/backups/kidtube}" "${KIDTUBE_BACKUP_KEEP:=14}"

    local ident='^[a-z_][a-z0-9_]{0,30}$' path='^/[A-Za-z0-9._/-]+$'
    [[ "${KIDTUBE_DOMAIN:-}" =~ ^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}$ ]] || die "KIDTUBE_DOMAIN must be a lower-case host name such as kidtube.example.com (got '${KIDTUBE_DOMAIN:-}')"
    [[ "$KIDTUBE_DOMAIN" != *CHANGE_ME* && "$KIDTUBE_DOMAIN" != *example.com ]] || die "KIDTUBE_DOMAIN is still the placeholder"
    [[ "$KIDTUBE_USER" =~ ^[a-z_][a-z0-9_-]{0,30}$ ]] || die "KIDTUBE_USER is not a valid user name"
    [[ "$KIDTUBE_DB_NAME" =~ $ident ]] || die "KIDTUBE_DB_NAME: lower-case letters, digits and _ only"
    [[ "$KIDTUBE_DB_USER" =~ $ident ]] || die "KIDTUBE_DB_USER: lower-case letters, digits and _ only"
    [[ "$KIDTUBE_PORT" =~ ^[0-9]{2,5}$ && "$KIDTUBE_PORT" -ge 1024 && "$KIDTUBE_PORT" -le 65535 ]] || die "KIDTUBE_PORT must be 1024-65535"
    [[ "$KIDTUBE_PG_PORT" =~ ^[0-9]{2,5}$ ]] || die "KIDTUBE_PG_PORT must be a number"
    [[ "$KIDTUBE_BACKUP_KEEP" =~ ^[0-9]+$ && "$KIDTUBE_BACKUP_KEEP" -ge 1 ]] || die "KIDTUBE_BACKUP_KEEP must be a positive integer"
    for p in KIDTUBE_DIR KIDTUBE_ENV_FILE KIDTUBE_WEBROOT KIDTUBE_BACKUP_DIR; do
        [[ "${!p}" =~ $path && "${!p}" != *..* ]] || die "$p must be an absolute path of letters, digits and . _ - / (got '${!p}')"
    done
    [[ "$KIDTUBE_DIR" != /home/* ]] || die "KIDTUBE_DIR must not be under /home (the service runs with ProtectHome=yes)"
    [[ "$KIDTUBE_CERT_METHOD" == webroot || "$KIDTUBE_CERT_METHOD" == nginx ]] || die "KIDTUBE_CERT_METHOD must be webroot or nginx"
    [[ "$KIDTUBE_NGINX_IPV6" == auto || "$KIDTUBE_NGINX_IPV6" == yes || "$KIDTUBE_NGINX_IPV6" == no ]] || die "KIDTUBE_NGINX_IPV6 must be auto, yes or no"
    [[ "${LETSENCRYPT_EMAIL:-}" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ && "$LETSENCRYPT_EMAIL" != CHANGE_ME* ]] || LETSENCRYPT_EMAIL=""
    # Derived
    NGINX_SITE_AVAILABLE="/etc/nginx/sites-available/kidtube.conf"
    NGINX_SITE_ENABLED="/etc/nginx/sites-enabled/kidtube.conf"
    CERT_DIR="/etc/letsencrypt/live/$KIDTUBE_DOMAIN"
}

# nginx_ipv6_lines: "yes" when the [::] listen lines should be rendered.
use_ipv6() {
    case "$KIDTUBE_NGINX_IPV6" in
        yes) return 0 ;;
        no) return 1 ;;
        *) ss -ltn 2> /dev/null | awk '{print $4}' | grep -qE '^\[::\]:(80|443)$' ;;
    esac
}

# render TEMPLATE  ->  stdout. Replaces @NAME@ placeholders; fails when one is left over.
render() {
    local tpl="$1" ipv6_80="" ipv6_443="" out
    if use_ipv6; then
        ipv6_80="listen [::]:80;"
        ipv6_443="listen [::]:443 ssl;"
    fi
    out="$(sed \
        -e "s|@KIDTUBE_DOMAIN@|$KIDTUBE_DOMAIN|g" \
        -e "s|@KIDTUBE_USER@|$KIDTUBE_USER|g" \
        -e "s|@KIDTUBE_PORT@|$KIDTUBE_PORT|g" \
        -e "s|@KIDTUBE_DIR@|$KIDTUBE_DIR|g" \
        -e "s|@KIDTUBE_DB_NAME@|$KIDTUBE_DB_NAME|g" \
        -e "s|@KIDTUBE_DB_USER@|$KIDTUBE_DB_USER|g" \
        -e "s|@KIDTUBE_PG_PORT@|$KIDTUBE_PG_PORT|g" \
        -e "s|@KIDTUBE_ENV_FILE@|$KIDTUBE_ENV_FILE|g" \
        -e "s|@KIDTUBE_WEBROOT@|$KIDTUBE_WEBROOT|g" \
        -e "s|@KIDTUBE_BACKUP_DIR@|$KIDTUBE_BACKUP_DIR|g" \
        -e "s|@KIDTUBE_BACKUP_KEEP@|$KIDTUBE_BACKUP_KEEP|g" \
        -e "s|@NGINX_LISTEN_80_V6@|$ipv6_80|g" \
        -e "s|@NGINX_LISTEN_443_V6@|$ipv6_443|g" \
        "$tpl")"
    if grep -qE '@[A-Z0-9_]+@' <<< "$out"; then
        die "unreplaced placeholder in $tpl: $(grep -oE '@[A-Z0-9_]+@' <<< "$out" | sort -u | tr '\n' ' ')"
    fi
    printf '%s\n' "$out"
}

# Run a command as the postgres OS user (peer authentication on the local socket, nothing in the command line).
as_postgres() { (cd / && runuser -u postgres -- "$@"); }
psql_admin() { as_postgres psql -X -q -p "$KIDTUBE_PG_PORT" -v ON_ERROR_STOP=1 "$@"; }
