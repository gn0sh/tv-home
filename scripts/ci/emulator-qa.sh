#!/usr/bin/env bash
# T9 emulator QA (docs/qa/tv-home-m1.md). Runs against one booted Android TV emulator with the release APK
# (signed in CI with a throwaway key, never the TVHOME_* key).
#
# Usage: emulator-qa.sh <apk> <out-dir> <screenshot-dir>
#   out-dir:        logs and dumps (scanned for secrets; not uploaded)
#   screenshot-dir: only the home-screen PNG goes here (uploaded as an artifact)
#
# Uses a DUMMY calendar URL only. It equals the FakeSecrets constant, which scan-secrets.sh deliberately ignores,
# so this script also counts the dummy itself in every captured file.
#
# Fails fast: the first failed check prints ::error:: and exits 1. Captured logs are still scanned on the way out.
set -euo pipefail
umask 077

APK=${1:?apk}; OUT=${2:?out-dir}; SHOT_DIR=${3:?screenshot-dir}
PKG=com.gnosh.tvhome
MAIN="$PKG/.AgendaActivity"
PROVISION_ACT="$PKG/.ProvisionActivity"
URI="content://$PKG.provision/calendar_url"
MAX_COLD_MS=${MAX_COLD_MS:-2000}   # AC8
# Fake value that passes the validator. Built from parts so the full string is not in this file's text either.
DUMMY_TOKEN="private-$(printf 'f%.0s' $(seq 1 32))"
DUMMY_URL="https://calendar.google.com/calendar/ical/x%40example.com/$DUMMY_TOKEN/basic.ics"

mkdir -p "$OUT" "$SHOT_DIR"
exec > >(tee "$OUT/console.log") 2>&1

LOGCAT_PID=""; SCANNED=0
step() { echo; echo "== $*"; }
pass() { echo "PASS: $*"; }
note() { echo "::notice::$*"; }
warn() { echo "::warning::$*"; }
fail() { echo "::error::$*"; exit 1; }
a() { timeout 90 adb "$@"; }
sh_() { timeout 90 adb shell "$@"; }

pid_of() { sh_ pidof "$PKG" 2>/dev/null | tr -d '\r' || true; }
resumed() { sh_ dumpsys activity activities | tr -d '\r' | grep -E 'topResumedActivity=|ResumedActivity:' || true; }
in_front() { resumed | grep -q "$PKG/"; }

ui_text() { # prints the UI hierarchy, or nothing if uiautomator cannot get an idle state
  local _
  for _ in 1 2 3; do
    if sh_ uiautomator dump /sdcard/qa-ui.xml >/dev/null 2>&1; then
      sh_ cat /sdcard/qa-ui.xml | tr -d '\r'; sh_ rm -f /sdcard/qa-ui.xml || true; return 0
    fi
    sleep 2
  done
}

expect_text() { # $1 = label, $2 = fixed text (from ScreenModel.kt) the screen must show
  local xml; xml=$(ui_text)
  [[ -n $xml ]] || fail "$1: uiautomator dump failed 3 times; cannot confirm the screen shows \"$2\""
  printf '%s\n' "$xml" > "$OUT/ui-$1.xml"
  grep -qF "$2" <<<"$xml" || fail "$1: screen does not show \"$2\""
  pass "$1: screen shows \"$2\""
}

# Network off for the app only. The TV emulator's default network is wired (the first CI run showed "Active default
# network: 100" after svc wifi/data off and airplane mode), and those cannot take it down without root. So the app's
# uid is denied in firewall chain OEM_DENY_3 (`cmd connectivity`, API 34, shell holds NETWORK_SETTINGS), and both
# get-* commands must confirm it. If this image lacks those commands, the offline checks still run but are labelled
# AC8-offline NOT VERIFIED; if the commands exist and the deny is not confirmed, the job fails.
OFFLINE_VERIFIED=unknown
network() { # on|off
  local c p
  if [[ $1 == on ]]; then
    sh_ cmd connectivity set-package-networking-enabled true "$PKG" >/dev/null 2>&1 || true
    sh_ cmd connectivity set-chain3-enabled false >/dev/null 2>&1 || true
    p=$(sh_ cmd connectivity get-package-networking-enabled "$PKG" 2>&1 | tr -d '\r' || true)
    echo "network on: package networking enabled=${p:-?}"
    return 0
  fi
  c=$(sh_ cmd connectivity set-chain3-enabled true 2>&1 | tr -d '\r' || true)
  p=$(sh_ cmd connectivity set-package-networking-enabled false "$PKG" 2>&1 | tr -d '\r' || true)
  if grep -qiE 'unknown|usage|not supported' <<<"$c$p"; then
    OFFLINE_VERIFIED=no
    warn "AC8-offline NOT VERIFIED: this image has no per-package networking command ($c $p); offline checks run online"
    return 0
  fi
  sleep 2
  c=$(sh_ cmd connectivity get-chain3-enabled 2>&1 | tr -d '\r' || true)
  p=$(sh_ cmd connectivity get-package-networking-enabled "$PKG" 2>&1 | tr -d '\r' || true)
  echo "network off: chain3: $c | package networking enabled: $p"
  if ! grep -qiE 'enabled|true' <<<"$c" || grep -qiE 'disabled|false' <<<"$c"; then
    fail "network off: firewall chain OEM_DENY_3 is not enabled ($c)"
  fi
  grep -qiE 'false' <<<"$p" || fail "network off: $PKG still has networking ($p)"
  [[ $OFFLINE_VERIFIED == no ]] || OFFLINE_VERIFIED=yes
  pass "network off: $PKG is denied all networking (OEM_DENY_3)"
}

cold_start() { # $1 = label; prints the TotalTime of each of 3 cold starts and checks the median against AC8
  local out t times=()
  for _ in 1 2 3; do
    sh_ am force-stop "$PKG"; sleep 1
    out=$(sh_ am start -W -n "$MAIN" | tr -d '\r')
    grep -q '^Status: ok' <<<"$out" || { echo "$out"; fail "$1: am start -W did not report Status: ok"; }
    t=$(sed -n 's/^TotalTime: *\([0-9]*\).*/\1/p' <<<"$out")
    [[ -n $t ]] || { echo "$out"; fail "$1: no TotalTime in am start -W output"; }
    times+=("$t")
    sleep 2
    [[ -n $(pid_of) ]] || fail "$1: process died after launch"
    in_front || fail "$1: AgendaActivity is not in front after launch"
  done
  local median; median=$(printf '%s\n' "${times[@]}" | sort -n | sed -n 2p)
  echo "$1 cold start TotalTime ms: ${times[*]} (median $median, limit $MAX_COLD_MS)"
  echo "$1 ${times[*]} median=$median" >> "$OUT/cold-start.txt"
  (( median < MAX_COLD_MS )) || fail "$1: median cold start ${median} ms is not under ${MAX_COLD_MS} ms (AC8)"
  if [[ $OFFLINE_VERIFIED == yes ]]; then pass "$1: cold start median ${median} ms, app offline (AC8-offline VERIFIED)"
  else warn "$1: cold start median ${median} ms, but the app was NOT offline (AC8-offline NOT VERIFIED)"; fi
}

