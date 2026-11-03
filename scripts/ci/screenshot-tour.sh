#!/usr/bin/env bash
# One-off screenshot tour of the home screen and the Board on the Android TV emulator (branch board-tour; never merged).
# Brian could not reach his TV, so this shows him the home screen and the Board in several states, made-up data only.
#
# Usage: screenshot-tour.sh <qa-apk> <shot-dir>
#   qa-apk:   the `qa` build (release + R8, debuggable), so run-as can plant the panel caches
#   shot-dir: the full-size PNGs (uploaded as an artifact)
# Each shot is also printed to the log as a base64 1280x720 JPEG between
#   ===SHOT <n> <name> BEGIN===   and   ===SHOT <n> END===
# followed by a line "TOUR <n> <name>: PRODUCED|NOT PRODUCED (<reason>)" from a uiautomator check of what is on screen.
# A state that cannot be produced is reported as NOT PRODUCED, never faked.
#
# Home shots run twice: at the emulator's own size, then with `wm size 1280x720` (canvas scaling check; reset after).
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
    pil) python3 -c 'import sys; from PIL import Image; Image.open(sys.argv[1]).convert("RGB").resize((1280, 720)).save(sys.argv[2], "JPEG", quality=80)' "$1" "$2" ;;
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
# Formats as :core reads them on this branch: CachedAgenda v1 (agenda-cache.json), the PanelCacheCodec envelope
# {"v":1,"at":..,"data":..} for panels/weather.json (WeatherDto with its 7 daily days), panels/alerts.json,
# panels/games.json and the per-team season files panels/season-<team>.json (GameDto lists), and brief-cache.json.
iso() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ; }
put() { # $1 = path under no_backup; content on stdin
  a shell "run-as $PKG sh -c 'cat > no_backup/$1'" || fail "run-as write of $1 failed"
}
rmc() { local f; for f in "$@"; do sh_ "run-as $PKG rm -f no_backup/$f" || true; done; }
local_date() { # $1 = day offset -> yyyy-mm-dd in the device zone
  local tz; tz=$(dev_tz); TZ=$tz date -d "@$(( $(dev_now) + $1 * 86400 ))" +%F
}
at_local() { # $1 = day offset, $2 = HH:MM in the device zone -> epoch
  local tz; tz=$(dev_tz)
  TZ=$tz date -d "$(local_date "$1") $2" +%s
}
midnight() { local tz; tz=$(dev_tz); TZ=$tz date -d "$(local_date "$1") 00:00" +%s; }
ev() { # $1 uid, $2 title, $3 start epoch, $4 minutes
  printf '{"uid":"%s","title":"%s","start":{"kind":"at","value":"%s"},"end":{"kind":"at","value":"%s"}}' \
    "$1" "$2" "$(iso "$3")" "$(iso $(( $3 + $4 * 60 )))"
}
day_ev() { # $1 uid, $2 title, $3 day offset
  printf '{"uid":"%s","title":"%s","start":{"kind":"day","value":"%s"}}' "$1" "$2" "$(local_date "$3")"
}

