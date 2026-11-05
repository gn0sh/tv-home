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
- If the Vizio answers but does not report its volume, Vol+ moves one step per tap (holding it does not repeat), and
  after 2 such steps in a row it stops and says the Vizio did not report its volume, until the Vizio reports it again.

**Pair the Vizio (once, about 2 minutes, at the TV).** Turn the Vizio on.
1. Double-click **Update TV Remote** on the desktop. It adds the pairing command. (No such icon yet? Do the
   one-time setup under "To set it up" below first.)
2. Open PowerShell in the tv-home folder.
3. On the Vizio, open Settings > Network > Network Information and note its IP address (for example 192.168.68.90).
4. Type `.\tv-remote\.venv\Scripts\tv-remote-vizio-pair.exe --host 192.168.68.90` (with the Vizio's address) and
   press Enter.
   Without `--host` it searches the network instead and shows the address it found: type `y` only if it matches the
   one on the Vizio's screen (the default is no). Windows Firewall may block the search; if it finds nothing, use
   `--host`.
5. A 4-digit PIN appears on the Vizio. Type it in PowerShell and press Enter. You should see `Vizio paired.`
6. Close TV Remote if it is open, reopen it, tap **Vizio** at the top, and press Vol- once. The card should show
   a number.

If it says the PIN was not accepted, run step 4 again for a new PIN. To unpair, run
`.\tv-remote\.venv\Scripts\tv-remote-vizio-pair.exe --forget`. That deletes the pairing from this PC only; to cut
the PC off on the TV too, remove "TV Remote (PC)" from the Vizio's paired devices (Settings > System > Mobile Devices,
approximately; the name varies by model).
The key the Vizio hands over is sealed with Windows
(DPAPI) in `%APPDATA%\tv-remote\vizio-auth.sealed` and never shown. The remote also remembers the Vizio's
certificate; if a different device answers at that address later, the remote refuses it and says to pair again.

**To update after the team merges a change: double-click Update TV Remote** on the desktop (or in the Start menu).
It gets the latest tv-home (`git pull --ff-only` on main), reinstalls the app only when its dependencies or the
installer changed, closes TV Remote and opens it again, then shows a small box with the result. It changes nothing
if the tv-home folder is not on main or has changes of its own, and says so; it never discards anything.

**To set it up (once, or if the icons are missing)**, in PowerShell from the tv-home folder:
```
git pull
.\scripts\install-remote-shortcut.ps1
```
It installs the remote app into `tv-remote\.venv` (about a minute the first time) and puts "TV Remote" and "Update TV
Remote" on the desktop and in the Start menu. Then tap **TV Remote**. Right-click it in the Start menu > Pin to taskbar to pin it. If an
older "TV Remote" is pinned to the taskbar, unpin it and pin the new one.

`-Remove` takes all the icons away. `-Classic` makes the icons open the older PowerShell remote
(`.\scripts\tv-remote-pc.ps1`, which still works on its own) instead. Both change only the PC.

It uses the adb in `%LOCALAPPDATA%\tv-home-tools` and the LG pairing in `%APPDATA%\tv-remote` (made by `tv_pair`), opens
no port and sends only the fixed commands its buttons name. Typed text may use letters, numbers, spaces and
`. , - _ @`. To use another Google TV address, start it from PowerShell instead of the icon:
`.\tv-remote\.venv\Scripts\tv-remote-app.exe --serial 192.168.68.71:5555`.

**Google TV settings in one step.** `.\scripts\tv-setting.ps1 <name> <value>` changes one setting from a fixed list
and prints its value before and after (debugging on, as for the remote; `-Serial` for another address). There is no
way to send it any other command. The list today:
- `.\scripts\tv-setting.ps1 cec-volume on` (or `off`): whether the Google TV sends volume to the TV over HDMI-CEC.
  `.\scripts\tv-setting.ps1 cec-volume` alone shows the current value.

**For the team:** after a merged change to the PC remote, the ask to Brian is "merge, then double-click Update TV
Remote", not git commands. Brian only types commands for one-time steps such as pairing the Vizio. A new Google TV
setting goes into the list in `scripts/tv-setting.ps1`, not into an adb line for Brian to paste.