no_app_crash_yet() { # $1 = label
  local c; c=$(a logcat -d -b crash | tr -d '\r' | grep -c "$PKG" || true)
  [[ $c -eq 0 ]] || { a logcat -d -b crash > "$OUT/crash-unexpected.txt" || true; fail "$1: logcat -b crash has $c line(s) for $PKG"; }
}

final_scan() {
  [[ $SCANNED -eq 1 ]] && return 0; SCANNED=1
  [[ -n $LOGCAT_PID ]] && kill "$LOGCAT_PID" 2>/dev/null || true
  timeout 60 adb logcat -d -b all -v threadtime > "$OUT/logcat-dump.txt" 2>/dev/null || true
  timeout 60 adb logcat -d -b crash -v threadtime > "$OUT/logcat-crash.txt" 2>/dev/null || true
  timeout 60 adb shell dumpsys dropbox --print data_app_crash > "$OUT/dropbox-data_app_crash.txt" 2>/dev/null || true
  timeout 60 adb shell dumpsys activity > "$OUT/dumpsys-activity.txt" 2>/dev/null || true
  local rc=0 f n
  echo; echo "== secret scan (scan-secrets.sh, then the dummy value itself)"
  scripts/ci/scan-secrets.sh "$OUT" || rc=1
  # scan-secrets ignores the FakeSecrets token on purpose, so count the dummy here. console.log is excluded: it is
  # still being written, and this script never prints the dummy.
  while IFS= read -r -d '' f; do
    [[ $f == "$OUT/console.log" ]] && continue
    n=$( { grep -aoF -e "$DUMMY_TOKEN" -e 'x%40example.com' -e 'x@example.com' -- "$f" || true; } | wc -l)
    if [[ $n -gt 0 ]]; then echo "::error::dummy calendar URL found $n time(s) in ${f#"$OUT"/}"; rc=1; fi
  done < <(find "$OUT" -type f -print0)
  [[ $rc -eq 0 ]] && pass "no secret pattern and no dummy value in captured logs, dropbox or dumpsys"
  return $rc
}
on_exit() { local rc=$?; declare -F m3_cleanup >/dev/null && m3_cleanup; final_scan || rc=1; exit $rc; }
trap on_exit EXIT

step "device"
echo "sdk=$(sh_ getprop ro.build.version.sdk | tr -d '\r') type=$(sh_ getprop ro.build.type | tr -d '\r') features: $(sh_ pm list features | tr -d '\r' | grep -c leanback || true) leanback"
a logcat -b all -c || true
a logcat -G 8M || true
adb logcat -b main,system,crash,events -v threadtime > "$OUT/logcat-stream.txt" 2>/dev/null &
LOGCAT_PID=$!

step "T9 install and launcher entry"
# --no-incremental: an .idsig next to the APK makes adb use incremental install, whose output and status differ.
inst=$(a install --no-incremental -r -g "$APK" 2>&1 | tr -d '\r') && irc=0 || irc=$?
echo "$inst"
if [[ $irc -ne 0 ]] || ! grep -q 'Success' <<<"$inst"; then fail "adb install failed (exit $irc)"; fi
sh_ pm path "$PKG" | grep -q '^package:' || fail "$PKG not installed"
r=$(sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER "$PKG" | tr -d '\r' | tail -1)
[[ $r == "$MAIN" || $r == "$PKG/com.gnosh.tvhome.AgendaActivity" ]] || fail "LEANBACK_LAUNCHER does not resolve to AgendaActivity (got: $r)"
pass "installed; LEANBACK_LAUNCHER resolves to $r"
# snow-uiautomator-idle: the Christmas hero animates; uiautomator waits for an idle UI. The app stops its animation when
# the system animator scale is 0, so freeze it for the whole run.
sh_ settings put global animator_duration_scale 0 >/dev/null 2>&1 || warn "could not set animator_duration_scale 0; uiautomator dumps may time out on an animated hero"
# M1 is "usable from any launcher" (spec M1); becoming Home is A2/M2. Report, don't fail.
h=$(sh_ cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | grep -c "$PKG/" || true)
note "HOME candidates from $PKG: $h (M1 manifest declares none; expected 0)"

step "AC8 cold start, network off, no cache, not set up"
sh_ pm clear "$PKG" >/dev/null || true
network off
cold_start "offline-not-set-up"
expect_text "offline-not-set-up" "Calendar not set up"
a exec-out screencap -p > "$SHOT_DIR/tv-home-home.png"
sig=$(head -c 8 "$SHOT_DIR/tv-home-home.png" | od -An -tx1 | tr -d ' \n')
size=$(wc -c < "$SHOT_DIR/tv-home-home.png")
[[ $sig == 89504e470d0a1a0a && $size -gt 10000 ]] || fail "screenshot is not a PNG or too small ($size bytes)"
pass "screenshot saved ($size bytes)"
no_app_crash_yet "cold start"

step "AC5 D-pad/Back walk (no focus trap, no crash)"
before=$(pid_of)
# No DPAD_CENTER here: on the home screen OK opens the full-screen calendar or launches an app (checked below).
WALK="DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_UP DPAD_UP DPAD_RIGHT DPAD_RIGHT DPAD_LEFT DPAD_DOWN DPAD_UP DPAD_LEFT DPAD_LEFT"
for k in $WALK; do
  sh_ input keyevent "KEYCODE_$k"; sleep 0.4
done
sleep 1
[[ -n $before && $(pid_of) == "$before" ]] || fail "process changed or died during the D-pad walk"
in_front || fail "AgendaActivity left the foreground during the D-pad walk"
pass "$(wc -w <<<"$WALK") D-pad keys: still in front, same process"
sh_ input keyevent KEYCODE_BACK; sleep 2
if in_front; then sh_ input keyevent KEYCODE_BACK; sleep 2; fi
in_front && fail "Back does not leave the app (focus/Back trap)"
pass "Back leaves the app (now: $(resumed | head -1 | sed 's/^ *//'))"
no_app_crash_yet "D-pad walk"

