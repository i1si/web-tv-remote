# Web TV Remote

Android app (Android 11+, TV boxes / projectors) that runs a web server on the box; any browser in
the home network becomes a remote. See README.md for user-facing docs and the HTTP API.

## Build & run

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # needs exactly one adb device, else use adb -s <serial> install -r …
./gradlew lintRelease          # warnings about PrivateApi in InjectorMain are expected
```

- Needs JDK 17 (AGP 8.7) and Android SDK platform 35. Gradle finds them via `org.gradle.java.home`
  in `~/.gradle/gradle.properties` and `sdk.dir` in `local.properties` (not committed).
- No unit tests. Verify on a device or the Android TV emulator; the web UI can be checked in a
  desktop browser against a box (`adb forward tcp:8080 tcp:8080`, then `http://localhost:8080`).
- CI: `.github/workflows/build.yml` builds the release APK; a `v*` tag publishes a GitHub release.
  Release builds fall back to the debug key when no `WTR_KEYSTORE*` env vars are set.

## Architecture

```
RemoteService (foreground)  ── WebServer (NanoHTTPD, :8080..8090, assets/web + /api/*)
                            ── AdbBridge ── LoopbackDadb (adb to 127.0.0.1:5555 on the box itself)
                            │               └─ starts InjectorMain via app_process as shell uid
                            ── Mdns (own responder, <device-name>.local)
InputController: injector → `adb shell input` → RemoteAccessibilityService → Android APIs
Pairing: 4-digit PIN shown by PinOverlay → 256-bit token cookie, only SHA-256 stored (Prefs)
RemoteState: observable state shared by service, server and MainActivity (TV screen)
```

- `injector/InjectorMain.java` is NOT run in the app process: AdbBridge launches it with
  `CLASSPATH=<apk> app_process / io.github.webtvremote.injector.InjectorMain <port> <token>`.
  It must stay Java-only, no Android Context. That is why minification is off.
- `input/AdbInternals.java` reaches into dadb's Kotlin-`internal` `AdbConnection` so we own the
  socket (dadb cannot abort a handshake stuck on the "Allow debugging?" prompt). Tied to
  dadb 1.2.x — recheck it when upgrading dadb.
- `server/Mdns.kt` is a hand-written responder (A records for our name only). JmDNS was tried
  and silently never received packets on a MediaTek box; don't reintroduce it.
- Autostart: BootReceiver plus RemoteAccessibilityService.onServiceConnected (the system binds
  accessibility services ~30 s before BOOT_COMPLETED reaches apps on slow boxes).
- Web UI (`app/src/main/assets/web/`) is plain HTML/CSS/JS, no build step, dark theme only,
  mobile-first. Commands go through an ordered queue in `app.js` (`send`/`key`).

## Conventions

- Every user-visible string exists in English and Russian: `res/values` + `res/values-ru`, and
  the `STRINGS` table in `app.js`. Escape apostrophes in Android strings (`\'`).
- Keep README.md and README.ru.md in sync when behaviour changes.
- TV UI is plain Views (no AppCompat/Compose); every control must be reachable with a D-pad.

## Gotchas when testing

- Android TV emulator: "Always allow" for ADB never sticks (the emulator's `adb_keys` is not
  writable by the framework), so the auth prompt reappears after every app restart. Not an app bug.
  On the emulator `adb usb` does not close TCP 5555, so it cannot simulate "no ADB" — set a closed
  port in prefs (`adb_port`) via `run-as` on a debug build instead.
- After `adb tcpip` restarts adbd, it can keep a phantom pending prompt ("prompt currently pending,
  skipping"); run `adb tcpip 5555` again.
- macOS Local Network privacy: a process without that permission gets `No route to host` for any
  LAN address (including mDNS). Check which app is blocked before blaming the box or the network.
- With several adb devices connected, always pass `-s <serial>`; never install onto a user's real
  box without asking.
- zsh does not word-split `$VAR`, so `A="adb -s x"; $A shell …` fails — use a function instead.
