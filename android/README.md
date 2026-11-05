# KidTube Android app

Kotlin + Jetpack Compose, single Gradle module `app`, application id `com.bbro.kidtube`.
Design: [`../docs/SPEC.md`](../docs/SPEC.md) (sections 3, 4, 12). Project rules: [`../CLAUDE.md`](../CLAUDE.md).

Status: project skeleton only. Placeholder screens (Setup, Parent Home, Kid Profile Picker), no business logic yet.

## Requirements
- Android Studio (recent enough for AGP 9.5) or just a JDK 17+ (Studio's bundled JBR 21 works).
- Android SDK with platform **android-37** and build-tools installed (SDK Manager).
- Nothing else is installed globally: the Gradle wrapper downloads Gradle 9.8.2 into `~/.gradle`.

## Open in Android Studio
1. File > Open > select this `android/` folder (not the repo root).
2. Studio creates `local.properties` with `sdk.dir` for you. If you build from the command line only, create it yourself:
   `sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk` (Windows; the colon must be escaped) or `sdk.dir=/home/<you>/Android/Sdk`.
   Alternatively set the `ANDROID_HOME` environment variable. The file is git-ignored.
3. Let Gradle sync, pick the `app` run configuration and press Run.

## Run on the emulator
- Create an AVD (Device Manager) running Android 14 (API 34) or newer, then Run.
- The debug build talks to `http://10.0.2.2:8000/`, which is your computer's `localhost:8000` as seen from the emulator.
  Start the backend with `uvicorn app.main:app --port 8000` (see `../backend`).
  Cleartext HTTP is allowed in debug **to 10.0.2.2 only** (`src/debug/res/xml/network_security_config.xml`).
- On a real phone in debug, 10.0.2.2 does not exist; use a release-style https URL instead.

## Tests and checks
```
./gradlew testDebugUnitTest                      # JVM unit tests
./gradlew assembleDebug testDebugUnitTest lintDebug   # the full pre-commit check
```
(`gradlew.bat` on Windows cmd/PowerShell; `./gradlew` works in Git Bash.) Reports: `app/build/reports/`.

## Release build and the backend URL
The release API base URL is never in source. It comes from the Gradle property `KIDTUBE_API_BASE_URL`
(default in `gradle.properties` is the placeholder `CHANGE-ME`). Put the real value in your **user** Gradle
properties so it is not committed, `~/.gradle/gradle.properties`:
```
KIDTUBE_API_BASE_URL=https://kidtube.example.com/
```
or pass it once: `./gradlew assembleRelease -PKIDTUBE_API_BASE_URL=https://kidtube.example.com/`.

Every release task (`assembleRelease`, `bundleRelease`, `lintRelease`, ...) fails with a clear message if the
property is missing, still `CHANGE-ME`, or not `https://`. A trailing `/` is added if you leave it off.
Release builds block all cleartext traffic. Signing is not configured yet; `assembleRelease` produces an unsigned APK
(sideload distribution: sign it with your own keystore, which must never be committed).

## Layout (packages under `com.bbro.kidtube`)
| Package | Purpose |
|---|---|
| `core` | `Clock`, family time zone, device role, Keystore AES-GCM wrapper (for the PIN) |
| `data` | Room database, DAOs, (later) repositories |
| `data.remote` | Retrofit API + DTOs |
| `sync` | WorkManager + outbox (empty for now) |
| `engine` | Kotlin port of the plan engine (empty for now) |
| `player` | YouTube IFrame wrapper + tracking (empty for now) |
| `ui.setup`, `ui.parent`, `ui.kid` | Screens; `ui.nav` NavHost, `ui.theme` theme |
| `di` | Hilt modules |

## Chosen versions (see `gradle/libs.versions.toml`)
AGP 9.5.0 (built-in Kotlin, so no `kotlin-android` plugin) on Gradle 9.8.2; Kotlin 2.4.21 with KSP 2.3.13;
Compose BOM 2026.09.00 (Material 3); Navigation Compose 2.10.2; Hilt 2.60.1 (+ androidx.hilt 1.4.0);
Room 2.8.5; WorkManager 2.12.0; DataStore 1.3.0; Retrofit 3.0.0 (built-in kotlinx.serialization converter) with
OkHttp 5.5.1; kotlinx.serialization 1.12.0; coroutines 1.11.0; desugar_jdk_libs 2.1.5;
JUnit 4.13.2, Turbine 1.2.1. compileSdk/targetSdk 37, minSdk 26.