# Other TV launcher apps on this image: the app row's tiles. Which is first depends on the image, so it is only reported.
others=$(sh_ cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER \
  | tr -d '\r' | sed 's/^[[:space:]]*//' | grep '/' | grep -v "^$PKG/" || true)
echo "other TV launcher entries:"; printf '%s\n' "$others" | sed 's/^/  /'

step "M2c dock: DPAD_DOWN reaches the Calendar tile, OK opens the full screen, Back returns to it"
# Home is two rows: the app carousel, and below it the dock with the Calendar tile. Initial focus is the first app tile
# (or the Calendar tile when the row has no apps), so DPAD_DOWN reaches the Calendar tile either way. The dock does not
# depend on other apps being installed. Still "not set up" here (no URL yet), so the full screen shows that message
# and the panel itself holds focus.
sh_ am force-stop "$PKG"; sh_ am start -W -n "$MAIN" >/dev/null; sleep 4
in_front || fail "M2b: home screen not in front before OK"
expect_text "m2b-home" "Calendar, "            # the tile's description: "Calendar, <weekday date>. <status>."
xml=$(ui_text)
[[ -n $xml ]] || fail "M2b: uiautomator dump failed before OK; cannot confirm the full screen is closed"
grep -qF "Calendar full screen" <<<"$xml" && fail "M2b: full-screen calendar is showing before OK"
if [[ -z $others ]]; then
  warn "M2c: no other TV apps on this image, so focus starts on the Calendar tile and DPAD_DOWN from the app row is NOT VERIFIED"
else
  # Make sure the app row has tiles (initial focus on the first app) before Down; otherwise focus would already be on
  # the Calendar tile and this step would pass without testing Down. The row shows a message instead of tiles while
  # apps load, fail, or none are found.
  rowmsg=""
  for _ in 1 2 3; do
    xml=$(ui_text)
    [[ -n $xml ]] || fail "M2c: uiautomator dump failed; cannot confirm the app row has tiles"
    rowmsg=$(grep -oE 'Loading apps|Couldn.{1,6}t list apps|No TV apps found' <<<"$xml" | head -1 || true)
    [[ -z $rowmsg ]] && break
    sleep 2
  done
  printf '%s\n' "$xml" > "$OUT/ui-m2c-app-row.xml"
  [[ -z $rowmsg ]] || fail "M2c: the app row still shows \"$rowmsg\" although this image has other TV apps"
  pass "M2c: the app row shows tiles (no loading/failed/empty message), so initial focus is on an app tile"
fi
sh_ input keyevent KEYCODE_DPAD_DOWN; sleep 1
sh_ input keyevent KEYCODE_DPAD_CENTER; sleep 2
in_front || fail "M2b: OK after DPAD_DOWN left the app (focus did not reach the Calendar tile; an app opened?)"
expect_text "m2b-full" "Calendar full screen"
expect_text "m2b-full-message" "Calendar not set up"
before=$(pid_of)
for k in DPAD_DOWN DPAD_DOWN DPAD_UP DPAD_LEFT DPAD_RIGHT; do sh_ input keyevent "KEYCODE_$k"; sleep 0.4; done
[[ -n $before && $(pid_of) == "$before" ]] || fail "M2b: process changed or died during D-pad on the full-screen calendar"
in_front || fail "M2b: D-pad on the full-screen calendar left the app"
expect_text "m2b-full-after-dpad" "Calendar full screen"
sh_ input keyevent KEYCODE_BACK; sleep 2
in_front || fail "M2b: Back on the full-screen calendar left the app (it should return to the row)"
xml=$(ui_text); printf '%s\n' "$xml" > "$OUT/ui-m2b-back.xml"
[[ -n $xml ]] || fail "M2b: uiautomator dump failed after Back"
grep -qF "Calendar full screen" <<<"$xml" && fail "M2b: Back did not close the full-screen calendar"
pass "M2b: Back closed the full-screen calendar and the app stayed in front"
# Focus is back on the Calendar tile if OK opens the full screen again (uiautomator's focused flag is not relied on).
# With apps present this also shows focus did not go to the first app tile (OK there would open that app).
sh_ input keyevent KEYCODE_DPAD_CENTER; sleep 2
in_front || fail "M2b: OK after Back left the app: focus went back to an app tile, not the Calendar tile"
expect_text "m2b-refocus" "Calendar full screen"
pass "M2b: focus was restored to the Calendar tile (OK reopened the full screen)"
sh_ input keyevent KEYCODE_BACK; sleep 2
in_front || fail "M2b: second Back on the full-screen calendar left the app"
sh_ input keyevent KEYCODE_BACK; sleep 2
if in_front; then
  echo "diagnostic: top resumed: $(resumed | head -1 | sed 's/^ *//')"
  xml=$(ui_text); printf '%s\n' "$xml" > "$OUT/ui-m2b-back-home.xml"
  if [[ -z $xml ]]; then echo "diagnostic: uiautomator dump failed"
  elif grep -qF "Calendar full screen" <<<"$xml"; then echo "diagnostic: \"Calendar full screen\" IS showing"
  else echo "diagnostic: \"Calendar full screen\" is not showing (home row in front)"; fi
  fail "M2b: one Back on the home row does not leave the app"
fi
pass "M2b: Back on the home row leaves the app"
note "M2b with event rows (focus on the first row, Up/Down through rows, scrolling) is NOT VERIFIED here: the emulator only has the dummy URL, so it never shows rows"
no_app_crash_yet "M2b calendar"

