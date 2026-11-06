# KidTube: Product and Technical Spec

Private family app for curated kids' video viewing. Not for Play Store; distributed as a sideloaded APK.

- **Client:** Android, Kotlin + Jetpack Compose (single app, Parent mode + Kid mode)
- **Backend:** Python FastAPI on a VPS (Postgres, SQLAlchemy 2, Alembic)
- **Core idea:** Parents share video links into the app (from YouTube, or WhatsApp text). The app saves them, even offline. Parents arrange videos into a flexible per-child plan. Kids open the app, pick their profile, and see only what the plan allows. Parents see watch time and progress.

---

## 1. Decisions (confirmed)

| Topic | Decision |
|---|---|
| Distribution | Family/personal circle only. Sideload APK. No Play Store, so no Families/COPPA program work. |
| Devices | Parent and kid can share one phone, or kids can use a separate device. Same APK, role chosen at setup. |
| Kids per device | Two or more kids can share one device. Each kid has a separate profile, plan, limits and history. |
| Kid login | None. Kid mode needs no account. |
| View counting | A view counts at **>= 90% watched** (or video end). Parent can override per item. |
| Time tracking | Track **watch time** (player actually playing) and **app time** (app in foreground). |
| Plan style | **Rolling queue**, not fixed calendar dates. Missed days shift the plan, nothing expires. |
| Sharing | Works offline and without login. Requires the parent PIN. Links sync when the network returns. |
| Platforms | YouTube is the primary platform (official embed). Facebook is best-effort. |

## 2. Goals and non-goals

**Goals**
- Fast capture: share from YouTube, or a WhatsApp message containing a link, in two taps plus PIN.
- Parents plan what each child watches, in what order, how many times, and how often.
- Kids only see what is currently allowed. Required videos come first.
- Flexible plans that absorb missed days.
- Parents see per-child watch time, app time and completion.

**Non-goals (for now)**
- Public release, ads, payments, social features.
- Downloading, caching, or re-hosting video files.
- Search or browsing of YouTube from the kid side.
- Precise watch tracking for Facebook videos.

## 3. Roles and modes

- **Parent:** has an account (email + password, JWT). Manages videos, children, plans, devices, reports. Sets a local **PIN**.
- **Child:** profile only (name, avatar, optional daily app-time limit). No credentials.
- **Device role:**
  - *Parent device:* parent mode by default. Can switch to kid mode.
  - *Shared phone:* same as above. Kid mode is locked down; exit needs PIN.
  - *Kid device:* paired by the parent via a 6-digit code or QR. Holds a device-scoped token that can only read the plan and write events.

### Kid mode behavior
1. "Who's watching?" screen with avatar tiles, one per child.
2. Selecting a child opens that child's feed only.
3. No external links, no search, no navigation outside the embed. Screen pinning (`startLockTask()`) on shared phones.
4. Exiting kid mode requires the parent PIN.

## 4. Architecture

```
Android app (Compose)                          FastAPI backend (VPS)
 ├─ Share target activity (ACTION_SEND)         ├─ Auth (JWT, device tokens)
 ├─ Room DB (videos, plans, events, outbox)     ├─ Videos + oEmbed verification
 ├─ WorkManager sync  ───── HTTPS ─────────────►├─ Plans + feed/projection engine
 ├─ Plan engine copy (offline enforcement)      ├─ Sync push/pull
 └─ YouTube IFrame player (WebView)             └─ Reports
                                                 Postgres (SQLite in dev), Nginx + Let's Encrypt
```

- The **server is the source of truth**. The device caches the plan and enforces limits locally so it works offline.
- The plan engine is a pure module (no DB access). The Python version is canonical, with a Kotlin port that must pass the same test vectors.

## 5. Offline-first link sharing

Share target is a small translucent activity, not the main app.

