# Binnacle — context for Claude

> **ABSOLUTE RULE — RECEIVE ONLY.** Nothing in this project ever sends anything to the car's CAN bus or the MCU. The dash app only *subscribes* to the stock CAN service and *receives* data. This is enforced in code (one class, two allowed binder calls: subscribe/unsubscribe) and by a build-time guard (`app/tools/check_readonly.sh`) that refuses to build if any write path is referenced. Never weaken or bypass it.

A read-only replacement instrument cluster / dashboard app (plus tooling) for the **Carview 10.25" Tesla Model 3/Y Android cluster** (ZIQI "A007" ROM). It shows live car data taken from the stock CAN app and launches CarPlay / Android Auto / the camera, running as an alternative Home app. It never modifies the stock system.

## The unit
- Carview 10.25" (sold by smeshmega), ODM **ZIQI A007**. SoC **Unisoc UMS512**. Android **10** under the hood (SDK 29) despite a "12" label. SELinux **permissive**, `userdebug`, root via `su 0` (note: `su 0 <cmd>`, not `su -c`).
- Stock dash = `com.xbs.launcher1` (Home). Car data app = `com.xbs.canapp`, which reads the MCU over serial `/dev/ttyHS0` and decodes CAN frames.

## How we read car data — READ ONLY
- Bind the **exported** service `com.xbs.canapp` (action `com.xbs.canapp.action.mcuservice`). AIDL: `com.mcu.iinterface.aidl.IMcuService` (helper `com.mcu.iinterface.McuManager`). Call `regPropIdList(Messenger, int[] codes)`; data arrives on the Messenger as `Message`s where `msg.what` = code and `msg.getData()` = a `Bundle` of fields. Call `unregPropIdList(Messenger)` to stop.
- Permission `com.xbs.permission.READ_CAN_DATABASE` is `protectionLevel="normal"`, so **any app** (our own signing key) can receive the data. No platform signature needed.
- Field map: **docs/FIELD_MAP.md**.

## READ-ONLY RULE (hard requirement)
The app only ever **registers and receives**. It must **never** call the write/tx side:
- never `IMcuService.txMessengerPack / txGeneralProtocol / txPerformUartPack / txUpdateProtocol / setUartNormalTransmitEnable`
- never the native MCU serial write (`com.mct.serial.headunit.McuSerial.writeData` / its `C()` enqueue)
- never write via `content://com.xbs.can.provider`
- never use the CarSetUp (`com.xbs.carsetup`) settings that push values to the car. (The dash only READS `Settings.System xbs_carmsgdisppost`, the CarPlay layout, in `StockScreens`; `check_readonly.sh` allows that one read and nothing else. It does not open the car-setup screen, because that screen also holds settings the CAN app forwards to the MCU.)

No bytes ever go to the MCU or the vehicle CAN bus.

## Must-keep features — never break these
- **Forward/reverse camera**: separate app `com.xbs.frontcam`, activity `com.xbs.frontcam.CameraActivity` (also responds to intent `com.xbs.action.reqcam`). Auto-shows on Drive via the CAN `frontcamstate` flag — a replacement Home re-does that (watch the flag, launch the camera). Independent of the Home app otherwise.
- **CarPlay / Android Auto** via ZLink (`com.zjinnova.zlink`) — the licensed *xinbasi-a007* build. Launch by intent; do not replace it.
- Keep the stock launcher `com.xbs.launcher1` installed as the **fallback Home**.

## ADB / network facts
- `adb connect <unit-ip>:5555` (persistent, no key auth on the LAN).
- **Single Wi-Fi radio**: wireless CarPlay brings up a SoftAP that drops the dash off home Wi-Fi, so ADB over the LAN dies while wireless CarPlay is connected.
- **Android Bluetooth stack is OFF** — BT is a separate Goodocom serial module, so there is no Android BLE radio (BLE apps won't find one).
- Not in the data feed: **true range (km)** and **brake/stop-light state**. On this car `batterylevel` is stuck at 77, so the dash decodes the battery % from code 11 `jhmail` instead (`BatteryDecoder`, a table recorded during a 13% → 100% Supercharge on 30 Oct; see docs/FIELD_MAP.md).

## Local shell (LocalShell)
- The unit's own ADB daemon listens on all interfaces at 5555 with no auth, so the app reaches it on 127.0.0.1 and runs a **fixed** list of shell-user (not root) commands, e.g. End CarPlay = `svc wifi disable && sleep 2 && svc wifi enable && echo BINNACLE_OK` (run() requires the marker) (never force-stop ZLink: it breaks the Goodocom Bluetooth until a reboot).
- `check_readonly.sh` pins the exact command list, keeps sockets in `LocalShell` only, and forbids caller-supplied text. Never add a command that touches the car, MCU, CAN, settings, properties or `su`.

## Steering-wheel input
`canapp` injects **standard Android KeyEvents** from the MCU. Scroll up/down = `DPAD_UP`(19)/`DPAD_DOWN`(20); right tilt (OK) = `ENTER`(66); left tilt = `BACK`(4); plus volume/media/home. Handle with normal `onKeyDown` / focus navigation — no special integration.

## Assets and privacy
The dash bundles its own car renders in `app/assets/cars/` (CC BY 4.0 model by Ameer Studio; keep the credit in `assets/cars/CREDITS.txt`, the README and Advanced → About). The dash never uses the stock dash's ZIQI renders (no /sdcard fallback, no storage permission); they have never been committed to this repo. Never add them. Never commit device identifiers (chip IDs, serials, MAC) or the ZLink licence key/SN/ID.

## Logging / working method
Record every change with how to undo it. Propose risky or outward-facing changes before applying. The owner keeps a running WORKLOG for the hands-on reverse-engineering; keep code-level decisions documented here and in docs/.
