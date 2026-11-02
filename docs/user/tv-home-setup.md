# TV Home setup: put your calendar link on the TV

You do this yourself, in your own PowerShell window on your Windows PC. Nobody else (and no agent) should ever see
your calendar link. Installing the app on the TV is a separate step that waits for your OK (task T11).

## 1. Turn on debugging on the Google TV
1. Settings > System > About. Click **Android TV OS build** (or "Build") seven times until it says you are a developer.
2. Settings > System > Developer options. Turn on **Network debugging** (or **Wireless debugging**; on older TVs
   **USB debugging** plus a USB cable).
3. Note the TV's IP address and port shown on that screen (for example `192.168.1.50:5555`).

## 2. Connect from the PC
Open **PowerShell** (Start menu > PowerShell) and run these one at a time.

If the TV shows a pairing code (Wireless debugging > Pair device with pairing code):
```
adb pair 192.168.1.50:PAIRPORT
```
Type the six-digit code when asked. Then connect:
```
adb connect 192.168.1.50:5555
adb devices
```
You should see your TV listed with `device` next to it. If it says `unauthorized`, accept the "Allow debugging?"
prompt on the TV (tick "Always allow").

## 3. Get your secret calendar link
In Google Calendar on the web: Settings > your calendar > **Integrate calendar** > **Secret address in iCal format**.
Copy it. Anyone with this link can read your calendar, so paste it only into the hidden prompt below, nowhere else.
After step 4, clear your clipboard history: press **Win+V**, then **Clear all**. If "Clipboard sync across devices" is on (Settings > System > Clipboard), clear that too.

## 4. Send it to the TV
In the same PowerShell window, from the tv-home folder:
```
.\scripts\provision.ps1
```
Paste the link at the prompt (it shows only as asterisks), press Enter. You should see:
```
Format valid. Sending to the TV...
configured=1 last=SAVED_CALENDAR
Saved on the TV.
Feed: size=... KB, VEVENT=..., RRULE=..., within app limits: yes
```
The feed line shows counts only. If it says `no`, tell the team; the link is still saved.

If PowerShell refuses to run the script: `Set-ExecutionPolicy -Scope Process Bypass`, then run it again.
With more than one device listed, add `-Serial 192.168.1.50:5555`.

## 5. Turn debugging off again
On the TV: Settings > System > Developer options > turn **Network debugging** (or USB debugging) **off**. On the PC: `adb disconnect`.

## Remove it later
```
.\scripts\provision.ps1 -Clear
```
You should see `configured=0` and `Removed.`

## Ask Claude: put an API key on the TV (optional)
The Ask Claude tile needs an Anthropic API key. Use a **dedicated** key in a workspace with a monthly spend limit set
first: anyone with adb access to the TV could read it. The app also stops at 20 questions a day.
1. Connect from the PC (steps 1 and 2).
2. In the same PowerShell window, from the tv-home folder:
   ```
   .\scripts\provision.ps1 -Key anthropic_api_key
   ```
3. Paste the key at the prompt (asterisks only), press Enter. You should see `configured=1 last=SAVED_API_KEY` and `Saved on the TV.`
4. Clear your clipboard history (Win+V, **Clear all**), then turn debugging off (step 5).

To remove it: `.\scripts\provision.ps1 -Key anthropic_api_key -Clear`.

## If something goes wrong
- `Not valid ... nothing was sent`: the link was cut short or is not the **secret iCal** address. Copy it again.
- `No device connected`: repeat step 2.
- `TV Home is not installed` or `App not installed or provider unavailable`: the app is not on the TV yet (waits for T11).
- `The TV did not accept it`: the stored link is unchanged; check you copied the whole link and the TV is awake.

## On-screen remote for the PC
A remote window for the PC's touchscreen or mouse, laid out like a Google TV remote: Power, Input and Settings; a round
direction pad with OK in the middle; Back, Home and Assistant; rewind, play/pause and fast forward; volume and channel
rockers with Mute and Guide; app tiles for the dashboard, YouTube, Netflix, Prime Video, Disney+, Max, Hulu, Peacock,
Paramount+, Apple TV, YouTube TV and Spotify (dimmed if the TV does not have the app); and a box that types text into
a search field on the TV. Hold an arrow, volume or channel button to repeat it. The keyboard works too: arrow keys,
Enter (OK), Backspace (Back) and Escape (Home). The window fits itself to the screen: tall on a big screen, wide and
short on a small one, and you can resize it.

**Volume and Mute go to the LG directly**, because the LG ignores volume from the Google TV. The card between the
volume and channel rockers shows the LG's volume (it stops at 60, like Claude's `lg_volume`). If the LG is not set up
or does not answer, volume down and Mute go to the Google TV instead, and volume up is not sent (only the LG can keep
it under 60); the line under the buttons says which.

