#!/usr/bin/env bash
# Black-box checks of a deployed KidTube. Run from any machine:
#   deploy/smoke_test.sh https://api.example.com
# Needs only curl. Exit status 0 = every check passed. Creates nothing and logs in as nobody.
set -u

base="${1:-}"
if [ -z "$base" ]; then
    echo "usage: $0 https://your.domain" >&2
    exit 2
fi
base="${base%/}"
host_part="${base#*://}"

pass=0
fail=0
ok() { printf 'PASS  %s\n' "$1"; pass=$((pass + 1)); }
bad() { printf 'FAIL  %s\n' "$1"; fail=$((fail + 1)); }
info() { printf 'INFO  %s\n' "$1"; }

# status <url> [curl args...]  ->  prints the HTTP status code (000 when the connection itself failed)
status() { local url="$1"; shift; curl -sS -o /dev/null --max-time 15 --max-redirs 0 -w '%{http_code}' "$@" "$url" 2> /dev/null || true; }

# 1. health over HTTPS. Plain curl (no -k) also proves the certificate chain and host name are valid.
if [[ "$base" == https://* ]]; then
    body="$(curl -sS --max-time 15 "$base/health" 2> /dev/null || true)"
    code="$(status "$base/health")"
    if [ "$code" = "200" ] && [[ "$body" == *'"ok"'* ]]; then ok "GET /health over HTTPS -> 200 $body"; else bad "GET /health over HTTPS -> $code (body: ${body:0:80})"; fi
else
    bad "base URL must start with https:// (got $base)"
fi

# 2. HTTP must redirect to HTTPS (and nothing may be served in clear text)
http_url="http://$host_part"
out="$(curl -sS -o /dev/null --max-time 15 --max-redirs 0 -w '%{http_code} %{redirect_url}' "$http_url/health" 2> /dev/null || true)"
if [[ "$out" == 30[1278]\ https://* ]]; then ok "HTTP redirects to HTTPS ($out)"; else bad "HTTP does not redirect to HTTPS (got: ${out:-no answer})"; fi

# 3. interactive docs and the schema must not be exposed
for path in /docs /redoc /openapi.json; do
    code="$(status "$base$path")"
    if [ "$code" = "404" ]; then ok "$path is not exposed (404)"; else bad "$path answered $code (expected 404)"; fi
done

# 4. an unauthenticated request to a protected route
code="$(status "$base/children")"
if [ "$code" = "401" ]; then ok "unauthenticated GET /children -> 401"; else bad "unauthenticated GET /children -> $code (expected 401)"; fi

# 5. extras
code="$(status "$base/does-not-exist")"
if [ "$code" = "404" ]; then ok "unknown path -> 404"; else bad "unknown path -> $code (expected 404)"; fi

headers="$(curl -sS -D - -o /dev/null --max-time 15 "$base/health" 2> /dev/null || true)"
if grep -qi '^x-content-type-options: *nosniff' <<< "$headers"; then ok "X-Content-Type-Options: nosniff"; else bad "X-Content-Type-Options header missing"; fi
if grep -qiE '^server: *nginx/[0-9]' <<< "$headers"; then bad "Server header leaks the nginx version (server_tokens off?)"; else ok "no nginx version in Server header"; fi
if grep -qi '^strict-transport-security:' <<< "$headers"; then ok "HSTS enabled"; else info "HSTS not enabled yet (enable it in the nginx site once everything above passes)"; fi

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
