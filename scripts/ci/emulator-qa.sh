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
MAX_REGRESSION_MS=${MAX_REGRESSION_MS:-300}   # AC8 baseline: PR median may exceed the merge base's by at most this
BASE_APK=${BASE_APK:-}   # merge base's APK, same throwaway signer (pull requests); empty or missing = no comparison
QA_BUILD_APK=${QA_BUILD_APK:-}   # the qa build (release + R8, debuggable) of the same code, used only by the M7 Q1/Q2 block (run-as cache planting)
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

# Emulator only: later steps run adb root and plant fake caches. Refuse any real device (for example Brian's TV).
# API 31+ emulators set ro.boot.qemu=1 and may leave ro.kernel.qemu empty, so either one counts.
qemu_k=$(sh_ getprop ro.kernel.qemu | tr -d '\r'); qemu_b=$(sh_ getprop ro.boot.qemu | tr -d '\r')
[[ $qemu_k == 1 || $qemu_b == 1 ]] || {
  echo "::error::emulator-qa.sh runs only on an emulator (ro.kernel.qemu='$qemu_k' ro.boot.qemu='$qemu_b')"; exit 1; }

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

# AC8: one uncounted warm-up cold start (the first launch after install or pm clear pays one-time costs: dex
# verification, profile and prefs file creation), then 5 counted cold starts (force-stop each time) and their median.
# Each prints TotalTime and WaitTime. The limit (MAX_COLD_MS) is unchanged; 5 samples make the median robust to the
# swiftshader runner's noise (same code measured 979-4180 ms across runs with 3 samples).
COLD_RUNS=5
COLD_MEDIAN=""; COLD_TIMES=()
median_of() { sort -n | awk '{a[NR]=$1} END {if (NR % 2) print a[(NR+1)/2]; else print int((a[NR/2]+a[NR/2+1])/2)}'; }
COLD_FAILED=""
cold_start() { # $1 = label, $2 = "nolimit" to skip the MAX_COLD_MS check, $3 = "soft": a launch failure warns, sets
  # COLD_FAILED and returns 0 instead of failing the job (the baseline APK: its failure means "not compared"). Always
  # called outside a conditional, so set -e stays on inside it (ac8-errexit-off). Sets COLD_MEDIAN.
  local out t w times=() i soft=${3:-}
  COLD_FAILED=""
  cs_fail() { if [[ $soft == soft ]]; then warn "$1"; COLD_FAILED=1; return 0; fi; fail "$1"; }
  for ((i = 0; i <= COLD_RUNS; i++)); do
    sh_ am force-stop "$PKG" || true; sleep 1
    # || true: a failed or hung launch is judged by the Status check below (soft for the base, a fail for the PR).
    out=$(sh_ am start -W -n "$MAIN" | tr -d '\r' || true)
    grep -q '^Status: ok' <<<"$out" || { echo "$out"; cs_fail "$1: am start -W did not report Status: ok"; return 0; }
    t=$(sed -n 's/^TotalTime: *\([0-9]*\).*/\1/p' <<<"$out")
    w=$(sed -n 's/^WaitTime: *\([0-9]*\).*/\1/p' <<<"$out")
    [[ -n $t ]] || { echo "$out"; cs_fail "$1: no TotalTime in am start -W output"; return 0; }
    if (( i == 0 )); then echo "$1 warm-up (not counted): TotalTime=$t WaitTime=${w:-?}"
    else echo "$1 run $i: TotalTime=$t WaitTime=${w:-?}"; times+=("$t"); fi
    sleep 2
    [[ -n $(pid_of) ]] || { cs_fail "$1: process died after launch"; return 0; }
    in_front || { cs_fail "$1: AgendaActivity is not in front after launch"; return 0; }
  done
  local median; median=$(printf '%s\n' "${times[@]}" | sort -n | sed -n "$(( (COLD_RUNS + 1) / 2 ))p")
  echo "$1 cold start TotalTime ms: ${times[*]} (median $median, limit $MAX_COLD_MS)"
  echo "$1 ${times[*]} median=$median" >> "$OUT/cold-start.txt"
  COLD_MEDIAN=$median; COLD_TIMES=("${times[@]}")
  [[ ${2:-} == nolimit ]] && return 0
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
# idle-test-sticky: never leave the idle_test override on the device, whatever failed (the TV also expires it at 60 min).
idle_cleanup() { timeout 30 adb shell content delete --uri "content://$PKG.provision/idle_test" >/dev/null 2>&1 || true; }
on_exit() {
  local rc=$?
  idle_cleanup
  declare -F m3_cleanup >/dev/null && m3_cleanup
  declare -F restore_role >/dev/null && restore_role   # qa-role-restore-trap: never leave the assistant role on us
  declare -F m7_offline >/dev/null && m7_offline off   # m7-dns-restore-on-exit: a failed Q1 must not leave DNS broken
  final_scan || rc=1
  exit $rc
}
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
# mic-to-claude: two launcher entries now (the app, and the "Ask Claude" alias the star button can open), so list
# them instead of resolving one (resolve-activity would give the chooser).
r=$(sh_ cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER "$PKG" | tr -d '\r')
grep -qE "^ *($MAIN|$PKG/com\.gnosh\.tvhome\.AgendaActivity)\$" <<<"$r" || fail "LEANBACK_LAUNCHER has no AgendaActivity entry (got: $r)"
grep -qE "^ *$PKG/(\.|com\.gnosh\.tvhome\.)AskClaudeEntry\$" <<<"$r" || fail "LEANBACK_LAUNCHER has no Ask Claude entry (got: $r)"
pass "installed; LEANBACK_LAUNCHER lists AgendaActivity and AskClaudeEntry"
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
# AC8 before/after (spec M7-1.5): the merge base's APK, same state (pm clear, network off), same method. Blocks are
# interleaved PR, base, PR, base (the PR block above is the first), and the pooled 10-run medians are compared, so
# emulator drift during the job hits both sides. The PR goes first in each pair (strict). If the gate trips, one more
# PR/base pair is measured and pooled before failing. Every number is printed.
pr_times=("${COLD_TIMES[@]}"); base_times=()
use_apk() { # $1 = apk, $2 = label; a failed base install sets AC8_STATUS ("not compared"), a failed PR install fails
  local out; out=$(a install --no-incremental -r -g "$1" 2>&1 | tr -d '\r' || true)
  if ! grep -q Success <<<"$out"; then
    if [[ $2 == base ]]; then warn "AC8 baseline: installing the base APK failed"; AC8_STATUS=not_compared; return 0; fi
    fail "AC8 baseline: installing the $2 APK failed"
  fi
  sh_ pm clear "$PKG" >/dev/null || true
  network off
}
# ac8-clear-hides-pr-crash: the crash buffer is checked (hard) for PR crashes right before the base is installed, so
# anything in it after the base block was written by the base: saved, judged soft, then cleared.
base_crash_check() { # $1 = round
  local c; c=$(a logcat -d -b crash | tr -d '\r' | grep -c "$PKG" || true)
  if [[ $c -gt 0 ]]; then
    a logcat -d -b crash > "$OUT/crash-base-baseline-$1.txt" 2>/dev/null || true
    warn "AC8 baseline: the base APK wrote $c crash line(s) in round $1"
    AC8_STATUS=not_compared
  fi
  a logcat -b crash -c >/dev/null 2>&1 || true
}
ac8_pair() { # $1 = round; a PR block then a base block (the first round's PR block is the one above). No return codes:
  # the soft result is AC8_STATUS, so this is never called inside a conditional (ac8-errexit-off).
  [[ $AC8_STATUS == ok ]] || return 0
  if (( $1 > 1 )); then use_apk "$APK" PR; cold_start "pr-offline-not-set-up-$1" nolimit; pr_times+=("${COLD_TIMES[@]}"); fi
  no_app_crash_yet "AC8 PR block $1"
  use_apk "$BASE_APK" base
  [[ $AC8_STATUS == ok ]] || return 0
  cold_start "base-offline-not-set-up-$1" nolimit soft
  [[ -z $COLD_FAILED ]] || AC8_STATUS=not_compared
  base_crash_check "$1"
  [[ $AC8_STATUS == ok ]] || return 0
  base_times+=("${COLD_TIMES[@]}")
}
ac8_compare() { # sets delta; prints everything
  pr_pooled=$(printf '%s\n' "${pr_times[@]}" | median_of); base_pooled=$(printf '%s\n' "${base_times[@]}" | median_of)
  delta=$(( pr_pooled - base_pooled ))
  echo "AC8 before/after: PR runs ${pr_times[*]} (median $pr_pooled); base runs ${base_times[*]} (median $base_pooled); delta ${delta} ms (max +${MAX_REGRESSION_MS})"
  echo "baseline pr=[${pr_times[*]}] base=[${base_times[*]}] pr_median=$pr_pooled base_median=$base_pooled delta=$delta" >> "$OUT/cold-start.txt"
}
if [[ -n $BASE_APK && -f $BASE_APK ]]; then
  AC8_STATUS=ok
  ac8_pair 1
  ac8_pair 2
  if [[ $AC8_STATUS == ok ]]; then
    ac8_compare
    if (( delta > MAX_REGRESSION_MS )); then
      warn "AC8: delta ${delta} ms over ${MAX_REGRESSION_MS}; measuring one more PR/base pair before deciding"
      ac8_pair 3
      if [[ $AC8_STATUS == ok ]]; then ac8_compare
      else  # ac8-round3-escape: rounds 1-2 were already over budget, so a base failure now cannot excuse it
        use_apk "$APK" PR
        fail "AC8: PR cold start was ${delta} ms over the base after rounds 1-2 (max ${MAX_REGRESSION_MS}), and the round-3 base run failed"
      fi
    fi
  fi
  use_apk "$APK" PR
  if [[ $AC8_STATUS == ok ]]; then
    (( delta <= MAX_REGRESSION_MS )) || fail "AC8: PR cold start median ${pr_pooled} ms is ${delta} ms over the base's ${base_pooled} ms (max ${MAX_REGRESSION_MS})"
    pass "AC8: PR cold start within ${MAX_REGRESSION_MS} ms of the merge base (delta ${delta} ms)"
  else
    warn "AC8 baseline NOT COMPARED: the base APK failed during the baseline (base-crash-fails-pr)"
  fi
  sh_ am start -W -n "$MAIN" >/dev/null; sleep 3   # the screen checks below expect the PR app up
else
  note "AC8 baseline NOT COMPARED: no base APK (not a PR against main, or the base did not build)"
fi
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

step "mic-to-claude / voice-overlay: with the assistant role, the mic (KEYCODE_ASSIST) shows the Ask Claude overlay over another app (full-screen Ask without RECORD_AUDIO); role restored after"
ROLE=android.app.role.ASSISTANT
REC="$PKG/com.gnosh.tvhome.ClaudeRecognitionService"
prev_roles=$(sh_ cmd role get-role-holders --user 0 "$ROLE" 2>/dev/null | tr -d '\r' | tr ';' ' ' || true)
prev_rec=$(sh_ settings get secure voice_recognition_service | tr -d '\r')
echo "before: assistant role holders: ${prev_roles:-<none>}; voice_recognition_service: $prev_rec"
ROLE_TAKEN=0
# qa-role-restore-trap: on_exit calls this too, so a failure anywhere after the role is taken still gives it back.
restore_role() {
  [[ ${ROLE_TAKEN:-0} == 1 ]] || return 0
  ROLE_TAKEN=0
  timeout 30 adb shell cmd role remove-role-holder --user 0 "$ROLE" "$PKG" 0 >/dev/null 2>&1 || true
  for h in $prev_roles; do timeout 30 adb shell cmd role add-role-holder --user 0 "$ROLE" "$h" 0 >/dev/null 2>&1 || echo "::warning::could not give the assistant role back to $h"; done
}
# The Ask entry also works by intent alone (no role): ACTION_ASSIST goes to AssistActivity, which opens Ask Claude.
sh_ am force-stop "$PKG"; sh_ input keyevent KEYCODE_HOME; sleep 2
sh_ am start -W -a android.intent.action.ASSIST -n "$PKG/com.gnosh.tvhome.AssistActivity" >/dev/null; sleep 3
in_front || fail "mic: ACTION_ASSIST to AssistActivity did not bring the app up"
expect_text "mic-assist-intent" "Ask Claude full screen"
pass "mic: ACTION_ASSIST opens Ask Claude"
sh_ input keyevent KEYCODE_BACK; sleep 1; sh_ input keyevent KEYCODE_BACK; sleep 1
sh_ input keyevent KEYCODE_HOME; sleep 2
# qa-bypass-masks-qualification: no bypass. If the app does not qualify for the role on its own, that is the bug.
ROLE_TAKEN=1   # set before the call: a half-applied add is still undone
sh_ cmd role add-role-holder --user 0 "$ROLE" "$PKG" 0 >/dev/null 2>&1 || fail "mic: add-role-holder refused $PKG (the app does not qualify for the assistant role)"
sleep 3
holders=$(sh_ cmd role get-role-holders --user 0 "$ROLE" 2>/dev/null | tr -d '\r')
grep -qF "$PKG" <<<"$holders" || fail "mic: $PKG is not the assistant role holder (got: $holders)"
sh_ settings get secure voice_interaction_service | tr -d '\r' | tee "$OUT/mic-voice-service.txt"
rec=$(sh_ settings get secure voice_recognition_service | tr -d '\r')
echo "with the role: voice_recognition_service: $rec"
# The role may or may not repoint the default recognizer (the API 34 TV image keeps Google's). Either is fine; anything
# else is not.
MIC_REC_OURS=0
if [[ $rec == "$REC" || $rec == "$PKG/.ClaudeRecognitionService" ]]; then
  MIC_REC_OURS=1
  pass "mic: the role pointed voice_recognition_service at our forwarding ClaudeRecognitionService"
elif [[ $rec == "$prev_rec" ]]; then
  pass "mic: the role left voice_recognition_service unchanged ($rec)"
  note "mic: other apps keep Google's recognizer unchanged on this image; the forwarding recognizer is not used here"
else
  fail "mic: voice_recognition_service changed to something that is neither its old value nor ours (before: $prev_rec, now: $rec)"
fi
# voice-overlay: with RECORD_AUDIO (installed with -g) the mic button shows the corner overlay over the app in front,
# which stays resumed underneath; without it, the full-screen Ask view as before. No force-stop from here on: it would
# unbind the voice service.
other=com.google.android.youtube.tv
if sh_ pm path "$other" 2>/dev/null | grep -q '^package:'; then
  sh_ monkey -p "$other" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 || true
else
  other=com.android.tv.settings
  sh_ am start -W -a android.settings.SETTINGS >/dev/null 2>&1 || true
fi
sleep 6
under=$(resumed | head -1 | sed 's/^ *//')
in_front && fail "overlay: test setup: the app in front is still ours ($under)"
grep -qF "$other/" <<<"$under" || { other=$(grep -oE '[a-z][a-z0-9_.]+/' <<<"$under" | head -1 | tr -d /); warn "overlay: expected app not in front; using '$other' ($under)"; }
[[ -n $other ]] || fail "overlay: no other app in front to test over"
echo "overlay: app in front: $other"
sh_ input keyevent KEYCODE_ASSIST; sleep 4
now=$(resumed | head -1 | sed 's/^ *//')
grep -qF "$other/" <<<"$now" || fail "overlay: KEYCODE_ASSIST took $other out of the resumed state (now: $now)"
in_front && fail "overlay: KEYCODE_ASSIST opened the full-screen app, not the overlay (now: $now)"
pass "overlay: $other stays resumed underneath the mic button's session"
xml=$(ui_text); printf '%s\n' "$xml" > "$OUT/ui-voice-overlay.xml"
if ! grep -qF 'content-desc="Ask Claude overlay"' <<<"$xml"; then
  sh_ dumpsys window windows | tr -d '\r' | grep -iE 'VoiceInteraction|mCurrentFocus' | head -5 || true
  fail "overlay: the Ask Claude overlay panel is not showing after KEYCODE_ASSIST"
fi
pass "overlay: the corner panel shows over $other"
# No API key and no Google recognizer on the emulator: the card must say so in one line, never hang listening.
grep -qE "isn&apos;t set up|isn't set up|Voice input isn|Listening" <<<"$xml" || warn "overlay: unexpected card text (see ui-voice-overlay.xml)"
note "overlay: speech (partial text, final result sent at once), the answer card, its 20 s fade and Open are NOT VERIFIED here: no recognizer or API key on the emulator (VoiceOverlay JVM tests cover the state machine; the device check is Brian's)"
sh_ input keyevent KEYCODE_BACK; sleep 2
xml=$(ui_text)
grep -qF 'content-desc="Ask Claude overlay"' <<<"$xml" && fail "overlay: Back did not dismiss the overlay"
grep -qF "$other/" <<<"$(resumed | head -1)" || fail "overlay: Back on the overlay reached $other (now: $(resumed | head -1))"
pass "overlay: Back dismisses the card and $other carries on"
# Fallback: without RECORD_AUDIO the mic button opens the full-screen Ask view (as before voice-overlay).
sh_ pm revoke "$PKG" android.permission.RECORD_AUDIO || fail "overlay: could not revoke RECORD_AUDIO (test setup)"
# qa-fallback-stale-xml: revoking kills the app; wait (up to 30 s) until the system has bound the voice service again.
bound=""
for _ in $(seq 1 30); do
  if sh_ dumpsys activity services "$PKG" | tr -d '\r' | grep -q 'ClaudeVoiceService'; then bound=1; break; fi
  sleep 1
done
[[ -n $bound ]] || { sh_ pm grant "$PKG" android.permission.RECORD_AUDIO || true; fail "overlay: the voice service was not bound again within 30 s of revoking RECORD_AUDIO"; }
sh_ input keyevent KEYCODE_HOME; sleep 2
sh_ input keyevent KEYCODE_ASSIST
# Fresh dumps after the press, until the full-screen view shows (up to 15 s).
xml=""
for _ in $(seq 1 15); do
  sleep 1
  in_front || continue
  xml=$(ui_text)
  grep -qF "Ask Claude full screen" <<<"$xml" && break
done
printf '%s\n' "$xml" > "$OUT/ui-mic-assist.xml"
if ! in_front; then echo "diagnostic: top resumed: $(resumed | head -1 | sed 's/^ *//')"; sh_ pm grant "$PKG" android.permission.RECORD_AUDIO || true; fail "mic: KEYCODE_ASSIST without RECORD_AUDIO did not open the app"; fi
grep -qF "Ask Claude full screen" <<<"$xml" || { sh_ pm grant "$PKG" android.permission.RECORD_AUDIO || true; fail "mic: KEYCODE_ASSIST without RECORD_AUDIO did not show the full-screen Ask view"; }
pass "mic: without RECORD_AUDIO, KEYCODE_ASSIST falls back to the full-screen Ask view"
sh_ input keyevent KEYCODE_BACK; sleep 1; sh_ input keyevent KEYCODE_BACK; sleep 1
sh_ pm grant "$PKG" android.permission.RECORD_AUDIO || fail "overlay: could not grant RECORD_AUDIO back"
restore_role
sleep 2
after=$(sh_ cmd role get-role-holders --user 0 "$ROLE" 2>/dev/null | tr -d '\r' | tr ';' ' ')
grep -qF "$PKG" <<<"$after" && fail "mic: the assistant role was not taken back from $PKG"
after_rec=$(sh_ settings get secure voice_recognition_service | tr -d '\r')
[[ $after_rec == "$prev_rec" ]] || warn "mic: voice_recognition_service after restore is '$after_rec', before it was '$prev_rec'"
pass "mic: assistant role restored (now: ${after:-<none>}; voice_recognition_service: $after_rec)"
no_app_crash_yet "mic-to-claude"
# forwarding-unproven-on-device: the round trip needs the qa APK's RecognizerProbeActivity, so it runs inside the
# M7 block while the qa APK is installed (mic_round_trip below), and only if the image has a Google recognizer.
mic_round_trip() {
  local recs google probe
  if [[ ${MIC_REC_OURS:-0} != 1 ]]; then
    note "mic round trip skipped: the assistant role did not repoint voice_recognition_service, so other apps keep Google's recognizer unchanged"
    return 0
  fi
  recs=$(sh_ cmd package query-services --brief -a android.speech.RecognitionService | tr -d '\r' | grep '/' | grep -v "$PKG/" || true)
  google=$(grep -E 'com\.google\.android\.(katniss|googlequicksearchbox|tts|as)/' <<<"$recs" | head -1 || true)
  if [[ -z $google ]]; then
    note "mic round trip NOT VERIFIED: this emulator image has no Google recognizer (recognizers: ${recs:-none}); check by hand on the TV (setup doc: YouTube voice search)"
    return 0
  fi
  echo "mic round trip: Google recognizer $google"
  ROLE_TAKEN=1
  sh_ cmd role add-role-holder --user 0 "$ROLE" "$PKG" 0 >/dev/null 2>&1 || fail "mic round trip: add-role-holder refused the qa APK"
  sleep 3
  sh_ am start -W -n "$PKG/com.gnosh.tvhome.RecognizerProbeActivity" >/dev/null; sleep 12
  xml=$(ui_text); printf '%s\n' "$xml" > "$OUT/ui-mic-round-trip.xml"
  probe=$(grep -oE 'recognizer-probe: [a-z]+( [0-9a-z]+)?' <<<"$xml" | tail -1 || true)
  sh_ input keyevent KEYCODE_BACK; sleep 1
  restore_role
  echo "mic round trip: ${probe:-<no probe text>}"
  case $probe in
    "recognizer-probe: ready"|"recognizer-probe: partial"|"recognizer-probe: results") ;;
    "recognizer-probe: error 5"|"recognizer-probe: error 9") fail "mic round trip: forwarding failed ($probe; 5 = ERROR_CLIENT, 9 = ERROR_INSUFFICIENT_PERMISSIONS)" ;;
    "recognizer-probe: error "[0-9]*) ;;   # e.g. no match or speech timeout: Google's recognizer took the forwarded request
    *) fail "mic round trip: no usable probe result (${probe:-none})" ;;
  esac
  pass "mic round trip: SpeechRecognizer through ClaudeRecognitionService reached $google ($probe)"
}

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
    "Quiet mode"*|"Watch the game on "*)
      # M7: the Watch and Quiet pills sit right-aligned in the dock band, so Down from a far-right tile may land there.
      pass "re-entry: Down from '$far' reached a dock pill ('${cur%%|*}')"; key DPAD_UP; cur=$(focus_after re-entry)
      [[ ${cur%%|*} == Calendar,* ]] || fail "re-entry: Up from the '${cur%%|*}' pill did not reach the Calendar tile (now '$cur')" ;;
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
  # Same through the Ask button: Down, Down, Up (to Calendar), Up (to the row), Right moves. A first Down that lands on
  # the M7 Watch or Quiet pill goes Up to Calendar first (Up from a pill is set to Calendar).
  key DPAD_DOWN
  cur=$(focus_after re-entry)
  case ${cur%%|*} in "Quiet mode"*|"Watch the game on "*) key DPAD_UP ;; esac
  key DPAD_DOWN
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
  pass "row-edit: Move right put '$first_name' after '$second_name', focus followed"
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

