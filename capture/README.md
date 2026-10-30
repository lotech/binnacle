# capture/ — BinnacleLogger (read-only)

A tiny Android app that binds the stock CAN service (com.xbs.canapp), subscribes to all data codes (0-127), and logs every field (with raw values + timestamps) to a file, plus logcat (tag `BinnacleLogger`). It only ever registers/unregisters and receives — it never writes to the MCU or CAN.

## Source
- `src/app/binnacle/logger/MainActivity.java` — thin UI that starts the capture service.
- `src/app/binnacle/logger/CaptureService.java` — foreground service: binds the AIDL service, registers a Messenger, logs each Bundle.
- `AndroidManifest.xml`

## GPS survey
Also logs the unit's own GPS (not the car): each fix as `[gps]` (position to 5 decimals, accuracy, altitude, speed, bearing and their accuracies, fix age), and satellite counts as `[gnss]` at most every 5 s. This is to learn what the receiver supplies (rate, accuracy, dropouts) for future features such as an offline map. It needs the location permission:
```
adb shell pm grant app.binnacle.logger android.permission.ACCESS_FINE_LOCATION
```
Logs then contain your location: keep them out of the repo.

## Build (no Gradle)
```
capture/build.sh    # -> capture/build/BinnacleLogger.apk
```
It refuses to build if the source has any binder call other than IMcuService register (1) / unregister (2), or a car-write API.

## Run
```
adb install -r BinnacleLogger.apk
# open "Binnacle logger"; it logs to:
#   /sdcard/Android/data/app.binnacle.logger/files/binnacle_log.txt
adb pull /sdcard/Android/data/app.binnacle.logger/files/binnacle_log.txt
```
Undo: `adb uninstall app.binnacle.logger`.
