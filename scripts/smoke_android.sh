#!/usr/bin/env bash
set -euo pipefail
BASE="app/build/outputs/apk/debug/app-debug.apk"
gradle --no-daemon :app:assembleDebug
test -s "$BASE"

smoke_launch() {
  local label="$1"
  echo "Installing test build: $label"
  adb logcat -c
  adb shell am force-stop com.panlocal.tool >/dev/null 2>&1 || :
  adb shell am start -W -n com.panlocal.tool/.LocalDriveActivity
  sleep 6
  if ! adb shell pidof com.panlocal.tool; then
    echo "FAIL: $label process not running"
    adb logcat -d -b crash -b main -v threadtime | grep -Ei 'panlocal|AndroidRuntime|fatal|verifyerror|dex|zygote|not found|classnotfound|Exception' | tail -100 || :
    adb shell dumpsys dropbox --print 2>/dev/null | grep -Ei -A 10 'panlocal|data_app_crash' | tail -70 || :
    return 1
  fi
  if adb logcat -d -b crash | grep -Eq 'FATAL EXCEPTION|Process: com.panlocal.tool'; then
    echo "FAIL: fatal Android crash ($label)"
    adb logcat -d -b crash | tail -140
    return 1
  fi
  echo "PASS: $label process active after launch"
}

adb install -r --no-incremental "$BASE"
smoke_launch "plain CI APK"

# Repeat the same personalization and APK signing process as the private
# owner build, but only with a public synthetic test value, never real secrets.
adb uninstall com.panlocal.tool
# First isolate effects of repacking/signing WITHOUT patching any DEX strings.
BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
"$BUILD_TOOLS/zipalign" -f 4 "$BASE" /tmp/resigned-aligned.apk
"$BUILD_TOOLS/apksigner" sign --ks /tmp/owner-ci.jks --ks-key-alias panlocal \
  --ks-pass pass:example-pass-12345 --key-pass pass:example-pass-12345 \
  --out /tmp/resigned.apk /tmp/resigned-aligned.apk
"$BUILD_TOOLS/apksigner" verify --verbose /tmp/resigned.apk
adb install -r --no-incremental /tmp/resigned.apk
smoke_launch "re-signed without DEX changes"
adb uninstall com.panlocal.tool
export PANLOCAL_EMBEDDED_SECRET="$(printf 'T%031d' 0)"
python3 scripts/personalize_apk.py "$BASE" /tmp/owner-unsigned.apk
unset PANLOCAL_EMBEDDED_SECRET

"$BUILD_TOOLS/zipalign" -f 4 /tmp/owner-unsigned.apk /tmp/owner-aligned.apk
"$BUILD_TOOLS/zipalign" -c 4 /tmp/owner-aligned.apk
keytool -genkeypair -noprompt -keystore /tmp/owner-ci.jks -alias panlocal \
  -storepass example-pass-12345 -keypass example-pass-12345 \
  -keyalg RSA -keysize 3072 -validity 3650 \
  -dname "CN=PanLocal Test APK, OU=CI, O=PanLocal, C=SG"
"$BUILD_TOOLS/apksigner" sign --ks /tmp/owner-ci.jks --ks-key-alias panlocal \
  --ks-pass pass:example-pass-12345 --key-pass pass:example-pass-12345 \
  --out /tmp/owner-personal-smoke.apk /tmp/owner-aligned.apk
"$BUILD_TOOLS/apksigner" verify --verbose /tmp/owner-personal-smoke.apk
adb install -r --no-incremental /tmp/owner-personal-smoke.apk
smoke_launch "post-injection and official APK signature"
adb shell uiautomator dump /sdcard/window.xml || :
adb shell cat /sdcard/window.xml | grep -F 'PanLocal V2.1' >/dev/null || \
  echo 'NOTE: UI text not visible in UIAutomator snapshot'
echo "PASS: both plain and personalized APK launched"
