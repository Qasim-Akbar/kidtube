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

- API contract fixtures: `backend/tests/contract_fixtures/*.json` are GENERATED from the real API by
  `backend/tests/test_contract_fixtures.py` (never hand-edit); the Android JSON-parsing tests read them.
  Any change to an API response shape makes `test_contract_fixtures_match_committed` fail. Refresh, review the diff, commit:
  `cd backend && UPDATE_FIXTURES=1 .venv/bin/pytest tests/test_contract_fixtures.py`
  Never put Authorization values in fixtures. Ids, tokens and server timestamps are normalised to placeholders.

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
Room, repositories); sync engine + WorkManager (SyncEngine push -> placements -> pull -> reverify, SyncWorker, SyncScheduler,
SyncStatus, Room v2); contract tests read the backend-generated fixtures.
Done (android UI, U1): design foundation (KidTubeTheme, light only, tokens/typography/shapes from docs/DESIGN.md, bundled
Nunito + Noto Naskh Arabic), English + Urdu (RTL) with per-app language, reusable components, the Setup screen.
Done (android UI, U2): Setup polish (subtitle, footer, equal 120dp cards), configurable server address, parent sign-in / create account,
parent PIN (create, PinGate, change), kid-phone pairing, start destination from stored state.
Done (android UI, U3): U2 review fixes (email/password/address/code fields always LTR + Latin font, code boxes left to right in Urdu), share target
(parent role only, offline), share sheet, Parent Home = Library (Plans / Reports "Coming soon"), Add child + default plan, Manage children,
Settings, Kid mode switch + leaving it with the PIN.
Done (android UI, U3b): fixes from the first emulator QA run (see "U3b fixes" below).
Not yet: Kotlin engine port, real Kid picker (profiles) / kid grid / player, Plans and Reports screens, plan editor.
Next: Android kid side (engine port, kid picker, kid grid, player); design the "Still to design" screens in docs/DESIGN.md first.

## Android share target, library and children (U3)
- Share target: `share/ShareActivity` (ACTION_SEND text/plain, own task, no recents). The manifest ships it `enabled="false"`; `share/ShareTargetManager`
  (started in KidTubeApp) enables it only while the stored role is PARENT (`ShareTargetPolicy`), so a kid-only phone never lists it. The Activity also
  shows "Sharing is for parents" if it is ever started on a non-parent device. Everything is local until sync: no network needed to save.
- Links: `share/SharedLinks` (pure). `extractFirstUrl` = first http(s) URL, trailing punctuation dropped, scheme lower-cased. `check` -> Supported(platform, url,
  key) | Unsupported | NoLink. YouTube hosts: youtube.com, www., m., youtu.be (music.youtube.com and other subdomains are NOT supported) and a video id is
  required; Facebook: facebook.com and its subdomains, fb.watch, fb.me (a path is required). `key` (`youtube:<id>`, `facebook:<host/path>`) is the duplicate key,
  also derived for library videos by `SharedLinks.keyOf`.
- Presets (`share/SharePreset`, the agreed mapping; a placement is ALWAYS one explicit row per child, never "every child"): Once a day = optional,
  view_target null, daily_cap 1 (unlimited: repeats every active day forever, never completes, excluded from projections); 3 times total = optional,
  view_target 3, daily_cap 1; Must watch first = required, no target, no cap (engine counts it as 1 view); Library only = NO placement (video only).
  anchor/on_miss/threshold stay default, no position is sent. `SharePreset.describe` reads stored items back (null = custom).
- Share sheet flow (`share/ShareViewModel`): PIN is checked inline (PinField + PinStore.verify, same lock rules) when Save is pressed -> duplicates
  (`ShareDuplicates`: video already in that child's plan, or already in the outbox for that child, or "Library only" when the video is already in the library/outbox;
  duplicates are left out and named, nothing is added twice) -> plans (a selected child with no plan gets the "Create default plan" offer; the PIN is NOT asked again;
  "Save without a plan" or a failed/offline creation still saves, the placement stays pending and the sync engine marks it `no_plan`) -> `OutboxRepository.enqueue`
  (which requests a sync) -> "Saved" screen that closes itself. No children: "Add a child first" + the Add child dialog.
