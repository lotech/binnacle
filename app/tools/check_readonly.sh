#!/bin/bash
# ABSOLUTE RULE: this app never sends anything to the CAN bus or the MCU. It only receives.
# This guard runs before every build (build.sh) and fails it if the source could write to the car.
set -uo pipefail
cd "$(dirname "$0")/.."
SRC="src AndroidManifest.xml res"
fail=0
bad() { echo "check_readonly: $*" >&2; fail=1; }

# 1. No reference to any car-write API: the IMcuService send/tx methods, the MCU serial writer,
#    the CAN content provider, the car-settings app, or content-resolver writes.
FORBIDDEN='txMessengerPack|txGeneralProtocol|txPerformUartPack|txUpdateProtocol|setUartNormalTransmitEnable|McuSerial|McuManager|writeData|com\.mct\.serial|com\.xbs\.can\.provider|com\.xbs\.carsetup|ContentProviderClient|\.insert\(|\.update\(|\.delete\(|bulkInsert|applyBatch|openOutputStream|openFileDescriptor|/dev/tty|\.put(Int|Long|Float|String)\(\s*(c\.)?getContentResolver|Settings\.(System|Global|Secure)\.put|(System|Global|Secure)\.put(Int|Long|Float|String)|SystemProperties|WRITE_SETTINGS|WRITE_SECURE_SETTINGS'
grep -rnE "$FORBIDDEN" $SRC && bad "forbidden car-write reference found (above)"

# 1b. A content resolver may be used in StockScreens.java only, for exactly one read:
#     Settings.System.getInt(c.getContentResolver(), KEY_LAYOUT, 0). Fails closed on grep errors.
ALLOWED='src/app/binnacle/StockScreens.java'
hits=$(grep -rlE 'ContentResolver|getContentResolver' $SRC); rc=$?
[ $rc -le 1 ] || bad "grep failed scanning for content resolver use"
for f in $hits; do [ "$f" = "$ALLOWED" ] || bad "content resolver used outside StockScreens: $f"; done
[ "$(grep -oE 'getContentResolver|ContentResolver' "$ALLOWED" | wc -l | tr -d ' ')" = 1 ] || bad "StockScreens must use the content resolver exactly once"
grep -q 'Settings.System.getInt(c.getContentResolver(), KEY_LAYOUT, 0)' "$ALLOWED" || bad "StockScreens may only read Settings.System xbs_carmsgdisppost"
grep -nE '\.(put|call|query|register|notify|acquire)[A-Za-z]*\(' "$ALLOWED" && bad "StockScreens may only read (above)"

# 2. No AIDL stubs or proxies for the stock service (so no write method can even be declared).
find src -name '*.aidl' | grep . && bad "AIDL files are not allowed"
grep -rnE 'IMcuService\.(Stub|Proxy)|extends +Binder|implements +IInterface' src && bad "service stub/proxy found (above)"

# 3. Exactly one class may make binder calls, and only IMcuService transactions 1 and 2.
hits=$(grep -rl '\.transact(' src)
[ "$hits" = "src/app/binnacle/CanFeed.java" ] || bad "binder transact() outside CanFeed: $hits"
[ "$(grep -c '\.transact(' src/app/binnacle/CanFeed.java)" = 1 ] || bad "CanFeed must have exactly one transact() call"
grep -q 'TX_REGISTER = 1, TX_UNREGISTER = 2;' src/app/binnacle/CanFeed.java || bad "transaction codes changed"
grep -q 'if (txn != TX_REGISTER && txn != TX_UNREGISTER) {' src/app/binnacle/CanFeed.java || bad "transaction allow-list changed"
[ "$(grep -cE 'call\(TX_(REGISTER|UNREGISTER), ' src/app/binnacle/CanFeed.java)" = 2 ] || bad "call() used with something other than TX_REGISTER / TX_UNREGISTER"
[ "$(grep -cE '[^.a-zA-Z]call\(' src/app/binnacle/CanFeed.java)" = 3 ] || bad "unexpected call() use in CanFeed"

# 4. Local shell (the unit's own ADB on 127.0.0.1): one class, fixed commands, nothing near the car.
hits=$(grep -rlE 'Socket\(|ServerSocket|ProcessBuilder|Runtime\.getRuntime' src)
[ -z "$hits" ] || [ "$hits" = "src/app/binnacle/LocalShell.java" ] || bad "sockets/processes outside LocalShell: $hits"
grep -nE 'ProcessBuilder|Runtime\.getRuntime' src/app/binnacle/LocalShell.java && bad "LocalShell must not start processes (above)"
cmds=$(grep -oE '^ *[A-Z_]+\("[^"]*"\)' src/app/binnacle/LocalShell.java | sed -E 's/^ *//')
expected='END_CARPLAY("svc wifi disable && sleep 2 && svc wifi enable && echo BINNACLE_OK")
BT_AUTOCONNECT_GET("settings get system xbs_isautoconnect && echo BINNACLE_OK")
BT_AUTOCONNECT_ON("settings put system xbs_isautoconnect true && echo BINNACLE_OK")
BT_AUTOCONNECT_OFF("settings put system xbs_isautoconnect false && echo BINNACLE_OK")'
[ "$cmds" = "$expected" ] || bad "LocalShell command list changed: review it, then update this guard"
[ "$(grep -c 'shell:' src/app/binnacle/LocalShell.java)" = 1 ] && grep -qF '("shell:" + cmd.shell + "\0")' src/app/binnacle/LocalShell.java || bad "LocalShell may only open shell:<Command.shell>"
grep -rnE 'LocalShell\.run\(' src | grep -vE 'LocalShell\.run\(cmd\)' && bad "LocalShell.run must take a Command (above)"

# 5. File writes: only CameraPane, only the camera decoder's two nodes (local hardware, not the car).
hits=$(grep -rlE 'FileWriter|FileOutputStream|RandomAccessFile' src)
[ -z "$hits" ] || [ "$hits" = "src/app/binnacle/CameraPane.java" ] || bad "file writes outside CameraPane: $hits"
grep -q 'DECODER_DIR = "/sys/ziqi/camera", INPUT = "2", SIGNAL = "AHD_1080P_30";' src/app/binnacle/CameraPane.java || bad "camera decoder write target changed"
[ "$(grep -cE 'writeDecoder\("' src/app/binnacle/CameraPane.java)" = 2 ] || bad "CameraPane may write only channel and N_P_pre_format"

[ $fail -eq 0 ] && echo "check_readonly: OK (receive-only: IMcuService register/unregister only)"
exit $fail