## Install a new TV Home release (one double-click)
After the team says a build is ready and you have merged it: double-click **Install TV Home.cmd** in the `scripts` folder of the tv-home folder.
It fetches the newest green build from `main` with the GitHub CLI (`gh`, signed in once with `gh auth login`), checks it is signed with your key and is the right non-debug app, and installs it on the TV. The last message (above "Press any key") says **DONE** when it worked; otherwise it says **NOT INSTALLED** and why, and changes nothing on the TV. The first time, the TV shows an "Allow debugging?" box: choose **Allow** (tick Always allow) within about 20 seconds and the same run carries on. If an `.apk` is already in `%LOCALAPPDATA%\tv-home-tools\incoming`, the script uses that one (it prints its name and date) instead of fetching; delete it to get the newest build.
Get the tv-home folder with git (`git pull`), not a GitHub "Download ZIP": Windows blocks scripts from a downloaded zip.
No `gh`? Download the `tv-home-release-apk` file from the newest green run on main (a push to main, not a pull request) at https://github.com/gn0sh/tv-home/actions/workflows/android.yml , unzip it, put the one `.apk` in `%LOCALAPPDATA%\tv-home-tools\incoming`, and double-click again. The TV's address is in `%LOCALAPPDATA%\tv-home-tools\tv-home-install.json` (made on first run).

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
another app be the real home, and the system handles the Home key before any app could catch it. The mic button
can open Claude instead: see "Use the mic button for Claude" below.) If you ever "Force stop" the
dashboard in the TV's app settings, Android switches it off; run `-TurnOn` again.

To undo: `.\scripts\tv-home-takeover.ps1 -Rollback`. It keeps any other accessibility service you turned on.
By hand, with the remote: press Home twice quickly, open Settings from the TV's own home, then System >
Accessibility, and turn off "Dashboard Home button". Or uninstall the dashboard.

## Continue tile
The first tile of the app row is **Continue in <app>** (for example "Continue in Netflix"): the streaming app you
used last. Press OK to open that app's home screen. Android keeps other apps' "Continue watching" lists for the TV's
own home screen only, so the tile can't show which show you were watching. Settings, the Play Store and apps you
hid never count.

The install script lets TV Home see which apps you used last
(`appops set com.gnosh.tvhome GET_USAGE_STATS allow`). Without that access the tile is hidden. To turn it off:
`adb shell appops set com.gnosh.tvhome GET_USAGE_STATS ignore`. Later installs leave that setting alone.

## Use the mic button for Claude (optional)
The remote's mic button can open Ask Claude instead of Google Assistant. The Google TV Streamer has no Settings
picker for this (Settings > Apps has no "Default apps"), so it is set from the PC with adb:
1. Connect from the PC (steps 1 and 2). Your TV's address (for example `192.168.68.71:5555`) is shown on its
   network debugging screen, and it can change, so check it there each time.
2. In PowerShell, run these two lines (the first makes TV Home the assistant, the second allows it the microphone):
   ```
   & "$env:LOCALAPPDATA\tv-home-tools\platform-tools\adb.exe" shell cmd role add-role-holder --user 0 android.app.role.ASSISTANT com.gnosh.tvhome 0
   & "$env:LOCALAPPDATA\tv-home-tools\platform-tools\adb.exe" shell pm grant com.gnosh.tvhome android.permission.RECORD_AUDIO
   ```
   Each prints nothing, or a short `Success`, when it works.
3. Turn debugging off again (step 5).

On a Google TV that does have the picker: **Settings > Apps > Default apps > Digital assistant app**, pick
**Ask Claude** (the app itself shows as **Agenda** elsewhere), then allow the microphone in **Settings > Apps > Agenda >
Permissions > Microphone** (menu names not checked on a TV).

Press the mic button: a small Claude card appears in the bottom-right corner over whatever is playing, with a
pulsing star while it listens. Speak your question; when you stop, it asks Claude straight away and shows the
answer on the card, which fades after about 20 seconds. Press Back to dismiss it sooner. If Claude names an app, an
**Open** button appears; nothing opens unless you press it. (Without the microphone line in step 2, the mic button
opens the full-screen Ask Claude view instead.) The card is new and not yet checked on the TV.

**What moves with it:** Google voice search moves too. While this is set, the mic button no longer opens Google
Assistant or Google's voice search, and "Hey Google" style voice commands for the TV won't work. Apps that have their
own mic icon (for example a search box in a streaming app) keep working. Usually the TV keeps Google's recognizer as
the default for them (the Android 14 TV test image does). If Android does switch the default to the assistant's
recognizer, TV Home passes each request straight on to Google's recognizer and hands back what it heard. TV Home never records or keeps the audio; Google's recognizer listens, as before. That pass-through needs the
microphone permission (the second line in step 2).

