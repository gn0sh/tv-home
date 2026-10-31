#!/usr/bin/env bash
# Fake-adb test: provision.sh sends the value only on stdin; never in argv or output.
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
t=$(mktemp -d); trap 'rm -rf "$t"' EXIT; mkdir "$t/bin"
cat > "$t/bin/adb" <<SH
#!/usr/bin/env bash
echo "\$*" >> "$t/argv.log"
case "\$*" in
 devices) printf 'List of devices attached\nemulator-5554\tdevice\n';;
 *"content write"*) cat > "$t/stdin.bin";;
 *"content query"*) echo "Row: 0 configured=1, last=SAVED_CALENDAR";;
esac
SH
chmod +x "$t/bin/adb"
secret="https://calendar.google.com/calendar/ical/me%40gmail.com/private-0123456789abcdef0123456789abcdef/basic.ics"
out=$(printf '  %s\n' "$secret" | PATH="$t/bin:$PATH" "$here/provision.sh" 2>&1)
cmp <(printf %s "$secret") "$t/stdin.bin" || { echo "FAIL: stdin differs"; exit 1; }
! grep -q 'private-' "$t/argv.log" || { echo "FAIL: value in argv"; exit 1; }
! grep -q 'private-' <<<"$out" || { echo "FAIL: value in output"; exit 1; }
if echo "https://evil.example/x" | PATH="$t/bin:$PATH" "$here/provision.sh" >/dev/null 2>&1; then echo "FAIL: bad value accepted"; exit 1; fi
echo "provision.sh tests passed"
