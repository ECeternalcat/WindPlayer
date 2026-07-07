# AGENTS.md

Operational cheat-sheet for AI assistants (and humans) working on WindPlayer.
Read this before running build/test commands.

## Project layout

```
core-mpv/      mpv FFI bindings (JNA on desktop, JNI via libplayer.so on Android)
core-vfs/      VFS layer: SFTP/WebDAV/FTP/Local + track matching
ui-compose/    Compose UI (desktop Main + Android share common types)
app-desktop/   Desktop entry point (JFrame + Swing + Compose)
app-android/   Android entry point (Activity + Compose)
lib/mpv-dev/   libmpv-2.dll + headers for desktop runtime
test/          Test video files
Documents/     Project planning + worklog + issues
```

### Source-set hierarchy (KMP)

Each KMP module (`core-mpv`, `core-vfs`, `ui-compose`) defines `desktop` + `android`
targets. `core-vfs` additionally defines an intermediate **`jvmShared`** source set
that holds JVM-only code shared between desktop and Android (SFTP/WebDAV/FTP
clients + `VfsUtils` + `KnownHostsManager` + `SshjCompat` + `FilePermissions`).
Hierarchy:

```
core-vfs:
  commonMain (target-agnostic: FileNode, ServerConfig, TrackMatcher, VfsClient,
              FileNodeComparator — pure Kotlin)
    └── jvmShared (JVM-only: SftpClient, WebdavClient, FtpClient, VfsUtils,
                   KnownHostsManager, SshjCompat, FilePermissions)
            ├── desktopMain (LocalClient, StreamProxy, VfsManager, CryptoUtil)
            └── androidMain  (StreamProxy — Android uses raw ServerSocket
                              because com.sun.net.httpserver isn't in the SDK)
```

This split exists so a future non-JVM target (iOS / JS) can compile against
`commonMain` without dragging in `java.*` / `net.schmizz.*` / `io.ktor.*`.
Tests in `commonTest` MUST only reference `commonMain` symbols (not jvmShared);
use `desktopTest` for jvmShared tests.

## Build / Test commands

All commands run from the repo root via `./gradlew` (or `.\gradlew.bat` on Windows).

### Desktop

```bash
# Compile check (fast, no packaging)
./gradlew :app-desktop:compileKotlinDesktop --no-daemon

# Run desktop distribution (needs libmpv-2.dll in lib/mpv-dev/)
./gradlew :app-desktop:run

# Build cross-platform ZIP distribution
./gradlew :app-desktop:distZip
```

### Android

```bash
# Compile check
./gradlew :app-android:compileDebugKotlin --no-daemon

# Build debug APK (output: app-android/build/outputs/apk/debug/)
./gradlew :app-android:assembleDebug --no-daemon
```

### Tests

```bash
# Desktop JVM tests in core-vfs (TrackMatcher, ServerConfig, VfsUtils, CryptoUtil)
./gradlew :core-vfs:desktopTest --no-daemon

# All tests across all targets
./gradlew :core-vfs:allTests --no-daemon
```

There is **no** `lint`, `detekt`, or `ktlint` configured. If asked to "run the linter",
run the test command above instead.

## CI/CD

Two GitHub Actions workflows exist:

- `.github/workflows/ci.yml` — runs on every push to master/main and on PRs.
  Compiles Desktop + Android, runs `:core-vfs:allTests` (88 tests across
  TrackMatcher / ServerConfig / VfsUtils / CryptoUtil), uploads the debug APK
  and test results as artifacts. Runs on `ubuntu-latest`. Declares
  `permissions: { contents: read }` (least-privilege).

- `.github/workflows/release.yml` — runs on `v*` tag push.
  Builds the Android APK and Desktop distribution ZIP, attaches them to a
  GitHub Release with auto-generated release notes.

## Common gotchas

- **`local.properties`** contains `sdk.dir` pointing to the developer's Android SDK
  install. CI uses `android-actions/setup-android@v3` instead. Never commit
  your local `local.properties` (it's already in `.gitignore`).

- **libmpv binary** is required for runtime, not for compile. CI compile checks
  pass without it. Running `:app-desktop:run` locally requires `libmpv-2.dll`
  (or `.so`/`.dylib`) in `lib/mpv-dev/`.

- **JDK 21** is the canonical version (enforced by CI via `actions/setup-java`).
  JDK 17 also works for Android builds (per `app-android/build.gradle.kts`
  `compileOptions`). `gradle.properties` does not pin the JDK version —
  the 21 requirement is CI-only.

- **`KnownHostsManager`** fails closed. SSH connections use TOFU
  (`TofuHostKeyVerifier`) backed by `~/.windplayer/known_hosts`. If the
  known_hosts file can't be opened/parsed, the manager returns a
  `RejectAllHostKeyVerifier` that refuses *every* host key — connections
  fail loudly rather than silently downgrading to MITM-vulnerable mode.
  The known_hosts file is created with `0600` permissions. **Do not**
  reintroduce `PromiscuousVerifier` as a fallback.

- **mpv cross-thread access** is serialized by an internal `lock` on both
  Android (`MpvPlayer.android`) and Desktop (`MpvPlayer.desktop`). Don't
  add `synchronized(player)` blocks outside the player — they only cause
  lock-ordering hazards (see `MpvRenderView` H7 from the 2026-06-23 audit).
  The event-loop thread is joined in `dispose()` before `mpv_terminate_destroy`.

- **Android EndFile reason** is inferred via `eof-reached` property query
  (see `MpvPlayer.android.kt::inferEndFileReason`) because `libplayer.so`'s
  `event(int)` JNI callback doesn't pass the real reason. Don't simplify this
  back to `if (fileLoadedBefore) 0 else 4` — that breaks auto-play-after-stop
  detection.
