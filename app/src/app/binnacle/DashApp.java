package app.binnacle;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the one CarState and the active feed for the whole process, so the forward-camera trigger
 * keeps working while CarPlay or the camera is in front of the dash.
 */
public class DashApp extends Application implements Feed.Sink, SharedPreferences.OnSharedPreferenceChangeListener {

    public interface Listener {
        void onCarChanged(int code);
    }

    /** Same debounce as the stock launcher: at most one camera open/close per second. */
    private static final long CAMERA_DEBOUNCE_MS = 1000;

    public final CarState car = new CarState();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Feed feed;
    private boolean demo;
    private String problem;
    private boolean lastFrontCam;
    private long lastCameraActionMs;
    private CarArt art;
    private int artColour = Integer.MIN_VALUE;
    private boolean artLoading;
    private boolean rainbowRoad;

    /**
     * The car sends lights/info (code 9) only when something changes, not when we subscribe, so
     * after the dash restarts (an update, a crash) the light and warning icons stayed blank until
     * the next change. The last code 9 is kept here and restored if it is recent enough to still be
     * true. Momentary values (indicators, the attention pulse) are never restored.
     */
    private static final String LIGHTS = "last_code9";
    private static final long LIGHTS_MAX_AGE_MS = 30 * 60_000L;
    private static final java.util.Set<String> NOT_RESTORED = new java.util.HashSet<>(
            java.util.Arrays.asList("leftlight", "rightlight", "driverwarning"));