step "M1b/M2d Ask Claude button: opens the Ask view, shows not set up, Back returns to the button, one Back exits"
# M2d: the Ask Claude pill sits bottom centre, below the dock (Calendar only): DPAD_DOWN from the app row reaches
# Calendar, DPAD_DOWN again the button (with no apps installed focus starts on Calendar and the second Down is a
# no-op on the button). No API key
# is ever put on the emulator, so the view must say it isn't set up and send nothing.
sh_ am force-stop "$PKG"; sh_ am start -W -n "$MAIN" >/dev/null; sleep 4
in_front || fail "M1b: home screen not in front"
expect_text "m1b-home" "Ask Claude. Talk or type a question."
sh_ input keyevent KEYCODE_DPAD_DOWN; sleep 1
sh_ input keyevent KEYCODE_DPAD_DOWN; sleep 1
sh_ input keyevent KEYCODE_DPAD_CENTER; sleep 2
in_front || fail "M1b: OK on the Ask Claude button left the app"
expect_text "m1b-ask" "Ask Claude full screen"
expect_text "m1b-not-set-up" "set up yet"           # "Ask Claude isn't set up yet" (the apostrophe is escaped in the dump)
sh_ input keyevent KEYCODE_BACK; sleep 2
in_front || fail "M1b: Back on the Ask view left the app (it should return to the home screen)"
xml=$(ui_text); printf '%s\n' "$xml" > "$OUT/ui-m1b-back.xml"
[[ -n $xml ]] || fail "M1b: uiautomator dump failed after Back"
grep -qF "Ask Claude full screen" <<<"$xml" && fail "M1b: Back did not close the Ask view"
# Focus went back to the Ask button if OK opens the Ask view again (not the calendar, not an app).
sh_ input keyevent KEYCODE_DPAD_CENTER; sleep 2
in_front || fail "M1b: OK after Back left the app: focus was not on the Ask button"
expect_text "m1b-refocus" "Ask Claude full screen"
pass "M1b: Back returned focus to the Ask Claude button (OK reopened the Ask view)"
sh_ input keyevent KEYCODE_BACK; sleep 2
in_front || fail "M1b: second Back on the Ask view left the app"
sh_ input keyevent KEYCODE_BACK; sleep 2
if in_front; then
  echo "diagnostic: top resumed: $(resumed | head -1 | sed 's/^ *//')"
  fail "M1b: one Back with focus on the Ask button does not leave the app"
fi
pass "M1b: one Back with focus on the Ask button leaves the app"
note "M1b speech input, the Claude API call, answers, Open and the follow-up are NOT VERIFIED here: no API key or recognizer on the emulator (JVM tests in :core cover the logic; the device check is Brian's)"
no_app_crash_yet "M1b ask"

# Return to our home screen from an app we opened. Third-party apps may consume Back (YouTube TV shows an exit
# dialog), so Back is tried first and then the app is force-stopped; either way our task must be underneath and resume.
return_home() { # $1 = label, $2 = launched package
  local how="" _
  for _ in 1 2; do sh_ input keyevent KEYCODE_BACK; sleep 3; if in_front; then how="Back"; break; fi; done
  if [[ -z $how ]]; then
    note "$1: $2 kept Back; force-stopping it to check our task is underneath"
    sh_ am force-stop "$2"; sleep 3
    in_front && how="force-stop of $2"
  fi
  [[ -n $how ]] || fail "$1: home screen did not return after Back or force-stop of $2 (now: $(resumed | head -1 | sed 's/^ *//'))"
  pass "$1: home screen resumed after $how"
}
LAUNCHED=""
expect_app_opened() { # $1 = label; sets LAUNCHED (not a $(...) capture, so fail's ::error:: line is printed)
  local top launched
  top=$(resumed | head -1 | sed 's/^ *//')
  in_front && fail "$1: OK did not open another app (still in front: $top)"
  launched=$(grep -oE '[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+' <<<"$top" | head -1 | cut -d/ -f1)
  [[ -n $launched ]] || fail "$1: cannot tell which app is in front ($top)"
  cut -d/ -f1 <<<"$others" | grep -qxF "$launched" || fail "$1: front app $launched is not one of the TV launcher apps"
  LAUNCHED=$launched
}

step "M2 app row: OK on the initial focus (first app tile) opens it; Down then Up returns to the app row"
if [[ -z $others ]]; then
  warn "M2 launch, M2c Up and single Back from an app tile NOT VERIFIED: this image has no other LEANBACK_LAUNCHER app, so the app row is empty"
else
  sh_ am force-stop "$PKG"; sh_ am start -W -n "$MAIN" >/dev/null; sleep 4
  in_front || fail "M2: home screen not in front before OK"
  sh_ input keyevent KEYCODE_DPAD_CENTER; sleep 5
  expect_app_opened "M2"
  pass "M2: OK on the first app tile (initial focus) opened $LAUNCHED"
  return_home "M2" "$LAUNCHED"
  # Down to the dock, Up back to the app row: OK must open an app again, not the full-screen calendar.
  sh_ input keyevent KEYCODE_DPAD_DOWN; sleep 1
  sh_ input keyevent KEYCODE_DPAD_UP; sleep 1
  sh_ input keyevent KEYCODE_DPAD_CENTER; sleep 5
  expect_app_opened "M2c"
  pass "M2c: Down then Up returned focus to the app row (OK opened $LAUNCHED; OK alone can re-enter the row, so the re-entry step below checks the focused tile)"
  return_home "M2c" "$LAUNCHED"
  # Focus is now on an app tile inside the carousel: one Back must leave the app (home-back-twice).
  sh_ input keyevent KEYCODE_BACK; sleep 2
  in_front && fail "M2c: one Back with focus on an app tile does not leave the app (now: $(resumed | head -1 | sed 's/^ *//'))"
  pass "M2c: one Back with focus on an app tile leaves the app"
  no_app_crash_yet "M2 launch"
fi

