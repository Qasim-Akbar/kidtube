# Deploying KidTube on a VPS that already runs nginx

Runbook for an **Ubuntu** VPS where nginx (and possibly PostgreSQL) already serve **other sites**. KidTube is added as one more
nginx site on **one host name you own** (for example `kidtube.yourdomain.org`). Nothing else on the machine is changed: no edits to
`nginx.conf` or other sites, no nginx or PostgreSQL restart, no change to other databases, `pg_hba.conf` or the firewall.

All settings live in **one file**, `deploy/deploy.env`, which every script reads. Below, `KIDTUBE_*` names refer to its values.

```
Android app ──HTTPS──► nginx (existing, :443)  server_name KIDTUBE_DOMAIN only
                         └─► uvicorn, ONE worker, 127.0.0.1:KIDTUBE_PORT   (systemd "kidtube", unprivileged user KIDTUBE_USER)
                               └─► PostgreSQL 127.0.0.1, own role + database  (no superuser)
```

| Path | What |
|---|---|
| `KIDTUBE_DIR` (`/opt/kidtube`) | `src/` code copy (`src.prev` = previous), `venv/`, `constraints.txt`, `VERSION` |
| `/etc/kidtube/kidtube.env` | all app settings and secrets, **mode 0600 root:root** |
| `/etc/nginx/sites-available/kidtube.conf` | the site; symlink in `sites-enabled/` |
| `/var/log/nginx/kidtube.access.log`, `kidtube.error.log` | nginx logs for this site only |
| `/var/backups/kidtube` | nightly `pg_dump` files, mode 700 `postgres` |
| `deploy/` in the repo | everything this runbook installs |

---

## 1. Prerequisites

* **DNS**: an **A record** (and AAAA only if the VPS has working IPv6) for `KIDTUBE_DOMAIN` pointing at the VPS. Check: `dig +short kidtube.yourdomain.org`.
* **Ports 80 and 443** reachable from the internet (they already are if nginx serves other sites; port 80 is needed for certificate issuing **and** renewal).
* Root/sudo access. nginx and PostgreSQL already installed and running.
* Packages (installing missing ones does not touch running services):
  ```bash
  sudo apt install -y python3 python3-venv rsync curl git openssl certbot
  ```
  Python 3.10 or newer (24.04 has 3.12). `psql` and `pg_dump` must exist (`postgresql-client` or the server package).
* The system clock must be synchronised (`timedatectl`): token expiry depends on it.

## 2. Copy the code

```bash
git clone <your-repository-url> ~/kidtube      # private repository: add a read-only deploy key first
cd ~/kidtube
```

(This checkout is only the source; `deploy.sh` copies `backend/` to `KIDTUBE_DIR/src`.)

## 3. Configure `deploy/deploy.env`

```bash
cp deploy/deploy.env.example deploy/deploy.env       # git-ignored: it holds your domain and e-mail
nano deploy/deploy.env
```

Fill in **`KIDTUBE_DOMAIN`** and **`LETSENCRYPT_EMAIL`**. The defaults are `KIDTUBE_USER=kidtube`, `KIDTUBE_PORT=8001`,
`KIDTUBE_DIR=/opt/kidtube`, database and role `kidtube`, `KIDTUBE_PG_PORT=5432` (`pg_lsclusters` shows the right port of the cluster
you want), certificates by the `webroot` method. Values are validated; a typo or unknown key stops the scripts.

## 4. Look for conflicts (read-only)

```bash
sudo deploy/check.sh
```

It changes nothing and prints `OK` / `NOTE` / `CONFLICT` / `UNKNOWN` lines: port in use, user/role/database/`server_name` already present,
DNS, nginx/certbot/PostgreSQL found, whether PostgreSQL listens beyond localhost (reported, **never changed**), whether the existing `pg_hba.conf`
already accepts a password login from 127.0.0.1 for the new role. Fix every `CONFLICT` (usually by changing a value in `deploy.env`) until it ends with
*No conflicts found*. Run it without `sudo` to see what it can check unprivileged (the PostgreSQL checks are then `UNKNOWN`).

## 5. Install (asks before every system change)

```bash
sudo deploy/install.sh --dry-run     # optional: prints each step, changes nothing
sudo deploy/install.sh
```

Every step explains what it will change and waits for `y` (anything else skips it). It is **idempotent**: run it again after a failure or after
skipping a step; finished steps are detected and skipped. It never restarts nginx or PostgreSQL; nginx is only **reloaded**, after `nginx -t` passed.

