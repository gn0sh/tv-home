#!/usr/bin/env bash
# Puts a secret on an emulator/CI device over adb stdin. Never echoes, logs or passes the value in argv.
# Usage: provision.sh [-k calendar_url|anthropic_api_key] [-s SERIAL] [-c]   (-c = clear)
# The value is read from stdin, or from the env var PROVISION_VALUE (kept off argv; unset right after reading).
# Set PROVISION_CHECK_FEED=1 to download a calendar feed once and print counts only.
set -euo pipefail
umask 077

key=calendar_url; serial=""; clear=0
while getopts "k:s:c" o; do
  case $o in k) key=$OPTARG ;; s) serial=$OPTARG ;; c) clear=1 ;; *) echo "usage: provision.sh [-k key] [-s serial] [-c]" >&2; exit 2 ;; esac
done
case $key in calendar_url|anthropic_api_key) ;; *) echo "ERROR: unknown key" >&2; exit 2 ;; esac
[[ -z $serial || $serial =~ ^[A-Za-z0-9:._-]+$ ]] || { echo "ERROR: odd serial" >&2; exit 2; }
command -v adb >/dev/null || { echo "ERROR: adb not found" >&2; exit 1; }

adbc() { if [[ -n $serial ]]; then adb -s "$serial" "$@"; else adb "$@"; fi; }
uri="content://com.gnosh.tvhome.provision/$key"

n=$(adb devices | awk 'NR>1 && NF {print}' | { if [[ -n $serial ]]; then grep -c "^$serial[[:space:]]" || true; else grep -c . || true; fi; })
[[ $n -eq 1 ]] || { echo "ERROR: need exactly one device (found $n); use -s SERIAL" >&2; exit 1; }

show_state() {
  local q c l
  q=$(adbc shell content query --uri "$uri" 2>&1 || true)
  c=$(sed -n 's/.*configured=\([0-9]\).*/\1/p' <<<"$q" | head -n1)
  l=$(sed -n 's/.*last=\([A-Z_]*\).*/\1/p' <<<"$q" | head -n1)
  echo "configured=${c:-?} last=${l:-?}"
  [[ ${c:-?} == 1 ]]
}

if [[ $clear -eq 1 ]]; then
  adbc shell content delete --uri "$uri" >/dev/null 2>&1 || { echo "ERROR: clear refused" >&2; exit 1; }
  show_state && { echo "ERROR: still configured" >&2; exit 1; }
  echo "Removed."; exit 0
fi

if [[ -n ${PROVISION_VALUE+x} ]]; then value=$PROVISION_VALUE; unset PROVISION_VALUE
else IFS= read -r value || true; fi
# Trim space and tab only (same as the TV).
value="${value#"${value%%[![:space:]]*}"}"; value="${value%"${value##*[![:space:]]}"}"
value=${value#$'\r'}; value=${value%$'\r'}
if [[ -z $value ]]; then echo "ERROR: nothing entered" >&2; exit 1; fi
case $key in
  calendar_url) re='^https://calendar\.google\.com/calendar/ical/[A-Za-z0-9%._-]+/private-[0-9a-f]{32}/basic\.ics$' ;;
  anthropic_api_key) re='^sk-ant-[A-Za-z0-9_-]{20,500}$' ;;
esac
if [[ ${#value} -gt 1024 || ! $value =~ $re ]]; then value=""; echo "ERROR: not valid for this key; nothing sent" >&2; exit 1; fi

# printf is a bash builtin, so the value never reaches any process's argv. No trailing newline.
if ! printf '%s' "$value" | adbc shell content write --uri "$uri" >/dev/null 2>&1; then
  value=""; echo "ERROR: content write failed" >&2; exit 1
fi
sleep 1
if ! show_state; then value=""; echo "ERROR: the TV did not store it" >&2; exit 1; fi
echo "Saved."

if [[ $key == calendar_url && ${PROVISION_CHECK_FEED:-0} == 1 ]]; then
  # curl reads the URL from a config on a pipe, so it is not in argv.
  tmp=$(mktemp); trap 'rm -f "$tmp"' EXIT
  if curl -sS --max-time 30 --max-filesize 10485760 -K <(printf 'url = "%s"\n' "$value") -o "$tmp" 2>/dev/null; then
    size=$(wc -c <"$tmp"); ev=$(grep -c '^BEGIN:VEVENT' "$tmp" || true); rr=$(grep -Ec '^RRULE[:;]' "$tmp" || true)
    ok=no; [[ $size -le 10485760 && $ev -le 50000 && $rr -le 5000 ]] && ok=yes
    echo "Feed: size=$((size/1024)) KB, VEVENT=$ev, RRULE=$rr, within app limits: $ok"
  else
    echo "Feed check: could not download the feed (the link is saved anyway)."
  fi
fi
value=""