step "Hero (M7): no focusable hero; focus starts on the apps row; one Back leaves"
# M7 removed the M2e focused-tile title (each tile labels itself); the hero is non-focusable text (home-back-twice).
sh_ am force-stop "$PKG"; sh_ am start -W -n "$MAIN" >/dev/null; sleep 4
in_front || fail "hero: home screen not in front"
# (The hero-nodes-not-focusable check is in the M7 Q1 step, where the hero has content.)
cur=$(focus_after hero)
if (( napps >= 1 )); then
  case ${cur%%|*} in ""|Calendar,*|"${ASK_DESC_PREFIX}"*) fail "hero: focus on launch is '${cur%%|*}', not an app tile (R1)" ;; esac
  pass "hero: focus on launch is on the apps row ('${cur%%|*}')"
else
  warn "hero: no other TV apps on this image; first focus on the apps row NOT VERIFIED here"
fi
sh_ input keyevent KEYCODE_BACK; sleep 2
in_front && fail "hero: one Back with the M7 hero showing does not leave the app (home-back-twice)"
pass "hero: one Back leaves the app"
no_app_crash_yet "hero"

step "M7 F4 idle with a fake clock (idle_test 30 s: DIM at 30 s, NEAR_BLACK at 60 s); any key restores"
# The provider's /idle_test override (emulator QA only) scales the LG thresholds; it is removed after the M3 checks.
idle_of() { ui_text | grep -oE 'resource-id="hero_idle_[A-Z_]+"' | head -1 | sed -E 's/.*hero_idle_([A-Z_]+)"$/\1/' || true; }
black_shown() { ui_text | grep -qF 'content-desc="Screen resting"'; }   # NEAR_BLACK_DESC in IdleLayer.kt
since() { echo $(( $(date +%s) - $1 )); }
sleep_until() { local n=$(( $1 + $2 - $(date +%s) )); (( n > 0 )) && sleep "$n"; return 0; }  # $1 = t0, $2 = seconds after it
sh_ content insert --uri "content://$PKG.provision/idle_test" --bind seconds:i:30 || fail "f4: idle_test insert refused"
sh_ am force-stop "$PKG"; sh_ am start -W -n "$MAIN" >/dev/null; t0=$(date +%s); sleep 4
in_front || fail "f4: home screen not in front"
st=$(idle_of); [[ $st == ACTIVE ]] || fail "f4: idle state at start is '$st', expected ACTIVE"
sleep_until "$t0" 36
st=$(idle_of); [[ $st == DIM ]] || fail "f4: after $(since "$t0") s the idle state is '$st', expected DIM"
pass "f4: DIM reached ($(since "$t0") s idle)"
before=$(focus_after f4)
key DPAD_RIGHT; t0=$(date +%s)
st=$(idle_of); [[ $st == ACTIVE ]] || fail "f4: a key in DIM did not restore (state '$st')"
if (( napps >= 2 )); then
  cur=$(focus_after f4)
  [[ ${cur%%|*} != "${before%%|*}" ]] || fail "f4: the key in DIM was swallowed (focus stayed on '${before%%|*}'); DIM must pass keys through"
  pass "f4: a key in DIM restores and passes through (focus moved to '${cur%%|*}')"
