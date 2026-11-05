#!/usr/bin/env bash
# probe-watch-next (throwaway branch, never merged): on the CI emulator only, can the app read other apps' Watch Next
# ("Continue watching") rows, and what do the fallbacks give? Usage: watch-next-probe.sh <qa-apk>
# Each finding is a line starting "PROBE ". Made-up data only; no secrets.
set -uo pipefail
APK=${1:?qa-apk}
PKG=com.gnosh.tvhome
EPG=com.android.providers.tv.permission.READ_EPG_DATA
WN=content://android.media.tv/watch_next_program
a() { timeout 90 adb "$@"; }
sh_() { timeout 90 adb shell "$@" </dev/null | tr -d '\r'; }
p() { echo "PROBE $*"; }
[[ $(sh_ getprop ro.kernel.qemu) == 1 || $(sh_ getprop ro.boot.qemu) == 1 ]] || { echo "::error::emulator only"; exit 1; }
run() { # $1 mode, $2 label
  sh_ logcat -c
  sh_ am start -W -n "$PKG/.probe.WatchNextProbeActivity" --es mode "$1" >/dev/null; sleep 2
  sh_ logcat -d -s WatchProbe:I | grep -oE 'WatchProbe: .*' | sed "s/^WatchProbe: /PROBE [$2] /"
}

p "image: fingerprint=$(sh_ getprop ro.build.fingerprint) sdk=$(sh_ getprop ro.build.version.sdk)"
p "image: tv provider: $(sh_ pm list packages | grep -oE 'com.android.providers.tv|com.google.android.tvrecommendations|com.google.android.engage[a-z.]*|com.google.android.apps.tv.launcherx|com.google.android.tvlauncher|com.google.android.leanbacklauncher|com.google.android.youtube.tv|com.google.android.katniss' | tr '\n' ' ')"
home=$(sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)
p "image: home activity: $home"
p "perm: READ_EPG_DATA definition: $(sh_ pm list permissions -f 2>/dev/null | grep -A5 "permission:$EPG\$" | grep -iE 'protectionLevel|package' | tr -s ' ' | tr '\n' '|')"
p "perm: WRITE_EPG_DATA definition: $(sh_ pm list permissions -f 2>/dev/null | grep -A5 'permission:com.android.providers.tv.permission.WRITE_EPG_DATA$' | grep -iE 'protectionLevel' | tr -s ' ' | tr '\n' '|')"
p "perm: dumpsys TvProvider permissions: $(sh_ dumpsys package com.android.providers.tv | grep -A2 -E 'READ_EPG_DATA|ACCESS_WATCHED_PROGRAMS|ACCESS_ALL_EPG_DATA' | grep -E 'prot=|Permission \[' | tr -s ' ' | tr '\n' '|' | head -c 900)"
hp=${home%%/*}
p "launcher $hp holds: $(sh_ dumpsys package "$hp" | grep -E 'tv.permission|PACKAGE_USAGE_STATS|EPG|WATCH' | grep -E 'granted=true' | tr -s ' ' | sort -u | tr '\n' '|' | head -c 900)"
p "launcher $hp providers/queries mentioning watch/engage: $(sh_ dumpsys package "$hp" | grep -ioE '(watch_next[a-z_]*|engage[a-z._]*|tvrecommendations[a-z._]*)' | sort -u | tr '\n' ' ' | head -c 400)"

out=$(a install --no-incremental -r "$APK" 2>&1 | tr -d '\r'); grep -q Success <<<"$out" || { echo "::error::install failed: $out"; exit 1; }
p "install (no -g): READ_EPG_DATA granted? $(sh_ dumpsys package $PKG | grep -m1 "$EPG" | tr -s ' ')"

# Seed: a row from another uid (the shell) and one of our own.
now=$(( $(date +%s) * 1000 ))
p "seed shell insert: $(sh_ content insert --uri $WN --bind type:i:3 --bind watch_next_type:i:0 --bind title:s:ProbeShellSeries --bind episode_title:s:EpTwo --bind season_display_number:s:1 --bind episode_display_number:s:2 --bind intent_uri:s:https://example.com/s/1 --bind last_engagement_time_utc_millis:l:$now 2>&1 | head -3 | tr '\n' ' ')"
p "seed shell query (shell's own view): $(sh_ content query --uri $WN --projection package_name:title 2>&1 | head -8 | tr '\n' '|')"
run insert_own own-insert
run query no-perm

p "pm grant READ_EPG_DATA: [$(sh_ pm grant $PKG $EPG 2>&1 | head -2 | tr '\n' ' ')] exit=$(timeout 60 adb shell pm grant $PKG $EPG >/dev/null 2>&1; echo $?)"
p "appops / dumpsys after grant: $(sh_ dumpsys package $PKG | grep -m1 "$EPG" | tr -s ' ')"
run query after-grant

# (b) UsageStats: before and after the appop, after using two apps.
for app in com.google.android.youtube.tv com.android.tv.settings; do
  sh_ monkey -p "$app" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 || true; sleep 4
done
sh_ input keyevent KEYCODE_HOME; sleep 2
run usage usage-before
p "pm grant PACKAGE_USAGE_STATS: [$(sh_ pm grant $PKG android.permission.PACKAGE_USAGE_STATS 2>&1 | head -2 | tr '\n' ' ')]"
p "appops set GET_USAGE_STATS allow: [$(sh_ appops set $PKG GET_USAGE_STATS allow 2>&1 | head -2 | tr '\n' ' ')] now: $(sh_ appops get $PKG GET_USAGE_STATS | tr '\n' ' ')"
run usage usage-after

# (c) our own Open history: only needs a deep link the target resolves.
p "c: YouTube VIEW deep link resolves to: $(sh_ cmd package resolve-activity --brief -a android.intent.action.VIEW -d 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' com.google.android.youtube.tv | tail -1)"
p "done"
