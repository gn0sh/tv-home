#!/usr/bin/env bash
# One-off screenshot tour of the home screen on the Android TV emulator (branch screenshot-tour; never merged).
# Brian could not reach his TV, so this shows him the merged home screen in nine states, using made-up data only.
#
# Usage: screenshot-tour.sh <qa-apk> <shot-dir>
#   qa-apk:   the `qa` build (release + R8, debuggable), so run-as can plant the panel caches
#   shot-dir: the full-size PNGs (uploaded as an artifact)
# Each shot is also printed to the log as a base64 1280x720 JPEG between
#   ===SHOT <n> <name> BEGIN===   and   ===SHOT <n> END===
# followed by a line "TOUR <n> <name>: PRODUCED|NOT PRODUCED (<reason>)" from a uiautomator check of what is on screen.
# A state that cannot be produced is reported as NOT PRODUCED, never faked.
#
# Fake data only: invented events and games, the FakeSecrets dummy calendar URL, a fixed location. Helpers follow
# scripts/ci/emulator-qa.sh (a, sh_, ui_text, the Private DNS offline switch from its M7 Q1 block).
set -euo pipefail
umask 077

APK=${1:?qa-apk}; SHOT_DIR=${2:?shot-dir}
PKG=com.gnosh.tvhome
MAIN="$PKG/.AgendaActivity"
P="content://$PKG.provision"
DUMMY_TOKEN="private-$(printf 'f%.0s' $(seq 1 32))"
DUMMY_URL="https://calendar.google.com/calendar/ical/x%40example.com/$DUMMY_TOKEN/basic.ics"
mkdir -p "$SHOT_DIR"

fail() { echo "::error::$*"; exit 1; }
warn() { echo "::warning::$*"; }
a() { timeout 90 adb "$@"; }
sh_() { timeout 90 adb shell "$@"; }

qemu_k=$(sh_ getprop ro.kernel.qemu | tr -d '\r'); qemu_b=$(sh_ getprop ro.boot.qemu | tr -d '\r')
[[ $qemu_k == 1 || $qemu_b == 1 ]] || fail "screenshot-tour.sh runs only on an emulator"

resumed() { sh_ dumpsys activity activities | tr -d '\r' | grep -E 'topResumedActivity=|ResumedActivity:' || true; }
in_front() { resumed | head -1 | grep -q "$PKG/"; }
ui_text() {
  local _
  for _ in 1 2 3; do
    if sh_ uiautomator dump /sdcard/tour-ui.xml >/dev/null 2>&1; then
      sh_ cat /sdcard/tour-ui.xml | tr -d '\r'; sh_ rm -f /sdcard/tour-ui.xml || true; return 0
    fi
    sleep 2
  done
}
key() { sh_ input keyevent "KEYCODE_$1"; sleep 1.2; }

# ---- image output -------------------------------------------------------------------------------------------------
JPEG_TOOL=""
if command -v convert >/dev/null 2>&1; then JPEG_TOOL=convert
elif command -v magick >/dev/null 2>&1; then JPEG_TOOL=magick
elif python3 -c 'import PIL' >/dev/null 2>&1; then JPEG_TOOL=pil
elif python3 -m pip install --quiet --user pillow >/dev/null 2>&1 && python3 -c 'import PIL' >/dev/null 2>&1; then JPEG_TOOL=pil
fi
echo "tour: JPEG tool: ${JPEG_TOOL:-none (PNG printed as is)}"
to_jpeg() { # $1 png, $2 jpg
  case $JPEG_TOOL in
    convert|magick) "$JPEG_TOOL" "$1" -resize 1280x720 -quality 80 "$2" ;;
    pil) python3 -I -c 'import sys; from PIL import Image; Image.open(sys.argv[1]).convert("RGB").resize((1280, 720)).save(sys.argv[2], "JPEG", quality=80)' "$1" "$2" ;;
    *) cp "$1" "$2" ;;
  esac
}
RESULTS=()
shot() { # $1 = n, $2 = name, $3 = verdict ("" = produced, else the reason it is not)
  local n=$1 name=$2 png="$SHOT_DIR/$1-$2.png" jpg
  jpg=$(mktemp --suffix=.jpg)
  a exec-out screencap -p > "$png" || { RESULTS+=("TOUR $n $name: NOT PRODUCED (screencap failed)"); return 0; }
  to_jpeg "$png" "$jpg" || cp "$png" "$jpg"
  echo "===SHOT $n $name BEGIN==="
  base64 "$jpg"
  echo "===SHOT $n END==="
  rm -f "$jpg"
  if [[ -z $3 ]]; then RESULTS+=("TOUR $n $name: PRODUCED"); else RESULTS+=("TOUR $n $name: NOT PRODUCED ($3)"); fi
  echo "${RESULTS[-1]}"
}

