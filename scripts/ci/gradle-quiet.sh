#!/usr/bin/env bash
# Runs ./gradlew with all output going to a file only, scans that file for secret patterns, and prints the output
# only if the scan passes (otherwise prints a redacted notice). Exit code: Gradle's, or 1 if the scan failed.
# Usage: gradle-quiet.sh <logfile> <gradlew args...>   (security finding #9: log-scan-after-exposure)
set -uo pipefail
log="$1"; shift
rc=0
./gradlew --no-daemon --console=plain "$@" > "$log" 2>&1 || rc=$?
if scripts/ci/scan-secrets.sh "$log"; then
  cat "$log"
else
  echo "::error::Gradle output withheld: it matched a secret pattern (see the scan lines above for the file name only)."
  [ "$rc" -eq 0 ] && rc=1
fi
exit "$rc"
