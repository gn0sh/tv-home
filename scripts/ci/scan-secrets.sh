#!/usr/bin/env bash
# Fails if any file under the given paths (or a log file) holds something shaped like a Google Calendar secret URL
# token (`private-` + 32 hex digits, either case) or an Anthropic API key prefix (`sk-ant-`), except the exact fake
# constants that the test fixtures use (core/src/test/kotlin/tvhome/core/TestSupport.kt, object FakeSecrets).
# Security finding #9.
#
# Nested archives (zip, jar, aar, apk; detected by the PK\3\4 magic bytes) are unpacked and scanned recursively, up to
# MAX_DEPTH levels deep. An archive nested deeper than that fails the scan, so nothing is skipped silently.
#
# Out of scope: a secret that is base64-encoded, percent-encoded, split across strings, compressed with something
# other than zip, or otherwise transformed. This is a tripwire for plain leaks, not a proof that none exist.
#
# Usage: scan-secrets.sh <file-or-dir>...   Prints file names and match counts only, never the matched text.
set -euo pipefail

TESTSUPPORT="${TESTSUPPORT:-core/src/test/kotlin/tvhome/core/TestSupport.kt}"
MAX_DEPTH="${MAX_DEPTH:-4}"

# Guard against drift: the exclusions below must still be the constants TestSupport.kt builds.
grep -qF '"private-" + "f".repeat(32)' "$TESTSUPPORT" || { echo "scan-secrets: fake calendar token in $TESTSUPPORT changed; update this script" >&2; exit 2; }
grep -qF '"sk-ant-" + "f".repeat(40)' "$TESTSUPPORT" || { echo "scan-secrets: fake API key in $TESTSUPPORT changed; update this script" >&2; exit 2; }
FAKE_TOKEN="private-$(printf 'f%.0s' $(seq 1 32))"
FAKE_KEY="sk-ant-$(printf 'f%.0s' $(seq 1 40))"

# Prefix match for sk-ant- (a bare prefix in shipped code also counts), 32-hex match (either case) for the token.
PATTERN='private-[0-9a-fA-F]{32}|sk-ant-[A-Za-z0-9_-]*'

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
status=0

is_zip() { [ "$(head -c 4 -- "$1" | od -An -tx1 | tr -d ' \n')" = "504b0304" ]; }

scan_target() { # $1 = file or dir, $2 = depth, $3 = label shown in messages
  local target="$1" depth="$2" label="$3" file hits sub n=0
  while IFS= read -r -d '' file; do
    local shown="$label${file#"$target"}"
    # Plain bytes (dex, UTF-8 resources, logs) and UTF-16LE strings (resources.arsc can use UTF-16 pools).
    hits=$( { grep -aoE "$PATTERN" -- "$file" || true
              { strings -a -el -- "$file" 2>/dev/null | grep -aoE "$PATTERN" || true; }
            } | grep -vxF -e "$FAKE_TOKEN" -e "$FAKE_KEY" || true)
    if [ -n "$hits" ]; then
      echo "scan-secrets: $(printf '%s\n' "$hits" | wc -l) suspicious match(es) in $shown"
      status=1
    fi
    if [ -s "$file" ] && is_zip "$file"; then
      if [ "$depth" -ge "$MAX_DEPTH" ]; then
        echo "scan-secrets: archive nested deeper than $MAX_DEPTH levels, not scanned: $shown"
        status=1
        continue
      fi
      n=$((n + 1))
      sub=$(mktemp -d -p "$WORK")
      if unzip -q -o -- "$file" -d "$sub" 2>/dev/null; then
        scan_target "$sub" $((depth + 1)) "$shown!"
      else
        echo "scan-secrets: could not unpack archive: $shown"
        status=1
      fi
    fi
  done < <(find "$target" -type f -print0)
}

for target in "$@"; do
  [ -e "$target" ] || { echo "scan-secrets: $target does not exist" >&2; exit 2; }
  scan_target "$target" 0 "$target"
done
[ "$status" -eq 0 ] && echo "scan-secrets: clean"
exit "$status"
