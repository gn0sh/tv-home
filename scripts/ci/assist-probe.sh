#!/usr/bin/env bash
# assist-probe (throwaway branch, never merged): on the CI emulator only, can the app become the default assistant,
# and what do the assist, voice and search keys open before and after? Also: which remote keys reach an
# accessibility key filter. Usage: assist-probe.sh <qa-apk> <shot-dir>
# Each finding is a line starting "PROBE "; screenshots go to the log as base64 JPEG between ===SHOT n name BEGIN/END===.
set -uo pipefail
APK=${1:?qa-apk}; SHOT_DIR=${2:?shot-dir}
PKG=com.gnosh.tvhome
ROLE=android.app.role.ASSISTANT
mkdir -p "$SHOT_DIR"
a() { timeout 90 adb "$@"; }
sh_() { timeout 90 adb shell "$@" </dev/null | tr -d '\r'; }
p() { echo "PROBE $*"; }

[[ $(sh_ getprop ro.kernel.qemu) == 1 || $(sh_ getprop ro.boot.qemu) == 1 ]] || { echo "::error::emulator only"; exit 1; }

shot() { # $1 n, $2 name
  local png="$SHOT_DIR/$1-$2.png" jpg; jpg=$(mktemp --suffix=.jpg)
  a exec-out screencap -p > "$png" || return 0
  python3 -c 'import sys; from PIL import Image; Image.open(sys.argv[1]).convert("RGB").resize((1280, 720)).save(sys.argv[2], "JPEG", quality=75)' "$png" "$jpg" 2>/dev/null || cp "$png" "$jpg"
  echo "===SHOT $1 $2 BEGIN==="; base64 "$jpg"; echo "===SHOT $1 END==="; rm -f "$jpg"
}
top() { sh_ dumpsys activity activities | grep -E 'topResumedActivity=' | head -1 | grep -oE '[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+' | head -1; }
probe_log() { sh_ logcat -d -s AssistProbe:I KeyProbe:I | grep -E 'AssistProbe|KeyProbe' | sed -E 's/^.*(AssistProbe|KeyProbe)[^:]*: /\1: /' | tr '\n' ';'; }
# Back to our home screen without a force-stop (that would unbind the voice service and disable the key filter).
neutral() { sh_ am start -W -n "$PKG/.AgendaActivity" >/dev/null; sleep 4; }

# ---- 4. which image -------------------------------------------------------------------------------------------------
p "image: fingerprint=$(sh_ getprop ro.build.fingerprint) product=$(sh_ getprop ro.product.name) sdk=$(sh_ getprop ro.build.version.sdk)"
p "image: features: $(sh_ pm list features | grep -oE 'feature:(android.software.leanback|com.google.android.tv.installed|android.software.voice_recognizers|android.software.live_tv|com.google.android.feature.GOOGLE_EXPERIENCE)[^ ]*' | tr '\n' ' ')"
p "image: google/assistant packages: $(sh_ pm list packages | grep -oE 'package:[a-z0-9_.]*(katniss|googlequicksearchbox|tv.launcherx|leanbacklauncher|tvlauncher|google.android.tv|assistant|velvet|tvrecommendations)[a-z0-9_.]*' | sed 's/package://' | tr '\n' ' ')"
p "image: home activity: $(sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"

out=$(a install --no-incremental -r -g "$APK" 2>&1 | tr -d '\r'); grep -q Success <<<"$out" || { echo "::error::install failed: $out"; exit 1; }

# ---- 2. the role ----------------------------------------------------------------------------------------------------
p "role: cmd role help mentions: $(sh_ cmd role help 2>&1 | grep -oE '(get-role-holders|add-role-holder|is-role-available|set-bypassing-role-qualification)' | sort -u | tr '\n' ' ')"
p "role: holders before: [$(sh_ cmd role get-role-holders $ROLE 2>&1 | tr '\n' ' ')]"
p "role: secure assistant=[$(sh_ settings get secure assistant)] voice_interaction_service=[$(sh_ settings get secure voice_interaction_service)] voice_recognition_service=[$(sh_ settings get secure voice_recognition_service)]"
p "role: dumpsys role ASSISTANT: $(sh_ dumpsys role | grep -A3 -i 'android.app.role.ASSISTANT' | head -4 | tr -s ' ' | tr '\n' '|')"
p "resolve: ASSIST -> $(sh_ cmd package query-activities --brief -a android.intent.action.ASSIST | grep / | tr '\n' ' ')"
p "resolve: VOICE_COMMAND -> $(sh_ cmd package query-activities --brief -a android.intent.action.VOICE_COMMAND | grep / | tr '\n' ' ')"
p "resolve: WEB_SEARCH -> $(sh_ cmd package query-activities --brief -a android.speech.action.WEB_SEARCH | grep / | tr '\n' ' ')"
p "resolve: VoiceInteractionService -> $(sh_ cmd package query-services --brief -a android.service.voice.VoiceInteractionService | grep / | tr '\n' ' ')"

