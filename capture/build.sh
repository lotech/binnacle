#!/bin/bash
# Builds build/BinnacleLogger.apk (no Gradle). Same tools and debug key as app/build.sh.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
JAR="$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)/android.jar"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
OUT=build
# receive-only: the only binder calls allowed are IMcuService register (1) and unregister (2)
if perl -ne 'print "$ARGV:$.: $_" if /\.transact\((?![12],)/' $(find src -name '*.java') | grep .; then echo "refusing: unexpected transact()"; exit 1; fi
if grep -rnE 'tx(MessengerPack|GeneralProtocol|PerformUartPack|UpdateProtocol)|McuSerial|com\.xbs\.can\.provider' src; then echo "refusing: car-write API"; exit 1; fi
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/res"
"$BT/aapt2" compile --dir res -o "$OUT/res/"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml --min-sdk-version 21 --target-sdk-version 29 "$OUT"/res/*.flat
javac -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$JAR" -d "$OUT/classes" $(find src -name '*.java')
"$BT/d8" --min-api 21 --release --lib "$JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')
(cd "$OUT" && zip -q unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey --ks-pass pass:android --out "$OUT/BinnacleLogger.apk" "$OUT/aligned.apk"
echo "built $OUT/BinnacleLogger.apk"