| Step | What happens |
|---|---|
| 1 | system user `KIDTUBE_USER` (no login shell, no home), `KIDTUBE_DIR`, `/etc/kidtube` |
| 2 | PostgreSQL role `KIDTUBE_DB_USER` (LOGIN only: **no superuser, no CREATEDB, no CREATEROLE**) with a **generated password** (only its SCRAM hash is sent to the server) and database `KIDTUBE_DB_NAME` owned by it, connectable only by it. Writes **`/etc/kidtube/kidtube.env`** (0600) with a generated `JWT_SECRET`, `DATABASE_URL` (127.0.0.1 + the password), `APP_ENV=prod`, `ENABLE_DOCS=false`, `TRUST_PROXY_HEADERS=true`, `ALLOW_REGISTRATION=true` (**for the first run only**, step 8). Then it tests the password login over 127.0.0.1; see "pg_hba" below if it fails |
| 3 | runs `deploy/deploy.sh`: copies the code, builds `venv` with the pinned dependencies (non-editable), installs `kidtube.service` and the backup timer, **runs `alembic upgrade head` as `KIDTUBE_USER`**, starts the service on `127.0.0.1:KIDTUBE_PORT`, checks `/health` (200), `/children` (401), `/docs` (404) |
| 4 | the HTTP-only nginx site (`server_name` = your domain **only**, no `default_server`) that serves just the certbot challenge; `nginx -t`; **reload** |
| 5 | `certbot certonly` for **this domain only** (webroot `KIDTUBE_WEBROOT`, or `KIDTUBE_CERT_METHOD=nginx`); no `--redirect`, no other site touched; the renewal reload hook is stored with this certificate only |
| 6 | the full HTTPS site; `nginx -t`; **reload** |

