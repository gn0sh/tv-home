#!/usr/bin/env bash
# Fake-adb tests for provision.sh: the value goes only to stdin, never argv or output; failures are detected.
# Uses the one FakeSecrets calendar token from core/src/test/.../TestSupport.kt (no real secret anywhere).
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
t=$(mktemp -d); trap 'rm -rf "$t"' EXIT; mkdir "$t/bin"
tok="private-$(printf 'f%.0s' {1..32})"
secret="https://calendar.google.com/calendar/ical/fake%40example.test/$tok/basic.ics"

# Fake adb. FAKE_LAST = what `content query` reports as "last"; FAKE_WRITE_OUT = what `content write` prints.
cat > "$t/bin/adb" <<SH
#!/usr/bin/env bash
echo "\$*" >> "$t/argv.log"
case "\$*" in
 devices) printf 'List of devices attached\nemulator-5554\tdevice\nemulator-5556\toffline\n';;
 *"pm path"*) echo "package:/data/app/x/base.apk";;
 *"content write"*) cat > "$t/stdin.bin"; echo "\${FAKE_WRITE_OUT:-}";;
 *"content query"*) echo "Row: 0 configured=1, last=\${FAKE_LAST:-SAVED_CALENDAR}";;
esac
SH
chmod +x "$t/bin/adb"
run() { PATH="$t/bin:$PATH" "$here/provision.sh" "$@"; }
fail() { echo "FAIL: $1"; exit 1; }

# 1. happy path; leading spaces/tab and a trailing newline are trimmed
out=$(printf ' \t%s\n' "$secret" | run 2>&1)
cmp <(printf %s "$secret") "$t/stdin.bin" || fail "stdin differs"
! grep -q 'private-' "$t/argv.log" || fail "value in argv"
! grep -q 'private-' <<<"$out" || fail "value in output"
grep -q 'Saved' <<<"$out" || fail "no success"

# 2. bad value is refused, nothing sent
: > "$t/argv.log"
if echo "https://evil.example/x" | run >/dev/null 2>&1; then fail "bad value accepted"; fi
! grep -q 'content write' "$t/argv.log" || fail "bad value was sent"

# 3. refused rewrite: configured=1 but last != SAVED_* is a failure
if echo "$secret" | FAKE_LAST=INVALID run >/dev/null 2>&1; then fail "stale success"; fi

# 4. provider error text with exit 0 is a failure, and the value is not echoed
set +e; out=$(echo "$secret" | FAKE_WRITE_OUT="Error: Unknown authority com.gnosh.tvhome.provision" run 2>&1); rc=$?; set -e
[[ $rc -ne 0 ]] || fail "provider error accepted"
! grep -q 'private-' <<<"$out" || fail "value in error output"

# 5. no am start fallback anywhere
! grep -q 'am start' "$t/argv.log" || fail "am start used"
echo "provision.sh tests passed"
