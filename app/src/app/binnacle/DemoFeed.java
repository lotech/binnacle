package app.binnacle;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Simulated drive for testing off the car (emulator / bench). Emits Bundles shaped exactly like the
 * real feed (same codes, keys and value types as the capture log), so CarState's decoding is
 * exercised too. 40-second loop: park, drive off, indicate, regen to a stop, reverse, park.
 */
final class DemoFeed implements Feed {

    private static final long TICK_MS = 100;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Sink sink;
    private long t0;
    private boolean running;

    DemoFeed(Sink sink) { this.sink = sink; }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            emit((SystemClock.elapsedRealtime() - t0) / 1000.0 % 40.0);
            h.postDelayed(this, TICK_MS);
        }
    };

    @Override public void start() {
        if (running) return;
        running = true;
        t0 = SystemClock.elapsedRealtime();
        sink.onStatus(null);
        h.post(tick);
    }

    @Override public void stop() {
        running = false;
        h.removeCallbacks(tick);
    }

    private void emit(double t) {
        int gear, speed;
        int power = 0;
        boolean regen = false;
        if (t < 6) { gear = CarState.GEAR_P; speed = 0; }
        else if (t < 20) { gear = CarState.GEAR_D; speed = (int) Math.min(62, (t - 7) * 6); power = t < 18 ? 45 : 8; }
        else if (t < 26) { gear = CarState.GEAR_D; speed = 62; power = 9; }
        else if (t < 33) { gear = CarState.GEAR_D; speed = (int) Math.max(0, 62 - (t - 26) * 9); regen = speed > 0; power = regen ? 30 : 0; }
        else if (t < 38) { gear = CarState.GEAR_R; speed = t < 34 ? 0 : 4; power = speed > 0 ? 4 : 0; }
        else { gear = CarState.GEAR_P; speed = 0; }
        boolean blink = ((long) (t * 1000) / 400) % 2 == 0;

        Bundle b10 = new Bundle();
        b10.putInt("carspeed", Math.max(0, speed));
        b10.putString("speedunit", "KM/H");
        b10.putInt("speedlimit", t < 20 ? 50 : 60);
        b10.putBoolean("speedlimitsw", true);
        b10.putInt("curentergyval", power | (regen ? 0x400 : 0));
        sink.onData(10, b10);

        Bundle b11 = new Bundle();
        b11.putInt("batterylevel", 77);
        b11.putInt("gear", gear);
        b11.putInt("totalmail", 54723);
        b11.putString("distunit", "km");
        b11.putString("enginetemp", "18.0℃");
        b11.putInt("jhmail", 34947);
        sink.onData(11, b11);

        Bundle b9 = new Bundle();
        boolean indicating = t >= 21 && t < 25;
        b9.putInt("leftlight", (indicating || t < 6) && blink ? 1 : 2);
        b9.putInt("rightlight", t < 6 && blink ? 1 : 2);   // hazards-style flash while parked
        b9.putBoolean("nearlight", true);
        b9.putBoolean("farlight", t >= 14 && t < 19);
        b9.putBoolean("postlight", true);
        b9.putBoolean("autodrivermode", t >= 12 && t < 26);
        b9.putBoolean("chargelight", false);
        b9.putBoolean("frontaclight", t < 6 || t >= 38);
        b9.putBoolean("backaclight", (t >= 26 && t < 33) || t >= 38);
        b9.putBoolean("autobiglight", t >= 6 && t < 14);   // auto high beam armed, then high beam 14-19
        b9.putBoolean("autohandlight", t >= 6 && t < 7.5); // Auto Hold at the standstill before driving off
        b9.putBoolean("safeline", t >= 6 && t < 8);
        b9.putInt("driverwarning", t >= 16 && t < 18 ? 1 : 0);   // an Autosteer attention pulse
        b9.putInt("carcolor", 4);
        b9.putInt("cardistance", 3);
        sink.onData(9, b9);

        Bundle b12 = new Bundle();
        String[] k = {"lf", "rf", "lb", "rb"};
        int[] p = {29000, 29000, 28700, 29600};   // 1/10000 bar -> 2.9 bar = 42 psi
        for (int i = 0; i < 4; i++) {
            b12.putInt(k[i] + "tpmsVal", t < 8 ? 64000 : p[i]);
            b12.putBoolean(k[i] + "tpmserr", false);
        }
        b12.putString("tpmsUnit", "bar");
        sink.onData(12, b12);

        Bundle b15 = new Bundle();
        b15.putInt("doorval", t < 1.5 ? 0 : t < 3.5 ? 0x80 : t < 5.5 ? 0x80 | 0x40 | 0x08 : 0);   // driver, then both fronts + boot
        sink.onData(15, b15);

        Bundle b16 = new Bundle();
        b16.putBoolean("frontcamstate", false);   // demo never triggers the real camera
        sink.onData(16, b16);

        Bundle b4 = new Bundle();
        int near = gear == CarState.GEAR_R ? (int) Math.max(1, 9 - (t - 34) * 2) : CarState.SENSOR_NONE;
        boolean rev = near != CarState.SENSOR_NONE;
        b4.putInt("rl", rev ? Math.min(10, near + 5) : near);   // a wall behind, angled: nearer on the right
        b4.putInt("rml", rev ? Math.min(10, near + 2) : near);
        b4.putInt("rmr", near);
        b4.putInt("rr", rev ? Math.min(10, near + 1) : near);
        sink.onData(4, b4);

        Bundle b3 = new Bundle();       // something ahead while creeping off from the standstill
        int ahead = gear == CarState.GEAR_D && speed < 10 ? (speed < 3 ? 2 : 6) : CarState.SENSOR_NONE;   // close at the standstill: shows STOP
        b3.putInt("fl", CarState.SENSOR_NONE);
        b3.putInt("fml", ahead);
        b3.putInt("fmr", ahead == CarState.SENSOR_NONE ? ahead : ahead + 2);
        b3.putInt("fr", CarState.SENSOR_NONE);
        sink.onData(3, b3);
    }
}
