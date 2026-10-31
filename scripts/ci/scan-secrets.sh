#!/usr/bin/env bash
# Fails if any file under the given paths (or a log file) holds something shaped like a Google Calendar secret URL
# token (`private-` + 32 hex) or an Anthropic API key prefix (`sk-ant-`), except the exact fake constants that the
# test fixtures use (core/src/test/kotlin/tvhome/core/TestSupport.kt, object FakeSecrets). Security finding #9.
# Usage: scan-secrets.sh <file-or-dir>...   Prints file names and match counts only, never the matched text.
set -euo pipefail

TESTSUPPORT="${TESTSUPPORT:-core/src/test/kotlin/tvhome/core/TestSupport.kt}"

# Guard against drift: the exclusions below must still be the constants TestSupport.kt builds.
grep -qF '"private-" + "f".repeat(32)' "$TESTSUPPORT" || { echo "scan-secrets: fake calendar token in $TESTSUPPORT changed; update this script" >&2; exit 2; }
grep -qF '"sk-ant-" + "f".repeat(40)' "$TESTSUPPORT" || { echo "scan-secrets: fake API key in $TESTSUPPORT changed; update this script" >&2; exit 2; }
FAKE_TOKEN="private-$(printf 'f%.0s' $(seq 1 32))"
FAKE_KEY="sk-ant-$(printf 'f%.0s' $(seq 1 40))"

# Prefix match for sk-ant- (a bare prefix in shipped code also counts), 32-hex match for the calendar token.
PATTERN='private-[0-9a-f]{32}|sk-ant-[A-Za-z0-9_-]*'

status=0
for target in "$@"; do
  [ -e "$target" ] || { echo "scan-secrets: $target does not exist" >&2; exit 2; }
  while IFS= read -r -d '' file; do
    # Plain bytes (dex, UTF-8 resources, logs) and UTF-16LE strings (resources.arsc can use UTF-16 pools).
    hits=$( { grep -aoE "$PATTERN" -- "$file" || true
              { strings -a -el -- "$file" 2>/dev/null | grep -aoE "$PATTERN" || true; }
            } | grep -vxF -e "$FAKE_TOKEN" -e "$FAKE_KEY" || true)
    if [ -n "$hits" ]; then
      echo "scan-secrets: $(printf '%s\n' "$hits" | wc -l) suspicious match(es) in $file"
      status=1
    fi
  done < <(find "$target" -type f -print0)
done
[ "$status" -eq 0 ] && echo "scan-secrets: clean"
exit "$status"