- Default plan: `data/repo/ChildSetup.kt`: `DEFAULT_PLAN` = 3 videos/day, every weekday (mask 127), server default catch-up, no end date, start = the child's local
  date (family timezone from the snapshot, fallback Asia/Karachi, via the injectable Clock). `ChildSetupRepository` (network only, parent): `addChild` (POST /children
  then PUT /children/{id}/plan; a failed plan step still returns Success(planCreated=false)), `createDefaultPlan`, `renameChild`; each pulls the snapshot afterwards
  so Room shows the change at once, then asks for a sync. Failures map to `ChildFailure` (NO_CONNECTION vs SERVER_UNREACHABLE decided by ConnectivityChecker).
  Avatar = one of five colour keys (coral, sun, sky, mint, lilac) stored in the child's `avatar` field (`ui/children/AvatarColor`).
- Library (`ui/parent/ParentHomeScreen`, `LibraryModel` pure mapper, `ParentHomeViewModel`): server videos (badge from video status; children + preset from plan items;
  newest first) plus outbox links not yet in the snapshot (Pending + "Waiting to send"; rejected = Unavailable + "Could not be added"; a synced video whose placement
  waits for a plan says "Waiting for a plan"). Child filter chips, pull-to-refresh (`SyncTrigger.syncNow`), Kid mode button (persists ActiveMode.KID, goes to the kid
  picker), settings icon. Thumbnails: Coil 3 (`VideoThumbnail`), the pastel placeholder is always drawn underneath.
- Kid mode exit: `KidProfilePickerScreen` shows a "Parents" button only on a PARENT-role device; PinGate, then `SessionStore.setActiveMode(PARENT)`. A kid-only device has no way out.
- Settings (`ui/parent/SettingsScreen`): Manage children (`ui/children/ManageChildren`: add, rename, "Create default plan" for children without a plan), Change PIN (PinGate first),
  language, and the debug sync panel (debug builds only). Routes: SETTINGS, MANAGE_CHILDREN.
- Form fields: `KidTubeTextField(latinInput = true)` forces LTR layout, left alignment and the Latin theme font (Nunito) even in Urdu; `CodeField` boxes are LTR.
- Tests: SharedLinksTest, SharePresetTest (+ outbox mapping), ShareDuplicatesTest, ShareTargetTest, ShareViewModelTest (production stack on SyncTestBase, incl. the no-plan offer),
  ChildSetupTest (default plan creation), LibraryModelTest, ShareAndLibraryRenderTest (en + ur RTL, LTR fields). `testing/Rows` builds entities for pure tests.