# The focused node in the UI dump, as "content-desc|x1|x2" (empty when nothing on screen holds input focus).
focused_node() {
  local xml node
  xml=$(ui_text)
  # An empty dump is a failed dump (ui_text retried already), not "nothing has focus": say so, so a flaky uiautomator
  # is not read as a focus bug (reentry-qa-flaky-dump). Runs in a $(...) subshell, so the error goes to stderr (which
  # is the log too) and the non-zero exit makes the caller's assignment fail under set -e.
  if [[ -z $xml ]]; then
    echo "::error::UI dump failed: uiautomator gave no hierarchy after its retries, so focus cannot be read" >&2
    exit 1
  fi
  printf '%s\n' "$xml" > "$OUT/ui-focus-last.xml"
  node=$(grep -oE '<node [^>]*focused="true"[^>]*>' <<<"$xml" | head -1 || true)
  [[ -n $node ]] || return 0
  local desc b fx1 fy1 fx2 fy2 n nx1 ny1 nx2 ny2
  desc=$(sed -E 's/.*content-desc="([^"]*)".*/\1/' <<<"$node")
  b=$(sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' <<<"$node")
  read -r fx1 fy1 fx2 fy2 <<<"$b"
  # Compose can report the description on a node inside the focused one (the Calendar tile does): then use the first
  # described node whose bounds lie within the focused node's bounds.
  if [[ -z $desc ]]; then
    while IFS= read -r n; do
      read -r nx1 ny1 nx2 ny2 <<<"$(sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' <<<"$n")"
      if (( nx1 >= fx1 && ny1 >= fy1 && nx2 <= fx2 && ny2 <= fy2 )); then
        desc=$(sed -E 's/.*content-desc="([^"]*)".*/\1/' <<<"$n"); break
      fi
    done < <(grep -oE '<node [^>]*content-desc="[^"]+"[^>]*>' <<<"$xml" || true)
  fi
  printf '%s|%s|%s\n' "$desc" "$fx1" "$fx2"
}
SCREEN_W=$(sh_ wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f1)
on_screen() { # $1 = focused_node output: the node lies inside the screen horizontally (the row scrolled to it)
  local x1 x2
  IFS='|' read -r _ x1 x2 <<<"$1"
  [[ -n $x1 && -n $x2 && $x1 -ge 0 && $x2 -le ${SCREEN_W:-1920} && $x2 -gt $x1 ]]
}
key() { sh_ input keyevent "KEYCODE_$1"; sleep 1.2; }
# After a key: the focused node, read again once after a pause if nothing held focus (a dump taken mid-change);
# if still nothing, print what the dump does hold (descriptions and focus flags of focusable nodes) for diagnosis.
focus_after() { # $1 = label
  local cur
  cur=$(focused_node) || exit 1   # a failed dump ends the subshell; set -e is not inherited into $(...)
  if [[ -z $cur ]]; then sleep 2; cur=$(focused_node) || exit 1; fi
  if [[ -z $cur ]]; then
    echo "diagnostic: $1: dump OK but no node has focused=true; focusable nodes:" >&2
    grep -oE '<node [^>]*focusable="true"[^>]*>' "$OUT/ui-focus-last.xml" \
      | sed -E 's/.*content-desc="([^"]*)".*focused="([a-z]+)".*bounds="([^"]*)".*/  desc=\1 focused=\2 bounds=\3/' >&2 || true
  fi
  printf '%s\n' "$cur"
}

ASK_DESC_PREFIX="Ask Claude"
step "Row re-entry (fix-app-row-scroll): scroll right, leave the row, come back Up, Right still moves the row"
# Brian's bug after M2d: the row scrolled on first open, but once focus left it (Down to Calendar or the Ask button)
# Up put focus on the row's invisible scroll container, and Right did nothing. Checked on the focused node of the UI
# dump, not by pressing OK: on Android OK (FocusDirection.Enter) re-enters the row by itself and hid the bug.
napps=$(printf '%s\n' "$others" | grep -c '/' || true)
if (( napps < 2 )); then
  warn "Row re-entry NOT VERIFIED: $napps other TV app(s) on this image, the row cannot move"
else
  sh_ am force-stop "$PKG"; sh_ am start -W -n "$MAIN" >/dev/null; sleep 4
  in_front || fail "re-entry: home screen not in front"
  prev=$(focused_node)
  [[ -n $prev ]] || fail "re-entry: no focused node on launch (expected the first app tile)"
  steps=$(( napps - 1 < 4 ? napps - 1 : 4 ))
  for ((i = 1; i <= steps; i++)); do
    key DPAD_RIGHT
    cur=$(focus_after re-entry)
    [[ -n $cur && ${cur%%|*} != "${prev%%|*}" ]] || fail "re-entry: Right $i did not move focus along the row (was '${prev%%|*}', now '${cur%%|*}')"
    on_screen "$cur" || fail "re-entry: Right $i focused '${cur%%|*}' off screen ($cur): the row did not scroll"
    prev=$cur
  done
  far=${prev%%|*}
  pass "re-entry: Right x$steps moved focus to '$far' and kept it on screen"
  (( napps >= 5 )) || warn "re-entry: only $napps apps, so the row may not overflow the screen; scrolling itself NOT VERIFIED here"
  key DPAD_DOWN
  cur=$(focus_after re-entry)
  # 2D search picks the dock target nearest below: the Calendar tile, or the Ask button from a tile far right.
  case ${cur%%|*} in
    Calendar,*) ;;
    "${ASK_DESC_PREFIX}"*|"")
      # The Ask button clears its semantics, so uiautomator shows no focused node while it has focus: confirm it with
      # OK (the Ask view opens) and Back (focus returns to the button, checked in M1b), then go Up to the Calendar tile.
      if [[ -z ${cur%%|*} ]]; then
        key DPAD_CENTER; sleep 1
        expect_text "re-entry-ask" "Ask Claude full screen"
        key BACK; sleep 1
        in_front || fail "re-entry: Back from the Ask view left the app"
      fi
      pass "re-entry: Down from '$far' reached the Ask button"; key DPAD_UP; cur=$(focus_after re-entry)
      [[ ${cur%%|*} == Calendar,* ]] || fail "re-entry: Up from the Ask button did not reach the Calendar tile (now '$cur')" ;;
    *) fail "re-entry: Down from '$far' did not reach the dock (focused node: '$cur')" ;;
  esac
  key DPAD_UP
  cur=$(focus_after re-entry)
  [[ ${cur%%|*} == "$far" ]] || fail "re-entry: Up from Calendar did not return to '$far' (now '${cur%%|*}'; empty means focus went to the row container)"
  on_screen "$cur" || fail "re-entry: '$far' is off screen after Up ($cur)"
  key DPAD_LEFT
  cur=$(focus_after re-entry)
  [[ -n $cur && ${cur%%|*} != "$far" ]] || fail "re-entry: Left after coming back did not move focus (now '${cur%%|*}')"
  back=${cur%%|*}
  pass "re-entry: Down, Up returned to '$far', and Left moved on to '$back'"
  # Same through the Ask button: Down, Down, Up (to Calendar), Up (to the row), Right moves.
  key DPAD_DOWN; key DPAD_DOWN
  cur=$(focus_after re-entry)
  if [[ -z ${cur%%|*} ]]; then  # the Ask button hides focus from uiautomator: confirm with OK, then Back
    key DPAD_CENTER; sleep 1
    expect_text "re-entry-ask-2" "Ask Claude full screen"
    key BACK; sleep 1
    in_front || fail "re-entry: Back from the Ask view left the app"
  else
    [[ ${cur%%|*} == "Ask Claude. Talk or type a question." ]] || fail "re-entry: Down, Down did not reach the Ask button (now '${cur%%|*}')"
  fi
  key DPAD_UP
  cur=$(focus_after re-entry)
  [[ ${cur%%|*} == Calendar,* ]] || fail "re-entry: Up from the Ask button did not reach Calendar (now '${cur%%|*}')"
  key DPAD_UP
  cur=$(focus_after re-entry)
  [[ ${cur%%|*} == "$back" ]] || fail "re-entry: Up from Calendar (via the Ask button) did not return to '$back' (now '${cur%%|*}')"
  key DPAD_RIGHT
  cur=$(focus_after re-entry)
  if [[ -z $cur || ${cur%%|*} == "$back" ]] || ! on_screen "$cur"; then
    fail "re-entry: Right after the Ask-button round trip did not move the row (now '${cur%%|*}')"
  fi
  pass "re-entry: Ask button -> Calendar -> row, and Right moved to '${cur%%|*}'"
  sh_ input keyevent KEYCODE_BACK; sleep 2
  in_front && fail "re-entry: one Back on an app tile after re-entry does not leave the app (home-back-twice)"
  pass "re-entry: one Back leaves the app"
  no_app_crash_yet "row re-entry"