# Settings: a default-apps / assistant picker?
n=1
for act in android.settings.MANAGE_DEFAULT_APPS_SETTINGS android.settings.VOICE_INPUT_SETTINGS android.settings.ASSIST_GESTURE_SETTINGS \
           android.app.role.action.REQUEST_ROLE; do
  r=$(sh_ cmd package resolve-activity --brief -a "$act" | tail -1)
  p "settings: $act resolves to [$r]"
done
for act in android.settings.MANAGE_DEFAULT_APPS_SETTINGS android.settings.VOICE_INPUT_SETTINGS; do
  sh_ am start -W -a "$act" >/dev/null 2>&1; sleep 4
  sh_ uiautomator dump /sdcard/p.xml >/dev/null 2>&1
  p "settings: $act top=$(top) texts: $(sh_ cat /sdcard/p.xml | grep -oE 'text="[^"]+"' | sed 's/text=//' | head -25 | tr '\n' ' ')"
  shot "$n" "settings-${act##*.}"; n=$((n + 1))
done

keys_round() { # $1 = label (before/after)
  local k
  for k in ASSIST VOICE_ASSIST SEARCH "--longpress SEARCH" "--longpress DPAD_CENTER" "--longpress HOME"; do
    neutral
    sh_ logcat -c
    # shellcheck disable=SC2086
    sh_ input keyevent $k
    sleep 4
    p "key[$1] $k -> top=$(top) | log: $(probe_log)"
    shot "$n" "key-$1-$(tr -d ' -' <<<"$k")"; n=$((n + 1))
  done
  neutral; sh_ logcat -c
  sh_ am start -W -a android.intent.action.ASSIST >/dev/null 2>&1; sleep 3
  p "intent[$1] am start ASSIST -> top=$(top) | log: $(probe_log)"
  neutral; sh_ logcat -c
  sh_ cmd voiceinteraction show 2>&1 | head -2 | sed 's/^/PROBE voiceinteraction show: /'
  sleep 3
  p "cmd[$1] voiceinteraction show -> top=$(top) | log: $(probe_log)"
}

# ---- the star button (MACRO_1): what handles it here, and does an app with a launcher entry show as a choice?
p "star: MagicAppSelection in packages: $(sh_ dumpsys package | grep -oE '[a-zA-Z0-9_.]+/[a-zA-Z0-9_.]*MagicApp[a-zA-Z0-9_.]*' | sort -u | tr '\n' ' ')"
p "star: launcher entries for us: $(sh_ cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER | grep "$PKG" | tr '\n' ' ')"
for k in 313 "--longpress 313"; do
  neutral; sh_ logcat -c
  # shellcheck disable=SC2086
  sh_ input keyevent $k
  sleep 4
  sh_ uiautomator dump /sdcard/p.xml >/dev/null 2>&1
  p "star: $k -> top=$(top) texts: $(sh_ cat /sdcard/p.xml | grep -oE 'text="[^"]+"' | sed 's/text=//' | head -20 | tr '\n' ' ')"
  shot "$n" "star-$(tr -d ' -' <<<"$k")"; n=$((n + 1))
done

# ---- 3. keys before ---------------------------------------------------------------------------------------------------
keys_round before

# Make the app the assistant.
p "role: add-role-holder: [$(sh_ cmd role add-role-holder --user 0 $ROLE $PKG 0 2>&1 | tr '\n' ' ')]"
sleep 3
p "role: holders after add: [$(sh_ cmd role get-role-holders $ROLE 2>&1 | tr '\n' ' ')]"
if ! sh_ cmd role get-role-holders $ROLE | grep -q "$PKG"; then
  p "role: retry with bypassing qualification: [$(sh_ cmd role set-bypassing-role-qualification true 2>&1) $(sh_ cmd role add-role-holder --user 0 $ROLE $PKG 0 2>&1 | tr '\n' ' ')]"
  sleep 3
  p "role: holders after bypass: [$(sh_ cmd role get-role-holders $ROLE 2>&1 | tr '\n' ' ')]"
  sh_ cmd role set-bypassing-role-qualification false >/dev/null 2>&1
fi
p "role: secure assistant=[$(sh_ settings get secure assistant)] voice_interaction_service=[$(sh_ settings get secure voice_interaction_service)]"
p "role: voiceinteraction dump: $(sh_ dumpsys voiceinteraction | grep -iE 'mComponent|mSessionComponentName|isActive|mValid|ServiceInfo|parseError|Probe' | head -6 | tr -s ' ' | tr '\n' '|')"

# ---- 3. keys after ----------------------------------------------------------------------------------------------------
keys_round after
sh_ cmd role remove-role-holder --user 0 $ROLE $PKG 0 >/dev/null 2>&1

# ---- the accessibility key filter: which keys arrive -------------------------------------------------------------------
sh_ settings put secure enabled_accessibility_services "$PKG/$PKG.probe.KeyProbeService"
sh_ settings put secure accessibility_enabled 1
sleep 5
p "keyfilter: enabled=[$(sh_ settings get secure enabled_accessibility_services)] connected: $(sh_ logcat -d -s KeyProbe:I | grep -c 'key filter connected')"
# The Streamer remote (18d1:9451): ASSIST, MACRO_1 (313, star), BUTTON_3 (190, YouTube), BUTTON_4 (191, Netflix),
# D-pad, Back, Home, volume, mute. Others for comparison.
for k in DPAD_UP 313 190 191 "--longpress 313" "--longpress 190" "--longpress BACK" "--longpress DPAD_CENTER" \
         VOLUME_MUTE BOOKMARK PROG_RED GUIDE SETTINGS ALL_APPS ASSIST VOICE_ASSIST SEARCH HOME; do
  neutral
  sh_ logcat -c
  # shellcheck disable=SC2086
  sh_ input keyevent $k
  sleep 2
  got=$(sh_ logcat -d -s KeyProbe:I | grep -oE 'key KEYCODE_[A-Z0-9_]+ action=[01] repeat=[0-9]+ longPress=(true|false)' | sort -u | tr '\n' ' ')
  p "keyfilter: $k -> ${got:-NOT RECEIVED} | top after=$(top)"
done
# `input keyevent` is injected and skipped the filter above (even DPAD_UP never arrived), so the same keys again
# through the emulator's own key device (`adb emu event send`, Linux key codes through the image's key layout), the
# path a real remote's keys take. Linux codes: KEY_UP 103, KEY_BACK 158, BTN_3 259, BTN_4 260, KEY_MACRO1 656,
# KEY_ASSISTANT 583, KEY_SEARCH 217, KEY_HOMEPAGE 172, KEY_BOOKMARKS 156, KEY_VOICECOMMAND 582, KEY_RED 398.
p "keydev: key devices: $(sh_ getevent -p 2>/dev/null | grep -E '^add device|name:' | tr -s ' ' | tr '\n' ' ' | head -c 600)"
for kc in 103:UP 158:BACK 259:BTN_3 260:BTN_4 656:MACRO1 583:ASSISTANT 582:VOICECOMMAND 217:SEARCH 156:BOOKMARKS 398:RED 172:HOMEPAGE; do
  code=${kc%%:*}; name=${kc#*:}
  neutral
  sh_ logcat -c
  a emu event send "EV_KEY:$code:1" "EV_SYN:0:0" >/dev/null 2>&1; sleep 0.2
  a emu event send "EV_KEY:$code:0" "EV_SYN:0:0" >/dev/null 2>&1
  sleep 3
  got=$(sh_ logcat -d -s KeyProbe:I | grep -oE 'key KEYCODE_[A-Z0-9_]+ action=[01] repeat=[0-9]+ longPress=(true|false)' | sort -u | tr '\n' ' ')
  p "keydev: linux $code ($name) -> ${got:-NOT RECEIVED} | top after=$(top)"
done
# long press of the star (MACRO1) and of BACK through the key device: held 1.5 s
for kc in 656:MACRO1 158:BACK; do
  code=${kc%%:*}; name=${kc#*:}
  neutral; sh_ logcat -c
  a emu event send "EV_KEY:$code:1" "EV_SYN:0:0" >/dev/null 2>&1; sleep 1.5
  a emu event send "EV_KEY:$code:0" "EV_SYN:0:0" >/dev/null 2>&1
  sleep 3
  got=$(sh_ logcat -d -s KeyProbe:I | grep -oE 'key KEYCODE_[A-Z0-9_]+ action=[01] repeat=[0-9]+ longPress=(true|false)' | sort -u | tr '\n' ' ')
  p "keydev: linux $code ($name) held 1.5 s -> ${got:-NOT RECEIVED} | top after=$(top)"
done
sh_ settings delete secure enabled_accessibility_services >/dev/null 2>&1
echo "PROBE done"
