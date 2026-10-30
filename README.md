# Binnacle

> **ABSOLUTE RULE — RECEIVE ONLY.** Nothing in this project ever sends anything to the car's CAN bus or the MCU. The dash app only *subscribes* to the stock CAN service and *receives* data. This is enforced in code (one class, two allowed binder calls: subscribe/unsubscribe) and by a build-time guard (`app/tools/check_readonly.sh`) that refuses to build if any write path is referenced. Never weaken or bypass it.

A **read-only** aftermarket instrument-cluster app and tooling for the Carview / ZIQI "A007" 10.25" Tesla Model 3/Y Android cluster (also sold as Hansshow / Tesery / VJOYCAR / Satonic — same OEM hardware).

The goal: a clean replacement Home/dashboard that shows the real car data (speed, battery %, gear, odometer, TPMS, lights, parking sensors, doors) and hands off to CarPlay / Android Auto / the reverse camera — **without ever writing to the car**, and fully reversible.

## Status
In daily use on the owner's car since 29 Oct 2026, as the unit's Home app (it replaces the stock dash; the stock launcher stays installed as the fallback).
- ✅ Reverse-engineered the stock CAN data interface and built a **field map** (`docs/FIELD_MAP.md`).
- ✅ A read-only **capture tool** (`capture/`) that subscribes to the stock CAN service and logs every field, plus GPS while driving.
- ✅ The **dash app** (`app/`): speed, gear, lights, power/regen, battery % (decoded, see below), tyres, parking sensors, doors, Autopilot, light/dark/auto theme, forward camera inside the dash, CarPlay start/end. See `app/README.md`.
- 🟡 Still open: split-screen CarPlay (ZLink draws at the wrong width), the car's Bluetooth reconnecting to the unit by itself, a phone-call/mic test, and the logger restarting itself after car sleep.

## Layout
| Folder | What |
|---|---|
| `app/` | The replacement read-only dash app (`app.binnacle`) |
| `capture/` | `BinnacleLogger` — a tiny read-only app that subscribes to the stock CAN service (`com.xbs.canapp`) and logs every field to a file; used to derive the field map |
| `mockups/` | HTML mockups of the dash layout — **own artwork only**, no vendor assets |
| `docs/` | `FIELD_MAP.md` (decoded CAN fields) and the ADB access guide |
| `app/tests/` | Plain-Java tests (tyre pressure, battery decoding), run by `app/build.sh` |
| `app/assets/cars/` | The dash's car renders, made from a CC BY 4.0 model (see Credits) |

## Capture tool — build & install
No Gradle required; it builds with the Android SDK command-line tools (`javac` → `d8` → `aapt2` → `apksigner`), or import `capture/` into Android Studio. Then:
```
adb connect <unit-ip>:5555
adb install -r BinnacleLogger.apk
# open "Binnacle logger" on the unit; it logs to:
#   /sdcard/Android/data/app.binnacle.logger/files/binnacle_log.txt
adb pull /sdcard/Android/data/app.binnacle.logger/files/binnacle_log.txt
```
It is **read-only** (subscribe + log only). Undo: `adb uninstall app.binnacle.logger`.

## Mockups
Open `mockups/cluster_mockup.html` in a browser (it loads web fonts; no vendor art). `mockups/cluster_mockup_renders.html` is the same mockup using the bundled car renders from `app/assets/cars/`.

## Safety & reversibility
- The app only **reads** the stock CAN feed; it never writes to the MCU or the vehicle CAN bus (see `CLAUDE.md`).
- It installs as a normal app and is set as the default Home; the stock launcher stays installed as the fallback. No system-partition changes. Reverse by switching Home back (`adb shell su 0 cmd package set-home-activity com.xbs.launcher1/.MainActivity`) or uninstalling.
- The few things it does on the unit itself are pinned by the same guard: a fixed list of shell-user commands through the unit's own ADB (`LocalShell`: cycle Wi-Fi to end CarPlay, read/set Bluetooth auto-connect), and the camera decoder's two input-select nodes in `/sys/ziqi/camera`.

## Credits
Car model: "Tesla 2018 Model 3" by Ameer Studio (https://sketchfab.com/uchiha.321abc), https://sketchfab.com/3d-models/tesla-2018-model-3-5ef9b845aaf44203b6d04e2c677e444f, CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). Modified: recoloured, mirrored for RHD, posed, rendered.

The in-app credit is under **Apps & settings → Advanced → About**.

## Licence
Code: MIT (see `LICENSE`). Car renders in `app/assets/cars/`: CC BY 4.0, from "Tesla 2018 Model 3" by Ameer Studio, modified (see Credits). Not affiliated with Tesla, ZIQI or any head-unit vendor; "Tesla" and "Model 3" are used only to say which car this is for.