fi

hero_text() { # the "Hero title: <label>" description in the UI dump (also defined as hero_title for the Hero step)
  ui_text | grep -oE 'content-desc="Hero title: [^"]*"' | head -1 | sed -E 's/^content-desc="Hero title: (.*)"$/\1/' || true
}
step "Row edit (M2g): long-press OK opens the menu, Move right reorders, Hide hides, the Hidden tile restores"
# The tile menu (a dialog) opens with focus on its first entry, Cancel; Move left is one Down from there, Move right
# two, Hide four. The menu entries are plain text nodes, so they are checked with expect_text, and chosen by key count.
# OK is held with `input keyevent --longpress` (press, a repeat flagged long-press after the timeout, release); API 34's
# input tool has no --duration, which was silently a short press. The release lands after the menu opened, so the menu
# must ignore it and choose nothing (menu-held-ok-repeat).
long_press_ok() { sh_ input keyevent --longpress KEYCODE_DPAD_CENTER; sleep 2; }
key_trace() { # the dispatcher's recent OK-key events and the input tool's keyevent usage, for a long press that misfired
  echo "--- recent DPAD_CENTER events (dumpsys input):"; sh_ dumpsys input | tr -d '\r' | grep -E 'KeyEvent.*keyCode=(23|DPAD_CENTER|KEYCODE_DPAD_CENTER)' | tail -20 || true
  echo "--- input keyevent usage:"; sh_ input 2>&1 | tr -d '\r' | grep -iA2 'keyevent' || true
}
if (( napps < 2 )); then
  warn "Row edit NOT VERIFIED: $napps other TV app(s) on this image, a reorder needs at least 2"
else
  sh_ am force-stop "$PKG"; sh_ am start -W -n "$MAIN" >/dev/null; sleep 4
  in_front || fail "row-edit: home screen not in front"
  first=$(focus_after row-edit); first_name=${first%%|*}; first_x=$(cut -d'|' -f2 <<<"$first")
  [[ -n $first_name ]] || fail "row-edit: no focused tile on launch (expected the first app tile)"
  key DPAD_RIGHT; second=$(focus_after row-edit); second_name=${second%%|*}
  [[ -n $second_name && $second_name != "$first_name" ]] || fail "row-edit: Right did not reach a second tile (now '$second_name')"
  key DPAD_LEFT
  # Long-press OK on the first tile: the menu shows all five entries.
  long_press_ok
  in_front || { key_trace; fail "row-edit: the long press left the app (it launched the tile instead of opening the menu)"; }
  for t in "Cancel" "Move left" "Move right" "Move to front" "Hide"; do expect_text "row-edit-menu" "$t"; done
  # Back closes the menu and focus returns to the same tile, which did not move during the hold.
  key BACK
  cur=$(focus_after row-edit)
  [[ ${cur%%|*} == "$first_name" ]] || fail "row-edit: after Back from the menu focus is on '${cur%%|*}', not '$first_name'"
  [[ $(cut -d'|' -f2 <<<"$cur") == "$first_x" ]] || fail "row-edit: the held OK moved '$first_name' without a choice ($cur)"
  pass "row-edit: Back closed the menu, the hold chose nothing, focus returned to '$first_name'"
  # Move right: the first tile swaps with the second and focus follows it.
  long_press_ok; expect_text "row-edit-held" "Move right"   # still open: the hold chose nothing
  key DPAD_DOWN; key DPAD_DOWN; key DPAD_CENTER; sleep 1
  cur=$(focus_after row-edit)
  [[ ${cur%%|*} == "$first_name" ]] || fail "row-edit: after Move right focus is on '${cur%%|*}', not the moved tile '$first_name'"
  moved_x=$(cut -d'|' -f2 <<<"$cur")
  (( moved_x > first_x )) || fail "row-edit: '$first_name' did not move right (x $first_x -> $moved_x)"
  on_screen "$cur" || fail "row-edit: the moved tile is off screen ($cur)"
  key DPAD_LEFT; cur=$(focus_after row-edit)
  [[ ${cur%%|*} == "$second_name" ]] || fail "row-edit: Left of the moved tile is '${cur%%|*}', expected '$second_name' (now first)"
  # The hero title follows the focused tile.
  [[ $(hero_text) == "$second_name" ]] || fail "row-edit: hero title is '$(hero_text)', expected '$second_name'"
  pass "row-edit: Move right put '$first_name' after '$second_name', focus followed, hero title updated"
  # Restore the order: Right back onto the moved tile, long-press, Move left (one Down from Cancel).
  key DPAD_RIGHT; long_press_ok; key DPAD_DOWN; key DPAD_CENTER; sleep 1
  cur=$(focus_after row-edit)
  [[ ${cur%%|*} == "$first_name" && $(cut -d'|' -f2 <<<"$cur") == "$first_x" ]] || fail "row-edit: Move left did not restore '$first_name' to the front ($cur)"
  pass "row-edit: Move left restored the original order"

  # Hide the first tile: it is gone from the row, and a "Hidden apps (1)" tile is at the end.
  long_press_ok; key DPAD_DOWN; key DPAD_DOWN; key DPAD_DOWN; key DPAD_DOWN; key DPAD_CENTER; sleep 1
  ui=$(ui_text); [[ -n $ui ]] || fail "row-edit: uiautomator dump failed after Hide"
  printf '%s\n' "$ui" > "$OUT/ui-row-edit-hidden.xml"
  grep -qF "content-desc=\"$first_name\"" <<<"$ui" && fail "row-edit: '$first_name' is still on the row after Hide"
  pass "row-edit: '$first_name' is gone from the row after Hide"
  cur=$(focus_after row-edit)
  [[ -n ${cur%%|*} ]] || fail "row-edit: no tile has focus after Hide"
  # The Hidden tile is the last one: walk Right until it is focused (it may be scrolled out of the composed items).
  found=""
  for _ in $(seq 1 $((napps + 1))); do
    cur=$(focus_after row-edit)
    if [[ ${cur%%|*} == "Hidden apps (1)" ]]; then found=1; break; fi
    key DPAD_RIGHT
  done
  [[ -n $found ]] || fail "row-edit: no 'Hidden apps (1)' tile at the end of the row after Hide"
  on_screen "$cur" || fail "row-edit: the Hidden tile is off screen ($cur)"
  pass "row-edit: 'Hidden apps (1)' is the last tile"
  # OK opens the list with the hidden app; OK on it puts it back, and (it was the last one) the list closes.
  key DPAD_CENTER; sleep 1
  expect_text "row-edit-hidden-list" "$first_name"
  key DPAD_CENTER; sleep 2
  cur=$(focus_after row-edit)
  [[ ${cur%%|*} == "$first_name" && $(cut -d'|' -f2 <<<"$cur") == "$first_x" ]] || fail "row-edit: after un-hiding, '$first_name' is not first and focused ($cur)"
  ui=$(ui_text); grep -qF 'content-desc="Hidden apps' <<<"$ui" && fail "row-edit: the Hidden tile is still there with no hidden apps"
  pass "row-edit: un-hiding restored '$first_name' to the front and removed the Hidden tile (original state)"
  # One Back from the row still leaves the app (home-back-twice): the menu and list are dialogs, not focus targets.
  sh_ input keyevent KEYCODE_BACK; sleep 2
  in_front && fail "row-edit: one Back on the home row after editing does not leave the app (home-back-twice)"
  pass "row-edit: one Back leaves the app"
  no_app_crash_yet "row edit"