else
  pass "f4: a key in DIM restores (pass-through NOT VERIFIED: fewer than 2 other apps)"
fi
sleep_until "$t0" 66
black_shown || fail "f4: after $(since "$t0") s the near-black layer ('Screen resting') is not shown"
st=$(idle_of); [[ $st == NEAR_BLACK ]] || fail "f4: near-black layer shown but the idle state is '$st'"
pass "f4: NEAR_BLACK reached ($(since "$t0") s idle) with only the resting clock"
before=$(focus_after f4)
key DPAD_RIGHT
black_shown && fail "f4: a key in NEAR_BLACK did not wake the screen"
st=$(idle_of); [[ $st == ACTIVE ]] || fail "f4: after the waking key the idle state is '$st'"
cur=$(focus_after f4)
[[ ${cur%%|*} == "${before%%|*}" ]] || fail "f4: the first key in NEAR_BLACK moved focus ('${before%%|*}' -> '${cur%%|*}'); it must only wake"
pass "f4: the first key in NEAR_BLACK only wakes the screen (focus stayed on '${cur%%|*}')"
key DPAD_LEFT
cur=$(focus_after f4)
if (( napps >= 2 )); then
  [[ ${cur%%|*} != "${before%%|*}" ]] || fail "f4: the key after the waking one was swallowed (idle-swallow-stale)"
  pass "f4: the next key after waking passes through"
