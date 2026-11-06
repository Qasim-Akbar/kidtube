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
- A phone is either a Parent device (can also switch to Kid mode; active mode is persisted; leaving kid mode needs the PIN)
  or a Kid-only device (paired by code). Several kids share one device through the profile picker.
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
- Use .venv/bin/alembic for migrations. SQLite in dev, Postgres in production.
- Tests on SQLite (default, in-memory): cd backend && .venv/bin/pytest
- Tests on Postgres (database name must end in _test; fixtures wipe it):
  cd backend && TEST_DATABASE_URL=postgresql+psycopg://kidtube_test:test-only-pw@localhost:5432/kidtube_test .venv/bin/pytest
  Run both before committing.

- Time: never call datetime.now() directly in app code; use the injectable get_now clock (app/core/clock.py).
- Everything is scoped by family_id. Another family's rows must return 404, not 403.
- Run uvicorn with a single worker (in-memory rate limiter).
- Production (Ubuntu VPS, no Docker): see DEPLOY.md; files in deploy/ (systemd unit, nginx, deploy.sh, backups, ufw).
  deploy/constraints.txt pins dependencies (tested on Python 3.12). Interactive docs are off unless APP_ENV=dev.
  New top-level API routes must also be added to the path list in deploy/nginx/kidtube.conf.

## Status
Done (backend): scaffold, models + migrations, auth, children, devices/pairing, login rate limit,
videos + oEmbed, pure plan engine (+ JSON vectors), plan/events/feed/projection endpoints,
sync (snapshot pull with ETag; per-child event history in the snapshot for offline engine use, built by the same
loader as the feed; combined push), per-child reports.
Deployment files done (deploy/, DEPLOY.md; not yet run on a real VPS).
Verified on PostgreSQL 18 as well as SQLite: the full test suite and the Alembic migrations.
Done (android): project skeleton; data layer, networking and auth (Retrofit API + DTOs, SessionStore, token refresh,
Room schema v1, repositories). Not yet: UI, sync worker (push/pull scheduling), Kotlin engine port.
Next: Android MVP (PIN, share target, outbox flush / sync worker).

## Android (android/)
- Stack: Kotlin 2.4, Jetpack Compose + Material 3, single module `app`, Gradle Kotlin DSL + version catalog
  (`android/gradle/libs.versions.toml`), AGP 9.5 on Gradle 9.8.2, KSP (not kapt), Hilt, Room, WorkManager,
  Retrofit + kotlinx.serialization + OkHttp, DataStore. minSdk 26, compile/target 37. java.time via core library desugaring.
  `androidx.security:security-crypto` is deprecated: the PIN uses the Keystore AES-GCM wrapper in `core/KeystoreCipher`.
  android-youtube-player is added later (player task), not yet.
- Packages under `com.bbro.kidtube`: core, data (+ data.entity, data.dao, data.repo), data.remote (+ data.remote.auth),
  sync, engine, player, ui.setup, ui.parent, ui.kid, ui.nav, ui.theme, di. Single Activity + NavHost.
- Backend shapes: DTOs in data.remote/Dtos.kt mirror backend/app/schemas field for field (UUIDs are Strings). Read the
  backend schema, never guess a field name; when a shape is unclear, stop and ask. Enums have an UNKNOWN fallback so a
  newer server never crashes an older app. Json = `KidTubeJson` (ignoreUnknownKeys, explicitNulls=false,
  coerceInputValues, encodeDefaults); PATCH bodies are JsonObjects (`PatchField`) so an explicit null can be sent.
- Networking: `AuthApi` (anonymous: register/login/refresh/pair/health) and `KidTubeApi` (bearer) are Retrofit interfaces;
  app code calls `KidTubeRemote`, whose methods all return `ApiResult` (Success | HttpError | RateLimited | NetworkError),
  never an exception. `ApiWiring.create` builds the stack for both Hilt and the tests.
- Session/auth (`core/SessionStore`, `data/remote/auth`): non-secret info (device role parent|kid|unset, family id and
  timezone, a SEPARATE persisted ActiveMode used only when role = parent) is plain DataStore; parent access/refresh tokens and
  the device token are encrypted with `SecretCipher` (Keystore AES-GCM). Never log tokens (debug logging is headers only,
  Authorization redacted, DebugLogRedactionTest). `sessionState` = Unauthenticated | ParentDevice(mode) | KidDevice |
  NeedsLogin | DeviceRevoked. Parent devices send the parent token in both modes (the UI hides parent screens in kid mode);
  kid devices send the device token and ignore setActiveMode. Refresh is single-flight (Mutex), proactive within 60 s of the
  JWT exp (Clock), a 401 is retried once; refresh rejected -> NeedsLogin (data kept); kid-token 401 -> DeviceRevoked.
  Logout clears secrets only.
- Room: schema version 1 is committed in app/schemas; EVERY later schema change needs a version bump + Migration (no
  destructive fallback). `SnapshotDao.replaceSnapshot` swaps children/videos/plans/items/history and sets history_cycle in one
  transaction. Overlay rule (SPEC section 9, the source of truth): currentCycle is bumped when a pull starts; a pushed event that
  is acked gets ackedCycle = currentCycle + 1; historyCycle = the pull that produced the stored snapshot (also advanced
  on a 304); an event is laid over the history iff it is pending, or acked with ackedCycle > historyCycle; rejected
  never. See `EventRepository.overlayEvents(childId)`. local_date = Clock in the FAMILY timezone from the snapshot
  (fallback Asia/Karachi).
- Tests: Room tests run on Robolectric (in-memory), network tests on MockWebServer, and the session store uses an in-memory
  DataStore in tests (the real file-backed DataStore cannot replace its file on a Windows JVM; it works on Android).
- Gradle needs a JDK 21. If JAVA_HOME points at a missing Android Studio JBR, set it for the shell, e.g. to the Temurin 21
  that Gradle downloads into ~/.gradle/jdks.
- Clock rule: never call System.currentTimeMillis / Instant.now / LocalDate.now (etc.) in app code. Inject
  `core.Clock` (the real impl `DeviceClock` is the only exception). `ClockRuleTest` scans src/main|debug|release and fails on violations.
- Engine semantics live in docs/SPEC.md section 7; the Kotlin engine (package `engine`) must pass every case in
  backend/tests/engine_vectors (feed/*.json, projection/*.json) exactly.
- Run checks: `cd android && ./gradlew assembleDebug testDebugUnitTest lintDebug` (Git Bash; `gradlew.bat` on cmd/PowerShell).
  Needs the Android SDK (platform 37): `sdk.dir` in android/local.properties (git-ignored) or ANDROID_HOME. See android/README.md.
- Release builds read the backend URL from Gradle property `KIDTUBE_API_BASE_URL` (never in source); they fail while it is
  missing, CHANGE-ME or not https. Debug uses http://10.0.2.2:8000/ with cleartext allowed to 10.0.2.2 only.
- android:allowBackup=false and backup/extraction rules exclude databases, prefs and files (PIN blobs). No analytics/ad SDKs.
