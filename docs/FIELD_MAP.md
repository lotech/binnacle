# Field map — stock CAN data (com.xbs.canapp)

Captured read-only from a live unit (parked + a short drive) via the `capture/` tool, correlated with physical actions (gear shifts, camera, indicators, boot, doors). Data arrives as `Bundle`s keyed by an integer code (`msg.what`); fields below are the Bundle keys.

The stock launcher registers codes `{1, 9, 10, 11, 12, 13, 14, 15, 16, 21}`; the capture tool registers 0–127 to catch everything.

| Code | Meaning | Fields (key = value notes) |
|---|---|---|
| 9 | car info / lights | `leftlight` / `rightlight` (1 = lit, 2 = off), `nearlight` (low beam), `farlight` (high beam), `postlight` (position/tail), `frontaclight` (front fog), `backaclight` (rear fog), `autobiglight` (auto high beam), `autohandlight` (Auto Hold: true while held at a standstill in D/R, verified 28 Oct), `chargelight`, `safeline` (seatbelt warning: **true = not fastened**, checked in the car 28 Oct; the other meanings come from the stock dash's icons), `tempalarm`, `driverwarning` (Autosteer attention nag: 1 for a 2-3 s pulse every few minutes, verified 28 Oct), `autodrivermode` (**Autosteer** on/off, verified 28 Oct; plain cruise control (TACC) has no flag; the stock dash pops a toast when it turns on), `carcolor`, `cardistance` (follow-distance setting; stock shows it as a number, red 0-2 / yellow 3-4 / green 5+) |
| 10 | speed / energy | `carspeed` (km/h — verified 0→47 on a drive), `speedunit`, `speedlimit` (sign value, e.g. 30/50), `speedlimitsw`, `curentergyval` (instantaneous power/energy; ~0–60 plus a `0x400`/1024 regen-direction flag) |
| 11 | battery / gear / odo / temp | `batterylevel` (meant to be SoC %, but **stuck at 77** on the owner's car: 77 all day on 28 Oct while the car went 58% → 42%; byte 4 of the MCU packet, passed straight through by canapp), `gear` (**0 = Park, 1 = Reverse, 3 = Drive**), `totalmail` (odometer km — increments while driving), `enginetemp` (°C), `distunit`, `jhmail` (**NOT range**; bytes 6:5 of the MCU packet, 16-bit, and the stock dash mis-shows it as "km". It moves exactly one step per 1 % of battery (high byte ±13) and its exact value identifies the %: a table recorded during a 13 % → 100 % Supercharge on 30 Oct decodes every reading seen (21–100 %) exactly; 100 % = 15760. Values 12 % apart share a high byte but differ by ≥ 34, and readings jitter by ±25/26, so the exact value is unique. Implemented in `app/src/app/binnacle/BatteryDecoder.java`; below 13 % the table is not yet checked in the car) |
| 12 | TPMS | `lftpmsVal` / `rftpmsVal` / `lbtpmsVal` / `rbtpmsVal` (+ `*err` flags), `tpmsUnit`. Note: reads a placeholder (e.g. 64000) until the sensors wake after driving. |
| 15 | doors / boot | `doorval` — bitfield of open lids, as the stock launcher reads it: 0x80 front right (**driver's door on this RHD car — verified**), 0x40 front left, 0x20 rear right, 0x10 rear left, 0x08 **boot (verified)**, 0x04 frunk. The unverified bits come from the stock code (`o1/v.java`), not from the car. |
| 16 | front camera | `frontcamstate` — goes true when shifting to **Drive** (forward-camera trigger). |
| 2 | cameras | `is360Start`, `isRevStart` (360 / reverse camera flags) |
| 6 | camera | `isRightCamStart` |
| 18 | (unused) | `curtLevel`, `curtRange` — both stayed pegged at 100 the whole drive; **not** a usable range. |
| 3 | front sensors | `fl`, `fml`, `fmr`, `fr` (front parking-sensor proximity, per corner) |
| 4 | rear sensors | `rl`, `rml`, `rmr`, `rr` (rear parking-sensor proximity, per corner) |

## Usable vs not
- **Usable for display:** speed, SoC %, gear, speed limit, odometer, outside temp, power/regen, all the lights, turn indicators, TPMS, parking sensors, doors/boot, camera triggers.
- **No Autopilot set speed** in any decoded field (all canapp Bundle keys checked).
- **Not available in the feed:** true remaining **range (km)** (show SoC % or an estimate), a resettable **trip meter** (only total odometer), and **brake/stop-light** state (to be re-tested in the car).

## Steering-wheel buttons
`canapp` injects standard Android KeyEvents (via `InputManager.injectInputEvent`): scroll up/down = `DPAD_UP`(19)/`DPAD_DOWN`(20), right tilt/OK = `ENTER`(66), left tilt/back = `BACK`(4), plus volume (24/25), media next/prev (87/88), play/pause (85), home (3). Handle as normal key events.
