# Deploying KidTube on a single Ubuntu VPS (no Docker)

Runbook for a fresh **Ubuntu 24.04** server and the domain **api.example.com** (replace it everywhere with your real
domain). Follow it top to bottom. Commands starting with `sudo` run on the VPS; "your workstation" means your own computer.

What you end up with:

```
Android app ──HTTPS──► nginx (443, TLS from Let's Encrypt)
                         └─► uvicorn, ONE worker, 127.0.0.1:8000   (systemd unit "kidtube", user "kidtube")
                               └─► PostgreSQL, localhost only        (role + database "kidtube")
```

| Path | What |
|---|---|
| `/opt/kidtube/venv` | Python virtualenv (owned by root, read-only for the service) |
| `/opt/kidtube/src` | copy of `backend/` (migrations live here; `src.prev` is the previous deploy) |
| `/opt/kidtube/constraints.txt` | pinned dependency versions |
| `/etc/kidtube/kidtube.env` | all settings and secrets, mode 640 `root:kidtube` |
| `/var/backups/kidtube` | nightly database dumps, mode 700 `postgres` |
| `deploy/` in the repo | every file this runbook installs |

You need: the VPS with root or sudo access, a domain with an **A (and AAAA, if the VPS has IPv6) record pointing at the
VPS** before step 7, and an SSH key pair on your workstation (`ssh-keygen -t ed25519` if you have none).

---

## 1. SSH key login, a non-root sudo user, no password login