fi

step "Hero (M2e): the hero title matches the focused tile after Right x2"
# The hero title carries the content description "Hero title: <label>"; the focused app tile's description is its label.
hero_title() {
  local xml; xml=$(ui_text)
  if [[ -z $xml ]]; then echo "::error::UI dump failed: uiautomator gave no hierarchy after its retries, so the hero title cannot be read" >&2; exit 1; fi
  grep -oE 'content-desc="Hero title: [^"]*"' <<<"$xml" | head -1 | sed -E 's/^content-desc="Hero title: (.*)"$/\1/' || true
}
if (( napps < 3 )); then
  warn "Hero title check NOT VERIFIED: $napps other TV app(s) on this image, Right x2 has nowhere to go"
else
  sh_ am force-stop "$PKG"; sh_ am start -W -n "$MAIN" >/dev/null; sleep 4
  in_front || fail "hero: home screen not in front"
  key DPAD_RIGHT; key DPAD_RIGHT
  cur=$(focused_node); want=${cur%%|*}
  [[ -n $want ]] || fail "hero: no focused tile after Right x2"
  got=$(hero_title)
  [[ -n $got ]] || fail "hero: no node with a 'Hero title: ...' description in the UI dump"
  [[ $got == "$want" ]] || fail "hero: title is '$got' but the focused tile is '$want'"
  pass "hero: after Right x2 the title '$got' matches the focused tile"
  sh_ input keyevent KEYCODE_BACK; sleep 2
  in_front && fail "hero: one Back with the hero showing does not leave the app (home-back-twice)"
  pass "hero: one Back leaves the app"
  no_app_crash_yet "hero"
fi

step "M3 Home and mic: off as installed; the takeover commands make Home and the mic open the dashboard; rollback restores"
# The same adb commands scripts/tv-home-takeover.ps1 runs on Brian's TV.
home_now() { sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | grep / | tail -1 || true; }
other_app() { sh_ am start -W -a android.settings.SETTINGS >/dev/null 2>&1 || true; sleep 3; }
stock_home=$(home_now)
# If a check below fails mid-step, put the emulator back (m3-dirty-on-fail), in case it is reused.
m3_dirty=1
m3_cleanup() {
  [[ -n ${m3_dirty:-} ]] || return 0
  sh_ cmd package set-home-activity "$stock_home" >/dev/null 2>&1 || true
  sh_ pm disable "$PKG/.HomeEntry" >/dev/null 2>&1 || true
  sh_ settings delete secure enabled_accessibility_services >/dev/null 2>&1 || true
  sh_ settings put secure accessibility_enabled 0 >/dev/null 2>&1 || true
}
[[ -n $stock_home && $stock_home != "$PKG/"* ]] || fail "m3: home is '$stock_home' before the takeover (HomeEntry must be off as installed)"
pass "m3: as installed, Home is still the TV's own ($stock_home)"
sh_ pm enable "$PKG/.HomeEntry" >/dev/null
sh_ cmd package set-home-activity "$PKG/.HomeEntry" >/dev/null
[[ $(home_now) == "$PKG/.HomeEntry" ]] || fail "m3: after set-home-activity, home is '$(home_now)'"
other_app
in_front && fail "m3: Settings did not come to the front (test setup)"
sh_ input keyevent KEYCODE_HOME; sleep 3
in_front || fail "m3: Home from another app did not bring the dashboard to the front ($(resumed))"
pass "m3: Home from another app opens the dashboard"
if (( napps >= 2 )); then
  start=$(focus_after m3); start_name=${start%%|*}
  key DPAD_RIGHT; key DPAD_RIGHT
  sh_ input keyevent KEYCODE_HOME; sleep 3
  cur=$(focus_after m3)
  [[ ${cur%%|*} == "$start_name" ]] || fail "m3: Home on the dashboard put focus on '${cur%%|*}', not the first tile '$start_name'"
  pass "m3: Home on the dashboard returns focus to the first tile ('$start_name')"
fi
key DPAD_DOWN; key DPAD_CENTER; sleep 2   # open the full-screen calendar (Down reaches the dock)
expect_text "m3-full" "Calendar full screen"
sh_ input keyevent KEYCODE_HOME; sleep 3
in_front || fail "m3: Home from a full screen left the app"
ui_text | grep -q 'content-desc="Calendar full screen"' && fail "m3: Home from the full-screen calendar did not return to the home row"
pass "m3: Home from the full-screen calendar returns to the home row"
sh_ cmd package set-home-activity "$stock_home" >/dev/null
sh_ pm disable "$PKG/.HomeEntry" >/dev/null
[[ $(home_now) == "$stock_home" ]] || fail "m3: rollback left home at '$(home_now)', expected '$stock_home'"
other_app; sh_ input keyevent KEYCODE_HOME; sleep 3
in_front && fail "m3: after rollback, Home still opens the dashboard"
pass "m3: rollback (set-home-activity back, HomeEntry off) restores the TV's own Home"