# The rest of the week (today-1 and today+1..+8; today's events are passed in): 4-8 a day, overlaps, an all-day
# event, a six-hour trip, and a 15-event day at today+2 (the Board's busy column). offset|HH:MM or "day"|minutes|title.
WEEK_TABLE='-1|08:30|60|Gym
-1|12:00|60|Lunch with Sam
-1|15:15|30|School pickup
-1|18:00|90|Soccer practice
1|07:00|45|Gym
1|09:00|60|Dentist
1|09:30|30|Standup
1|12:30|60|Lunch with Priya
1|15:15|30|School pickup
1|18:30|120|Jen birthday dinner
2|day||Spirit week: pajama day
2|07:00|60|Gym
2|08:00|20|School drop-off
2|09:00|30|Standup
2|09:15|60|Vet appointment
2|10:30|90|Design review
2|11:00|30|Vendor call
2|12:00|60|Lunch with Alex
2|13:00|60|Budget sync
2|13:30|45|Interview: frontend
2|14:30|30|One-on-one with Dana
2|15:15|30|School pickup
2|16:00|45|Piano lesson
2|17:30|90|Soccer practice
2|19:00|90|PTA meeting
2|20:30|30|Call Mom
3|day||Recycling pickup
3|09:00|30|Standup
3|11:00|60|Car service
3|15:15|30|School pickup
3|19:00|120|Book club
4|09:00|30|Standup
4|10:00|45|Haircut
4|12:00|90|Team lunch
4|14:00|60|Quarterly planning
4|14:30|30|Plumber window
4|18:00|60|Date night
5|08:00|360|Road trip to Chattanooga
5|15:00|90|Aquarium
5|18:30|90|Dinner on the river
5|21:00|60|Hotel check-in
6|09:00|60|Pancake breakfast
6|11:00|120|Drive home
6|16:00|60|Grocery run
6|18:00|60|Meal prep
7|09:00|30|Standup
7|10:00|60|Physical therapy
7|13:00|60|Code review
7|15:15|30|School pickup
7|19:00|60|Choir rehearsal
8|09:00|30|Standup
8|12:00|60|Lunch with Sam
8|15:15|30|School pickup
8|17:30|90|Soccer practice'
week_events() { # prints the table's events as comma-separated JSON
  local out="" n=0 off hm min title
  while IFS='|' read -r off hm min title; do
    n=$((n + 1))
    if [[ $hm == day ]]; then out+="${out:+,}$(day_ev "w$n" "$title" "$off")"
    else out+="${out:+,}$(ev "w$n" "$title" "$(at_local "$off" "$hm")" "$min")"; fi
  done <<<"$WEEK_TABLE"
  printf '%s' "$out"
}
plant_agenda() { # $@ = today's event JSON objects; the week table is added. Window = fetchWindow: today-1 .. today+8.
  local now list; now=$(dev_now); list=$(IFS=,; echo "$*")
  printf '{"v":1,"fetchedAt":"%s","windowStart":"%s","windowEnd":"%s","events":[%s,%s]}' \
    "$(iso "$now")" "$(iso "$(midnight -1)")" "$(iso "$(midnight 8)")" "$list" "$(week_events)" | put agenda-cache.json
}
evening_agenda() { # today at 19:05: four past events, the next one in 40 min, one later
  local now; now=$(dev_now)
  plant_agenda \
    "$(ev t1 "Gym" "$(at_local 0 06:30)" 60)" \
    "$(ev t2 "Call with the plumber" "$(at_local 0 10:00)" 30)" \
    "$(ev t3 "Lunch with Priya" "$(at_local 0 12:00)" 60)" \
    "$(ev t4 "School pickup" "$(at_local 0 15:15)" 30)" \
    "$(ev t5 "Trash night" $((now + 40 * 60)) 15)" \
    "$(ev t6 "Movie night" "$(at_local 0 20:30)" 120)"
}
morning_agenda() { # today at 07:30
  local now; now=$(dev_now)
  plant_agenda \
    "$(ev m1 "Dentist" $((now + 50 * 60)) 60)" \
    "$(ev m2 "Standup" "$(at_local 0 09:30)" 30)" \
    "$(ev m3 "Lunch with Priya" "$(at_local 0 12:00)" 60)" \
    "$(ev m4 "School pickup" "$(at_local 0 15:15)" 30)" \
    "$(ev m5 "Trash night" "$(at_local 0 20:00)" 15)"
}