First login is as root (or the image's default user). Create your admin user:

```bash
adduser admin                      # choose any name; set a password (sudo will ask for it)
usermod -aG sudo admin
```

On your workstation, install your public key for that user and test it **in a new terminal**:

```bash
ssh-copy-id admin@api.example.com   # or the VPS IP address, if DNS is not ready yet
ssh admin@api.example.com           # must log in without asking for the account password
sudo whoami                         # must print: root
```

Only now turn off password and root login. Keep your working session open until the test at the end succeeds:

```bash
sudo tee /etc/ssh/sshd_config.d/10-kidtube-hardening.conf >/dev/null <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
EOF
sudo sshd -t && sudo systemctl restart ssh
sudo sshd -T | grep -iE '^(passwordauthentication|permitrootlogin|kbdinteractiveauthentication)'   # all "no"
```

(`10-` sorts before cloud-init's `50-cloud-init.conf`; sshd uses the first value it reads, so this file wins.)
In a **second new terminal** confirm `ssh admin@api.example.com` still works. From now on work as `admin`.

## 2. Packages

```bash
sudo apt update && sudo apt full-upgrade -y
sudo apt install -y ufw fail2ban nginx certbot python3 python3-venv rsync curl git unattended-upgrades
sudo dpkg-reconfigure -plow unattended-upgrades     # answer Yes: automatic security updates
timedatectl | grep -E 'System clock synchronized|NTP service'    # both "yes"/"active": token expiry depends on the clock
```

Python is the system one (3.12 on 24.04). `requires-python` in `backend/pyproject.toml` is `>=3.10`, so 3.12 is fine.
`nginx` starts immediately with Ubuntu's default site on port 80; step 7 relies on that for the certificate.

Get the code onto the server, in your home directory (this checkout is only the *source*; deploy.sh copies it to `/opt/kidtube`):

```bash
git clone <your-repository-url> ~/kidtube          # private repo: add a read-only deploy key first
# or, from your workstation, without git on the server:
#   rsync -a --exclude .venv --exclude .git --exclude __pycache__ ./ admin@api.example.com:kidtube/
cd ~/kidtube
```

## 3. Firewall and fail2ban

Make sure the SSH rule exists before the firewall is switched on (the script does that in order). If SSH listens on a
port other than 22, run it as `sudo SSH_PORT=2222 deploy/firewall/ufw.sh`.

```bash
sudo deploy/firewall/ufw.sh
sudo ufw status verbose            # allow: OpenSSH, 80/tcp, 443/tcp (v4 and v6); default: deny incoming

sudo install -m 644 deploy/firewall/fail2ban-sshd.local /etc/fail2ban/jail.d/kidtube-sshd.local
sudo systemctl enable --now fail2ban && sudo systemctl restart fail2ban
sudo fail2ban-client status sshd   # "Currently banned" appears here when someone guesses wrongly
```

Add your own fixed IP to `ignoreip` in that file if you have one. PostgreSQL is never opened in the firewall (step 4
also makes it listen on localhost only).

## 4. PostgreSQL

**Option A: Ubuntu's package** (PostgreSQL 16 on 24.04, security updates through `apt`):

```bash
sudo apt install -y postgresql
```

**Option B: PGDG** (the PostgreSQL project's repository, newer majors; the development suite was run on 18):

```bash
sudo apt install -y postgresql-common
sudo /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh      # adds the repository
sudo apt install -y postgresql-18                                  # or another current major
```

Either way, find the major version and apply the settings. Listening only on localhost and SCRAM passwords:

```bash
pg_lsclusters                                  # e.g. "16  main  5432  online" -> major = 16, port = 5432
PGV=16                                         # <- set to YOUR major version
sudo install -m 644 -o root -g root deploy/postgres/99-kidtube.conf /etc/postgresql/$PGV/main/conf.d/99-kidtube.conf
sudo systemctl restart postgresql
sudo ss -ltnp | grep 5432                      # only 127.0.0.1:5432 and [::1]:5432, never 0.0.0.0 or [::]
```

Create the role and database (no superuser, no CREATEDB) and set its password interactively:

```bash
sudo -u postgres psql -v ON_ERROR_STOP=1 -f deploy/postgres/setup.sql
openssl rand -hex 24                           # copy this: it is the database password (step 5)
sudo -u postgres psql -c '\password kidtube'   # paste it twice
```

Check `pg_hba.conf` uses `scram-sha-256` and nothing weaker (`deploy/postgres/pg_hba.conf.example` shows the shape; the
default of both Ubuntu and PGDG already matches it):

```bash
sudo -u postgres psql -c "select line_number, type, database, user_name, address, auth_method from pg_hba_file_rules"
psql -h 127.0.0.1 -U kidtube kidtube -c 'select current_user'     # asks for the password: proves TCP + scram works
```

If the `auth_method` column shows `trust`, `md5` or `password` on any line, edit
`/etc/postgresql/$PGV/main/pg_hba.conf` to match the example and `sudo systemctl reload postgresql`.

## 5. Environment file and first deploy

Generate the JWT secret (once; see "things that will bite you"):

```bash
python3 -c "import secrets; print(secrets.token_urlsafe(48))"
```

First run of the deploy script creates `/etc/kidtube/kidtube.env` (mode 640, `root:kidtube`) from the example and stops
so you can fill it in:

```bash
sudo deploy/deploy.sh                          # prints "Created /etc/kidtube/kidtube.env ... Edit it now" and exits
sudo nano /etc/kidtube/kidtube.env
#   JWT_SECRET=<the generated secret>
#   DATABASE_URL=postgresql+psycopg://kidtube:<the database password>@127.0.0.1:5432/kidtube     (change 5432 if pg_lsclusters said otherwise)
#   leave APP_ENV=prod, TRUST_PROXY_HEADERS=true, ALLOW_REGISTRATION=true (for now), DEFAULT_TIMEZONE=Asia/Karachi
ls -l /etc/kidtube/kidtube.env                 # -rw-r----- root kidtube
sudo deploy/deploy.sh                          # the real deploy
```

The script stops at the first error, refuses to run while a `CHANGE_ME` placeholder is left, never prints secrets, and
on failure prints how to roll back. It: creates the `kidtube` system user, copies the code to `/opt/kidtube/src`, builds
`/opt/kidtube/venv` with the pinned dependencies and a **non-editable** install, installs the systemd units (service,
backup service and timer), runs `alembic upgrade head`, restarts the service, and checks `/health` on
`127.0.0.1:8000`, that `/children` answers 401 and that `/docs` is off.

```bash
systemctl status kidtube --no-pager
curl -s http://127.0.0.1:8000/health          # {"status":"ok"}
```

## 6. Proxy headers: what the app does with `X-Forwarded-For`

Two things could rewrite the client address: uvicorn's own proxy-header handling and the app's `TRUST_PROXY_HEADERS`.
The service runs uvicorn with **`--no-proxy-headers`** so only the app does it:

* The app needs the real client IP for the login and pairing rate limits. With `TRUST_PROXY_HEADERS=true` it takes the
  **last** entry of `X-Forwarded-For`. nginx **overwrites** that header with the connection's real address
  (`proxy_set_header X-Forwarded-For $remote_addr;`), so the header contains exactly one value and a client cannot
  prepend a fake one.
* If uvicorn also processed the header, the same information would be applied twice by two independent switches, and
  turning one off would silently change what the other sees. One switch is easier to reason about.
* The scheme (`https`) is not needed by the app. The only visible effect would be absolute redirect URLs, which nginx
  fixes with `proxy_redirect`.
* uvicorn listens on `127.0.0.1` only, so nobody can reach it without passing through nginx. That is what makes
  `TRUST_PROXY_HEADERS=true` safe.

## 7. nginx and certbot

DNS must already point `api.example.com` at this server (`dig +short api.example.com`). Ubuntu's default nginx site is
still active on port 80 and serves `/var/www/html`, which is all certbot needs for the first certificate:

```bash
sudo certbot certonly --webroot -w /var/www/html -d api.example.com --agree-tos -m you@example.com
```

Install the site (this substitutes your domain, replacing every `api.example.com`) and take the default site out:

```bash
sed 's/api\.example\.com/YOUR.DOMAIN/g' deploy/nginx/kidtube.conf | sudo tee /etc/nginx/sites-available/kidtube >/dev/null
sudo ln -sf /etc/nginx/sites-available/kidtube /etc/nginx/sites-enabled/kidtube
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

Reload nginx after every renewal, and check that renewal works **now**, not in 60 days:

```bash
sudo install -m 755 deploy/certbot/reload-nginx.sh /etc/letsencrypt/renewal-hooks/deploy/reload-nginx
systemctl list-timers certbot.timer --no-pager      # a next run is scheduled (Ubuntu's apt certbot)
sudo certbot renew --dry-run                        # "Congratulations, all simulated renewals succeeded"
```

The nginx site only forwards the paths the API has (`/health`, `/auth/`, `/children`, `/videos`, `/plan-items`,
`/events`, `/devices`, `/sync/`, `/reports`) and answers 404 to everything else, so `/docs`, `/redoc` and
`/openapi.json` are unreachable even if the app flag were wrong. It also limits `/auth/` (60 requests/minute/IP) and
`/devices/pair` (30/minute/IP), far above what the app's own lockout (5 failures per 10 minutes) allows.

## 8. Create the first parent account, then close registration

`ALLOW_REGISTRATION=true` is only for this step. Type the password at the prompt (it stays out of your shell history;
do not use `"` or `\` in it, it goes into JSON):

```bash
read -rp 'Parent e-mail: ' EMAIL
read -rsp 'Password (min 8 chars): ' PW; echo
curl -sS -X POST https://api.example.com/auth/register -H 'Content-Type: application/json' \
  -d "$(printf '{"email":"%s","password":"%s","family_name":"Our Family","timezone":"Asia/Karachi"}' "$EMAIL" "$PW")"
# -> {"parent_id":"...","family_id":"...","email":"..."}        (HTTP 201)
curl -sS -X POST https://api.example.com/auth/login -H 'Content-Type: application/json' \
  -d "$(printf '{"email":"%s","password":"%s"}' "$EMAIL" "$PW")" | cut -c1-60      # {"access_token":"eyJ...  = it works
unset PW
```

**Now close registration, or anyone who finds the URL can create a family on your server:**

```bash
sudo sed -i 's/^ALLOW_REGISTRATION=.*/ALLOW_REGISTRATION=false/' /etc/kidtube/kidtube.env
sudo systemctl restart kidtube
curl -sS -X POST https://api.example.com/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"x@example.com","password":"12345678"}'          # must say: {"detail":"Registration is disabled"}
```

## 9. Smoke test

From any machine with `curl` (your workstation is best, because it tests the real public path):

```bash
deploy/smoke_test.sh https://api.example.com
```

It prints PASS/FAIL for: `/health` over HTTPS (with a valid certificate), HTTP redirecting to HTTPS, `/docs`, `/redoc`
and `/openapi.json` not exposed, an unauthenticated `/children` being 401, an unknown path being 404, and the
security headers. When everything passes, turn on HSTS (browsers then refuse plain HTTP for a year, so do this last):

```bash
sudo sed -i 's/^    #add_header Strict-Transport-Security/    add_header Strict-Transport-Security/' /etc/nginx/sites-available/kidtube
sudo nginx -t && sudo systemctl reload nginx
deploy/smoke_test.sh https://api.example.com      # now also reports "PASS  HSTS enabled"
```

## 10. Backups and a restore test

`deploy.sh` installed a daily timer (03:15 server time) that runs `pg_dump` in custom format into
`/var/backups/kidtube` (mode 600, kept 14 days, pruned only after a new dump succeeded).

```bash
systemctl list-timers kidtube-backup.timer --no-pager
sudo systemctl start kidtube-backup.service          # run one now
journalctl -u kidtube-backup --no-pager -n 20
sudo ls -l /var/backups/kidtube                      # kidtube-<UTC timestamp>.dump
```

**Test that a dump really restores** (into a scratch database named `kidtube_restore_test`, dropped afterwards; the live
database is not touched). Do this once now and after any big change:

```bash
sudo -u postgres /usr/local/sbin/kidtube-restore-test                    # newest dump
sudo -u postgres /usr/local/sbin/kidtube-restore-test /var/backups/kidtube/kidtube-20260101T031500Z.dump
# ends with: PASS: dump restores cleanly   (it prints the alembic revision and a row count per table)
```

### Restore (disaster recovery)

On the same server, replacing the live database with a dump:

```bash
sudo systemctl stop kidtube
sudo -u postgres psql -c 'DROP DATABASE kidtube WITH (FORCE)'
sudo -u postgres psql -v ON_ERROR_STOP=1 -f deploy/postgres/setup.sql        # recreates the empty database (the role keeps its password)
sudo -u postgres pg_restore --exit-on-error --dbname=kidtube /var/backups/kidtube/kidtube-<timestamp>.dump
sudo systemctl start kidtube && curl -s http://127.0.0.1:8000/health
```

On a **new server**: do steps 1 to 5 first (so the `kidtube` role, its password and `/etc/kidtube/kidtube.env` exist),
copy the dump over, skip the DROP, and run the last three commands. The dump contains the schema revision; run
`sudo deploy/deploy.sh` afterwards if the code is newer than the dump.

### Off-machine copies

A backup on the same disk does not survive losing the VPS. Pull `/var/backups/kidtube` to another machine regularly,
for example from your workstation (needs sudo without a password prompt for this command, or copy with a root-owned
helper of your own):

```bash
mkdir -p ~/kidtube-backups && ssh admin@api.example.com 'sudo tar -C /var/backups/kidtube -cf - .' | tar -xf - -C ~/kidtube-backups
```

The dumps hold parent e-mail addresses, password hashes and children's names: keep the copies private.

## 11. Updating

```bash
cd ~/kidtube && git pull
sudo deploy/deploy.sh                 # or: sudo deploy/deploy.sh --pull   (does the git pull as you)
deploy/smoke_test.sh https://api.example.com
```

`deploy.sh` takes a database backup first, keeps the previous code in `/opt/kidtube/src.prev`, applies migrations,
restarts, and checks health. `--skip-backup` skips the backup. If anything fails it prints the exact rollback commands
(previous code, `alembic downgrade <revision>`, or restore the pre-deploy dump). It is safe to run again after fixing the cause.

Dependency versions are pinned in `deploy/constraints.txt`. They only change when you change that file (see its header);
security fixes in Python packages are therefore a deliberate update, not automatic.

If the nginx site changed, `sudo nginx -t && sudo systemctl reload nginx` after copying it as in step 7 (deploy.sh does
not touch nginx).

## 12. Logs

```bash
journalctl -u kidtube -f                       # the API (follow); add -n 200 --no-pager for the last lines
journalctl -u kidtube --since "1 hour ago" -p warning
sudo tail -f /var/log/nginx/access.log /var/log/nginx/error.log     # who called what (429 = the nginx rate limit)
journalctl -u kidtube-backup -n 30 --no-pager  # the nightly backup
sudo fail2ban-client status sshd               # banned SSH addresses
systemctl status kidtube --no-pager            # restarts, memory
```

uvicorn's access log is off (nginx has it, with real client addresses). Logs can contain request paths and ids, never
passwords or tokens (they travel in headers and bodies).

## 13. Optional: run the PostgreSQL test suite on the VPS

Runs all tests on the **server's** PostgreSQL version and Python, against a throwaway database. The test fixtures wipe
the database they are pointed at, and pytest refuses to start unless its name ends in `_test`. Never use the production database.

```bash
sudo -u postgres psql -c "CREATE ROLE kidtube_test LOGIN CREATEDB PASSWORD 'test-only-pw'"     # CREATEDB: one migration test makes a scratch database
sudo -u postgres psql -c "CREATE DATABASE kidtube_test OWNER kidtube_test"

cd ~/kidtube/backend
python3 -m venv /tmp/kidtube-test-venv
/tmp/kidtube-test-venv/bin/pip install -q -c ../deploy/constraints.txt '.[dev]'
TEST_DATABASE_URL=postgresql+psycopg://kidtube_test:test-only-pw@localhost:5432/kidtube_test \
  /tmp/kidtube-test-venv/bin/pytest -q                 # about 3 minutes; expect "1531 passed"

sudo -u postgres psql -c "DROP DATABASE IF EXISTS kidtube_test" -c "DROP DATABASE IF EXISTS kidtube_mig_test" -c "DROP ROLE kidtube_test"
rm -rf /tmp/kidtube-test-venv
```

(Use port 5433 or whatever `pg_lsclusters` shows if it is not 5432.)

---

## Things that will bite you

* **One worker only.** The login and pairing rate limiters live in the memory of a single process. With two workers
  (or two instances) each counts separately: attackers get N times the guesses and lockouts become random. The unit file
  says `--workers 1`; do not "scale up" by changing it. To scale you first need a shared limiter store.
* **`TRUST_PROXY_HEADERS` must match the proxy setup.** Behind this nginx it must be `true`. If it were `false`, every
  request would look like it comes from 127.0.0.1, so five wrong passwords from anyone would lock out the whole family.
  If you ever let clients reach uvicorn directly (don't: it binds to 127.0.0.1), it must be `false`, because clients
  could then forge the header.
* **Keep `JWT_SECRET` stable.** Changing it invalidates every access and refresh token: all parents are logged out and
  must sign in again. Kid devices keep working (their own tokens are separate). Back it up with the env file; losing it
  is the same as changing it.
* **Close registration** after creating the first parent (`ALLOW_REGISTRATION=false`, step 8).
* **Certificate renewal** needs port 80 open, `/var/www/html` present and DNS still pointing here. Check
  `sudo certbot renew --dry-run` after any change to nginx, the firewall or DNS, and look at
  `systemctl list-timers certbot.timer` once in a while. Let's Encrypt certificates last 90 days. Expiry makes the
  Android app fail with certificate errors.
* **New API routes need an nginx line.** The site only forwards a fixed list of path prefixes (step 7). If a future
  version adds a top-level route (say `/notifications`), add it to the regex `location` in
  `deploy/nginx/kidtube.conf`, or nginx answers 404 for it. Run `deploy/smoke_test.sh` after updates.
* **PostgreSQL major-version upgrades are not automatic.** Ubuntu security updates stay on the same major (16.x).
  A new major arrives through `do-release-upgrade` or by installing a new PGDG package, and it creates a *second*
  cluster (often on port 5433) next to the old one. Take a backup first (`sudo systemctl start kidtube-backup.service`),
  use `pg_upgradecluster` (or dump and restore into the new cluster), then update `DATABASE_URL` (port) in the env file,
  and the `conf.d` path (`/etc/postgresql/<major>/main/conf.d/`). Do not delete the old cluster until the
  restore test and the smoke test pass. A dump from major N can be restored into N or newer, not older.
* **A new Ubuntu release or a new system Python breaks the virtualenv.** Recreate it: `sudo rm -rf /opt/kidtube/venv &&
  sudo deploy/deploy.sh`. Dependency pins were tested on Python 3.12.
* **Backups on the same disk are not backups.** Copy them off the machine (step 10) and run the restore test now and then.
* **The firewall can lock you out.** Keep a working SSH session open when changing `ufw` or `sshd` settings, and test
  with a second session before closing the first.
* **Disk and memory.** The service is capped at 512 MB (`MemoryMax` in the unit); if `systemctl status kidtube` shows
  it was killed ("oom-kill"), raise it. Dumps pile up for 14 days; `df -h /var/backups` should stay far from full.
* **Backup time is server local time**; `timedatectl` shows the zone. Day boundaries inside the app always use the
  family timezone (`Asia/Karachi`), independent of the server.

## What was and was not verified before this was written

Verified on a development machine, **not on a real VPS**:

* Python 3.12.13 with the pinned dependencies (`deploy/constraints.txt`): the whole test suite passes on SQLite (1530
  passed, 1 skipped) and on PostgreSQL 18 (1531 passed).
* Installing the project non-editable into a clean 3.12 virtualenv, running `alembic upgrade head` from a copy of
  `backend/`, and starting uvicorn with exactly the flags of the unit file (`--workers 1 --no-proxy-headers ...`) with
  `APP_ENV=prod`: `/health` 200; `/docs`, `/redoc`, `/openapi.json` 404; `/children` 401; no `Server` header.
* The login rate limit keys on the last `X-Forwarded-For` entry (a prepended fake address changes nothing).
* `shellcheck` 0.11.0 is clean on every script; `systemd-analyze verify` found no syntax problem in the three units
  (it only complains that the installed paths do not exist on a development machine).
* `kidtube.env.example` parses the way systemd reads it and passes the app's settings validation.

**Not run**: `nginx -t` on `deploy/nginx/kidtube.conf` (nginx is not installed on the development machine and could not
be built there). The config targets nginx 1.24 (Ubuntu 24.04). Step 7 runs `sudo nginx -t` before reloading, so a mistake
shows up there. Also not run: `deploy.sh`, `ufw.sh`, the backup and restore scripts and `smoke_test.sh` against real
systems, certbot, fail2ban, and PostgreSQL 16 (the 24.04 package). The first real deploy is the test of those.