## Android auth, PIN and navigation (U2)
- Server address: `core/ServerAddressStore` (same DataStore, key server_address; default = BuildConfig.API_BASE_URL; debug default from Gradle property
  KIDTUBE_DEBUG_API_BASE_URL, else http://10.0.2.2:8001/). `ServerAddressPolicy`: https:// always, http:// only when BuildConfig.ALLOW_CLEARTEXT_API (debug);
  a stored value that fails the policy falls back to the default. The API client reads the CURRENT value: Retrofit is built with a placeholder and
  `BaseUrlInterceptor` (both OkHttp clients, so token refresh too) rewrites scheme/host/port/path prefix per request from a `BaseUrlSource`
  (`StoreBaseUrlSource` in Hilt). `ApiWiring.create(..., baseUrlSource = null)` keeps the old fixed-URL behaviour for tests. The DEBUG network_security_config (src/debug/res/xml, overrides the main one) allows cleartext to any host so a dev PC LAN address works; release
  keeps src/main/res/xml/network_security_config.xml = no cleartext at all, and `ServerAddressPolicy` only accepts http:// when BuildConfig.ALLOW_CLEARTEXT_API (debug).
  See "Emulator networking" below for why 10.0.2.2 may not work from the app.
- The field lives under "Advanced" on the sign-in and pairing screens; the address is validated and saved on Sign in / Connect (an invalid one is not sent and opens Advanced).
- Sign-in (`ui/auth/SignIn.kt`): `AuthRepository.login` saves the session and sets role = Parent. "Create account" is offered by default; there is no capability
  endpoint, so a register answer of 403/404 hides it for the rest of the screen's life and shows a plain message. Errors map through `data/repo/AuthFailures`
  (WrongCredentials 401, RateLimited(Retry-After), NoConnection vs ServerUnreachable decided by `ConnectivityChecker`, EmailTaken 409, InvalidInput 422,
  RegistrationClosed 403/404, WrongCode 400/422 for pairing; 0/404/502-504 = ServerUnreachable).
- Pairing (`ui/auth/Pairing.kt`): 6-digit `CodeField`, `AuthRepository.pairDevice` (device name from `DeviceNameSource`, Build.MODEL) saves the token and sets role = Kid.
  Kid-only devices never see the parent area or a PIN prompt.
- PIN (`core/PinStore`): PBKDF2-HMAC-SHA256 (100k iterations, 16-byte salt, `PinHasher`), the record is encrypted with the Keystore `SecretCipher` (AAD kidtube.pin.v1)
  in the session DataStore; the PIN is never stored. 4 digits (`PinStore.PIN_LENGTH`). 5 wrong tries lock for 30 s (`MAX_ATTEMPTS`, `LOCK_MS`); count and lock
  are persisted; the lock runs on the monotonic clock (injected `Clock.elapsedRealtimeMillis`), wall clock only as the capped fallback after a reboot.
  `ui/pin/PinGate` (dialog + `PinGateViewModel`) is THE prompt for every later PIN need: `PinGate(onVerified, onDismiss)`. `CreatePinScreen(isChange)`.
  Parent Home has a settings placeholder with "Change PIN" (PinGate, then the change screen).
- Navigation: `ui/nav/StartDestination.resolve(state, info, hasPin)` (pure, tested): no role -> Setup; Parent without PIN -> Create PIN; Parent -> Parent Home, or the
  Kid picker when the persisted ActiveMode is KID; Kid device -> Kid picker; NeedsLogin -> Sign in; DeviceRevoked / kid role without token -> Pairing.
  `AppStartViewModel` computes it once; MainActivity shows a blank ground frame until then. The role is stored only when sign-in / pairing succeeds,
  so back from either returns to Setup. Routes live in ui/nav/StartDestination.kt.
- Tests: `testing/AuthRig` wires the production stack (ApiWiring + dynamic base URL + stores on one in-memory DataStore) to a MockWebServer; error shapes come from
  the contract fixtures (the 403/404 registration-closed answers are the only synthetic ones).

## Android UI foundation (U1)
- Design source of truth: docs/DESIGN.md. Theme in `ui.theme`: `KidTubeTheme` (light only, no dynamic colour; `KidTubeTheme.colors` =
  `KidTubeColors` tokens), `KidTubeFonts`, `kidTubeTypography(isUrdu)`, `KidTubeShapes`/`KidTubeDimens`. White text only on primary/ink.
- Fonts: res/font/nunito.ttf and noto_naskh_arabic.ttf are Google Fonts VARIABLE fonts (weights pinned with FontVariation, API 26+);
  licences (OFL) in app/fonts-licenses/. The font family, sizes and line height switch with the locale (`isUrduLocale()`).
- Localization: ALL text in `values/strings.xml` and `values-ur/strings.xml` (lint fails on a missing translation, so add both).
  Urdu copy is a first draft for a native reader to fix. `res/xml/locales_config.xml` (en, ur) + `android:localeConfig`; supportsRtl;
  Compose uses start/end only. Keep Latin things LTR inside Urdu (wordmark, PIN digits, language toggle).
- Per-app language: `core/LanguageStore` (DataStore, same file as the session, key app_language) is the persisted choice;
  `core/LocaleApplier` (`AppCompatLocaleApplier`) calls AppCompatDelegate.setApplicationLocales (recreates the activity). MainActivity is an
  AppCompatActivity and Theme.KidTube has an AppCompat parent (needed below API 33); the AppLocalesMetadataHolderService entry makes AppCompat
  restore the locale itself, KidTubeApp also re-applies the DataStore value at start.
- Components (`ui.components`, each with an English + Urdu RTL @Preview via `@EnglishAndUrduPreviews`): LanguageToggle, BigChoiceCard,
  PrimaryButton, Chip, StatusBadge (+ BadgeStatus), PinField (digits only, default length 4 = DEFAULT_PIN_LENGTH, not yet a spec decision), KidTubeLogo.
  Still to build: VideoRow, KidAvatar, KidTile, LeftTodayDots, TimeLeftBar.
- UI tests: Compose tests run under Robolectric with `@Config(qualifiers = "ur-ldrtl")` (plain "ur" does NOT give RTL in Robolectric).

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
- Room: schema versions 1 and 2 are committed in app/schemas (v2 adds `reverify_attempt`, `MIGRATION_1_2` in KidTubeDatabase, registered via `ALL_MIGRATIONS`); EVERY later schema change needs a version bump + Migration (no
  destructive fallback). `SnapshotDao.replaceSnapshot` swaps children/videos/plans/items/history and sets history_cycle in one
  transaction. Overlay rule (SPEC section 9, the source of truth): currentCycle is bumped when a pull starts; a pushed event that
  is acked gets ackedCycle = currentCycle + 1; historyCycle = the pull that produced the stored snapshot (also advanced
  on a 304); an event is laid over the history iff it is pending, or acked with ackedCycle > historyCycle; rejected
  never. See `EventRepository.overlayEvents(childId)`. local_date = Clock in the FAMILY timezone from the snapshot
  (fallback Asia/Karachi).
- Sync (package `sync`): `SyncEngine.runCycle()` is single-flight (concurrent callers share one cycle) and always PUSH THEN PULL:
  (1) `POST /sync/push` loop (<=50 videos + <=200 events per request; parent devices send videos, kid devices only events; guard: max 20
  passes, an item already tried this cycle is not resent, a pass with no state change stops the loop); (2) placements
  (`POST /children/{id}/plan/items`, parent only); (3) `SnapshotRepository.pull()` (200 replace / 304 advance history_cycle), then the
  placements again if the pull changed anything; (4) reverify (parent only, YouTube + pending only, <=5 per cycle, once per hour per video,
  attempts in `reverify_attempt`, pruned with the snapshot). Per-item results: video created/existing -> synced + server_video_id (a
  `created` video that is still `pending` with retryable=true is NOT an error and is never resent), rejected -> permanent; events
  accepted/duplicate -> acked (ackedCycle = next pull), rejected -> permanent. Placement states: 201 or 409 -> done; `childId == null` is
  expanded into one row per snapshot child; a child whose stored plan is null, or a 404 with the exact text in
  `PlacementErrors.NO_PLAN_DETAIL` ("This child has no plan yet", pinned by ContractTest), stays pending with reason `no_plan` (never
  invent a plan); no children yet -> `no_children`; other 4xx -> failed + reason. `SyncOutcome` = Success | RetryLater | RateLimited(retryAfter) |
  NeedsLogin | DeviceRevoked | PartialFailure(counts). Local writes only happen after the server answer and every call is idempotent, so a
  kill at any point just resends. `SyncStatusProvider.status` is a Flow (phase, lastSuccessAt, lastError, pending/rejected counts,
  `placementsWaitingForPlan` separate from `failedPlacements`).
- WorkManager: `SyncScheduler` (a `SyncTrigger` the repos call) owns unique work: periodic 15 min (CONNECTED), `kidtube-sync-now` (KEEP,
  expedited only on API 31+), `kidtube-sync-debounced` (30 s delay, KEEP so a viewing session still syncs, never expedited), and
  `kidtube-sync-delayed` after a 429 (the worker then returns success, never also retry). Triggers: outbox enqueue, event record (debounced),
  process foreground (ProcessLifecycleOwner in KidTubeApp), network regained (`NetworkMonitor`; the initial registration callback is ignored).
  Everything is cancelled while the session is NeedsLogin / DeviceRevoked / Unauthenticated and restarted when it changes.
  A debug-only (BuildConfig.DEBUG) panel on ParentHomeScreen shows a Sync now button and the SyncStatus fields.
- Contract tests: `ContractFixtures` (test) reads `../backend/tests/contract_fixtures` at test time (system property `contract.fixtures.dir`,
  also a Gradle test input); nothing is copied. The only hand-written JSON is test/resources/forward_compat (a NEWER server probe).
  The migration test builds the v1 database from the committed schema JSON (`room.schemas.dir`) and opens it with the production Room builder.
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
  missing, CHANGE-ME or not https. Debug defaults to http://10.0.2.2:8001/ (override with Gradle property `KIDTUBE_DEBUG_API_BASE_URL`, see "Emulator networking"); cleartext is allowed in debug only.
- android:allowBackup=false and backup/extraction rules exclude databases, prefs and files (PIN blobs). No analytics/ad SDKs.

## U3b fixes and lessons (from the emulator QA run)
- **Library "missing row" root cause (B3):** NOT a data or Room-invalidation problem (the state always had the new row; `LibraryLiveUpdateTest` proves the
  outbox + snapshot flows re-emit through the real stack, also after the Library was off-screen for longer than the WhileSubscribed window). It was the UI: a
  `LazyColumn` with stable `key`s keeps the previously-first row anchored, so a row inserted ABOVE it (every new share, and its "Waiting to send" row) landed above
  the viewport until the parent pulled down. Fix in `LibraryContent`: if the list was at the top before the update, `scrollToItem(0)` when the newest row changes
  (a parent who scrolled down is left alone). Regression: `LibraryScrollTest` (failed first, then green). Also `OutboxRepository.observeItems` is now ONE
  transactional Room query (`OutboxDao.observeWithPlacements`) so a link is never seen without its placements.
- **Edge-to-edge:** `MainActivity` and `ShareActivity` call `enableEdgeToEdge`; every screen must stay clear of the system bars. Screens built on `Scaffold` get that
  for free; the share sheet has no Scaffold and uses `safeDrawingPadding()`. `EdgeToEdgeTest` injects window insets and checks titles start below the status bar.
  New screens without a Scaffold must apply insets themselves.
- **Emulator networking (B6):** from the app, `http://10.0.2.2:8001` can time out (`SocketTimeoutException: failed to connect to /10.0.2.2 (port 8001) from /10.0.2.16
  ... after 15000ms`) although `adb shell nc 10.0.2.2 8001` connects. Not app code: no proxy, no VPN, no private DNS, the cleartext config is fine (it would fail
  instantly), OkHttp uses exactly the stored address. Raw `nc` run as the app's uid (`adb shell run-as com.bbro.kidtube nc -w 3 -z HOST 8001`) behaves the same:
  with Wi-Fi on and the app in the foreground it reaches the PC's LAN IP and the internet but NOT 10.0.2.2, while the shell uid reaches all three. The app's
  sockets leave through the emulator's virtual Wi-Fi (wlan0, 10.0.2.16) while the shell uses eth0, and the emulator has two interfaces on 10.0.2.0/24. Toggling
  Wi-Fi/airplane mode in the emulator makes it worse (app traffic can stop completely): cold boot the AVD instead.
  Working workarounds, any one of them: (1) point the debug build at the PC's LAN address: `KIDTUBE_DEBUG_API_BASE_URL=http://192.168.x.x:8001/` in
  `~/.gradle/gradle.properties` (picked up by Android Studio and the command line; or type it under Advanced on the sign-in screen, which stores it); with the
  backend in WSL also run `netsh interface portproxy add v4tov4 listenaddress=<LAN IP> listenport=8001 connectaddress=<WSL IP> connectport=8001` (admin; the WSL IP
  changes after `wsl --shutdown`). (2) `adb reverse tcp:8001 tcp:8001` and use `http://127.0.0.1:8001/` (loopback, no network interface involved; repeat after every
  emulator start). A real phone is not affected.
- **Plurals:** counts in messages are `<plurals>` (`pin_gate_wrong`, `pin_gate_locked`, `error_rate_limited`), used with `pluralStringResource`; English and Urdu both have one/other.
- **Latin inside Urdu:** `ui/components/LatinAwareText` (and `latinRunsAnnotated`) draws every run of Latin letters/digits/links (names, YouTube, numbers) in Nunito when the UI is Urdu
  (Naskh's own Latin glyphs are serif and clash). `Chip`, `PrimaryButton`, `MessageBanner`, `VideoRow` and the share sheet/children screens use it: use it wherever user data is shown inside translated text.
- **PrimaryButton(loading = true)** shows a spinner inside the button (same size, not pressable); sign-in, pairing, create PIN, share Save and Add child use it, so nothing around the button moves.
- **PinField** is four outlined circles that fill as digits are typed (hidden numeric field underneath, always LTR, never shows digits).
- **Share sheet lock banner** counts down once a second from the PIN store's injected Clock (`ShareViewModel.refreshLock`), like the PIN dialog.
- Share sheet stays a full-screen activity (docs/SPEC.md section 5 says so). Root `.gitignore` ignores `qa-screenshots/`.