# Weather: the WeatherDto as WeatherFeed builds it from Open-Meteo (condition = the WMO weather_code's label,
# precipChance = precipitation_probability_max), with 7 daily days from today.
forecast_days() {
  local i out="" row
  local rows=('62|45|Partly cloudy|10' '58|47|Light rain|70' '55|44|Thunderstorm|80' '60|41|Clear|0' '64|46|Overcast|20' '59|43|Showers|40' '61|42|Mostly clear|10')
  for i in 0 1 2 3 4 5 6; do
    IFS='|' read -r hi lo cond pc <<<"${rows[$i]}"
    row=$(printf '{"date":"%s","highF":%s,"lowF":%s,"condition":"%s","precipChance":%s}' "$(local_date "$i")" "$hi" "$lo" "$cond" "$pc")
    out+="${out:+,}$row"
  done
  printf '%s' "$out"
}
plant_weather() { # $1 = the fields before "days"
  printf '{"v":1,"at":"%s","data":{%s,"days":[%s]}}' "$(iso "$(dev_now)")" "$1" "$(forecast_days)" | put panels/weather.json
}
W_EVENING='"tempF":58,"condition":"Partly cloudy","nextPrecip":"Rain after 2 AM","highF":62,"lowF":45,"highHour":15,"overnightLowF":44'
W_MORNING='"tempF":47,"condition":"Mostly clear","nextPrecip":"Rain after 4 PM","highF":62,"lowF":45,"highHour":15,"overnightLowF":46'
W_STORM='"tempF":66,"condition":"Thunderstorm","precipNow":"RAIN","highF":71,"lowF":52,"highHour":14,"overnightLowF":52'
plant_alerts() { printf '{"v":1,"at":"%s","data":{"alerts":[%s]}}' "$(iso "$(dev_now)")" "$1" | put panels/alerts.json; }

# Games: panels/games.json (the combined list the hero reads) and the per-team season caches (NHL for the Predators,
# ESPN for Ohio State), the same games in each, so the offline season fallback agrees with the planted list.
game() { # $1 team, $2 start epoch, $3 opponent, $4 home true/false, $5 networks JSON list
  printf '{"team":"%s","start":"%s","opponent":"%s","home":%s,"phase":"UPCOMING","networks":%s}' "$1" "$(iso "$2")" "$3" "$4" "$5"
}
plant_games() { # $1 = preds game JSON list (comma-separated), $2 = ohio-state list
  local at; at=$(iso "$(dev_now)")
  printf '{"v":1,"at":"%s","data":[%s]}' "$at" "$1" | put panels/season-predators.json
  printf '{"v":1,"at":"%s","data":[%s]}' "$at" "$2" | put panels/season-ohio-state.json
  printf '{"v":1,"at":"%s","data":[%s,%s]}' "$at" "$1" "$2" | put panels/games.json
}
no_games() { rmc panels/games.json panels/season-predators.json panels/season-ohio-state.json; }
# Next games, not today: Preds in 2 days on ESPN+, Ohio State in 4 on FOX, and a later Preds game with no network yet.
week_games() {
  plant_games \
    "$(game predators "$(at_local 2 19:00)" Stars true '["ESPN+","FDSN South"]'),$(game predators "$(at_local 6 18:00)" Jets false '[]')" \
    "$(game ohio-state "$(at_local 4 11:00)" Penn\ State true '["FOX"]'),$(game ohio-state "$(at_local 11 14:30)" Purdue false '["BTN"]')"
}

# Brief: brief-cache.json as BriefEngine writes it. A line that names no fact ("uses":[]) depends only on the date, so
# its fingerprint is SHA-256 of "date=<Weekday>, <yyyy-mm-dd>" in the device's local date. No key: never refreshed.
BRIEF_LINE="A full day today, and an umbrella day later this week."
plant_brief() {
  local tz now d fp; tz=$(dev_tz); now=$(dev_now)
  d=$(TZ=$tz LC_ALL=C date -d "@$now" '+%A, %F')
  fp=$(printf 'date=%s' "$d" | sha256sum | cut -d' ' -f1)
  printf '{"line":"%s","uses":[],"fingerprint":"%s","at":"%s"}' "$BRIEF_LINE" "$fp" "$(iso "$now")" | put brief-cache.json
}

home() { # restart the app with the planted data and bring the home screen to the front
  sh_ am force-stop "$PKG"
  sh_ am start -W -n "$MAIN" >/dev/null
  sleep 9   # first frame, settle, cache load
  in_front || sh_ am start -W -n "$MAIN" >/dev/null
}
board() {
  sh_ am force-stop "$PKG"
  sh_ am start -W -n "$MAIN" -a com.gnosh.tvhome.action.BOARD >/dev/null
  sleep 9
}
has() { grep -qE "$1" <<<"$UI"; }
look() { UI=$(ui_text); }
quiet() { sh_ content insert --uri "$P/quiet" --bind on:b:"$1"; }

