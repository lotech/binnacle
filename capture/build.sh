#!/bin/bash
# Builds build/BinnacleLogger.apk (no Gradle). Same tools and key as app/build.sh: the dash and the
# logger share a signature permission, so they must be signed with the same key. RELEASE=1 signs
# with the release key (apksigner asks for its password).
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
JAR="$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)/android.jar"
if [ -n "${RELEASE:-}" ]; then
    KEYSTORE="${KEYSTORE:-$HOME/.android/binnacle-release.jks}"; KS_ALIAS="${KS_ALIAS:-binnacle}"
else
    KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"; KS_ALIAS="${KS_ALIAS:-androiddebugkey}"; KS_PASS="${KS_PASS:-android}"
fi
OUT=build
# Refuse to build anything that could write to the car: the source, then (below) the compiled
# classes and the APK. The only binder calls allowed are IMcuService register (1) and unregister (2).
tools/check_readonly.sh
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/res"
"$BT/aapt2" compile --dir res -o "$OUT/res/"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml --min-sdk-version 21 --target-sdk-version 29 "$OUT"/res/*.flat
javac -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$JAR" -d "$OUT/classes" $(find src -name '*.java')
tools/check_bytecode.sh "$OUT/classes"
"$BT/d8" --min-api 21 --release --lib "$JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')
(cd "$OUT" && zip -q unsigned.apk classes.dex)
tools/check_bytecode.sh --apk "$OUT/unsigned.apk"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ -n "${RELEASE:-}" ]; then
    "$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" --out "$OUT/BinnacleLogger.apk" "$OUT/aligned.apk"
else
    "$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" --ks-pass "pass:$KS_PASS" --out "$OUT/BinnacleLogger.apk" "$OUT/aligned.apk"
fi
echo "built $OUT/BinnacleLogger.apk"
