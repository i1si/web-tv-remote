# Web TV Remote

[Русская версия](README.ru.md)

Control an Android TV box or projector from the browser on any phone, tablet or computer
in your home network. No app to install on the phone: open a link, enter a PIN, done.

- Runs on the box itself (Android 11+, Android TV or regular Android).
- A built-in web server, reachable from any device on the LAN.
- PIN pairing the first time a browser connects.
- Starts automatically when the box / projector is powered on.
- The TV app shows the link and a QR code to open on the phone.
- Dark-only, mobile-first web UI that can be added to the home screen.

```
 ┌───────────────────────────┐
 │  ⏻        ⌨        ⚙     │   power · keyboard · settings
 │        ╭───────╮          │
 │        │  ˄    │          │
 │      ˂ │  OK   │ ˃        │   D-pad with OK
 │        │  ˅    │          │
 │        ╰───────╯          │
 │  ↩        ⌂        ☰     │   back · home · options
 │  [ − Quieter ][ + Louder ]│   volume
 │  ▢ YouTube ▢ Kodi ▢ …  →  │   your apps, scrolls sideways
 └───────────────────────────┘
```

## How it works

```
 phone browser ──HTTP──▶ Web TV Remote (foreground service on the box)
                           ├─ NanoHTTPD web server (UI + JSON API, port 8080)
                           ├─ PIN pairing, token cookie
                           └─ input backends, best first:
                                1. injector process started via ADB (fast, all keys)
                                2. `input keyevent` via ADB shell (slow fallback)
                                3. accessibility service (back/home, D-pad on Android 13+, text)
                                4. Android APIs (volume, launching apps)
```

A regular Android app cannot send key events (D-pad, Menu, Power) to other apps. Web TV Remote
therefore connects to the box's **own** ADB daemon over the loopback interface (`127.0.0.1:5555`,
"Network debugging") and starts a tiny helper process as the `shell` user — the same technique
scrcpy uses. The helper injects key events directly into the input system, so presses are
instant. Nothing leaves the device, and no computer is needed.

Without ADB the remote still works in a limited mode: Back, Home, volume, app launching, the
keyboard (with the accessibility service), and on Android 13+ also the D-pad.

## Installation

1. Download `web-tv-remote.apk` from [Releases](../../releases) (or the latest CI artifact).
2. Install it on the box, e.g. from a USB stick with a file manager, or
   `adb install web-tv-remote.apk` from a computer.
3. Open **Web TV Remote** once on the box. This is required: Android does not start an app at
   boot until it has been opened at least once.

### Enable ADB (recommended, needed for the D-pad and all buttons)

1. Settings → Device preferences → About → click **Build** 7 times to unlock Developer options.
2. Settings → Device preferences → Developer options → enable **USB debugging** and
   **Network debugging** / **ADB over network** (the name differs between vendors).
3. Come back to Web TV Remote. The box shows **"Allow debugging?"** — tick
   **"Always allow from this computer"** and confirm with the physical remote. This happens once.

The status line on the TV switches to "full, via ADB". The app retries ADB on its own and right
away whenever it is opened, so after enabling Network debugging just come back to it. The app also grants itself the
"display over other apps" permission through ADB (used for the PIN overlay) and exempts itself
from battery optimisation.

> **No "Network debugging" toggle?** Some boxes only have Android 11+ *Wireless debugging*
> (pairing code, random port), which is not supported yet. Connect the box to a computer once
> and run `adb tcpip 5555`; on many devices you also need `adb shell setprop persist.adb.tcp.port 5555`
> (if allowed) so the port survives reboots.

### Accessibility service (optional)

Enables typing in any language (e.g. Cyrillic) straight into the focused text field, and
Back/Home without ADB. With ADB connected the app switches it on automatically, once — if you
turn it off later it stays off. Without ADB, use the **Accessibility** button in the TV app.

## Using the remote

1. On the box, open Web TV Remote: it shows a link such as `http://living-room.local:8080` and a QR code for it
   (the IP address is shown only until the name is announced).
2. Open the link on your phone (scan the QR code).
3. A 4-digit PIN appears on the TV (as an overlay on top of whatever is playing). Enter it.
   The browser is remembered; the PIN is not asked again.

Buttons:

| Button | Tap | Hold |
| --- | --- | --- |
| D-pad | move | repeats |
| OK, Back, Home | press | long press (e.g. context menu, recent apps) |
| Volume −/+ | step | repeats |
| Volume slider | sets the box's media volume directly (hidden if the box has fixed HDMI volume) | |
| Power | sends Power (sleep) | |
| Keyboard | opens a text field mirrored to the TV; Enter/backspace work too | |
| Settings | opens the box's settings | |
| Apps | launches the app; "Edit" adds, removes and reorders apps (stored on the box, shared by all phones) | |

On a computer the keyboard works too: arrows, Enter = OK, Esc/Backspace = Back, Home, `+`/`-` = volume.

### Add to the home screen