**Check it once:** with the mic button set to Claude, open YouTube, go to its search and use its mic icon. Say a
word. If YouTube shows what you said, the pass-through works. If its mic does nothing or shows an error, check
the microphone line in step 2; if that ran, set the assistant back to Google (below) and tell the team.

**To undo:** connect from the PC and give the role back to Google Assistant:
```
& "$env:LOCALAPPDATA\tv-home-tools\platform-tools\adb.exe" shell cmd role add-role-holder --user 0 android.app.role.ASSISTANT com.google.android.katniss 0
```
(With the picker: the same path, pick **Google** again.) Uninstalling the dashboard also gives the button back to Google.

## Star button opens Claude (optional)
The star button on the remote can open Ask Claude straight away, as its own tile ("Ask Claude", with a star banner).
The menu names below are **unverified** (from Google's help pages for the Google TV Streamer remote, not checked on
the TV); look for the nearest match:
1. Open Settings (the gear, top right).
2. Go to **Settings > Remotes & accessories > Customize star button** (may be called **Customize remote buttons**
   or **Shortcut button**). *Unverified.*
3. Pick **Ask Claude** from the app list (not "Agenda": that opens the dashboard). *Unverified.*

Press the star button: Ask Claude opens and starts listening. To undo, the same menu, pick another app or **None**.

## Home screen panels: switches, TV profile, travel times (optional)
None of these are secrets, so they are plain adb commands (connect first, as in steps 1 and 2). A value the TV does
not accept is refused and nothing is stored. The same list prints with `scripts/provision.sh -h`.
```
$P = "content://com.gnosh.tvhome.provision"
```
- **Turn a panel off or on** (weather, alerts, games, brief; all on by default. The brief line asks Claude at most
  twice a day, and only once the Ask Claude key is set, with a free local line until then; off, no brief line shows):
  `adb shell content insert --uri $P/panel --bind id:s:alerts --bind on:b:false` (brief off:
  `--bind id:s:brief --bind on:b:false`).
  Back to the defaults: `adb shell content delete --uri $P/panel`.
- **TV profile** (burn-in protection; LG is the default and the safer one):
  `adb shell content insert --uri $P/tv_profile --bind value:s:vizio` (or `lg`). Back to automatic:
  `adb shell content delete --uri $P/tv_profile`.
- **Travel time** ("Leave by 5:40" instead of "in 42 min" for events whose title contains the word):
  `adb shell content insert --uri $P/travel --bind keyword:s:Dentist --bind minutes:i:20`. Keyword up to 40
  characters, 1 to 180 minutes, at most 10 rules; the same keyword again replaces its time. Remove all:
  `adb shell content delete --uri $P/travel`.
- **Watch pill** (game days: opens the app that carries the game): map network names to apps, and optionally a
  default network per team: `adb shell content insert --uri $P/watch --bind networks:s:'ESPN+=com.espn.gtv' --bind defaults:s:'predators=ESPN+'`.
  Up to 32 networks and 8 teams, names up to 40 characters; one bad entry refuses the whole line. Remove:
  `adb shell content delete --uri $P/watch`.
  Without a mapping the built-in one applies. An adb mapping wins for the whole game: every network the game lists is
  tried against it first, and the built-in map only when none of those apps is installed. Broadcast and local
  channels (ABC, CBS, NBC, FOX, The CW, ION, PBS, MyNetworkTV, Scripps, the Nashville and Columbus call signs like
  WTVF) open the CDE app, found by a label containing "CDE" or "Lightband" (only when exactly one app matches); national cable (ESPN, TNT, TBS, truTV, FS1, FS2, NHL Network, Big Ten
  Network, CBS Sports Network, FanDuel Sports Network) opens its own app when installed (ESPN, Max, FOX Sports, FanDuel
  Sports Network), else the CDE app; streaming (ESPN+, Peacock, Prime Video, Max, Hulu, Paramount+, Apple TV, Disney+)
  opens its own app only when installed. Nothing that matches an installed app means no Watch pill.
  The ESPN, Max and FOX Sports apps may still ask for a TV provider sign-in for cable channels; CDE is the fallback
  only when the dedicated app is not installed.
- **Quiet mode** (hides event titles; the Quiet pill at the end of the dock does the same):
  `adb shell content insert --uri $P/quiet --bind on:b:true` (or `false`).
