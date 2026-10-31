#!/bin/bash
# Builds build/binnacle.apk with the Android SDK command-line tools (no Gradle).
#   ANDROID_HOME   SDK path (default ~/Library/Android/sdk)
#   KEYSTORE       signing keystore (default ~/.android/debug.keystore, alias androiddebugkey, pass android)
# Keep using the same keystore for every build, or `adb install -r` will refuse the upgrade.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
JAR="$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)/android.jar"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
KS_ALIAS="${KS_ALIAS:-androiddebugkey}"
KS_PASS="${KS_PASS:-android}"
# Installed package name: com.xbs.launcher, so the stock CAN app treats the dash as its own launcher and
# skips its door popup (see com.xbs.launcher.MainActivity). The Java code stays in app.binnacle.
# APP_ID=app.binnacle builds a copy that can sit beside it (the pop-up then shows as before).
APP_ID="${APP_ID:-com.xbs.launcher}"
OUT=build

# Refuse to build anything that could write to the car.
tools/check_readonly.sh
tests/run.sh

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes"

"$BT/aapt2" compile --dir res -o "$OUT/res/"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml --rename-manifest-package "$APP_ID" \
    --java "$OUT/gen" --min-sdk-version 21 --target-sdk-version 29 -A assets "$OUT"/res/*.flat

javac -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$JAR" -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java')
"$BT/d8" --min-api 21 --release --lib "$JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')

(cd "$OUT" && zip -q unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" --ks-pass "pass:$KS_PASS" \
    --out "$OUT/binnacle.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/binnacle.apk"
echo "built $OUT/binnacle.apk as $APP_ID ($(du -h "$OUT/binnacle.apk" | cut -f1))"
