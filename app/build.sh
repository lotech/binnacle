#!/bin/bash
# Builds build/binnacle.apk with the Android SDK command-line tools (no Gradle).
#   ANDROID_HOME   SDK path (default ~/Library/Android/sdk)
#   KEYSTORE       signing keystore (default ~/.android/debug.keystore, alias androiddebugkey, pass android)
#   RELEASE=1      sign with the release key instead (default ~/.android/binnacle-release.jks, alias
#                  binnacle); apksigner asks for its password on the terminal, nothing stores it.
#                  Also writes build/Binnacle-<version>.apk for the GitHub release.
# Keep using the same keystore for every build, or `adb install -r` will refuse the upgrade.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
JAR="$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)/android.jar"
RELEASE="${RELEASE:-}"
if [ -n "$RELEASE" ]; then
    KEYSTORE="${KEYSTORE:-$HOME/.android/binnacle-release.jks}"
    KS_ALIAS="${KS_ALIAS:-binnacle}"
else
    KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
    KS_ALIAS="${KS_ALIAS:-androiddebugkey}"
    KS_PASS="${KS_PASS:-android}"
fi
# Installed package name: com.xbs.launcher, so the stock CAN app treats the dash as its own launcher and
# skips its door popup (see com.xbs.launcher.MainActivity). The Java code stays in app.binnacle.
# APP_ID=app.binnacle builds a copy that can sit beside it (the pop-up then shows as before).
APP_ID="${APP_ID:-com.xbs.launcher}"
OUT=build

# iCloud conflict copies ("Name 2.java") would be compiled or packed in: refuse.
dupes=$(find src res assets \( -name '* 2.*' -o -name '* 2' \) | head -3)
[ -z "$dupes" ] || { echo "iCloud duplicates in the tree (delete them first):"; echo "$dupes"; exit 1; }

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
# ...and check what javac produced as well: no source trick gets past the bytecode.
tools/check_bytecode.sh "$OUT/classes"
"$BT/d8" --min-api 21 --release --lib "$JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')

(cd "$OUT" && zip -q unsigned.apk classes.dex)
tools/check_bytecode.sh --apk "$OUT/unsigned.apk"   # one dex, no native code, nothing hidden in assets
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ -n "$RELEASE" ]; then          # no --ks-pass: apksigner prompts for it
    "$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" --out "$OUT/binnacle.apk" "$OUT/aligned.apk"
else
    "$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" --ks-pass "pass:$KS_PASS" \
        --out "$OUT/binnacle.apk" "$OUT/aligned.apk"
fi
"$BT/apksigner" verify "$OUT/binnacle.apk"
if [ -n "$RELEASE" ]; then
    VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
    cp "$OUT/binnacle.apk" "$OUT/Binnacle-$VERSION.apk"
    echo "release copy: $OUT/Binnacle-$VERSION.apk"
fi
echo "built $OUT/binnacle.apk as $APP_ID ($(du -h "$OUT/binnacle.apk" | cut -f1))"
