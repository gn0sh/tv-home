#!/usr/bin/env bash
# One-off (branch board-tour, never merged): builds tiny code-free TV apps for the Watch pill tour (PR #30).
# Each has one LEANBACK_LAUNCHER activity that is the framework's own android.app.Activity (android:hasCode="false"),
# so it launches to a blank screen and needs no dex. Signed with a throwaway key made here; nothing is reused.
#
# Usage: tour-fake-apps.sh <out-dir>   -> <out-dir>/cde-a.apk, cde-b.apk, espn.apk
set -euo pipefail
OUT=${1:?out-dir}
mkdir -p "$OUT"
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?no Android SDK}}
BT=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
JAR=$(find "$SDK/platforms" -name android.jar | sort -V | tail -1)
[[ -x $BT/aapt2 && -f $JAR ]] || { echo "::error::aapt2 or android.jar missing"; exit 1; }
WORK=$(mktemp -d)
keytool -genkeypair -keystore "$WORK/k.jks" -storepass tourpass -keypass tourpass -alias tour -keyalg RSA \
  -keysize 2048 -validity 2 -dname "CN=tour fake app" >/dev/null 2>&1

make_app() { # $1 file name, $2 package, $3 label
  local d="$WORK/$1"
  mkdir -p "$d"
  cat > "$d/AndroidManifest.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$2">
  <uses-feature android:name="android.software.leanback" android:required="false" />
  <uses-feature android:name="android.hardware.touchscreen" android:required="false" />
  <application android:label="$3" android:hasCode="false">
    <activity android:name="android.app.Activity" android:label="$3" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
      </intent-filter>
    </activity>
  </application>
</manifest>
EOF
  "$BT/aapt2" link -o "$d/u.apk" -I "$JAR" --manifest "$d/AndroidManifest.xml" --min-sdk-version 26 --target-sdk-version 34
  "$BT/zipalign" -f 4 "$d/u.apk" "$d/a.apk"
  "$BT/apksigner" sign --ks "$WORK/k.jks" --ks-pass pass:tourpass --ks-key-alias tour --out "$OUT/$1.apk" "$d/a.apk"
  echo "tour-fake-apps: $1.apk = $2 \"$3\""
}
make_app cde-a com.example.tourfake.cde "CDE"
make_app cde-b com.example.tourfake.cdetwo "CDE"
make_app espn com.example.tourfake.espn "ESPN"
rm -rf "$WORK"