a11y_before=$(sh_ settings get secure enabled_accessibility_services | tr -d '\r')
on_before=$(sh_ settings get secure accessibility_enabled | tr -d '\r')
svc="$PKG/$PKG.MicKeyService"
if [[ -z $a11y_before || $a11y_before == null ]]; then list="$svc"; else list="$a11y_before:$svc"; fi
sh_ settings put secure enabled_accessibility_services "$list"
sh_ settings put secure accessibility_enabled 1
sleep 3
mic_ok=""
for k in ASSIST VOICE_ASSIST SEARCH; do
  other_app
  sh_ input keyevent "KEYCODE_$k"; sleep 3
  if in_front && ui_text | grep -q "content-desc=\"Ask Claude full screen\""; then
    pass "m3: the $k key opens Ask Claude from another app"; mic_ok=1
    sh_ input keyevent KEYCODE_BACK; sleep 1
  else
    warn "m3: the $k key did not open Ask Claude on the emulator ($(resumed | head -1))"
  fi
done
# Other keys pass through untouched while the service is on: Right still moves focus on the dashboard's row.
if (( napps >= 2 )); then
  sh_ am force-stop "$PKG"; sh_ am start -W -n "$MAIN" >/dev/null; sleep 4
  a=$(focus_after m3-pass); key DPAD_RIGHT; b=$(focus_after m3-pass)
  [[ -n ${a%%|*} && ${b%%|*} != "${a%%|*}" ]] || fail "m3: with the mic service on, Right did not move focus ('${a%%|*}' -> '${b%%|*}')"
  pass "m3: other keys pass through the mic service (Right moved '${a%%|*}' -> '${b%%|*}')"
fi
if [[ -z $a11y_before || $a11y_before == null ]]; then sh_ settings delete secure enabled_accessibility_services >/dev/null
else sh_ settings put secure enabled_accessibility_services "$a11y_before"; fi
[[ $on_before == 1 ]] || on_before=0   # a missing setting reads back as "null"
sh_ settings put secure accessibility_enabled "$on_before"
m3_dirty=""
[[ -n $mic_ok ]] || fail "m3: no mic key (ASSIST, VOICE_ASSIST, SEARCH) opened Ask Claude through the key filter"
no_app_crash_yet "m3"

step "AC6 provision the dummy URL over stdin (content write, scripts/provision.sh)"
network on
PROVISION_VALUE="$DUMMY_URL" scripts/provision.sh | tr -d '\r' > "$OUT/provision.txt" \
  || { cat "$OUT/provision.txt"; fail "provision.sh (content write over stdin) failed; see the fallback in architecture section 5"; }
grep -q '^Saved\.$' "$OUT/provision.txt" || fail "provision.sh did not report Saved."
pass "content write over stdin works: $(grep -m1 configured= "$OUT/provision.txt")"

step "DUMP guard"
q=$(sh_ content query --uri "$URI" | tr -d '\r')
grep -q 'configured=1' <<<"$q" || fail "shell content query does not see configured=1"
grep -qF -e "$DUMMY_TOKEN" -e 'example.com' <<<"$q" && fail "provider query returned the value"
pass "shell (uid 2000, holds DUMP) can query; the row has no value"
if sh_ 'command -v su' >/dev/null 2>&1; then
  d=$(sh_ su nobody content query --uri "$URI" 2>&1 | tr -d '\r' || true)
  if grep -q 'configured=' <<<"$d"; then fail "uid nobody could query the provider"; fi
  if grep -qE 'SecurityException|Permission Denial' <<<"$d"; then pass "uid nobody is refused (SecurityException)"
  else warn "uid nobody check inconclusive"; fi
else
  note "no su on this image ($(sh_ getprop ro.build.type | tr -d '\r')); non-shell caller not tested here"
fi
note "a separate third-party test app calling the provider is not built (cost); the DUMP permission on the provider and ProvisionActivity is checked in the app job's merged-manifest step"

step "AC8 cold start, network off, no cache, URL set"
network off
cold_start "offline-configured"
expect_text "offline-configured" "reach calendar"
no_app_crash_yet "offline configured"

step "fetch attempt with network on (dummy URL fails; error paths must not log it)"
network on
sh_ am force-stop "$PKG"
sh_ am start -W -n "$MAIN" >/dev/null
sleep 12
[[ -n $(pid_of) ]] || fail "process died after a failed fetch"
no_app_crash_yet "failed fetch"
pass "failed fetch handled without a crash"

step "#10 ProvisionActivity leaves no recents entry and no retained extras"
sh_ am force-stop "$PKG"
# The dummy goes into am's argv on the emulator only; the real path is provision.sh (stdin).
sh_ am start -W -n "$PROVISION_ACT" --es calendar_url "$DUMMY_URL" >/dev/null
sleep 4
sh_ dumpsys activity recents | tr -d '\r' > "$OUT/dumpsys-recents.txt"
grep -q 'ProvisionActivity' "$OUT/dumpsys-recents.txt" && fail "ProvisionActivity is in recents"
sh_ dumpsys activity activities | tr -d '\r' > "$OUT/dumpsys-activities.txt"
grep -q 'ProvisionActivity' "$OUT/dumpsys-activities.txt" && fail "ProvisionActivity record still exists after it finished"
pass "no ProvisionActivity in recents or the activity stack (extras checked by the final scan of dumpsys)"

step "forced crash: logcat -b crash and dropbox"
sh_ am start -W -n "$MAIN" >/dev/null; sleep 2
cr=$(sh_ am crash "$PKG" 2>&1 | tr -d '\r' || true)
sleep 3
if grep -qiE 'unknown|error' <<<"$cr"; then warn "am crash not supported here: $cr"
else
  [[ -z $(pid_of) ]] || warn "process still alive after am crash"
  echo "crash buffer lines for $PKG: $(a logcat -d -b crash | grep -c "$PKG" || true)"
  echo "dropbox data_app_crash entries for $PKG: $(sh_ dumpsys dropbox --print data_app_crash | grep -c "Process: $PKG" || true) (ReleaseCrashHandler does not delegate, so 0 is expected)"
  pass "forced crash done; buffers are scanned below"
fi
note "marker repro (throw RuntimeException(\"MARKER\") from Handler.post) needs an app hook and is not run here"

final_scan
echo; echo "AC8-offline: $([[ $OFFLINE_VERIFIED == yes ]] && echo VERIFIED || echo 'NOT VERIFIED')"
echo "All emulator QA checks passed."