- **iPhone / iPad (Safari):** Share → *Add to Home Screen*. Opens full-screen like an app.
- **Android (Chrome):** ⋮ → *Add to Home screen*. Browsers allow a real "installed app" (full screen,
  no address bar) only for HTTPS sites. To get it for a local address, open
  `chrome://flags/#unsafely-treat-insecure-origin-as-secure`, add the remote's address
  (e.g. `http://192.168.1.50:8080`), enable, relaunch Chrome, then use *Install app*.

### Permanent address (`<device-name>.local`)

The box announces itself via mDNS under a name made from its device name (Android settings →
Device preferences → About → Device name): "Living room" becomes **`http://living-room.local:8080`**,
Cyrillic is transliterated, and `tv-remote.local` is used if nothing usable is left. If another device
already answers for the name, the box takes `-2`, `-3`… and keeps that name from then on, so two
identical boxes never swap addresses. The TV screen shows the current name. Unlike the IP, this address survives IP changes, so it is the one to bookmark or
add to the home screen. iPhone/iPad, macOS, Windows and Linux (avahi) resolve it; on Android phones it
depends on the Android version and browser. When the remote is opened by IP on a device that can
resolve the name, it offers a link to the permanent address. Switching addresses counts as a new site
for the browser, so the PIN is asked once more.

Alternatively give the box a fixed IP address in your router.

## Security

- Every API call needs a paired browser. Pairing requires the PIN shown on the TV screen, so
  only someone who can see the TV can pair.
- The PIN is random, expires after 3 minutes and is replaced after 5 wrong attempts; every wrong
  attempt is delayed by a second.
- After pairing the browser gets a random 256-bit token in an `HttpOnly`, `SameSite=Strict`
  cookie. The box stores only its SHA-256 hash. **Forget paired devices** on the TV revokes all of them.
- Requests whose `Host` header is a domain name (other than `localhost`/`*.local`) are rejected to
  block DNS-rebinding attacks from web pages.
- The ADB connection is to `127.0.0.1` only. The injector helper listens on `127.0.0.1` and
  requires a random per-launch token.
- Traffic is plain HTTP inside your home network. Do not expose the port to the internet.

Note that enabling Network debugging lets any device on your LAN *ask* for ADB access; it still
has to be confirmed on the TV screen.

## Building

Requirements: JDK 17 and the Android SDK (platform 35).

```sh
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

Without signing configuration the release build is signed with the debug key so it can be
sideloaded directly. To sign with your own key set `WTR_KEYSTORE`, `WTR_KEYSTORE_PASSWORD`,
`WTR_KEY_ALIAS` and `WTR_KEY_PASSWORD` (the GitHub workflow reads them from the secrets
`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Pushing a `v*` tag publishes a release.

The web UI is plain HTML/CSS/JS in [app/src/main/assets/web](app/src/main/assets/web), no build step.

### Project layout

```
app/src/main/
├── assets/web/              web remote (index.html, app.css, app.js, manifest, service worker)
└── java/io/github/webtvremote/
    ├── MainActivity.kt      TV screen: link, QR code, PIN, status
    ├── RemoteService.kt     foreground service: web server + ADB link
    ├── BootReceiver.kt      autostart on boot / after update
    ├── PinOverlay.kt        PIN on top of other apps
    ├── server/              WebServer (HTTP + API), Pairing (PIN, tokens)
    ├── input/               AdbBridge, InputController, accessibility service, Apps
    └── injector/            InjectorMain — runs as the shell user via app_process
```

### HTTP API

All endpoints except `status` and `pair/*` need the pairing cookie. Bodies are JSON.

| Method & path | Body | |
| --- | --- | --- |
| `GET /api/status` | | `{paired, device, version, adb, injector, accessibility}` |
| `POST /api/pair/request` | | show a PIN on the TV |
| `POST /api/pair/confirm` | `{pin, name}` | sets the cookie |
| `POST /api/key` | `{key, mode}` | `key`: `up down left right ok back home menu power volume_up volume_down mute enter del play_pause settings`; `mode`: `click` (default), `long`, `down`, `up` |
| `POST /api/text` | `{full, deleteCount, append}` | set the focused field to `full` (or delete N chars and type `append`) |
| `GET /api/apps` | | launchable apps `[{pkg, label}]` |
| `GET /api/apps/icon?pkg=` | | PNG icon |
| `GET /api/volume` · `POST /api/volume` | `{level}` | media volume `{level, max, fixed}` |
| `GET /api/favorites` · `POST /api/favorites` | `{pkgs: [...]}` | apps shown on the remote |
| `POST /api/launch` | `{pkg}` | launch an app |

Errors: `401 not_paired`, `503 adb_required` (the action needs ADB), `500 failed`.

## Limitations

- Android 11+ *Wireless debugging* with a pairing code is not supported; legacy ADB over TCP (port 5555) is.
- After **Power**, many boxes turn off Wi-Fi, so they cannot be woken from the browser.
- Keys are injected as a virtual keyboard; a few apps that only accept their own remote's events may ignore some keys.
- Text into apps with their own on-screen keyboard (not a real text field) is typed with key
  events; non-Latin characters are pasted from the clipboard, which some apps do not support.

## License

[MIT](LICENSE)
