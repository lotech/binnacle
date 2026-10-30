<p align="center"><img src="docs/images/icon.svg" width="128" height="128" alt="Binnacle icon: a compass card with N and a red lubber line"></p>

<h1 align="center">Binnacle</h1>

<p align="center">A **read-only** replacement instrument-cluster app and tooling for the Carview / ZIQI "A007" 10.25" Tesla Model 3/Y Android cluster (sold as Hansshow / Tesery / VJOYCAR / Satonic).</p>

The goal: a clean replacement Home/dashboard fixing some of the jank in the software that will never come via the overpromised OTA. Real car data (speed, battery %, gear, odometer, TPMS, lights, parking sensors, doors) and hands off to CarPlay / Android Auto / the reverse camera — **without ever writing to the car**, and fully reversible.

> **ABSOLUTE RULE — RECEIVE ONLY.** Nothing in this project ever sends anything to the car's CAN bus or the MCU. The dash app only *subscribes* to the stock CAN service and *receives* data. This is enforced in code (one class, two allowed binder calls: subscribe/unsubscribe) and by a build-time guard (`app/tools/check_readonly.sh`) that refuses to build if any write path is referenced. Never weaken or bypass it.

## Why "Binnacle"?
On a ship, the binnacle is the stand in front of the helm that holds the compass, lit so the helmsman can read it at a glance.

## Features
- **Live car data, never written to:** speed, gear (R P N D), speed-limit sign, indicators and lights, power and regen bar, tyre pressures (psi or bar), parking sensors, doors, boot and frunk, outside temperature, odometer.
- **Your car, in 3D:** Shows whichever door is open, opens it, and highlights the boot and frunk. In Drive it sits on a moving road with headlight glow, and on Autopilot the lane turns blue (or a rainbow road, if you like). Supports Left and Right Hand Drive Cars and the original Dashes colour options. Different colours are available via the app and downloaded from the repo.
- **Real battery %:** decoded from a field the stock software mislabels (see below), with a range estimate and charging animation.
- **Efficiency:** Wh/km over the last 5 km, a 15-minute power and regen graph, and this drive's totals.
- **GPS compass**
- **Parking sensors you can read:** coloured arcs around the car, a STOP sign when you're close, and a sensor card that stays visible while the camera is up.
- **Forward camera inside the dash:** now is the picture is actually smooth.
- **Autopilot attention warning:** a blue LED bar that pulses out from the centre, with a soft breathing glow around the centre window.
- **CarPlay on your terms:** starts only when you tap CarPlay, and one tap ends it and rejoins your home Wi-Fi.
- **Light, dark or Auto theme:** Auto follows the screen brightness the car sets, so it goes dark with the car at night.
- **Steering-wheel control**

## What it fixes compared with the stock dash
- **Android Settings Access:** the stock dash went out of its way to hide the normal Android Settings app, Binnacle restores this.
- **Battery %:** on some cars the stock "battery level" field is stuck (77 % all day here), and the stock dash shows another field as "km" when it isn't range at all. Binnacle decodes the real % from that second field, checked against a full 13 % to 100 % charge.
- **No door pop-up:** the stock software throws a full-screen picture up every time a door or the boot opens. Binnacle keeps your dash in view and shows the doors on its own car instead.
- **Camera that works:** the stock camera could fail with "camera error 4" after a reboot and judders on the 56 Hz panel. Binnacle sets up the camera input itself, paces frames evenly, and hides the start-up roll.
- **CarPlay that doesn't hijack your phone:** with the stock setup, CarPlay grabbed the phone and the unit's Wi-Fi whenever it could. Now it only connects when asked.
- **No stray toasts:** the stock dash's background pop-ups over the screen are gone.
- **Warnings that clear on time:** the attention warning goes away when the car clears it, not seconds later.
- **Theme that doesn't flip-flop:** Auto waits for the car's light level to settle before switching.

## Planned/Hoped for Features.
- CarPlay/Android Auto in the centre of the screen.
- Installed Navigation apps in the centre of the screen with access to Cached data.
- Improved design with more options for centre screen.

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

## Binnacle Log tool — build & install
This was an important part of the project to understand all the information coming from the Dashes MCU and the CANBUS. It is **NOT** installed by default and must be installed manually via ADB. When installed it displays an obvious icon to indicate it is logging. I care about privacy and security so if installed an icon is displayed to indicate it is logging. This is a simple tool but record ALL the data for the last 7 days and so should be considered not something you would want unless you want to help the project.

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