# ---- clock: device-local time of day for the morning and evening cards --------------------------------------------
# Tries the device clock (cmd alarm set-time, shell's SET_TIME) in America/Chicago, the TV's zone; if that is refused,
# shifts the time zone to a fixed-offset zone where the local hour is right (cmd alarm set-timezone). Checked by the
# device's own `date`, so a refused change shows up as NOT PRODUCED.
dev_now() { sh_ date +%s | tr -d '\r'; }
dev_tz() { local z; z=$(sh_ getprop persist.sys.timezone | tr -d '\r'); echo "${z:-UTC}"; }
sh_ settings put global auto_time 0 >/dev/null 2>&1 || true
sh_ settings put global auto_time_zone 0 >/dev/null 2>&1 || true
sh_ cmd alarm set-timezone America/Chicago >/dev/null 2>&1 || true
sleep 1
echo "tour: device zone $(dev_tz), device time $(sh_ date | tr -d '\r')"
local_clock() { # $1 = HH, $2 = MM: make the device's local time today HH:MM (hour exact; minutes when set-time works)
  local tz target now off z
  tz=$(dev_tz)
  target=$(TZ=$tz date -d "$(TZ=$tz date -d "@$(dev_now)" +%F) $1:$2" +%s)
  sh_ cmd alarm set-time "$((target * 1000))" >/dev/null 2>&1 || true
  sleep 2
  now=$(dev_now)
  if (( now - target < 120 && target - now < 120 )); then echo "tour: clock set to $(sh_ date | tr -d '\r')"; return 0; fi
  # Fallback: keep the time, move the zone. Etc/GMT signs are inverted (Etc/GMT+6 is UTC-6).
  off=$(( (10#$1 - 10#$(date -u -d "@$now" +%H) + 36) % 24 - 12 ))
  (( off < -12 )) && off=$((off + 24))
  if (( off == 0 )); then z=Etc/GMT; elif (( off > 0 )); then z="Etc/GMT-$off"; else z="Etc/GMT+$((-off))"; fi
  sh_ cmd alarm set-timezone "$z" >/dev/null 2>&1 || true
  sleep 2
  [[ $(sh_ date +%H | tr -d '\r') == "$1" ]] || { warn "tour: could not make the local hour $1 (time and zone changes refused)"; return 1; }
  echo "tour: set-time refused; zone moved to $z, local time $(sh_ date | tr -d '\r')"
}

# ---- install and provision ----------------------------------------------------------------------------------------
a uninstall "$PKG" >/dev/null 2>&1 || true
out=$(a install --no-incremental -r -g "$APK" 2>&1 | tr -d '\r' || true)
grep -q Success <<<"$out" || fail "install of the qa APK failed ($out)"
[[ $(sh_ "run-as $PKG sh -c 'mkdir -p no_backup/panels && echo ok'" | tr -d '\r') == ok ]] || fail "run-as does not work with the qa APK"
PROVISION_VALUE="$DUMMY_URL" scripts/provision.sh | tr -d '\r' | grep -q '^Saved\.$' || fail "provisioning the dummy calendar URL failed"
sh_ content insert --uri "$P/location" --bind lat:d:36.53 --bind lon:d:-87.36 || fail "location insert refused"

# An installed app for the ESPN+ mapping (the Watch pill needs a launchable package): YouTube if present.
WATCH_PKG=com.google.android.youtube.tv
if ! sh_ pm path "$WATCH_PKG" 2>/dev/null | grep -q '^package:'; then
  WATCH_PKG=$(sh_ cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER 2>/dev/null \
    | tr -d '\r' | grep -oE '^ *[a-z][a-z0-9_.]+/' | tr -d ' /' | grep -v "^$PKG$" | head -1 || true)
fi
echo "tour: ESPN+ is mapped to ${WATCH_PKG:-nothing installed}"

# ---- offline: Private DNS strict to an unresolvable host (as emulator-qa.sh Q1) ------------------------------------
sh_ settings put global private_dns_specifier tour-offline.invalid
sh_ settings put global private_dns_mode hostname
off_ok=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  off_ok=1
  for h in api.weather.gov api.open-meteo.com api-web.nhle.com site.api.espn.com calendar.google.com; do
    o=$(sh_ ping -c 1 -W 2 "$h" 2>&1 | tr -d '\r' || true)
    grep -qiE 'unknown host|bad address|name or service not known|no address associated' <<<"$o" || { off_ok=""; break; }
  done
  [[ -n $off_ok ]] && break
  sleep 2
done
[[ -n $off_ok ]] || fail "could not take the emulator offline (feed hosts still resolve); live fetches would replace the planted data"
echo "tour: offline (no feed host resolves)"

# ---- planted data -------------------------------------------------------------------------------------------------
iso() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ; }
put() { # $1 = path under no_backup; content on stdin
  a shell "run-as $PKG sh -c 'cat > no_backup/$1'" || fail "run-as write of $1 failed"
}
rmc() { local f; for f in "$@"; do sh_ "run-as $PKG rm -f no_backup/$f" || true; done; }
at_local() { # $1 = day offset, $2 = HH:MM in the device zone -> epoch
  local tz; tz=$(dev_tz)
  TZ=$tz date -d "$(TZ=$tz date -d "@$(( $(dev_now) + $1 * 86400 ))" +%F) $2" +%s
}
ev() { # $1 uid, $2 title, $3 start epoch, $4 minutes
  printf '{"uid":"%s","title":"%s","start":{"kind":"at","value":"%s"},"end":{"kind":"at","value":"%s"}}' \
    "$1" "$2" "$(iso "$3")" "$(iso $(( $3 + $4 * 60 )))"
}
day_ev() { # $1 uid, $2 title, $3 day offset
  local tz d; tz=$(dev_tz); d=$(TZ=$tz date -d "@$(( $(dev_now) + $3 * 86400 ))" +%F)
  printf '{"uid":"%s","title":"%s","start":{"kind":"day","value":"%s"}}' "$1" "$2" "$d"
}
plant_agenda() { # $@ = event JSON objects
  local now list; now=$(dev_now); list=$(IFS=,; echo "$*")
  printf '{"v":1,"fetchedAt":"%s","windowStart":"%s","windowEnd":"%s","events":[%s]}' \
    "$(iso "$now")" "$(iso $((now - 3 * 86400)))" "$(iso $((now + 20 * 86400)))" "$list" | put agenda-cache.json
}
plant_weather() { # $1 = data JSON
  printf '{"v":1,"at":"%s","data":%s}' "$(iso "$(dev_now)")" "$1" | put panels/weather.json
}
plant_alerts() { printf '{"v":1,"at":"%s","data":{"alerts":[%s]}}' "$(iso "$(dev_now)")" "$1" | put panels/alerts.json; }
plant_games() { printf '{"v":1,"at":"%s","data":[%s]}' "$(iso "$(dev_now)")" "$1" | put panels/games.json; }
evening_agenda() {
  local now; now=$(dev_now)
  plant_agenda \
    "$(ev t1 "Trash night" $((now + 40 * 60)) 15)" \
    "$(ev t2 "Dentist" "$(at_local 1 09:00)" 60)" \
    "$(ev t3 "Jen's birthday dinner" "$(at_local 1 18:30)" 120)" \
    "$(ev t4 "Soccer practice" "$(at_local 2 17:00)" 90)" \
    "$(day_ev t5 "Recycling pickup" 3)" \
    "$(ev t6 "Book club" "$(at_local 4 19:00)" 120)"
}
weather_evening='{"tempF":64,"condition":"Partly cloudy","nextPrecip":"Rain after 2 AM","highF":71,"lowF":49,"highHour":15,"overnightLowF":49}'

home() { # restart the app with the planted data and bring the home screen to the front
  sh_ am force-stop "$PKG"
  sh_ am start -W -n "$MAIN" >/dev/null
  sleep 9   # first frame, settle, cache load
  in_front || sh_ am start -W -n "$MAIN" >/dev/null
}
has() { grep -qE "$1" <<<"$UI"; }
look() { UI=$(ui_text); }

# 1. Normal evening: weather chip, next event with countdown, the time-of-day ("Tomorrow") card.
v=""
local_clock 19 05 || v="the device clock and zone could not be set to evening"
rmc panels/alerts.json panels/games.json
evening_agenda; plant_weather "$weather_evening"
home; look
has 'content-desc="Hero now: [^"]*in [0-9]+ min' || v="${v:+$v; }no next event with a countdown"
has 'content-desc="Hero card: Tomorrow' || v="${v:+$v; }no Tomorrow time-of-day card"
has 'text="[0-9]+°F' || v="${v:+$v; }no weather chip"
shot 1 evening "$v"

# 2. Morning, with the weather arc ("Today") card.
v=""
local_clock 07 30 || v="the device clock and zone could not be set to morning"
now=$(dev_now)
plant_agenda \
  "$(ev m1 "Dentist" $((now + 50 * 60)) 60)" \
  "$(ev m2 "Jen's birthday dinner" "$(at_local 0 18:30)" 120)" \
  "$(ev m3 "Trash night" "$(at_local 0 20:00)" 15)" \
  "$(ev m4 "Soccer practice" "$(at_local 1 17:00)" 90)"
plant_weather '{"tempF":52,"condition":"Sunny","nextPrecip":"Rain after 4 PM","highF":74,"lowF":51,"highHour":15,"overnightLowF":50}'
home; look
has 'content-desc="Hero card: Today' || v="${v:+$v; }no Today weather card"
shot 2 morning "$v"

# Evening again for the rest.
local_clock 19 05 || warn "tour: evening clock not set for shots 3-9; they do not depend on the hour"
evening_agenda; plant_weather "$weather_evening"

# 3. Preds game day with the Watch pill (ESPN+ mapped to an installed app).
v=""
if [[ -n $WATCH_PKG ]]; then
  sh_ content insert --uri "$P/watch" --bind networks:s:"ESPN+=$WATCH_PKG" --bind defaults:s:"predators=ESPN+;ohio-state=ESPN+" \
    || v="watch map insert refused"
else v="no installed app to map ESPN+ to"; fi
now=$(dev_now)
plant_games '{"team":"predators","start":"'"$(iso $((now + 55 * 60)))"'","opponent":"Blues","home":true,"phase":"UPCOMING","networks":["ESPN+"]}'
home; look
has 'content-desc="Hero card: Predators' || v="${v:+$v; }no Predators game card"
has 'content-desc="Watch the game on ESPN\+"' || v="${v:+$v; }no Watch pill"
shot 3 preds-game-day "$v"

# 4. Ohio State live game.
v=""
now=$(dev_now)
plant_games '{"team":"ohio-state","start":"'"$(iso $((now - 95 * 60)))"'","opponent":"Michigan","home":true,"phase":"LIVE","ownScore":24,"oppScore":17,"period":"Q3","networks":["ESPN+"]}'
home; look
has 'content-desc="Hero card: Ohio State 24, Michigan 17' || v="no live Ohio State card"
shot 4 ohio-state-live "$v"

# 5. Severe storm alert override (the game stays planted: the alert must win).
v=""
now=$(dev_now)
plant_alerts '{"event":"Severe Thunderstorm Warning","severity":"Severe","kind":"OVERRIDE","until":"'"$(iso $((now + 75 * 60)))"'"}'
plant_weather '{"tempF":71,"condition":"Thunderstorm","precipNow":"RAIN","highF":78,"lowF":58,"highHour":14,"overnightLowF":58}'
home; look
has 'content-desc="Hero card: Severe Thunderstorm Warning until' || v="no alert override card"
shot 5 severe-storm "$v"
rmc panels/alerts.json panels/games.json
plant_weather "$weather_evening"

# 6. Quiet on (event titles hidden).
v=""
sh_ content insert --uri "$P/quiet" --bind on:b:true || v="quiet insert refused"
home; look
has 'resource-id="hero_quiet"' || v="${v:+$v; }no Quiet marker"
has 'Trash night' && v="${v:+$v; }an event title still shows"
shot 6 quiet "$v"
sh_ content insert --uri "$P/quiet" --bind on:b:false >/dev/null 2>&1 || true

# 7. The full-screen calendar (Down reaches the dock's Calendar tile, OK opens it).
v=""
home
key DPAD_DOWN; key DPAD_CENTER; sleep 3
look
has 'content-desc="Calendar full screen"' || {
  key DPAD_DOWN; key DPAD_CENTER; sleep 3; look   # with no apps installed focus starts on the dock
  has 'content-desc="Calendar full screen"' || v="the full-screen calendar did not open"
}
shot 7 calendar-full-screen "$v"

# 8. DIM and 9. NEAR_BLACK, with idle_test 30 s (DIM at 30 s, NEAR_BLACK at 60 s of no keys).
sh_ content insert --uri "$P/idle_test" --bind seconds:i:30 || warn "tour: idle_test insert refused"
home; t0=$(date +%s)
n=$(( t0 + 38 - $(date +%s) )); (( n > 0 )) && sleep "$n"
look; v=""
has 'resource-id="hero_idle_DIM"' || v="not in DIM"
shot 8 dim "$v"
n=$(( t0 + 70 - $(date +%s) )); (( n > 0 )) && sleep "$n"
look; v=""
has 'content-desc="Screen resting"' || v="not in NEAR_BLACK"
shot 9 near-black "$v"
sh_ content delete --uri "$P/idle_test" >/dev/null 2>&1 || true

echo
echo "== Tour summary"
printf '%s\n' "${RESULTS[@]}"
