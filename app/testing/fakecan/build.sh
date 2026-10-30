#!/bin/bash
# Builds the EMULATOR-ONLY fake CAN service (testing/fakecan/build/fakecan.apk).
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
JAR="$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)/android.jar"
rm -rf build && mkdir -p build/classes
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml
javac -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --min-api 21 --lib "$JAR" --output build $(find build/classes -name '*.class')
(cd build && zip -q unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --out build/fakecan.apk build/aligned.apk
echo "built testing/fakecan/build/fakecan.apk"
