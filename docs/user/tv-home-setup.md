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