1. Parent shares from YouTube, or shares a WhatsApp message (plain text).
2. The app extracts URLs from the text with a regex. If several links are found, show a checklist.
3. Parent PIN dialog (PIN hash stored locally via Keystore + EncryptedSharedPreferences, so it works offline).
4. **Local verification** is a pattern check only: recognised YouTube/Facebook URL, video ID extracted, duplicate detected.
5. Parent picks the quick schedule: which child(ren), required or optional, view target, daily cap (e.g. once a day, twice a day).
6. Link is saved to Room with status `pending_sync`.
7. WorkManager syncs when the network returns. The **server does real verification**: oEmbed title, thumbnail, embeddable or not. The video becomes `verified` or `unavailable`, and the parent sees a badge.

If links are saved before the first login, they attach to the family after the parent logs in.

## 6. Data model

All primary keys are UUIDs. Events carry a client-generated UUID for idempotency.

- **Family**: id, name, timezone (default `Asia/Karachi`)
- **Parent**: id, family_id, email, password_hash
- **Child**: id, family_id, name, avatar, daily_app_minutes (nullable)
- **Device**: id, family_id, name, role (`parent` | `kid`), token_hash, paired_at, revoked_at
- **PairingCode**: id, family_id, created_by (parent, nullable), code_hash (keyed hash of the 6-digit code; the code itself is never stored), expires_at (10 min), used_at (single use), created_at
- **Video**: id, family_id, platform (`youtube` | `facebook`), platform_video_id, url, title, thumbnail_url, duration_seconds, status (`pending` | `verified` | `unavailable`), added_by, added_at
- **Plan**: id, child_id, pace_type (`videos_per_day` | `minutes_per_day`), pace_value, active_weekdays (bitmask or list), max_extra_per_day (catch-up cap, default 1), start_date, end_date (nullable), version
- **PlanItem**: id, plan_id, video_id, position, required (bool), view_target (nullable = unlimited), daily_cap (nullable), watch_threshold (default 0.9), anchor_date (nullable), on_miss (`shift` | `skip` | `drop`, default `shift`), status (`active` | `completed` | `dropped`), added_on (date: the child's local date when the item joined the plan, in the family timezone; feeds the catch-up window). Item `status` is only changed by a parent; view events never mutate it (completion is derived by the engine from the event log). Positions are kept dense (0..n-1) by the API. A video can be an `active` item of a given plan only once.
- **Event** (append-only log):
  - id (client UUID), child_id, device_id, type, occurred_at (UTC), local_date (child's local date), payload (JSON)
  - Types: `view_complete` (plan_item_id, watched_pct 0..1), `watch_progress` (video_id, seconds >= 0), `app_session` (start, end: ISO-8601 with offset, end >= start, at most 24 h), `playback_error` (video_id, code), `time_extension` (seconds > 0; **parent-only**: grants extra app time for the event's `local_date`, so a kid device can never extend its own screen time)
  - `plan_item_id` is also stored as a nullable column on `view_complete` rows (denormalised from the payload) so views can be aggregated in plain SQL, portable across SQLite and Postgres. Indexes: `(child_id, local_date)`, `(child_id, type, occurred_at)`, `(child_id, type, plan_item_id, local_date)` (views per item per day), `(child_id, type, local_date)` (per-day second sums).
  - Event `type` is stored as a plain string without a database constraint, so adding a type needs no migration.

A child's "views done" and "today's count" are always **computed from events**, never stored as counters.

## 7. Plan engine

A plan is an **ordered queue with a pace**, not videos pinned to dates.

- Items stay in the queue until completed. Unwatched items stay at the front, so days behind shift the schedule forward.
- Days not in `active_weekdays` are days off, not misses.
- **Catch-up cap:** after missed days, a child gets at most `pace + max_extra_per_day` per day, so a backlog is absorbed gradually.
- **Anchored items** (tied to a date): `on_miss` decides `shift` (default, moves to the next slot), `skip`, or `drop`.
- Weekly and monthly calendar views are **projections**: the engine simulates the queue forward at the current pace and shows when items/plan will finish. The projection is recomputed after every completion.

### Feed algorithm (for one child, for today)

1. If today is not an active weekday, return "no plan today".
2. Compute today's quota: `pace + allowed catch-up extra`, and the remaining time budget if the pace is minutes-based.
3. Walk the queue in order, keeping items that are `active` and whose `daily_cap` is not reached today and whose `view_target` is not reached overall.
4. If any **required** item is incomplete: return only the first incomplete required item, **locked**. When it completes, return the next required item.
5. When no required items remain: return optional items up to the remaining quota/time.
6. When quota or time is used up: return "done for today".

Also apply the child's optional **daily app-time limit**: when reached, show a "time's up" screen. A parent can extend it with the PIN (e.g. +15 min).

### View counting
- A view is recorded at `watched_pct >= watch_threshold` (default 0.9) or when the video ends.
- Opening a video and leaving does not use up a view.
- Parent can override the threshold per item.

### Engine semantics (canonical)

Implemented in `backend/app/engine` (pure: frozen dataclasses, no DB/ORM/clock/timezones; the caller passes the child's local `today`). Weekday: Monday=0..Sunday=6, mask bit `1 << weekday`. Ordering is always `(anchored-today first, position, id)`. Entry points: `build_feed(plan, items, views, seconds, app_seconds, limits, today, default_duration_seconds=300)` and `project(plan, items, views, seconds, today, days, default_duration_seconds=300)`.

1. **Views.** `views_total` / `views_today` per item count `ViewRecord`s (one per `view_complete` event). Records for unknown item ids are ignored, and so are records dated after `today` (clock skew must not complete items early). The client decides when a view happened; the 90% rule is not re-checked here.
2. **Completion.** Finite target `N = view_target`; a REQUIRED item with no `view_target` has `N = 1`. An OPTIONAL item with no `view_target` is unlimited and never completes. Complete when `views_total >= N`. Items with status `completed`/`dropped` never appear.
3. **Active day.** `start_date <= today <= end_date` (if set) and the weekday bit is set. Days off are not misses.
4. **Quota.** Videos mode: `quota_today = pace_value + extra`, `extra = min(backlog, max_extra_per_day)`. `backlog = max(0, expected - done)` over the 14 calendar days before today, clipped to start at `max(start_date, earliest added_on of non-dropped items)`; `expected = pace_value * (active days in window)`, `done` = view records in the window. Minutes mode: the same in seconds (`pace_value*60` per active day, `done` = watch-seconds records), and `max_extra_per_day` is interpreted as **minutes** (`extra_seconds = min(backlog, max_extra_per_day*60)`). `quota_today` and `backlog` are in videos (videos mode) or seconds (minutes mode). The model default `max_extra_per_day = 1` is therefore only one minute of catch-up in minutes mode; the API layer should default it to `pace_value` when creating a minutes plan. Remaining today: videos mode `remaining_videos = max(0, quota - views today across all items)`; minutes mode `remaining_seconds = max(0, quota_seconds - seconds watched today)` (views do not consume a minutes quota; a video may be started while `remaining_seconds > 0` and may overrun).
5. **App-time limit.** If `daily_app_minutes` is set and today's app seconds `>= daily_app_minutes*60 + app_time_extra_seconds`, status is `time_up`.
6. **Eligible today.** Status `active`, not complete, `views_today < daily_cap` (if set), and anchors: `anchor_date > today` is not yet eligible; `== today` is eligible and sorted before non-anchored items; `< today` and incomplete applies `on_miss`: `shift` = ordinary queue item, `skip` = excluded and listed in `skipped_item_ids`, `drop` = excluded and listed in `dropped_item_ids`.
7. **Required-first.** If remaining quota > 0 and any eligible item is required, the feed is ONLY the first such item with `locked=true`. Required items consume quota (they do not bypass the pace). A required item whose `daily_cap` is reached today is not eligible and does not block the others.
8. **Optional items.** Eligible non-required items, each at most once. Videos mode: at most `remaining_videos` items. Minutes mode: keep adding while the accumulated duration before adding `< remaining_seconds` (duration `null` = `default_duration_seconds`).
9. **Status precedence.** `outside_dates`, `day_off`, `empty` (no non-dropped items), `all_done` (no active item is still pending: every finite item complete, no unlimited items, nothing anchored in the future), `time_up`, `done_today` (quota exhausted or nothing eligible), `ok`. For `outside_dates`/`day_off`, `quota_today` and `backlog` are 0.
10. **FeedResult.** `status, items[{item_id, video_id, locked, required}], quota_today, views_today, remaining_videos|null, remaining_seconds|null, backlog, skipped_item_ids, dropped_item_ids`.

**Projection** simulates day offsets 0..90 assuming every offered view is completed. Each simulated day runs a loop that mirrors the real app, where the feed is recomputed after every completed view: compute the feed with the views simulated so far that day, take the offered items (a locked feed is one item, an optional feed can be several), mark them all complete and append them to the day's views in order, then repeat until the feed offers nothing (quota or time budget used up, nothing eligible). A hard cap of 500 steps per day guards against runaway loops. So three required items with pace 3 finish on day 0; a required item is followed by optionals in the same day; a required item with `daily_cap = 1` is offered once per day and the plan spreads across days; and in minutes mode a required video is followed by optionals until the remaining seconds run out (a long required video can use the whole day). Within one day an uncapped item with `view_target > 1` can be offered in several steps, and each step re-checks `daily_cap` against the views simulated so far that day.

- Offset 0: the FIRST step is exactly `build_feed` (finite items only), so day-0 views start with the feed's items; catch-up quota applies to the whole day-0 loop, and watch-seconds already recorded today count in minutes mode. The app-time limit is ignored.
- Later days: base pace with no catch-up, starting from zero views and zero seconds that day.
- Unlimited optional items are excluded from every day, including day 0, and listed in `excluded_unlimited_item_ids`.
- `days` only truncates the output (0..91 entries). `finish_date` is the date of the last scheduled view once every finite item is complete within the horizon, else `null` (also `null` when nothing is scheduled). `remaining_finite_views` is the finite views still owed as of today; `unscheduled_item_ids` are finite items still incomplete at the end of the horizon.

## 8. Tracking

- **Watch time:** with the YouTube IFrame Player API (via the `android-youtube-player` library), accumulate seconds only while the player state is *playing*. Handle seeking (don't count skipped time as watched).
- **App time:** foreground time per child session via `ProcessLifecycleOwner`, counted only while the screen is on. Write a **heartbeat every 30 s** to Room, so a killed process loses at most 30 s. Synced as `app_session` events.
- **Facebook:** no player API, so precise tracking is impossible. Count opens only, or defer Facebook support.
- **Playback errors:** YouTube embed errors (e.g. 101/150 embedding disabled, video removed) are logged as `playback_error`. Kid sees a friendly message and skips to the next video. Parent sees the video flagged.
- Anti-cheat (low priority): detect device clock changes using `elapsedRealtime` plus last known server time.

## 9. Sync

KidTube is a private family app with tens of videos, so sync is a **snapshot** design: no cursors, no tombstones.

- Local writes go to Room and to an **outbox** (new links, events).
- **Pull:** `GET /sync/pull` (parent or kid device) returns the whole family snapshot in one response: `server_time` (UTC), family `timezone`, `children` (each with its `plan`: config plus ordered items, or `null` when it has none, and its `history`, see below) and `videos`. Shapes are exactly those of `GET /children`, `GET /children/{id}/plan` and `GET /videos`. The client **replaces** its cache with the snapshot, so deleted videos and plan items vanish without tombstones.
- `snapshot_version` is a hash of the content (everything except `server_time`) and is sent as the `ETag`. With `If-None-Match` matching it (weak validators, lists and `*` are understood) the server answers **304 with no body**. Any child, video, plan or plan-item change changes it, and so does any new event that appears in a `history` (below); a no-op edit, a duplicate event or a `playback_error` does not. The cost is constant: eight queries, however large the family or the event log is.
- **History (per child), so an offline device can run the plan engine.** Each child carries `history`, built by the same loader as `GET /children/{id}/feed` (`load_histories` in `app/services/engine_input.py`; one function, three grouped queries for all children):
  - `views`: `[{plan_item_id, local_date, count}]`, **all-time** up to the family-local today, grouped, and only for items that are still in that child's plan (views of deleted items, or events pointing at another child's item, are not included). These are the engine's `views` (one record per `count`).
  - `watch_seconds`: `[{local_date, seconds}]`, sum of `watch_progress` per day (engine `seconds`).
  - `app_seconds`: `[{local_date, seconds}]`, sum of `app_session` durations per day (engine `app_seconds`).
  - `time_extension_seconds`: `[{local_date, seconds}]`, sum of parent-granted `time_extension` per day (engine `limits.app_time_extra_seconds` for today).
  - The three seconds lists cover the last **30 family-local days** (today and the 29 before it; the engine itself only needs 14), are whole seconds, sorted by date, and omit days without events. Everything is grouped by `events.local_date`, never by UTC timestamps. Events dated after the server's today are not included (the engine ignores them as well).
  - **Merge rule:** the server only knows events it has received. A client builds its engine input from the snapshot history **plus its own events that the server has not yet counted**: events still in the outbox, and events that a push acknowledged (`accepted` or `duplicate`) but that were acknowledged after the snapshot was fetched. Drop an acknowledged event from the local merge only when a snapshot fetched after the acknowledgement has been stored, in the same transaction that replaces the cache (so nothing is counted twice, and nothing is missed in between). Permanently rejected events are dropped at once. The natural order is push, then pull.
- **Push:** `POST /sync/push` is what the app sends when the network returns. Body `{videos: [{url}], events: [...]}` (at most 50 videos and 200 events, more is a 422). It calls the same services as `POST /videos` and `POST /events` and returns one result per item; one bad item never fails the batch:
  - `videos[]`: `{index, status: created | existing | rejected, video_id, video_status, reason, retryable}`.
  - `events[]`: `{index, id, status: accepted | duplicate | rejected, reason, retryable}`.
  - Videos need a **parent** token. A kid device that sends videos gets each one `rejected` with reason `forbidden`; its events are still processed.
  - `retryable` tells a permanent rejection from a retryable situation. It is `false` for every rejection (invalid URL, `forbidden`, unknown child, bad payload): resending the same item cannot succeed. It is `true` only on a `created` video whose YouTube verification could not complete (oEmbed network trouble). That video **does exist** (status `pending`), so it is not an error and must not be resent; call `POST /videos/{id}/reverify` later.
- Resending an identical batch changes nothing: videos come back `existing` (no outbound request), events `duplicate`.
- **Conflict rules:**
  - Events are append-only (client UUID is the idempotency key), so no conflicts.
  - Plan edits are server-side and versioned (`plan.version`, bumped on every actual change); kid devices cannot edit plans, so a parent edit always wins and devices simply take the next snapshot.
  - Daily limits are recomputed from the merged event log, so two devices cannot both grant "one more view" after syncing.
- Every event stores the child's `local_date` (family timezone), so day boundaries follow the child's day, not UTC. The server accepts a `local_date` from `today - EVENT_MAX_AGE_DAYS` (default 30, so a device can stay offline for a family trip) to `today + 1`.
- Deleting a video also removes its plan items; the affected plans are renumbered (positions stay dense) and their `version` is bumped.

## 10. Auth and devices

- Parent: `POST /auth/register`, `POST /auth/login` returns a JWT (short-lived access token + refresh token).
- Device pairing: `POST /devices/pair-code` (parent, returns a 6-digit code valid 10 minutes) and `POST /devices/pair` (code returns a device-scoped token).
- Device tokens may only: read the family's plan, videos and children; write events. Nothing else.
- `POST /devices/{id}/revoke` cuts off a lost device.
- Parent PIN is local to the device (hashed), separate from the account password.

## 11. API summary

```
POST   /auth/register            POST /auth/login           POST /auth/refresh
POST   /children                 GET  /children             PATCH /children/{id}
POST   /videos                   GET  /videos               DELETE /videos/{id}
POST   /videos/{id}/reverify
GET    /children/{id}/plan       PUT  /children/{id}/plan
POST   /children/{id}/plan/items PATCH/DELETE /plan-items/{id}
POST   /children/{id}/plan/reorder   POST /events
GET    /children/{id}/feed       GET  /children/{id}/projection?from=&days=
POST   /devices/pair-code        POST /devices/pair         POST /devices/{id}/revoke
GET    /devices
POST   /sync/push                GET  /sync/pull
GET    /reports/children/{id}?range=day|week|month&date=
GET    /health
```

`POST /videos` accepts a URL: validates the pattern, normalises to a platform video ID, dedupes per family, fetches YouTube oEmbed (`youtube.com/oembed?url=...&format=json`), and marks the status.

Details: the body is a single bare URL (surrounding text is rejected). Returns 201 for a new video, or 200 with the existing one when it is a duplicate (so offline sync retries are safe). YouTube is verified via oEmbed (200 `verified`; 401/403/404 `unavailable`; timeout/5xx leave it `pending`). Facebook is not verified and stays `pending` with title "Facebook video". The oEmbed request is built only from the parsed 11-character video ID against the fixed host `www.youtube.com` (no redirects, 5 s timeout, size cap); a user-supplied URL is never fetched. `POST /videos/{id}/reverify` retries verification and never downgrades a status on a transient failure. Facebook short links are stored with a prefixed `platform_video_id` (`fb.watch:<code>`, `share:<code>`) so their namespaces cannot collide with numeric video IDs.

### Plans, events, feed and projection (implemented)

All routes are scoped by `family_id`: another family's child, plan, item or video is a 404. Device tokens can read (`GET` plan, feed, projection, children, videos) and write events; everything else is parent-only (401 for a device token).

- `GET /children/{id}/plan` (parent or device): config plus items in order, each with video title, thumbnail, platform, status. 404 when the child has no plan; GET never creates one.
- `PUT /children/{id}/plan` (parent): creates (201) or updates (200). `pace_value >= 1`, `active_weekdays` 1..127, `end_date >= start_date`. If `max_extra_per_day` is omitted it defaults to 1 in videos mode and to `pace_value` in minutes mode (where it counts minutes). `version` is bumped on every actual change (a PUT that changes nothing leaves it alone).
- `POST /children/{id}/plan/items`: `{video_id, required, view_target, daily_cap, anchor_date, on_miss, watch_threshold, position?}`. The video must belong to the family (404) and must not be `unavailable` (422); the same video twice as an active item is 409. `position` is a zero-based index (omitted = append); `added_on` is the family-local date. `PATCH /plan-items/{id}` (including `status`, set by a parent only), `DELETE /plan-items/{id}` and `POST /children/{id}/plan/reorder {item_ids}` (must list every item exactly once, else 422) each bump `version` when something changed.
- `POST /events` (parent or device): up to 200 events per request (more is a 422). Each event gets its own result `{index, id, status: accepted | duplicate | rejected, reason}`; one bad event never fails the batch and resending a batch is harmless (the client UUID is the idempotency key, backed by the primary key). Validation: the child belongs to the caller's family; known type; per-type payload shape (above); `occurred_at` is timezone-aware; `local_date` is within [today - `EVENT_MAX_AGE_DAYS`, today + 1] in the family timezone (setting, default 30); `time_extension` is rejected for device tokens. A device may write events for any child of its family (shared phones). Unknown payload keys are dropped.
- `GET /children/{id}/feed` (parent or device): local "today" is the injected clock converted to the family timezone. The loader (`app/services/engine_input.py`) builds the engine input from the plan and the child's history (`load_histories`, also used by the sync snapshot: views aggregated in one grouped query, watch seconds (the engine uses the last 14 days), today's app-session seconds), and `Child.daily_app_minutes` plus today's `time_extension` seconds; it then calls the engine's JSON entry point. Active items whose video is `unavailable` are not passed to the engine and are listed in `unavailable_item_ids`. Returned items carry title, thumbnail, platform, platform_video_id and duration. **Units: `quota_today`, `backlog` are videos in `videos_per_day` mode and seconds in `minutes_per_day` mode** (the response includes `pace_type`). The parent-only query parameter `?date=YYYY-MM-DD` previews another local day; a device using it gets 403.
- `GET /children/{id}/projection?days=30` (parent or device; 1..91): same loading, runs `project()`; views are enriched with title and thumbnail.

### Sync and reports (implemented)

- `GET /sync/pull` and `POST /sync/push`: see section 9. Pull answers `ETag` / `Cache-Control: private, no-cache` and 304 on `If-None-Match`.
- `GET /reports/children/{id}?range=day|week|month&date=YYYY-MM-DD`: **parent only** (a device token is 401; another family's child is 404). `range` is required; `date` defaults to the family-local today. Details in section 14.

## 12. Android app

### Screens
- **Setup:** choose device role; parent login or pairing code; create PIN.
- **Share target:** link checklist, PIN, child + schedule quick form.
- **Parent mode:** library (videos with status badges), children manager, per-child plan editor (drag to reorder, required toggle, view target, daily cap), weekly/monthly projection view, reports, devices, settings.
- **Kid mode:** profile picker, feed grid (thumbnails), player, "done for today" and "time's up" screens.

### Modules (suggested)
`data` (Room, Retrofit, repositories), `sync` (WorkManager, outbox), `engine` (Kotlin port of plan engine), `player` (IFrame wrapper + tracking), `parent` UI, `kid` UI, `core` (PIN, security, time).

### Playback rules
- YouTube only through the official IFrame embed in a WebView (or `android-youtube-player`). Never download or re-stream.
- Set `rel=0`, `fs=0`, `modestbranding=1`. Block all WebView navigation away from the player.
- No external links, no related videos UI in kid mode.
- Store only URLs and metadata. Never host video files.

## 13. Security and privacy

- HTTPS only (Nginx + Let's Encrypt). Passwords hashed (argon2/bcrypt).
- oEmbed fetching is protected against SSRF: host allowlist, timeouts, no cross-host redirects, size limits.
- Only recognised URL patterns are accepted, so the backend is not an open "save any URL" endpoint.
- Device tokens are scoped and revocable. Kid devices cannot reach parent endpoints.
- Collect minimal child data (name, avatar, usage). No third-party analytics SDKs.
- The interactive API docs and schema (`/docs`, `/redoc`, `/openapi.json`) are served only when `APP_ENV=dev` (or `ENABLE_DOCS=true`); nginx also forwards only the known API paths.
- Production runs on one VPS without Docker: nginx, one uvicorn worker (the rate limiter is in memory), local PostgreSQL, systemd, nightly `pg_dump`. See `DEPLOY.md` and `deploy/`.

## 14. Reports

`GET /reports/children/{id}?range=day|week|month&date=YYYY-MM-DD` (parent only). `day` is that date, `week` is Monday..Sunday containing it, `month` is the calendar month containing it. Everything is grouped by `events.local_date` (the child's day), never by UTC timestamps.

Response:
- `start`, `end` and `days[]`: **every** date in the range (zeros included) with `watch_seconds` (sum of `watch_progress`), `app_seconds` (sum of `app_session` durations), `views_completed` (count of `view_complete`) and `browsing_seconds = max(0, app_seconds - watch_seconds)`. Seconds are rounded to whole seconds once per day.
- `totals`: the same four numbers for the range. Total `browsing_seconds` is `max(0, total app - total watch)`; it is not the sum of the daily values (a day where watching exceeds app time contributes 0 per day, but offsets other days in the total).
- `top_videos`: up to 10 by views in the range (then by watch seconds): `video_id, title, thumbnail_url, views, watch_seconds`. Views are attributed to a video through the plan item, so views of a since-deleted plan item still count in `days` and `totals` but not here.
- `per_video`: every non-dropped plan item with `views_total` (all-time, up to today) against `view_target`, `views_in_range`, `required` and `completed` (the engine's completion rule: status `completed`, or finite target reached; an optional item without a target never completes).
- `playback_errors`: `{video_id, title, code, count}` in the range (a code stored as `150` and as `"150"` is one code, returned as a string).
- `planned_vs_watched`: expected vs actual over the range **clipped to the plan's start/end dates and to today** (today counts, future days do not). Videos mode: `expected = pace_value * active days`, `actual` = views, `unit: videos`. Minutes mode: `expected = pace_value * 60 * active days`, `actual` = watch seconds, `unit: seconds`. `actual` is summed over the same clipped window. `null` when the child has no plan. Catch-up quota is not part of "expected".
- `plan_progress`: `finish_date` and `remaining_finite_views` from the engine's `project()` as of today (same loader as the projection endpoint, whatever the range). `null` when there is no plan.

Implementation notes: group-by queries over `(local_date, plan_item_id)`, `(local_date, video_id)` and `(video_id, code)`; the JSON payload keys are only grouped on, never filtered on. App-session durations are the exception: end minus start of two ISO strings has no portable SQL form, so that one query returns the range's `app_session` payloads and sums them in Python. The number of queries is constant (it does not grow with days, videos or events).

## 15. Backend project layout

```
backend/
  app/
    api/        routers
    core/       config, security, deps
    models/     SQLAlchemy models
    schemas/    pydantic v2 schemas
    services/   oembed, sync, reports
    engine/     pure plan engine (feed, projection) + tests
  alembic/
  tests/
  pyproject.toml, Dockerfile, .env.example
```

## 16. Testing

- Engine: pytest cases for missed days shifting the queue, weekends off, catch-up cap, required-first locking, daily_cap, view_target, 90% completion, anchored items (shift/skip/drop), time budget, and two children with separate plans.
- Shared **test vectors** (JSON) used by both Python and Kotlin engine tests, so the two stay identical. Files live in `backend/tests/engine_vectors/feed/*.json` and `projection/*.json`, each `{"name", "input", "expected"}`. Feed `input`: `{plan, items, views, seconds, app_seconds, limits, today, default_duration_seconds?}`, `expected`: a `FeedResult` dict. Projection `input`: `{plan, items, views, seconds, today, days, default_duration_seconds?}`, `expected`: a `Projection` dict. Dates are `YYYY-MM-DD` strings; expected values are written by hand, not generated from engine output. The Python runner is `app.engine.run_feed` / `run_projection`; the Kotlin port must reproduce every `expected` exactly.
- Sync: duplicate pushes, out-of-order events, offline then reconnect.
- Auth: device token scope enforcement.
- oEmbed: mocked HTTP, SSRF guard cases.

## 17. Milestones

1. **Backend MVP:** scaffold, models, auth + device pairing, videos + oEmbed.
2. **Plan engine:** feed + projection with full tests; feed/projection endpoints.
3. **Sync + reports + deployment** (Docker, Nginx, HTTPS, DEPLOY.md).
4. **Android MVP:** setup, PIN, share target, Room outbox, kid profile picker, grid, YouTube player, simple feed from server.
5. **Android plans:** plan editor, Kotlin engine port with offline enforcement, projections.
6. **Tracking:** watch time, app time heartbeats, reports UI, time limits.
7. **Hardening:** lock-task mode, playback error handling, clock-tamper checks, Facebook best-effort, notifications for unavailable links.

## 18. Open questions (decide later)

- Facebook: open-count only, or drop support?
- Notifications to parents (e.g. a video became unavailable, kid finished a required video)?
- Per-child content tags or categories to help planning?
- Multiple parents per family, and how plan edits are attributed?