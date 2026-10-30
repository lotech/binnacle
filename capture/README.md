# capture/ — Binnacle logger (optional developer tool, read-only)

A small separate Android app that records the car data the head unit publishes, for working on the dash (finding new fields, checking a drive). **You don't need it to use Binnacle**, and Binnacle never installs it.

## What it records, and what it doesn't
- **Records:** every field the stock CAN service (`com.xbs.canapp`) publishes, codes 0–127 (speed, gear, doors, battery, lights, tyre pressures, odometer and so on), with timestamps.
- **Never records location.** It has no location permission.
- **Never sends anything to the car.** It only registers, receives and unregisters (IMcuService transactions 1 and 2), and the build refuses anything else.

## Where the logs go, and for how long
- In the app's **private storage** (`/data/data/app.binnacle.logger/files/logs/`), not on `/sdcard`, so other apps can't read them.
- They roll over at 10 MB, keeping one older file, and anything older than **7 days** is deleted.
- The logger's screen shows what's stored, with **Delete logs now** and **Export logs to Downloads** (copies them out only when you ask).
- **Uninstalling the app deletes every log.**

## Starting and stopping
- Open **Binnacle logger** and tap **Start recording** or **Stop recording**. A notification shows while it records.
- The car's sleep force-stops it. If you want it running on every drive, turn on Apps & settings → Advanced → **Drive logger: start with the dash** in Binnacle (off by default).
- Only Binnacle can start it: the service needs `app.binnacle.permission.CONTROL_LOGGER`, a signature permission, so only apps signed with the same key qualify.

## Build and install (no Gradle)
```
capture/build.sh    # -> capture/build/BinnacleLogger.apk
adb install -r capture/build/BinnacleLogger.apk
```
Remove it from Binnacle (Advanced → Remove drive logger) or with `adb uninstall app.binnacle.logger`.

## Source
- `src/app/binnacle/logger/MainActivity.java`: the screen (status, start/stop, delete, export).
- `src/app/binnacle/logger/CaptureService.java`: the foreground service that binds the CAN service, registers a Messenger and writes each Bundle.