fi
no_app_crash_yet "f4 idle"

step "M3 Home: off as installed; turning on the Home watch makes Home open the dashboard; rollback restores"
# The same adb commands scripts/tv-home-takeover.ps1 runs on Brian's TV. Google TV's launcher holds HOME at priority 2
# (home-role-priority) and the system takes Home before an accessibility key filter, so the app watches for the TV's
# home coming up and brings itself to the front.
other_app() { sh_ am start -W -a android.settings.SETTINGS >/dev/null 2>&1 || true; sleep 3; }
a11y_before=$(sh_ settings get secure enabled_accessibility_services | tr -d '\r')
on_before=$(sh_ settings get secure accessibility_enabled | tr -d '\r')
[[ $on_before == 1 ]] || on_before=0   # a missing setting reads back as "null"
svc="$PKG/$PKG.HomeWatchService"
m3_restore() {
  if [[ -z $a11y_before || $a11y_before == null ]]; then sh_ settings delete secure enabled_accessibility_services >/dev/null 2>&1 || true
  else sh_ settings put secure enabled_accessibility_services "$a11y_before" >/dev/null 2>&1 || true; fi
  sh_ settings put secure accessibility_enabled "$on_before" >/dev/null 2>&1 || true
}
# m3-launcher-dialog: twice (PR #21's run and c46545e's, each about 11 min after boot) a Home press brought up the
# launcher's own com.google.android.tvlauncher/.dialog.ShowDialogsActivity, in the launcher's task, instead of its
# home activity. HomeWatch acts only on the home activity class, so it leaves that dialog alone, on purpose: it is the
# TV's own prompt, and on Brian's TV he sees it on Home, dismisses it with Back, and the launcher's home coming back
# is what redirects. So QA treats it as the launcher's, not ours: each M3 Home press goes through m3_home, which on
# that dialog records what it shows (text, the launcher task), dismisses it with Back, records what came up, and
# repeats the press once from the same starting point. Every check still runs against a press without the dialog; the
# dialog twice in a row, or more than 3 times in M3, fails.
LAUNCHER_DIALOG=".dialog.ShowDialogsActivity"
m3_dialogs=0
launcher_dialog_up() { resumed | head -1 | grep -qF "$LAUNCHER_DIALOG"; }
record_launcher_dialog() { # $1 = label
  local xml; xml=$(ui_text)
  printf '%s\n' "$xml" > "$OUT/ui-launcher-dialog-$m3_dialogs.xml"
  echo "---- m3-launcher-dialog #$m3_dialogs ($1): on screen"
  grep -oE '(text|content-desc|resource-id)="[^"]+"' <<<"$xml" | sort -u | head -40 || true
  echo "---- m3-launcher-dialog #$m3_dialogs: dumpsys activity activities, launcher task"
  sh_ dumpsys activity activities | tr -d '\r' \
    | awk '/^ *\* Task\{/{t=($0 ~ /tvlauncher|type=home/)} t' | grep -vE 'Intent \{.*(extras|dat=)' | head -60 || true
}
dismiss_launcher_dialog() { # $1 = label
  m3_dialogs=$((m3_dialogs + 1))
  record_launcher_dialog "$1"
  (( m3_dialogs <= 3 )) || fail "m3-launcher-dialog: ShowDialogsActivity came up $m3_dialogs times in M3; not a one-off prompt"
  sh_ input keyevent KEYCODE_BACK; sleep 3
  launcher_dialog_up && fail "m3-launcher-dialog: Back did not dismiss ShowDialogsActivity ($1)"
  note "m3-launcher-dialog #$m3_dialogs ($1): dismissed with Back; then on top: $(resumed | head -1 | grep -o '[a-z0-9_.]*/[A-Za-z0-9_.]*' | head -1)$(in_front && echo ' (the dashboard: the launcher home coming back redirected)')"
}
m3_home() { # $1 = settle seconds; the rest = what sets the press up again before the one retry
  local wait=$1; shift
  sh_ input keyevent KEYCODE_HOME; sleep "$wait"
  launcher_dialog_up || return 0
  dismiss_launcher_dialog "${FUNCNAME[1]}:${BASH_LINENO[0]}"
  sleep 4   # outside the double-press window of any redirect the dismissal caused
  if (( $# )); then "$@"; fi
  sh_ input keyevent KEYCODE_HOME; sleep "$wait"
  if launcher_dialog_up; then record_launcher_dialog retry; fail "m3-launcher-dialog: ShowDialogsActivity came back on the retried Home press"; fi
  return 0
}
# Retry setups (m3-retry-precondition): each re-asserts the starting point the original press had.
m3_dash() { # the dashboard in front again (an app shown: ends a stay)
  sh_ am start -W -n "$MAIN" >/dev/null; sleep 4
  in_front || fail "m3 retry setup: the dashboard did not come to the front ($(resumed | head -1))"
}
m3_other() { # another app in front: not the dashboard, not the TV's own home or its dialog
  local top
  other_app
  top=$(resumed | head -1 | grep -o '[a-z0-9_.]*/[A-Za-z0-9_.]*' | head -1)
  [[ -n $top && ${top%%/*} != "$PKG" && ${top%%/*} != "${stock_pkg:-com.google.android.tvlauncher}" ]] \
    || fail "m3 retry setup: Settings is not in front ($top)"
}
m3_refocus() { # dashboard with focus moved off the first tile, as before the focus check
  local m
  m3_dash; key DPAD_RIGHT; key DPAD_RIGHT
  m=$(focus_after m3)
  [[ -n ${m%%|*} && ${m%%|*} != "$start_name" ]] || fail "m3 retry setup: Right x2 did not move focus off '$start_name'"
}
m3_full() { # the full-screen calendar showing, as before the Home-from-full-screen check
  m3_dash; key DPAD_DOWN; key DPAD_CENTER; sleep 2
  expect_text "m3-full-retry" "Calendar full screen"
}
m3_idle_to() { # dashboard awake, then $1 s of idle_test time, and the state that the original press started from
  local t
  m3_dash
  [[ $(idle_of) == ACTIVE ]] || { key DPAD_RIGHT; sleep 1; }   # a key wakes (F4); any focus move is harmless here
  t=$(date +%s); sleep_until "$t" "$1"
  if (( $1 >= 60 )); then black_shown || fail "m3-idle: NEAR_BLACK not reached before the retried Home check"
  else [[ $(idle_of) == DIM ]] || fail "m3-idle: DIM not reached before the retried Home check"; fi
}
# If a check below fails mid-step, put the emulator back (m3-dirty-on-fail), in case it is reused.
m3_dirty=1
m3_cleanup() { [[ -n ${m3_dirty:-} ]] && m3_restore; return 0; }
other_app; m3_home 3 m3_other
in_front && fail "m3: as installed, Home opened the dashboard (the Home watch must be off until turned on)"
stock_pkg=$(resumed | head -1 | grep -o '[a-z0-9_.]*/[A-Za-z0-9_.]*' | head -1); stock_pkg=${stock_pkg%%/*}
[[ -n $stock_pkg ]] || fail "m3: could not read the TV's own home package (test setup)"
pass "m3: as installed, Home is still the TV's own ($stock_pkg)"
if [[ -z $a11y_before || $a11y_before == null ]]; then list="$svc"; else list="$a11y_before:$svc"; fi
sh_ settings put secure enabled_accessibility_services "$list"
sh_ settings put secure accessibility_enabled 1
sleep 3
# The service must be bound before Home is pressed.
for _ in $(seq 1 10); do sh_ dumpsys accessibility | tr -d '\r' | grep -q "HomeWatchService" && break; sleep 1; done
a11y_state() { sh_ dumpsys accessibility | tr -d '\r' | grep -iE 'Bound services|Enabled services|HomeWatchService' | head -12; }
sh_ dumpsys accessibility | tr -d '\r' | grep -q "HomeWatchService" || { a11y_state; fail "m3: the Home watch was not bound after turning it on"; }
other_app
in_front && fail "m3: Settings did not come to the front (test setup)"
m3_home 3 m3_other
if ! in_front; then
  a11y_state
  fail "m3: Home from another app did not bring the dashboard to the front ($(resumed | head -1))"
fi
pass "m3: Home from another app opens the dashboard"
# F4: the Home redirect works from DIM and from NEAR_BLACK, and comes back awake (idle_test 30 s still set).
m3t=$(date +%s); sleep_until "$m3t" 36
st=$(idle_of); [[ $st == DIM ]] || fail "m3-idle: DIM not reached before the Home check (state '$st')"
m3_home 3 m3_idle_to 36
in_front || fail "m3-idle: Home from DIM did not bring the dashboard back ($(resumed | head -1))"
st=$(idle_of); [[ $st == ACTIVE ]] || fail "m3-idle: after Home from DIM the state is '$st', expected ACTIVE"
pass "m3-idle: Home from DIM redirects and wakes the screen"
m3t=$(date +%s); sleep_until "$m3t" 66
black_shown || fail "m3-idle: NEAR_BLACK not reached before the Home check"
m3_home 3 m3_idle_to 66
in_front || fail "m3-idle: Home from NEAR_BLACK did not bring the dashboard back ($(resumed | head -1))"
black_shown && fail "m3-idle: after Home from NEAR_BLACK the screen is still black"
pass "m3-idle: Home from NEAR_BLACK redirects and wakes the screen"
sh_ content delete --uri "content://$PKG.provision/idle_test" >/dev/null 2>&1 || true
sleep 4   # outside the double-press window before the next check
# The way out (home-recovery-unreachable): Home again soon after the redirect keeps the TV's own home, with its
# Settings, until another app comes up (HomeRedirect in :core has the timing cases).
# A fresh redirect, then Home again about a second later (inside the 2.5 s window).
m3_first_of_double() {
  m3_home 1 m3_dash
  in_front || fail "m3: the first Home of the double press did not redirect (m3-qa-double-vacuous; $(resumed | head -1))"
}
m3_first_of_double
m3_home 4 eval 'm3_dash; m3_first_of_double'
in_front && fail "m3: a quick second Home did not leave the TV's own home showing"
resumed | head -1 | grep -qF "$stock_pkg/" || fail "m3: a quick second Home did not show the TV's own home ($(resumed | head -1))"
pass "m3: Home twice quickly leaves the TV's own home (and its Settings) showing"
m3_home 4
in_front && fail "m3: Home on the TV's own home during a stay brought the dashboard back"
pass "m3: further Home presses keep the TV's own home until an app is opened"
other_app; m3_home 3 m3_other
in_front || fail "m3: after leaving the TV's own home, Home did not open the dashboard again ($(resumed | head -1))"
pass "m3: once another app has been opened, Home opens the dashboard again"
if (( napps >= 2 )); then
  start=$(focus_after m3); start_name=${start%%|*}
  key DPAD_RIGHT; key DPAD_RIGHT
  moved=$(focus_after m3)
  [[ -n ${moved%%|*} && ${moved%%|*} != "$start_name" ]] || fail "m3: Right x2 did not move focus off '$start_name' (test setup; m3-home-focus-vacuous)"
  m3_home 3 m3_refocus
  cur=$(focus_after m3)
  [[ ${cur%%|*} == "$start_name" ]] || fail "m3: Home on the dashboard put focus on '${cur%%|*}', not the first tile '$start_name'"
  pass "m3: Home on the dashboard returns focus to the first tile ('$start_name')"
fi
m3_home 3 m3_dash   # focus back on the first tile, as before Down in M2b
key DPAD_DOWN; key DPAD_CENTER; sleep 2   # open the full-screen calendar (Down reaches the dock)
expect_text "m3-full" "Calendar full screen"
m3_home 3 m3_full
in_front || fail "m3: Home from a full screen left the app"
ui_text | grep -q 'content-desc="Calendar full screen"' && fail "m3: Home from the full-screen calendar did not return to the home row"
pass "m3: Home from the full-screen calendar returns to the home row"
m3_restore
m3_dirty=""
sleep 2
other_app; m3_home 3 m3_other
in_front && fail "m3: after rollback, Home still opens the dashboard"
pass "m3: rollback (Home watch off, settings restored) gives Home back to the TV"
echo "m3-launcher-dialog: ShowDialogsActivity dismissed $m3_dialogs time(s) in M3"
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

m7_relog() { # restart the background logcat stream (appending) after adbd restarts
  [[ -n $LOGCAT_PID ]] && kill "$LOGCAT_PID" 2>/dev/null || true
  adb logcat -b main,system,crash,events -v threadtime >> "$OUT/logcat-stream.txt" 2>/dev/null &
  LOGCAT_PID=$!
}
step "M7 Q1: hero shows the next event with a countdown and a severe-alert override (planted caches, network off)"
# The release APK has no cache-planting path and is not debuggable, so the caches are planted one of two ways:
#  runas (primary): the app job's qa APK (QA_BUILD_APK: the release build with R8, made debuggable, debug key, same
#    applicationId) replaces
#    the release app for Q1 and Q2 only, and `run-as` writes into its no_backup dir. The android-tv API 34 image is a
#    user build without adb root, so CI takes this route.
#  root (fallback, images that allow adb root): the release app's files are written as root.
# Neither works: Q1 fails, never only a warning. The release APK is reinstalled (and the dummy URL re-provisioned)
# afterwards, so every later step runs against release.
m7_mode=""; m7_why=""
data=/data/user/0/$PKG/no_backup; uid=""
m7_install() { # $1 = apk, $2 = label. A different signer means uninstall first, which wipes the app's data.
  local out
  network on   # clears the old uid's deny before the uid can change
  a uninstall "$PKG" >/dev/null 2>&1 || true
  out=$(a install --no-incremental -r -g "$1" 2>&1 | tr -d '\r' || true)
  grep -q 'Success' <<<"$out" || { m7_why="installing the $2 APK failed ($out)"; return 1; }
  PROVISION_VALUE="$DUMMY_URL" scripts/provision.sh | tr -d '\r' > "$OUT/provision-m7-$2.txt" || true
  grep -q '^Saved\.$' "$OUT/provision-m7-$2.txt" || { m7_why="re-provisioning the dummy URL on the $2 APK failed"; return 1; }
}
m7_put() { # $1 = path under no_backup, content on stdin; read back so a silent write failure cannot pass
  if [[ $m7_mode == runas ]]; then
    a shell "run-as $PKG sh -c 'cat > no_backup/$1'" || fail "m7: run-as write of $1 failed"
    [[ $(sh_ "run-as $PKG sh -c 'test -s no_backup/$1 && echo ok'" | tr -d '\r') == ok ]] || fail "m7: $1 is empty after the run-as write"
  else
    a shell "cat > $data/$1" || fail "m7: root write of $1 failed"
    sh_ chown "$uid:$uid" "$data/$1"; sh_ chmod 600 "$data/$1"; sh_ restorecon "$data/$1" >/dev/null 2>&1 || true
    [[ $(sh_ "test -s $data/$1 && echo ok" | tr -d '\r') == ok ]] || fail "m7: $1 is empty after the root write"
  fi
}
m7_rm() { # paths under no_backup
  local f
  for f in "$@"; do
    if [[ $m7_mode == runas ]]; then sh_ "run-as $PKG rm -f no_backup/$f" || true; else sh_ rm -f "$data/$f" || true; fi
  done
}
# Offline for Q1/Q2 (qa-q1-live-overwrite): this image has no per-package networking command, so `network off` leaves
# the app online, and the first alerts fetch (due at once) replaces the planted Tornado Warning with the real, empty NWS
# answer, in memory and on disk (correct per ADR 0008; LiveFeedsPanelsTest.plantedQaAlertCacheShowsOffline...).
# svc wifi/data and airplane mode do not take the wired default network down (first CI run), so Q1 uses a global
# setting the shell may write: Private DNS in strict mode with a resolver host that cannot resolve, which fails every
# hostname lookup for every app (the feeds use hostnames only). Confirmed below by lookups of each feed host from the
# shell, and afterwards by the app itself: no weather fetch landed and the planted alerts file is unchanged.
M7_FEED_HOSTS="api.weather.gov api.open-meteo.com api-web.nhle.com site.api.espn.com calendar.google.com"
m7_dns_mode=""; m7_dns_spec=""; m7_offline_on=""
m7_offline() { # on|off
  local h out i ok
  if [[ $1 == off ]]; then
    [[ -n $m7_offline_on ]] || return 0
    if [[ -z $m7_dns_spec || $m7_dns_spec == null ]]; then sh_ settings delete global private_dns_specifier >/dev/null 2>&1 || true
    else sh_ settings put global private_dns_specifier "$m7_dns_spec" || true; fi
    if [[ -z $m7_dns_mode || $m7_dns_mode == null ]]; then sh_ settings delete global private_dns_mode >/dev/null 2>&1 || true
    else sh_ settings put global private_dns_mode "$m7_dns_mode" || true; fi
    m7_offline_on=""
    echo "m7 offline: Private DNS restored (mode=${m7_dns_mode:-default})"
    return 0
  fi
  m7_dns_mode=$(sh_ settings get global private_dns_mode | tr -d '\r')
  m7_dns_spec=$(sh_ settings get global private_dns_specifier | tr -d '\r')
  sh_ settings put global private_dns_specifier qa-offline.invalid
  sh_ settings put global private_dns_mode hostname
  m7_offline_on=1
  for i in 1 2 3 4 5 6 7 8 9 10; do
    ok=1
    for h in $M7_FEED_HOSTS; do
      out=$(sh_ ping -c 1 -W 2 "$h" 2>&1 | tr -d '\r' || true)
      # A lookup failure must be shown, not inferred: a missing ping or a refused ICMP would also print no address.
      grep -qiE 'unknown host|bad address|name or service not known|no address associated' <<<"$out" || { ok=""; break; }
    done
    [[ -n $ok ]] && break
    sleep 2
  done
  [[ -n $ok ]] || fail "m7 offline: no lookup failure shown for $h with Private DNS strict to an unresolvable host ($out); Q1 cannot be offline"
  pass "m7 offline: no feed host resolves (Private DNS strict, unresolvable resolver host)"
}
m7_ls() { # file names, sizes and stamps under no_backup; never file contents except the QA-planted alerts envelope
  if [[ $m7_mode == runas ]]; then sh_ "run-as $PKG ls -l no_backup no_backup/panels" 2>&1 | tr -d '\r' || true
  else sh_ ls -l "$data" "$data/panels" 2>&1 | tr -d '\r' || true; fi
}
m7_cat() { # $1 = path under no_backup
  if [[ $m7_mode == runas ]]; then sh_ "run-as $PKG cat no_backup/$1" 2>/dev/null | tr -d '\r' || true
  else sh_ cat "$data/$1" 2>/dev/null | tr -d '\r' || true; fi
}
m7_diag() { # on a hero failure: what the hero showed and what is on disk (QA data only; no secrets live in no_backup)
  echo "---- m7 diagnostics: hero nodes in the dump"
  grep -oE '(content-desc="Hero [^"]*"|resource-id="hero_[^"]*")' <<<"${ui:-}" || echo "(no hero nodes)"
  echo "---- m7 diagnostics: run-as ls -l no_backup"; m7_ls
  echo "---- m7 diagnostics: panels/alerts.json (QA-planted envelope or the app's rewrite)"; m7_cat panels/alerts.json | head -c 600; echo
  echo "---- m7 diagnostics: private_dns_mode=$(sh_ settings get global private_dns_mode | tr -d '\r')"
}
m7_fail() { m7_diag; fail "$@"; }
m7_swapped=""
if [[ -z $QA_BUILD_APK || ! -f $QA_BUILD_APK ]]; then
  m7_why="no qa APK (QA_BUILD_APK='$QA_BUILD_APK')"
elif m7_swapped=1; m7_install "$QA_BUILD_APK" qa; then
  if [[ $(sh_ "run-as $PKG sh -c 'mkdir -p no_backup/panels && test -w no_backup/panels && echo ok'" 2>&1 | tr -d '\r') == ok ]]; then
    m7_mode=runas
    pass "m7: qa APK (release + R8, debuggable) installed for Q1; caches are planted with run-as"
  else
    m7_why="run-as $PKG does not work with the qa APK"
  fi
fi
if [[ -z $m7_mode ]]; then
  warn "m7: run-as route unavailable ($m7_why); trying adb root"
  if [[ -n $m7_swapped ]]; then
    m7_install "$APK" release || fail "m7: putting the release APK back failed: $m7_why"
  fi
  if a root 2>&1 | tr -d '\r' | grep -qiE 'restarting|already running as root'; then
    a wait-for-device; sleep 2
    [[ $(sh_ id -u | tr -d '\r') == 0 ]] && m7_mode=root
    m7_relog   # adbd restarted, which ends the logcat stream
  fi
  [[ -n $m7_mode ]] || fail "m7: cannot plant the hero caches: neither run-as ($m7_why) nor adb root works on this image"
  uid=$(sh_ stat -c %u "/data/user/0/$PKG" | tr -d '\r')
  [[ $uid =~ ^[0-9]+$ ]] || fail "m7: could not read the app's uid ($uid)"
  sh_ mkdir -p "$data/panels"; sh_ chown -R "$uid:$uid" "$data"; sh_ chmod -R u+rwX,go-rwx "$data"
  sh_ restorecon -R "$data" >/dev/null 2>&1 || true
  pass "m7: adb root works; caches are planted as root into the release app"
fi
{
  sh_ content insert --uri "content://$PKG.provision/location" --bind lat:d:36.53 --bind lon:d:-87.36 \
    || fail "m7: location insert refused"
  sh_ am force-stop "$PKG"
  iso() { date -u -d "$1" +%Y-%m-%dT%H:%M:%SZ; }
  # qa-midnight-flake: an event 42 min out that lands on tomorrow in the emulator's zone shows as tomorrow's first,
  # with no countdown. Then the countdown check is skipped (the alert check still runs).
  etz=$(sh_ getprop persist.sys.timezone | tr -d '\r'); etz=${etz:-UTC}
  same_day=""; [[ $(TZ=$etz date -d '+50 minutes' +%F) == $(TZ=$etz date +%F) ]] && same_day=1
  now=$(iso now); ev=$(iso '+42 minutes'); evend=$(iso '+102 minutes'); until=$(iso '+90 minutes')
  ws=$(iso '-2 days'); we=$(iso '+3 days')
  agenda='{"v":1,"fetchedAt":"'$now'","windowStart":"'$ws'","windowEnd":"'$we'","events":[{"uid":"qa-1","title":"QA Dentist","start":{"kind":"at","value":"'$ev'"},"end":{"kind":"at","value":"'$evend'"}}]}'
  alerts='{"v":1,"at":"'$now'","data":{"alerts":[{"event":"Tornado Warning","severity":"Extreme","kind":"OVERRIDE","until":"'$until'"}]}}'
  printf '%s' "$agenda" | m7_put agenda-cache.json
  printf '%s' "$alerts" | m7_put panels/alerts.json
  network off   # no-op on images without per-package networking; m7_offline is what takes the app offline
  m7_offline on
  sh_ am start -W -n "$MAIN" >/dev/null; sleep 8   # first frame, 1.5 s settle, cache load
  ui=$(ui_text); [[ -n $ui ]] || m7_fail "m7: uiautomator dump failed"
  printf '%s\n' "$ui" > "$OUT/ui-m7-hero.xml"
  a exec-out screencap -p > "$SHOT_DIR/tv-home-m7-hero.png" || true
  for n in "Hero now: " "Hero card: "; do
    grep -qF "content-desc=\"$n" <<<"$ui" || m7_fail "m7: no '$n...' node in the hero (planted caches not shown)"
  done
  grep -oE '<node [^>]*(content-desc="Hero (now|card): [^"]*"|resource-id="hero_idle_[A-Z_]+")[^>]*>' <<<"$ui" | grep -q 'focusable="true"' \
    && m7_fail "m7: a hero node is focusable (home-back-twice)"
  pass "m7: the hero's Now and card nodes are shown and none is focusable"
  if [[ -n $same_day ]]; then
    grep -qE 'content-desc="Hero now: [^"]*in (4[0-2]|3[0-9]) min"' <<<"$ui" \
      || m7_fail "m7: the hero does not show the next event with an 'in N min' countdown"
    pass "m7: the hero shows the next event with a countdown"
  else
    warn "m7: countdown check skipped: the planted event would fall after midnight in $etz (qa-midnight-flake)"
  fi
  grep -qE 'content-desc="Hero card: Tornado Warning until [^"]+"' <<<"$ui" \
    || m7_fail "m7: an active Tornado Warning does not take the context card"
  pass "m7: a severe alert overrides the context card"
  # The app could not fetch: weather (on, location set, due at once) wrote nothing, and the planted alerts are intact.
  m7_cat panels/weather.json | grep -q . && m7_fail "m7: the app fetched weather during Q1, so it was not offline"
  m7_cat panels/alerts.json | grep -qF '"at":"'"$now"'"' || m7_fail "m7: the planted alerts file was rewritten during Q1 (a fetch got through)"
  pass "m7: the app could not fetch during Q1 (no weather cache written, planted alerts unchanged)"
  cur=$(focus_after m7)
  if (( napps >= 1 )); then
    case ${cur%%|*} in ""|Calendar,*|"${ASK_DESC_PREFIX}"*) m7_fail "m7: with the hero full, first focus is '${cur%%|*}', not the apps row (R1)" ;; esac
    pass "m7: with the hero full, focus starts on the apps row ('${cur%%|*}')"
  fi
  sh_ input keyevent KEYCODE_BACK; sleep 2
  in_front && m7_fail "m7: one Back with the M7 hero full does not leave the app (home-back-twice)"
  pass "m7: one Back returns home (leaves the app)"
  no_app_crash_yet "m7 hero"

  # ---- M7 Q2 (F5, F6): Watch pill only on game day with a mapped, installed app; dock focus order; Quiet ----
  yt=com.google.android.youtube.tv
  m7_rm panels/alerts.json   # the game card, not the alert, for this part
  if ! sh_ pm path "$yt" 2>/dev/null | grep -q '^package:'; then
    warn "M7 Watch pill NOT VERIFIED on the emulator: $yt is not installed on this image"
  elif [[ $(TZ=$etz date -d '+100 minutes' +%F) != $(TZ=$etz date +%F) ]]; then
    warn "M7 Watch pill check skipped: a game 90 min out would fall after midnight in $etz"
  else
    q2_restart() { sh_ am force-stop "$PKG"; sh_ am start -W -n "$MAIN" >/dev/null; sleep 8; }
    # The description may end ", opens <app>" when the pill names the app (watch-desc-mismatch).
    watch_shown() { ui_text | grep -qE 'content-desc="Watch the game on YouTube(, opens [^"]*)?"'; }
    sh_ content insert --uri "content://$PKG.provision/watch" --bind networks:s:"YouTube=$yt" --bind defaults:s:predators=YouTube \
      || fail "q2: watch map insert refused"
    # No game: no pill, even with the map set.
    m7_rm panels/games.json
    q2_restart
    watch_shown && fail "q2: the Watch pill shows with no game today"
    pass "q2: no game today, no Watch pill"
    gstart=$(iso '+90 minutes')
    games='{"v":1,"at":"'$now'","data":[{"team":"predators","start":"'$gstart'","opponent":"QA Opponent","home":true,"phase":"UPCOMING"}]}'
    printf '%s' "$games" | m7_put panels/games.json
    q2_restart
    watch_shown || fail "q2: an upcoming game today with a mapped, installed app shows no Watch pill"
    pass "q2: game day with a mapped, installed app shows the Watch pill"
    # Each dock pill's full description (closing quote included) must be present. Whether it sits on the focusable
    # node is not checked here: uiautomator shows every tile's and pill's description on a non-focusable child with the
    # same bounds, app-wide; the backlog item pill-a11y-node settles that with a real TalkBack / AccessibilityNodeInfo check.
    ui=$(ui_text)
    for d in 'Watch the game on YouTube(, opens [^"]*)?' 'Quiet mode, off\. Turn on to hide event titles\.' 'Ask Claude\. Talk or type a question\.'; do
      grep -qE "content-desc=\"$d\"" <<<"$ui" || fail "q2: no node has the description /$d/"
    done
    pass "q2: the Watch, Quiet and Ask pills show their full descriptions"
    # Focus order (R1): apps row > Calendar > Ask > Watch > Quiet.
    first=$(focus_after q2)
    case ${first%%|*} in ""|Calendar,*|"${ASK_DESC_PREFIX}"*|"Quiet mode"*|"Watch the game on "*) fail "q2: first focus is '${first%%|*}', not the apps row (R1)" ;; esac
    key DPAD_DOWN; cur=$(focus_after q2)
    [[ ${cur%%|*} == Calendar,* ]] || fail "q2: Down from the first tile reached '${cur%%|*}', not the Calendar tile"
    key DPAD_DOWN; cur=$(focus_after q2)
    # The Ask pill hides its focused node from uiautomator (it clears its semantics), so an empty node is confirmed by
    # behaviour: OK opens the Ask view, and Back returns focus to the pill.
    case ${cur%%|*} in
      "${ASK_DESC_PREFIX}"*) ;;
      "")
        key DPAD_CENTER; sleep 1
        expect_text "q2-ask" "Ask Claude full screen"
        key BACK; sleep 1
        in_front || fail "q2: Back from the Ask view left the app" ;;
      *) fail "q2: Down from Calendar reached '${cur%%|*}', not the Ask pill" ;;
    esac
    pass "q2: Down from Calendar reached the Ask pill"
    key DPAD_RIGHT; cur=$(focus_after q2)
    [[ ${cur%%|*} =~ ^Watch\ the\ game\ on\ YouTube(,\ opens\ .+)?$ ]] || fail "q2: Right from Ask reached '${cur%%|*}', not the Watch pill"
    key DPAD_RIGHT; cur=$(focus_after q2)
    [[ ${cur%%|*} == "Quiet mode, off"* ]] || fail "q2: Right from Watch reached '${cur%%|*}', not the Quiet pill"
    key DPAD_RIGHT; cur=$(focus_after q2)
    [[ ${cur%%|*} == "Quiet mode, off"* ]] || fail "q2: the Quiet pill is not last in the dock band (Right moved to '${cur%%|*}')"
    pass "q2: focus order apps row > Calendar > Ask > Watch > Quiet (last)"
    # Quiet: OK hides titles on the next redraw, shows the marker, survives a restart; OK again restores.
    ui=$(ui_text); grep -qF 'QA Dentist' <<<"$ui" || fail "q2: the planted event title is not on screen before Quiet (test setup)"
    key DPAD_CENTER; sleep 2
    ui=$(ui_text); printf '%s\n' "$ui" > "$OUT/ui-q2-quiet.xml"
    grep -qF 'QA Dentist' <<<"$ui" && fail "q2: an event title is still on screen with Quiet on"
    grep -qF 'resource-id="hero_quiet"' <<<"$ui" || fail "q2: no visible Quiet marker with Quiet on"
    grep -qF 'Quiet mode, on' <<<"$ui" || fail "q2: the Quiet pill does not say on"
    pass "q2: Quiet hides event titles on the next redraw and shows the marker"
    # quiet-calendar-fullscreen: OK on the Calendar tile opens the full screen, which hides the title too.
    key DPAD_UP   # Quiet pill -> Calendar (Up from a pill is set to Calendar)
    cur=$(focus_after q2)
    [[ ${cur%%|*} == Calendar,* ]] || fail "q2: could not reach the Calendar tile from the Quiet pill (now '${cur%%|*}')"
    key DPAD_CENTER; sleep 2
    ui=$(ui_text); printf '%s\n' "$ui" > "$OUT/ui-q2-quiet-calendar.xml"
    grep -qF 'content-desc="Calendar full screen"' <<<"$ui" || fail "q2: OK on the Calendar tile did not open the full screen"
    grep -qF 'QA Dentist' <<<"$ui" && fail "q2: the full-screen calendar shows an event title with Quiet on"
    pass "q2: the full-screen calendar hides event titles with Quiet on"
    key BACK; sleep 1
    in_front || fail "q2: Back from the full-screen calendar left the app"
    q2_restart
    ui=$(ui_text)
    grep -qF 'resource-id="hero_quiet"' <<<"$ui" && ! grep -qF 'QA Dentist' <<<"$ui" || fail "q2: Quiet did not survive a restart"
    pass "q2: Quiet survives a restart"
    sh_ content insert --uri "content://$PKG.provision/quiet" --bind on:b:false || fail "q2: quiet off over adb refused"
    q2_restart
    ui=$(ui_text); grep -qF 'QA Dentist' <<<"$ui" || fail "q2: titles did not come back with Quiet off"
    pass "q2: Quiet off shows titles again"
    # The map is required: without it, no pill on the same game day.
    sh_ content delete --uri "content://$PKG.provision/watch" >/dev/null 2>&1 || true
    q2_restart
    watch_shown && fail "q2: the Watch pill shows with no watch map"
    pass "q2: no watch map, no Watch pill"
    sh_ input keyevent KEYCODE_BACK; sleep 2
    in_front && fail "q2: one Back with the pills showing does not leave the app (home-back-twice)"
    no_app_crash_yet "m7 q2"
  fi
  sh_ content delete --uri "content://$PKG.provision/watch" >/dev/null 2>&1 || true
  sh_ content insert --uri "content://$PKG.provision/quiet" --bind on:b:false >/dev/null 2>&1 || true
  m7_rm agenda-cache.json panels/alerts.json panels/games.json
  sh_ content delete --uri "content://$PKG.provision/location" >/dev/null 2>&1 || true
  m7_offline off
  network on
  mic_round_trip
  if [[ $m7_mode == root ]]; then
    a unroot >/dev/null 2>&1 || true; a wait-for-device; sleep 2; m7_relog
  else
    m7_install "$APK" release || fail "m7: putting the release APK back after Q1 failed: $m7_why"
    # runas-revoke-check: the package manager must report the installed app as not debuggable (pkgFlags), not just
    # "run-as failed" (which any error would satisfy).
    pk=$(sh_ dumpsys package "$PKG" | tr -d '\r')
    grep -qE '^ *pkgFlags=\[' <<<"$pk" || fail "m7: dumpsys package $PKG shows no pkgFlags after the release reinstall"
    grep -E '^ *(pkgFlags|flags)=\[' <<<"$pk" | grep -q 'DEBUGGABLE' && fail "m7: the reinstalled $PKG is still DEBUGGABLE"
    sh_ run-as "$PKG" true >/dev/null 2>&1 && fail "m7: run-as still works after reinstalling the release APK"
    # m7-reinstall-state: the later steps need only the release app, the dummy URL (fetch attempt, final scan) and
    # networking on; ProvisionActivity and the forced crash need nothing else. Location, watch map, Quiet, idle test and
    # the Home watch were wiped with the qa app and are not used after this point.
    sh_ content query --uri "$URI" | tr -d '\r' | grep -q 'configured=1' || fail "m7: the dummy URL is not configured on the reinstalled release APK"
    pass "m7: release APK reinstalled; dumpsys shows it not debuggable; dummy URL configured; later steps run on release"
  fi
}

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
