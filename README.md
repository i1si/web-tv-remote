# Web TV Remote

[Русская версия](README.ru.md)

Turn any phone, tablet or computer into a remote for your Android TV box or projector. Nothing to
install on the phone: open a link in the browser, enter a PIN shown on the TV, and you're done.

<p align="center">
  <img src="docs/remote.jpeg" width="300" alt="The remote in a phone browser: power, keyboard and settings buttons, D-pad with OK, Back, Home, Menu, volume slider and app shortcuts">
</p>

- Works on Android TV boxes and projectors with Android 11 or newer.
- Any number of phones in your home Wi-Fi can use it at the same time.
- Starts by itself when the TV box or projector is turned on.
- Can be added to the phone's home screen and opens like a regular app.

## Install

1. Install `web-tv-remote.apk` from [Releases](../../releases).
2. Open **Web TV Remote** on the box once. After that it starts automatically every time the box is
   turned on.

### Unlock all buttons (recommended)

Out of the box Android does not let an app press buttons for you, so without this step only Back,
Home, volume, the keyboard and app shortcuts work. To get the D-pad, OK and all other buttons,
turn on "Network debugging" once:

1. On the box: **Settings → Device preferences → About**, then click **Build** seven times until
   you see "You are now a developer".
2. Go back to **Device preferences → Developer options** and turn on **USB debugging** and
   **Network debugging** (on some boxes it is called **ADB over network**).
   The **Developer options** button in Web TV Remote opens this screen for you.
3. Open Web TV Remote again. A window **"Allow debugging?"** appears: tick
   **"Always allow from this computer"** and press **OK** with the box's own remote.

The TV screen now says **"Control: full, via ADB"**. You only need to do this once.

The permission is used only by Web TV Remote on the box itself; no computer is involved and
nothing is sent outside your home network.

## Connect your phone

1. Open Web TV Remote on the box. On the right you'll see a link like `http://living-room.local:8080`
   and a QR code.
2. Scan the QR code with the phone camera, or type the link into the phone's browser.
   The phone must be on the same Wi-Fi as the box.
3. A 4-digit PIN appears on the TV. Enter it on the phone.

The phone is remembered, so the PIN is asked only once. To disconnect all phones, press
**Forget paired devices** in Web TV Remote on the box.

The link uses the box's name (from **Settings → Device preferences → About → Device name**), so it
keeps working even when your router gives the box a new IP address. Bookmark it.

## Add to the home screen

- **iPhone / iPad (Safari):** Share → **Add to Home Screen**. The remote then opens full-screen.
- **Android (Chrome):** ⋮ → **Add to Home screen**. It opens with the browser's address bar;
  that is how Chrome treats sites in a home network.

## Troubleshooting

**The phone shows "Limited mode" and the D-pad does nothing.**
Network debugging is off or was not allowed. Follow [Unlock all buttons](#unlock-all-buttons-recommended).
If the TV says *Confirm "Allow debugging"*, look for the window on the TV and confirm it with the
box's own remote.

**There is no "Network debugging" option.**
Some boxes only have *Wireless debugging* with a pairing code, which is not supported yet. You
can still turn on network debugging from a computer once:
connect the box over USB and run `adb tcpip 5555`. On some boxes this has to be repeated after
every reboot.

**The link from the QR code does not open on my Android phone.**
Some Android phones do not understand `.local` names. Find the box's IP address in your router's
list of devices and open `http://<IP address>:8080` instead. Giving the box a fixed IP in the
router keeps that address from changing.

**The page says "TV is not reachable".**
Check that the phone and the box are on the same Wi-Fi network and that the box is on, not in
sleep mode.

**The remote does not work after the box is turned on.**
Open Web TV Remote on the box once — Android does not start an app automatically until it has
been opened at least one time. On slow boxes give it about a minute after power-on.

**I don't see the PIN on the TV.**
Open Web TV Remote on the box: the PIN is shown there as well. On the phone, tap
**Show the PIN again** if it has expired.

## Limitations

- The remote cannot turn on a box or projector that is switched off, because the remote itself runs
  on the box. **Power** only puts the box to sleep. Pressing it again wakes the box only if it stays
  on Wi-Fi while asleep, and many boxes turn Wi-Fi off in sleep.
- The phone and the box must be in the same home network; the remote does not work over the internet.
- Some apps that only accept their own remote may ignore a few buttons.
- In apps with their own on-screen keyboard (instead of a normal text field), non-Latin text is
  pasted, which some apps do not support.

## Privacy

Web TV Remote works only inside your home network and does not send anything to the internet.
Only a phone that has entered the PIN shown on the TV can control it.

## For developers

Build with JDK 17 and Android SDK 35: `./gradlew assembleRelease`. The web remote is plain
HTML/CSS/JS in [app/src/main/assets/web](app/src/main/assets/web). See [CLAUDE.md](CLAUDE.md) for the
architecture.

## License

[MIT](LICENSE)
