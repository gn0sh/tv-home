#!/usr/bin/env bash
# One-off screenshot tour of the home screen and the Board on the Android TV emulator (branch board-tour; never merged).
# Brian could not reach his TV, so this shows him the home screen and the Board in several states, made-up data only.
#
# Usage: screenshot-tour.sh <qa-apk> <shot-dir> <fake-app-dir>
#   qa-apk:   the `qa` build (release + R8, debuggable), so run-as can plant the panel caches
#   shot-dir: the full-size PNGs (uploaded as an artifact)
# Each shot is also printed to the log as a base64 1280x720 JPEG between
#   ===SHOT <n> <name> BEGIN===   and   ===SHOT <n> END===
# followed by a line "TOUR <n> <name>: PRODUCED|NOT PRODUCED (<reason>)" from a uiautomator check of what is on screen.
# A state that cannot be produced is reported as NOT PRODUCED, never faked.
#
# This run: the Watch pill's built-in defaults of PR #30 (watch-defaults), with fake TV apps.
#
# Fake data only: invented events and games, the FakeSecrets dummy calendar URL, a fixed location. Helpers follow
# scripts/ci/emulator-qa.sh (a, sh_, ui_text, the Private DNS offline switch from its M7 Q1 block).
set -euo pipefail
umask 077

APK=${1:?qa-apk}; SHOT_DIR=${2:?shot-dir}; FAKE=${3:?fake-app-dir}
PKG=com.gnosh.tvhome
MAIN="$PKG/.AgendaActivity"
P="content://$PKG.provision"
DUMMY_TOKEN="private-$(printf 'f%.0s' $(seq 1 32))"
DUMMY_URL="https://calendar.google.com/calendar/ical/x%40example.com/$DUMMY_TOKEN/basic.ics"
mkdir -p "$SHOT_DIR"

