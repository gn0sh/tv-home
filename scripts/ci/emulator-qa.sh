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
  if [[ -z $xml ]]; then warn "$1: uiautomator dump unavailable; relying on resumed activity and screenshot"; return 0; fi
  printf '%s\n' "$xml" > "$OUT/ui-$1.xml"
  grep -qF "$2" <<<"$xml" || fail "$1: screen does not show \"$2\""
  pass "$1: screen shows \"$2\""
}

network() { # on|off
  if [[ $1 == off ]]; then
    sh_ svc wifi disable || true; sh_ svc data disable || true
    sh_ cmd connectivity airplane-mode enable || true
  else
    sh_ cmd connectivity airplane-mode disable || true
    sh_ svc wifi enable || true; sh_ svc data enable || true
  fi
  sleep 3
  local d; d=$(sh_ dumpsys connectivity | tr -d '\r' | grep -m1 'Active default network' || true)
  echo "network $1: ${d:-unknown}"
  if [[ $1 == off && -n $d && $d != *none* ]]; then warn "network still has a default route after turning it off"; fi
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
  pass "$1: cold start median ${median} ms"
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
on_exit() { local rc=$?; final_scan || rc=1; exit $rc; }
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
for k in DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_UP DPAD_UP DPAD_RIGHT DPAD_LEFT DPAD_CENTER DPAD_DOWN DPAD_CENTER DPAD_UP DPAD_LEFT; do
  sh_ input keyevent "KEYCODE_$k"; sleep 0.4
done
sleep 1
[[ -n $before && $(pid_of) == "$before" ]] || fail "process changed or died during the D-pad walk"
in_front || fail "AgendaActivity left the foreground during the D-pad walk"
pass "12 D-pad keys: still in front, same process"
sh_ input keyevent KEYCODE_BACK; sleep 2
if in_front; then sh_ input keyevent KEYCODE_BACK; sleep 2; fi
in_front && fail "Back does not leave the app (focus/Back trap)"
pass "Back leaves the app (now: $(resumed | head -1 | sed 's/^ *//'))"
no_app_crash_yet "D-pad walk"

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
echo; echo "All emulator QA checks passed."