### What the nginx site does (`deploy/nginx/kidtube.conf`)
One standalone file for **one** `server_name`: HTTP redirects to HTTPS; HTTPS proxies to `127.0.0.1:KIDTUBE_PORT` with `X-Forwarded-For` overwritten by the
real peer address and `X-Forwarded-Proto` (this is what `TRUST_PROXY_HEADERS=true` relies on); `client_max_body_size 256k`, short client and 30 s proxy timeouts
(300 s for `/sync/` which verifies new links); security headers (`nosniff`, `X-Frame-Options`, `Referrer-Policy`, a restrictive CSP; HSTS is a commented-out option);
`server_tokens off` for this server only; separate logs `kidtube.access.log` / `kidtube.error.log` (Ubuntu's logrotate already rotates `/var/log/nginx/*.log`).
Only the API's path prefixes are forwarded, everything else (including `/docs`, `/openapi.json`) is 404. It also rate-limits `/auth/` and `/devices/pair`.
TLS protocols/ciphers and HTTP/2 are **not** set here: they belong to the shared listen socket and stay as your existing nginx configuration has them.
IPv6 `listen` lines are added only if nginx already listens on IPv6 (`KIDTUBE_NGINX_IPV6=auto`).

### Testing and rolling back nginx changes by hand
```bash
sudo nginx -t                                   # ALWAYS before a reload; a failing test changes nothing
sudo systemctl reload nginx                     # reload, never restart: other sites keep serving
# roll back this site completely (other sites untouched):
sudo rm /etc/nginx/sites-enabled/kidtube.conf && sudo nginx -t && sudo systemctl reload nginx
```
`install.sh` restores the previous site file and symlink by itself when `nginx -t` fails. To change the site later, edit `deploy/nginx/kidtube.conf`, then run
`sudo deploy/install.sh` (step 6 shows a diff first) or render by hand: `sudo bash -c '. deploy/lib.sh; load_config; render deploy/nginx/kidtube.conf > /etc/nginx/sites-available/kidtube.conf' && sudo nginx -t && sudo systemctl reload nginx`.

### Certificates
`sudo certbot renew --dry-run` must succeed (renewal needs port 80, DNS still pointing here, and `KIDTUBE_WEBROOT`). `systemctl list-timers certbot.timer` shows the schedule (Ubuntu's package).
Alternative to the webroot method: `KIDTUBE_CERT_METHOD=nginx` uses certbot's nginx plugin (`certbot certonly --nginx -d DOMAIN`), which briefly edits and reloads nginx for that domain.

### pg_hba (only if step 2 says the login failed)
The service connects to `127.0.0.1` as `KIDTUBE_DB_USER` with a password. Ubuntu's default `pg_hba.conf` already contains
`host all all 127.0.0.1/32 scram-sha-256`, so nothing is needed. If your file is stricter, add **only this line**, **above** any broader rule that would reject it:
```
host    kidtube    kidtube    127.0.0.1/32    scram-sha-256
```
then `sudo systemctl reload postgresql` (reload, not restart) and run `install.sh` again. The scripts never edit `pg_hba.conf` themselves.
(Alternative without a password: unix socket + peer authentication needs an OS user and role with the same name and `DATABASE_URL=postgresql+psycopg://kidtube@/kidtube?host=/var/run/postgresql`; the default rules allow it, but the service user must be able to reach the socket.)

## 6. The environment file

`/etc/kidtube/kidtube.env` is created by step 2 (never overwritten afterwards). Mode **0600 root:root** (systemd reads it as root). Check: `sudo ls -l /etc/kidtube/kidtube.env`.
Keys: `APP_ENV=prod`, `JWT_SECRET` (generated; **back it up** - changing it logs every parent out), `DATABASE_URL`, `ENABLE_DOCS=false`, `TRUST_PROXY_HEADERS=true`, `ALLOW_REGISTRATION=true` (until step 8),
`DEFAULT_TIMEZONE=Asia/Karachi` and the rate-limit knobs. The format is `KEY=value` (systemd syntax: no quotes, no trailing comments). After editing: `sudo systemctl restart kidtube`.

## 7. The service

`kidtube.service` (rendered from `deploy/kidtube.service`): dedicated unprivileged user, uvicorn on **`127.0.0.1:KIDTUBE_PORT` only**, **`--workers 1`** (the login/pairing rate limiter lives in process memory),
`--no-proxy-headers` (only the app reads `X-Forwarded-For`, via `TRUST_PROXY_HEADERS`), `Restart=on-failure`, and hardening: `NoNewPrivileges`, `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`,
`PrivateDevices`, no capabilities, syscall filter, `MemoryMax=512M`.
```bash
systemctl status kidtube --no-pager
curl -s http://127.0.0.1:8001/health            # {"status":"ok"}   (use your KIDTUBE_PORT)
```

## 8. Create the first parent, then close registration

`ALLOW_REGISTRATION=true` exists only for this step. Type the password at the prompt (it stays out of your shell history; avoid `"` and `\`):

```bash
DOMAIN=kidtube.yourdomain.org
read -rp 'Parent e-mail: ' EMAIL
read -rsp 'Password (min 8 chars): ' PW; echo
curl -sS -X POST https://$DOMAIN/auth/register -H 'Content-Type: application/json' \
  -d "$(printf '{"email":"%s","password":"%s","family_name":"Our Family","timezone":"Asia/Karachi"}' "$EMAIL" "$PW")"
# -> {"parent_id":"...","family_id":"...","email":"..."}   (HTTP 201)
curl -sS -X POST https://$DOMAIN/auth/login -H 'Content-Type: application/json' \
  -d "$(printf '{"email":"%s","password":"%s"}' "$EMAIL" "$PW")" | cut -c1-40        # {"access_token":"eyJ...  = login works
unset PW
```

**Now close registration**, or anyone who finds the URL can create a family:
```bash
sudo sed -i 's/^ALLOW_REGISTRATION=.*/ALLOW_REGISTRATION=false/' /etc/kidtube/kidtube.env
sudo systemctl restart kidtube
curl -sS -X POST https://$DOMAIN/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"x@example.com","password":"12345678"}'        # must say: {"detail":"Registration is disabled"}
```

## 9. Smoke test

From your workstation (tests the real public path):
```bash
deploy/smoke_test.sh https://kidtube.yourdomain.org
```
PASS/FAIL for `/health` over HTTPS with a valid certificate, HTTP redirecting to HTTPS, `/docs` `/redoc` `/openapi.json` not exposed, unauthenticated `/children` = 401,
unknown path = 404, security headers. Optional HSTS: remove the `#` before `add_header Strict-Transport-Security` in `deploy/nginx/kidtube.conf`, rerun install step 6, and run the test again.

## 10. Backups and restore

`deploy.sh` installs a timer (`kidtube-backup.timer`, daily 03:15 server time) running `pg_dump` (custom format) into `KIDTUBE_BACKUP_DIR`, mode 600, keeping the **newest 14** (`KIDTUBE_BACKUP_KEEP`);
old ones are pruned only after a new dump succeeded and was verified with `pg_restore --list`.
```bash
systemctl list-timers kidtube-backup.timer --no-pager
sudo systemctl start kidtube-backup.service                 # a dump right now
journalctl -u kidtube-backup -n 20 --no-pager
sudo ls -l /var/backups/kidtube                              # kidtube-<UTC timestamp>.dump
```

**Test restore into a scratch database** (`<KIDTUBE_DB_NAME>_restore_test`; refuses to run if a database of that name already exists, drops only what it created; the live database is not touched):
```bash
sudo -u postgres /usr/local/sbin/kidtube-restore-test                       # newest dump
sudo -u postgres /usr/local/sbin/kidtube-restore-test /var/backups/kidtube/kidtube-<timestamp>.dump
# ends with: PASS: dump restores cleanly   (prints the alembic revision and a row count per table)
```
Do it once now and after big changes.

**Restore (disaster recovery)**, replacing the live database from a dump:
```bash
sudo systemctl stop kidtube
sudo -u postgres psql -p 5432 -c 'DROP DATABASE kidtube WITH (FORCE)'                     # your KIDTUBE_DB_NAME / port
sudo -u postgres psql -p 5432 -c 'CREATE DATABASE kidtube OWNER kidtube ENCODING '"'"'UTF8'"'"' TEMPLATE template0'
sudo -u postgres pg_restore -p 5432 --exit-on-error --dbname=kidtube /var/backups/kidtube/kidtube-<timestamp>.dump
sudo systemctl start kidtube && curl -s http://127.0.0.1:8001/health
```
On a **new server**: run steps 1-5 first (role, env file), copy the dump over, then the same commands; run `sudo deploy/deploy.sh` afterwards if the code is newer than the dump.
Copy `/var/backups/kidtube` **off the machine** regularly (the dumps hold e-mail addresses, password hashes and children's names; keep copies private):
`ssh admin@host 'sudo tar -C /var/backups/kidtube -cf - .' | tar -xf - -C ~/kidtube-backups`.

## Start over with empty data

To throw away test accounts, children, videos and events before the real family starts (or after a bad experiment), run on the VPS, from the checkout:

```bash
sudo deploy/reset_data.sh --dry-run     # prints every command it would run, changes nothing, needs no root
sudo deploy/reset_data.sh               # the real thing
```

It is interactive. In order it: shows what will be erased and makes you **type the domain** (`KIDTUBE_DOMAIN`) to confirm; takes a **pg_dump backup** (`kidtube-backup`) and stops if that fails; stops the `kidtube` service; **drops and recreates only the database `KIDTUBE_DB_NAME`** (owner `KIDTUBE_DB_USER`, same locked-down privileges as `install.sh`); runs `alembic upgrade head` as the service user; sets `ALLOW_REGISTRATION=true` in the env file; starts the service and checks `/health`. It refuses to run if `DATABASE_URL` in the env file names a different database than `KIDTUBE_DB_NAME`, or if that name is `postgres`/`template0`/`template1`. No other database, role, site, nginx or PostgreSQL setting is touched, and the service user, JWT secret and database password stay as they are.

Afterwards: create the real parent account (step 8; phones that still hold the old test account must sign out or clear app data, and kid phones pair again), then **close registration again**:

```bash
sudo sed -i 's/^ALLOW_REGISTRATION=.*/ALLOW_REGISTRATION=false/' /etc/kidtube/kidtube.env && sudo systemctl restart kidtube
```

If something fails after the database was dropped, the script prints the backup path and the exact `pg_restore` commands. The dump is in `KIDTUBE_BACKUP_DIR` (default `/var/backups/kidtube`, mode 600).

## 11. Updating the app

```bash
cd ~/kidtube && git pull
sudo deploy/deploy.sh                # or: sudo deploy/deploy.sh --pull
deploy/smoke_test.sh https://kidtube.yourdomain.org
```
`deploy.sh` takes a database backup first (`--skip-backup` to skip), keeps the previous code in `KIDTUBE_DIR/src.prev`, re-renders the units, **applies migrations as `KIDTUBE_USER`**,
restarts **only the kidtube service** and checks health. On failure it prints the exact rollback commands; it is safe to run again. It never touches nginx, certbot or PostgreSQL.
Dependency versions are pinned in `deploy/constraints.txt`. If `deploy/nginx/kidtube.conf` changed (for example a new API route prefix), re-run `sudo deploy/install.sh` (step 6).

## 12. Logs

```bash
journalctl -u kidtube -f                                  # the API; add -n 200 --no-pager for the last lines
journalctl -u kidtube --since "1 hour ago" -p warning
sudo tail -f /var/log/nginx/kidtube.access.log /var/log/nginx/kidtube.error.log     # 429 = the nginx rate limit
journalctl -u kidtube-backup -n 30 --no-pager
```
uvicorn's access log is off (nginx has it, with real client addresses). Logs can hold request paths and ids, never passwords or tokens.

## 13. Rollback and uninstall

* **Bad deploy**: follow the commands `deploy.sh` printed (previous code in `src.prev`, `alembic downgrade <revision>`, or restore the pre-deploy dump).
* **Take the site offline, keep everything**: `sudo rm /etc/nginx/sites-enabled/kidtube.conf && sudo nginx -t && sudo systemctl reload nginx` and `sudo systemctl disable --now kidtube`.
* **Remove KidTube entirely** (destructive; take a last dump first). Nothing here touches other sites or databases:
  ```bash
  sudo systemctl disable --now kidtube kidtube-backup.timer
  sudo rm /etc/systemd/system/kidtube.service /etc/systemd/system/kidtube-backup.service /etc/systemd/system/kidtube-backup.timer && sudo systemctl daemon-reload
  sudo rm -f /etc/nginx/sites-enabled/kidtube.conf /etc/nginx/sites-available/kidtube.conf && sudo nginx -t && sudo systemctl reload nginx
  sudo certbot delete --cert-name kidtube.yourdomain.org
  sudo -u postgres psql -c 'DROP DATABASE kidtube' -c 'DROP ROLE kidtube'
  sudo rm -rf /opt/kidtube /etc/kidtube /var/backups/kidtube /var/www/kidtube-acme /usr/local/sbin/kidtube-backup /usr/local/sbin/kidtube-restore-test
  sudo userdel kidtube
  ```

---

## Things that will bite you

* **One worker only.** The rate limiters are in process memory; two workers = N times the guesses. The unit says `--workers 1`; to scale you first need a shared limiter store.
* **`TRUST_PROXY_HEADERS` must match the proxy.** Behind this nginx: `true`. If it were `false`, every request would look like 127.0.0.1 and five wrong passwords from anyone would lock out the family. Never expose uvicorn directly (it binds to 127.0.0.1).
* **Keep `JWT_SECRET` stable** and back it up with the env file; kid devices are unaffected by a change, parents must sign in again.
* **Close registration** after the first parent (step 8).
* **Certificate renewal** needs port 80, DNS and `KIDTUBE_WEBROOT`. Another site's `return 301`/catch-all on port 80 that matches your host name would break it; this site's own server block answers the challenge. Expiry makes the Android app fail with certificate errors.
* **New API routes need an nginx line** (the regex `location` in `deploy/nginx/kidtube.conf`, then install step 6). Run `smoke_test.sh` after updates.
* **Another site wins if it claims the same `server_name`** or if nginx has a conflicting `listen` option: `check.sh` and `nginx -t` report these; fix the name, never "solve" it with `default_server`.
* **PostgreSQL major upgrades are not automatic**: a new cluster appears (often on another port); update `KIDTUBE_PG_PORT` and the port in `DATABASE_URL`, take a dump first, run the restore test.
* **A new Ubuntu release or system Python breaks the virtualenv**: `sudo rm -rf /opt/kidtube/venv && sudo deploy/deploy.sh`.
* **Backups on the same disk are not backups**: copy them off the machine.
* **Memory**: `MemoryMax=512M` in the unit; raise it if `systemctl status kidtube` shows an oom-kill.
* **Backup time is server local time**; day boundaries inside the app always use the family timezone.

## What was and was not verified

Verified on a development machine: the backend test suites (SQLite and PostgreSQL) and, earlier, a clean non-editable install under Python 3.12 with the pinned dependencies and a uvicorn start with the unit's flags;
`bash -n` on every script; template rendering of every file with sample values (no placeholder left); `deploy/check.sh` and `install.sh --dry-run` (read-only paths) on a machine without nginx.

**Not run anywhere**: `nginx -t` on the rendered site (nginx is not installed on the development machine; the config uses only long-standing directives), `install.sh` and `deploy.sh` against a real system, certbot, the SCRAM
password hash against a real PostgreSQL, the backup/restore scripts, `smoke_test.sh`, and the Android release build with signing. The first real run is the test; every step stops on the first error and nginx is only reloaded after `nginx -t`.
