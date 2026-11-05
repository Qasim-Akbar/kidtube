# KidTube – project context

Private family app (not for Play Store). One Android app (Kotlin + Jetpack Compose)
with Parent mode and Kid mode, plus a Python FastAPI backend on a VPS.

## Repo layout
- backend/  FastAPI, SQLAlchemy 2, Alembic, Postgres (SQLite in dev), pytest
- android/  Kotlin, Compose, Room, WorkManager, Retrofit
- docs/SPEC.md  full design

## Core rules
- Offline-first: the app saves shared links locally (Room outbox) and syncs later.
- Sync is idempotent: every event has a client-generated UUID; append-only event log.
- Server is source of truth; daily limits are computed from the event log, never a stored counter.
- Several kids can share one device. Plans, limits, watch time and app time are all per child_id.
- Kids need no login. Kid devices use a device-scoped token (read plan, write events only).
- Parents log in with JWT. A parent PIN (hashed locally) gates sharing and exiting kid mode.
- Playback: YouTube only via official IFrame embed. Never download or re-stream videos.
  Facebook is best-effort (open-count only). Store links and metadata, never video files.
- A view counts at >=90% watched (parent can override per item).
- Track both watch time (player in PLAYING state) and app time (foreground heartbeat every 30s).
- Day boundaries use the child's local date (Asia/Karachi), stored on each event.

## Plan model (rolling queue, not calendar-bound)
- Per child: ordered items, pace (videos_per_day or minutes_per_day), active weekdays, optional end date.
- Item: position, required, view_target, daily_cap, optional anchor_date + on_miss (shift|skip|drop).
- Missed days never expire items; the queue shifts. max_extra_per_day caps catch-up.
- Feed order: required items first (only the first incomplete one, locked), then optional until
  quota or time budget is reached, then "done for today".
- Weekly/monthly calendar views are projections of the queue at the current pace.

## Conventions
- Python: type hints, pydantic v2, pure-Python engine modules with unit tests.
- Never commit secrets; use .env and .env.example.
- Run tests before saying a task is done. Commit after each task with a clear message.
- Engine semantics live in docs/SPEC.md section 7; backend/tests/engine_vectors is the contract with the Kotlin port.

## Dev environment
- Python 3.10 venv at backend/.venv. Run tests with: cd backend && .venv/bin/pytest
- Use .venv/bin/alembic for migrations. SQLite in dev/tests, Postgres in production.

- Time: never call datetime.now() directly in app code; use the injectable get_now clock (app/core/clock.py).
- Everything is scoped by family_id. Another family's rows must return 404, not 403.
- Run uvicorn with a single worker (in-memory rate limiter).


## Status
Done (backend): scaffold, models + migrations, auth, children, devices/pairing, login rate limit,
videos + oEmbed, pure plan engine (+ JSON vectors), plan/events/feed/projection endpoints,
sync (snapshot pull with ETag, combined push), per-child reports.
Next: deployment, then Android.
Migrations are verified on SQLite only; verify on Postgres before deploying.