# Watch map: ESPN+ and FOX to an installed app. The whole remote command is one quoted string: adb shell joins its
# arguments into a device shell command line, where the unquoted ';' in the old insert split it, so the device ran
# `ohio-state=ESPN+` as a command and the insert exited non-zero ("watch map insert refused" in the last run).
WATCH_OK=""
if [[ -n $WATCH_PKG ]]; then
  if sh_ "content insert --uri $P/watch --bind 'networks:s:ESPN+=$WATCH_PKG;FOX=$WATCH_PKG' --bind 'defaults:s:predators=ESPN+;ohio-state=FOX'"; then
    WATCH_OK=1
  else warn "tour: watch map insert refused"; fi
fi
quiet false >/dev/null 2>&1 || true

# ---- home shots (run at each size) --------------------------------------------------------------------------------
home_shots() { # $1 = first shot number, $2 = name suffix
  local n=$1 sfx=$2 v now

  # Evening: weather chip, next event with countdown, the Tomorrow card, the brief line.
  v=""
  local_clock 19 05 || v="the device clock and zone could not be set to evening"
  rmc panels/alerts.json; no_games
  evening_agenda; plant_weather "$W_EVENING"; plant_brief
  home; look
  has 'content-desc="Hero now: [^"]*in [0-9]+ min' || v="${v:+$v; }no next event with a countdown"
  has 'content-desc="Hero card: Tomorrow' || v="${v:+$v; }no Tomorrow time-of-day card"
  has 'text="[0-9]+°F' || v="${v:+$v; }no weather chip"
  has "resource-id=\"dock_brief\"" || v="${v:+$v; }no brief line"
  has "text=\"$BRIEF_LINE\"" || v="${v:+$v; }brief shows the template line, not the planted one"
  shot "$n" "evening$sfx" "$v"; n=$((n + 1))

  # Morning, with the weather arc (Today) card.
  v=""
  local_clock 07 30 || v="the device clock and zone could not be set to morning"
  morning_agenda; plant_weather "$W_MORNING"; plant_brief
  home; look
  has 'content-desc="Hero card: Today' || v="${v:+$v; }no Today weather card"
  shot "$n" "morning$sfx" "$v"; n=$((n + 1))

  local_clock 19 05 || warn "tour: evening clock not set; the remaining home shots do not depend on the hour"
  evening_agenda; plant_weather "$W_EVENING"; plant_brief

  # Severe storm alert override (a game is planted too: the alert must win).
  v=""
  now=$(dev_now)
  week_games
  plant_alerts '{"event":"Severe Thunderstorm Warning","severity":"Severe","kind":"OVERRIDE","until":"'"$(iso $((now + 75 * 60)))"'"}'
  plant_weather "$W_STORM"
  home; look
  has 'content-desc="Hero card: Severe Thunderstorm Warning until' || v="no alert override card"
  shot "$n" "severe-storm$sfx" "$v"; n=$((n + 1))
  rmc panels/alerts.json; no_games
  plant_weather "$W_EVENING"

  # Quiet on (event titles hidden).
  v=""
  quiet true || v="quiet insert refused"
  home; look
  has 'resource-id="hero_quiet"' || v="${v:+$v; }no Quiet marker"
  has 'Trash night|Movie night' && v="${v:+$v; }an event title still shows"
  shot "$n" "quiet$sfx" "$v"; n=$((n + 1))
  quiet false >/dev/null 2>&1 || true

  # Next game card (no game today): Predators in two days, with the network.
  v=""
  week_games
  home; look
  has 'content-desc="Hero card: Predators vs Stars [^"]*TV: ESPN\+' || v="no next-game card naming the network"
  shot "$n" "next-game$sfx" "$v"; n=$((n + 1))

  # Preds game day with the Watch pill (ESPN+ mapped to an installed app).
  v=""
  [[ -n $WATCH_PKG ]] || v="no installed app to map ESPN+ to"
  [[ -n $WATCH_PKG && -z $WATCH_OK ]] && v="watch map insert refused"
  now=$(dev_now)
  plant_games "$(game predators $((now + 55 * 60)) Blues true '["ESPN+"]'),$(game predators "$(at_local 6 18:00)" Jets false '[]')" \
    "$(game ohio-state "$(at_local 4 11:00)" Penn\ State true '["FOX"]')"
  home; look
  has 'content-desc="Hero card: Predators vs Blues' || v="${v:+$v; }no Predators game-day card"
  has 'content-desc="Watch the game on ESPN\+"' || v="${v:+$v; }no Watch pill"
  shot "$n" "preds-game-day$sfx" "$v"
}