    private void saveLights(Bundle b) {
        SharedPreferences.Editor e = getSharedPreferences(LIGHTS, MODE_PRIVATE).edit().clear();
        for (String k : b.keySet()) {
            if (NOT_RESTORED.contains(k)) continue;
            Object v = b.get(k);
            if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) e.putInt(k, (Integer) v);
        }
        e.putLong("_at", System.currentTimeMillis()).apply();
    }

    private void restoreLights() {
        SharedPreferences p = getSharedPreferences(LIGHTS, MODE_PRIVATE);
        long age = System.currentTimeMillis() - p.getLong("_at", 0);
        if (age < 0 || age > LIGHTS_MAX_AGE_MS) return;
        Bundle b = new Bundle();
        for (java.util.Map.Entry<String, ?> en : p.getAll().entrySet()) {
            if (en.getKey().startsWith("_")) continue;
            Object v = en.getValue();
            if (v instanceof Boolean) b.putBoolean(en.getKey(), (Boolean) v);
            else if (v instanceof Integer) b.putInt(en.getKey(), (Integer) v);
        }
        if (!b.isEmpty()) car.apply(9, b, false);
    }

    public static DashApp get(android.content.Context c) { return (DashApp) c.getApplicationContext(); }

    @Override public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        applyTextSize();
        // until the car's lights are known, Auto starts in the theme it last used
        String mode = prefs.getString("theme", "dark");
        Style.applyTheme("light".equals(mode) || ("auto".equals(mode) && prefs.getBoolean("theme_last_light", false)));
        rainbowRoad = prefs.getBoolean("autopilot_rainbow", false);
        startFeed();
        startCompass();
    }

    /** onCarChanged code: a car-picture pack finished downloading (or failed); screens showing it refresh. */
    public static final int PACKS_CHANGED = -4;

    /** Tell every screen something changed outside the car feed (e.g. PACKS_CHANGED). */
    public void notifyChanged(int code) {
        for (Listener l : listeners) l.onCarChanged(code);
    }

    /** onCarChanged code for a new GPS heading. */
    public static final int HEADING_CHANGED = -3;
    private final Compass compass = new Compass(this, new Compass.Sink() {
        @Override public void onHeading() {
            for (Listener l : listeners) l.onCarChanged(HEADING_CHANGED);
        }
    });

    /** GPS heading for the compass; starts once location is granted (also retried from the dash). */
    public Compass compass() { return compass; }
    public void startCompass() { compass.start(); }

    private void startFeed() {
        if (feed != null) feed.stop();
        // No stock CAN service (emulator / other device) -> demo, so the UI is never just blank.
        demo = prefs.getBoolean("demo", false) || !CanFeed.available(this);
        feed = demo ? new DemoFeed(this) : new CanFeed(this, this);
        // Never let values from the previous feed (e.g. simulated ones) pass as live readings.
        car.reset();
        efficiency.reset();                    // demo kilometres never count towards the real drive
        if (!demo) restoreLights();
        lastFrontCam = false;
        main.removeCallbacks(cameraAction);
        problem = null;
        for (Listener l : listeners) l.onCarChanged(-1);
        feed.start();
    }

    private void applyTextSize() {
        int pct;
        try {
            pct = Integer.parseInt(prefs.getString("text_size", "100"));
        } catch (RuntimeException e) {
            pct = 100;
        }
        Style.textScale = Math.max(100, Math.min(130, pct)) / 100f;
    }

    /** onCarChanged code telling the screens to rebuild in the new theme. */
    public static final int THEME_CHANGED = -2;
    /** Auto theme: how long the screen level must stay past a threshold before the theme follows. */
    private static final long THEME_SETTLE_MS = 30_000;
    /** Screen-brightness levels (0-100) for Auto: dark at or below, light at or above. */
    private static final int AUTO_DARK = 30, AUTO_LIGHT = 45;
    private final Runnable themeSwitch = new Runnable() { @Override public void run() { updateTheme(true); } };
    private boolean themeWantLight, themePending;

    /**
     * Theme setting (Display): light, dark, or auto = follow the car's screen brightness (code 18,
     * dark at or below AUTO_DARK, light at or above AUTO_LIGHT), after it holds for THEME_SETTLE_MS.
     * Call with now = true to apply at once (setting changed, timer fired).
     */
    void updateTheme(boolean now) {
        String mode = prefs.getString("theme", "dark");
        // Auto follows the brightness the car sets for the screen (code 18), which tracks the Tesla's
        // own display: headlights flipped at dusk and go off when parked at night (in-car, 29 Oct).
        // Hysteresis: dark at or below AUTO_DARK, light at or above AUTO_LIGHT, keep it in between.
        // Until the level is known, keep the current theme.
        boolean want;
        if ("auto".equals(mode)) {
            int lvl = car.screenLevel;
            if (lvl < 0 || (lvl > AUTO_DARK && lvl < AUTO_LIGHT)) { themePending = false; main.removeCallbacks(themeSwitch); return; }
            want = lvl >= AUTO_LIGHT;
        } else {
            want = "light".equals(mode);
        }
        if ("auto".equals(mode) && !now && want != Style.light) {
            if (want != themeWantLight || !themePending) {
                themeWantLight = want;
                themePending = true;
                main.removeCallbacks(themeSwitch);
                main.postDelayed(themeSwitch, THEME_SETTLE_MS);
            }
            return;
        }
        themePending = false;
        main.removeCallbacks(themeSwitch);
        if (want == Style.light) return;
        if (!now) return;
        Style.applyTheme(want);
        prefs.edit().putBoolean("theme_last_light", want).apply();
        for (Listener l : listeners) l.onCarChanged(THEME_CHANGED);
    }

    /** Rainbow road on Autopilot (Dash settings), cached so the draw loop never reads preferences. */
    public boolean rainbowRoad() { return rainbowRoad; }

    public boolean isDemo() { return demo; }

    private final EfficiencyTracker efficiency = new EfficiencyTracker();
    /** Energy use since the dash started (fed on every code 10, whichever app is in front). */
    public EfficiencyTracker efficiency() { return efficiency; }
    public String feedProblem() { return problem; }
    public SharedPreferences prefs() { return prefs; }

    public void addListener(Listener l) { listeners.addIfAbsent(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    @Override public void onData(int code, Bundle data) {
        try {
            car.apply(code, data);
            if (code == 10) efficiency.feed(android.os.SystemClock.elapsedRealtime(),
                    car.regen ? -car.power : car.power, car.speedKmh());
            if (code == 16) onFrontCam(car.frontCamRequest);
            if (code == 9 && !demo) saveLights(data);
        } catch (Throwable t) {
            android.util.Log.w("binnacle", "bad data for code " + code, t);
            return;
        }
        if (code == 18) updateTheme(false);        // screen brightness: Auto theme follows the car's display
        for (Listener l : listeners) l.onCarChanged(code);
    }

    /**
     * The car renders for the current colour, or null while they load. Decoded once, off the UI
     * thread, and shared by all views; listeners get onCarChanged(-1) when they are ready.
     */
    public CarArt art() {
        final int colour = car.carColour;
        final boolean lhd = leftHandDrive();
        final int key = colour * 2 + (lhd ? 1 : 0);   // a colour or driving-side change reloads
        if (art != null && artColour == key) return art;
        if (!artLoading) {
            artLoading = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    CarArt a;
                    try {
                        a = CarArt.load(DashApp.this, colour, lhd);
                    } catch (Throwable t) {
                        a = CarArt.NONE;
                    }
                    final CarArt loaded = a;
                    main.post(new Runnable() {
                        @Override public void run() {
                            art = loaded;
                            artColour = key;
                            artLoading = false;
                            for (Listener l : listeners) l.onCarChanged(-1);
                        }
                    });
                }
            }, "car-art").start();
        }
        return art;
    }

    /** Driving side (Display → Driving side): left-hand drive uses the lhd car renders. */
    public boolean leftHandDrive() { return "lhd".equals(prefs.getString("drive_side", "rhd")); }

    /** Forget the renders and load them again (e.g. the driving side changed). */
    public void reloadArt() {
        artColour = Integer.MIN_VALUE;
        art();
    }

    @Override public void onStatus(String p) {
        problem = p;
        // Feed lost (service died, not found, or refused): nothing on screen is current any more,
        // so clear it rather than keep showing the last speed / gear / indicators as live.
        if (p != null) {
            car.reset();
            lastFrontCam = false;
        }
        for (Listener l : listeners) l.onCarChanged(-1);
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if ("demo".equals(key)) startFeed();
        if ("text_size".equals(key)) applyTextSize();
        if ("autopilot_rainbow".equals(key)) rainbowRoad = prefs.getBoolean(key, false);
        if ("theme".equals(key)) updateTheme(true);
        if ("drive_side".equals(key)) reloadArt();
        for (Listener l : listeners) l.onCarChanged(-1);
    }

    /**
     * Re-does the stock launcher's forward-camera behaviour: open the camera when the car raises
     * frontcamstate (shift to Drive), ask the camera to close when it drops. Only when we are the
     * Home app, so it never doubles up with the stock launcher while we run as a normal app.
     */
    private void onFrontCam(boolean want) {
        if (want == lastFrontCam) return;
        lastFrontCam = want;
        // the experimental in-dash camera (DashActivity) owns the flag when it is on
        if (demo || !prefs.getBoolean("auto_camera", false) || prefs.getBoolean("dash_camera", false)
                || !Launch.isDefaultHome(this)) return;
        long since = SystemClock.elapsedRealtime() - lastCameraActionMs;
        long delay = since >= CAMERA_DEBOUNCE_MS ? 0 : CAMERA_DEBOUNCE_MS - since;
        cameraWanted = want;
        main.removeCallbacks(cameraAction);     // only the pending camera action, nothing else on main
        main.postDelayed(cameraAction, delay);
    }

    private boolean cameraWanted;
    private final Runnable cameraAction = new Runnable() {
        @Override public void run() {
            lastCameraActionMs = SystemClock.elapsedRealtime();
            if (cameraWanted) Launch.camera(DashApp.this); else Launch.closeCamera(DashApp.this);
        }
    };
}