**Optional check, once**: drag a link (for example from your browser's address bar) onto the remote window. Nothing
should happen and the remote should stay as it is. If the window changes to a web page, close it, reopen TV Remote, and
tell the team; the file `%LOCALAPPDATA%\tv-remote\remote-app.log` says why.

**LG or Vizio.** When the Google TV is plugged into the Vizio, tap **Vizio** in the switch at the top of the remote
(tap **LG** to go back). It only changes where Vol+, Vol- and Mute go; every other button works the same either way.
The remote remembers the choice next time it opens. With Vizio picked:
- **Once paired** (steps below), Vol+, Vol- and Mute go straight to the Vizio over your home network (SmartCast).
  The card shows the Vizio's volume, and **Vol+ stops at 60**, as with the LG. The LG is never contacted.
- **Not paired yet**: the card says "Pair the Vizio" and Vol+ is not sent. Vol- and Mute go to the Google TV, which
  passes them to the Vizio over HDMI-CEC (the Vizio may ignore them).
- If the Vizio answers but does not report its volume, Vol+ moves one step per tap (holding it does not repeat).

**Pair the Vizio (once, about 2 minutes, at the TV).** Turn the Vizio on. In PowerShell from the tv-home folder:
1. Type `git pull` and press Enter.
2. Type `.\scripts\install-remote-shortcut.ps1` and press Enter. It adds the pairing command.
3. Type `.\tv-remote\.venv\Scripts\tv-remote-vizio-pair.exe` and press Enter. It looks for the Vizio and asks
   "Found ... at 192.168.x.x. Use it? [Y/n]": press Enter. If it finds none, type the Vizio's IP address (on the TV:
   Settings > Network > Network Information) and press Enter. To skip the search, add `--host 192.168.x.x`.
4. A 4-digit PIN appears on the Vizio. Type it in PowerShell and press Enter. You should see `Vizio paired.`
5. Close TV Remote if it is open, reopen it, tap **Vizio** at the top, and press Vol- once. The card should show
   a number.

If it says the PIN was not accepted, run step 3 again for a new PIN. To unpair, run
`.\tv-remote\.venv\Scripts\tv-remote-vizio-pair.exe --forget`. The key the Vizio hands over is sealed with Windows
(DPAPI) in `%APPDATA%\tv-remote\vizio-auth.sealed` and never shown. The remote also remembers the Vizio's
certificate; if a different device answers at that address later, the remote refuses it and says to pair again.

**To update, or to set it up the first time**, in PowerShell from the tv-home folder:
```
git pull
.\scripts\install-remote-shortcut.ps1
```
It installs the remote app into `tv-remote\.venv` (about a minute the first time) and puts "TV Remote" on the desktop
and in the Start menu. Then tap **TV Remote**. Right-click it in the Start menu > Pin to taskbar to pin it. If an
older "TV Remote" is pinned to the taskbar, unpin it and pin the new one.

`-Remove` takes both icons away. `-Classic` makes the icons open the older PowerShell remote
(`.\scripts\tv-remote-pc.ps1`, which still works on its own) instead. Both change only the PC.

It uses the adb in `%LOCALAPPDATA%\tv-home-tools` and the LG pairing in `%APPDATA%\tv-remote` (made by `tv_pair`), opens
no port and sends only the fixed commands its buttons name. Typed text may use letters, numbers, spaces and
`. , - _ @`. To use another Google TV address, start it from PowerShell instead of the icon:
`.\tv-remote\.venv\Scripts\tv-remote-app.exe --serial 192.168.68.71:5555`.

## Home button (optional; you choose)
As installed, the dashboard changes nothing about Home. To switch it on, in PowerShell from the tv-home folder (it
shows what it will change and asks you to type YES):
```
.\scripts\tv-home-takeover.ps1 -TurnOn
```
The remote's Home button then opens the dashboard, with focus on the first tile. You will see the TV's own home for a
moment first; that is expected. (Right after the TV starts up you may still land on the TV's own home; press Home.)
Back on the dashboard's row also comes back to the dashboard, after the same flash.

**To reach the TV's own home** (its Settings gear, apps and inputs): press Home twice quickly. It stays until you open
an app; after that, Home opens the dashboard again.

How it works: "Dashboard Home button" in the TV's accessibility settings notices when the TV's own home comes up and
opens the dashboard. It is told which app comes to the front, never what is on screen, and keeps nothing. (Google TV does not let
another app be the real home, and the system handles the Home and mic keys before any app could catch them, so the
mic button stays Google Assistant; Ask Claude is on the dashboard and on the PC remote.) If you ever "Force stop" the
dashboard in the TV's app settings, Android switches it off; run `-TurnOn` again.

To undo: `.\scripts\tv-home-takeover.ps1 -Rollback`. It keeps any other accessibility service you turned on.
By hand, with the remote: press Home twice quickly, open Settings from the TV's own home, then System >
Accessibility, and turn off "Dashboard Home button". Or uninstall the dashboard.