home_shots 1 ""

# ---- Board ----------------------------------------------------------------------------------------------------------
local_clock 19 05 || warn "tour: evening clock not set for the Board shots"
evening_agenda; plant_weather "$W_EVENING"; plant_brief; week_games; rmc panels/alerts.json

# 7. Board full screen (the dock's Board, opened by its intent). Columns carry "<date>, N events" for TalkBack; the
# block titles are only drawn, so they are checked in the day list.
v=""
board; look
has 'content-desc="Board full screen"' || v="the Board did not open"
has 'content-desc="[^"]*, 1[2-9] events"' || v="${v:+$v; }no busy day column"
has 'Predators[^"]*vs Stars[^"]*TV: ESPN\+' || v="${v:+$v; }no Predators next game with its network"
has 'Ohio State[^"]*vs Penn State[^"]*TV: FOX' || v="${v:+$v; }no Ohio State next game with its network"
shot 7 board "$v"

# 8. Board day list for the busy day (today+2): Right twice from today, then OK.
v=""
key DPAD_RIGHT; key DPAD_RIGHT; key DPAD_CENTER; sleep 2
look
has 'content-desc="Board day list"' || v="the day list did not open"
has 'Spirit week' || v="${v:+$v; }not the busy day's list"
has 'Vet appointment' || v="${v:+$v; }no event titles in the list"
shot 8 board-day-list "$v"

# 9. Board in Quiet (every title "Busy"): checked in the busy day's list, then Back to the week for the shot.
v=""
quiet true || v="quiet insert refused"
board
key DPAD_RIGHT; key DPAD_RIGHT; key DPAD_CENTER; sleep 2
look
has 'content-desc="Board day list"' || v="${v:+$v; }the day list did not open"
has 'Vet appointment|Design review|Spirit week' && v="${v:+$v; }an event title still shows"
has 'Busy' || v="${v:+$v; }no Busy rows"
key BACK; sleep 2
look
has 'content-desc="Board full screen"' || v="${v:+$v; }not back on the Board week"
shot 9 board-quiet "$v"
quiet false >/dev/null 2>&1 || true

# 10. DIM, with idle_test 30 s (DIM at 30 s of no keys).
sh_ content insert --uri "$P/idle_test" --bind seconds:i:30 || warn "tour: idle_test insert refused"
home; t0=$(date +%s)
n=$(( t0 + 38 - $(date +%s) )); (( n > 0 )) && sleep "$n"
look; v=""
has 'resource-id="hero_idle_DIM"' || v="not in DIM"
shot 10 dim "$v"
sh_ content delete --uri "$P/idle_test" >/dev/null 2>&1 || true

# ---- home shots again at 1280x720 (the design canvas scales; checked by eye and by the same uiautomator checks) ----
trap 'sh_ wm size reset >/dev/null 2>&1 || true' EXIT
echo "tour: default size $(sh_ wm size | tr -d '\r' | tr '\n' ' ')density $(sh_ wm density | tr -d '\r' | tr '\n' ' ')"
if sh_ wm size 1280x720; then
  sleep 3
  home_shots 11 "-720p"
else
  warn "tour: wm size 1280x720 refused; no 720p shots"
fi
sh_ wm size reset || warn "tour: wm size reset failed"
trap - EXIT

echo
echo "== Tour summary"
printf '%s\n' "${RESULTS[@]}"