fail() { echo "::error::$*"; exit 1; }
warn() { echo "::warning::$*"; }
a() { timeout 90 adb "$@"; }
sh_() { timeout 90 adb shell "$@" </dev/null; }

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
shot() { # $1 = n, $2 = name, $3 = verdict ("" = produced, else the reason it is not), $4 = a PNG already taken on the device
  local n=$1 name=$2 png="$SHOT_DIR/$1-$2.png" jpg
  jpg=$(mktemp --suffix=.jpg)
  if [[ -n ${4:-} ]]; then
    a exec-out cat "$4" > "$png" && [[ -s $png ]] || { RESULTS+=("TOUR $n $name: NOT PRODUCED (device capture missing)"); return 0; }
  else
    a exec-out screencap -p > "$png" || { RESULTS+=("TOUR $n $name: NOT PRODUCED (screencap failed)"); return 0; }
  fi
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

# ---- Watch pill defaults (PR #30 watch-defaults) -------------------------------------------------------------------
# No adb watch map, so only the built-in defaults decide. Fake code-free TV apps (scripts/ci/tour-fake-apps.sh):
# com.example.tourfake.cde "CDE", com.example.tourfake.cdetwo "CDE", com.example.tourfake.espn "ESPN".
sh_ content delete --uri "$P/watch" >/dev/null 2>&1 || true
quiet false >/dev/null 2>&1 || true
rmc panels/alerts.json; no_games
FOCUSED=""
CDE_A=com.example.tourfake.cde; CDE_B=com.example.tourfake.cdetwo; ESPN_F=com.example.tourfake.espn
for p_ in "$CDE_A" "$CDE_B" "$ESPN_F"; do a uninstall "$p_" >/dev/null 2>&1 || true; done
fake_install() { # $1 = apk name in $FAKE
  local o; o=$(a install -r "$FAKE/$1.apk" 2>&1 | tr -d '\r' || true)
  grep -q Success <<<"$o" || { warn "tour: install of fake $1 failed ($o)"; return 1; }
  echo "tour: installed fake $1"
}
preds_on() { # $1 = networks JSON list: Preds at 19:00 today (now 17:00)
  plant_games "$(game predators "$(at_local 0 19:00)" Blues true "$1")" "$(game ohio-state "$(at_local 4 11:00)" Penn\ State true '["FOX"]')"
}
replant() { evening_agenda; plant_weather "$1"; plant_brief; }
pill_desc() { { grep -oE 'content-desc="Watch the game on [^"]*"' <<<"$UI" | head -1 | sed 's/^content-desc="//; s/"$//'; } || true; }
pill_text() { { grep -oE 'text="Watch( on [^"]*)?"' <<<"$UI" | head -1 | sed 's/^text="//; s/"$//'; } || true; }
# Focus the pill: Down from the app row lands on the Calendar tile; Right goes Board, Ask Claude, then the pill (seen
# in the last run, where a focused="true" search on the pill's node never matched and Right went on to Quiet).
focus_pill() {
  key DPAD_DOWN; key DPAD_RIGHT; key DPAD_RIGHT; key DPAD_RIGHT
  look
  # Every focused node (the Compose host View reports focused too), and the node that carries the pill's description.
  { grep -oE '<node[^>]* focused="true"[^>]*>' <<<"$UI" | sed -E 's/ (bounds|index|package|checkable|checked|enabled|scrollable|long-clickable|password|selected)="[^"]*"//g; s/^/tour: focused: /'; } || true
  { grep -oE '<node[^>]*content-desc="Watch the game on [^>]*>' <<<"$UI" | sed -E 's/ (index|package|checkable|checked|enabled|scrollable|long-clickable|password|selected)="[^"]*"//g; s/^/tour: pill node: /'; } || true
  FOCUSED=$({ grep -oE '<node[^>]* focused="true"[^>]*>' <<<"$UI" | grep -oE 'content-desc="Watch the game on [^"]*"' | head -1 | sed 's/^content-desc="//; s/"$//'; } || true)
  echo "tour: focused pill description: '$FOCUSED'"
  echo "tour: Ask node: $({ grep -oE '<node[^>]*content-desc="Ask Claude[^>]*>' <<<"$UI" | head -1 | grep -oE '(content-desc|clickable|focusable|focused|bounds)="[^"]*"' | tr '\n' ' '; } || true)"
  echo "tour: Quiet node: $({ grep -oE '<node[^>]*content-desc="Quiet mode[^>]*>' <<<"$UI" | head -1 | grep -oE '(content-desc|clickable|focusable|focused|bounds)="[^"]*"' | tr '\n' ' '; } || true)"
}
top_pkg() { { resumed | head -1 | grep -oE '[a-z][a-z0-9_.]+/' | head -1 | tr -d /; } || true; }
# One pill case: $1 n, $2 name, $3 expected pill text ("" = no pill), $4 expected package, $5 network
pill_case() {
  local n=$1 name=$2 want=$3 pkg=$4 net=${5:-} v="" got txt top
  home; look
  got=$(pill_desc); txt=$(pill_text)
  echo "tour: $name: pill text '${txt}', description '${got}'"
  if [[ -z $want ]]; then
    [[ -z $got && -z $txt ]] || v="a Watch pill shows ('$txt')"
    has 'content-desc="Hero card: Predators vs Blues' || v="${v:+$v; }no Predators game-day card"
    shot "$n" "$name" "$v"
    return 0
  fi
  [[ -z $txt || $txt == "$want" ]] || v="pill text '$txt', expected '$want'"
  [[ $got == *", opens "* ]] || v="${v:+$v; }description does not name the app (label fell back to plain Watch)"
  [[ $got == "Watch the game on $net"* ]] || v="${v:+$v; }description '${got:-none}'"
  shot "$n" "$name" "$v"
  v=""
  focus_pill
  [[ $FOCUSED == "Watch the game on $net"* ]] || v="focused node description '$FOCUSED'"
  has 'content-desc="Ask Claude\. Talk or type a question\." clickable="true"[^>]* focusable="true"' || v="${v:+$v; }Ask description not on a clickable focusable node"
  has 'content-desc="Quiet mode, off\. Turn on to hide event titles\." clickable="true"[^>]* focusable="true"' || v="${v:+$v; }Quiet description not on a clickable focusable node"
  shot "$((n + 1))" "$name-focused" "$v"
  v=""
  key DPAD_CENTER; sleep 3
  top=$(top_pkg)
  echo "tour: OK on the pill opened ${top:-nothing} (expected $pkg)"
  [[ $top == "$pkg" ]] || v="OK opened '${top:-nothing}', expected $pkg"
  shot "$((n + 2))" "$name-launched" "$v"
}

v=""
local_clock 17 00 || warn "tour: clock not set to 17:00"
replant "$W_EVENING"

# (a) broadcast/local network, one CDE app installed: Watch opens CDE.
fake_install cde-a || true
preds_on '["SCRIPPS"]'
pill_case 1 watch-scripps-cde "Watch on CDE" "$CDE_A" SCRIPPS

# (b) streaming only (ESPN+), the ESPN app installed: Watch opens ESPN.
fake_install espn || true
replant "$W_EVENING"; preds_on '["ESPN+"]'
pill_case 4 watch-espnplus-espn "Watch on ESPN" "$ESPN_F" ESPN+

# (c) a network the defaults do not know: no pill.
replant "$W_EVENING"; preds_on '["Gnosh Sports Net"]'
pill_case 7 watch-unknown-network "" ""

# (d) two apps labelled CDE: ambiguous, no pill.
fake_install cde-b || true
replant "$W_EVENING"; preds_on '["SCRIPPS"]'
pill_case 8 watch-two-cde "" ""

for p_ in "$CDE_A" "$CDE_B" "$ESPN_F"; do a uninstall "$p_" >/dev/null 2>&1 || true; done

echo
echo "== Tour summary"
printf '%s\n' "${RESULTS[@]}"
