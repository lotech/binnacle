package app.binnacle;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Heading from the unit's GPS (1 Hz, about 4.7 m accuracy in the 29 Oct drive logs). GPS only
 * knows the direction of travel, so a heading counts only above MIN_SPEED_MS; parked, the last one
 * is kept and reported as stale. Needs ACCESS_FINE_LOCATION, granted once at install
 * (pm grant <APP_ID> android.permission.ACCESS_FINE_LOCATION); without it there is no compass.
 * Reads location only: nothing goes to the car.
 */
final class Compass implements LocationListener {
    /** About 5 km/h: below that GPS bearings wander. */
    static final float MIN_SPEED_MS = 1.4f;
    /** A heading older than this is shown as stale (dimmed). */
    static final long FRESH_MS = 5_000;

    interface Sink { void onHeading(); }

    private static final String[] POINTS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private final Context context;
    private final Sink sink;
    private float heading = Float.NaN;
    private long headingMs;
    private boolean running;

    Compass(Context context, Sink sink) {
        this.context = context;
        this.sink = sink;
    }

    /** Starts GPS updates if the permission is granted; false if not (no compass then). */
    boolean start() {
        if (running) return true;
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false;
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !lm.getAllProviders().contains(LocationManager.GPS_PROVIDER)) return false;
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
            running = true;
        } catch (RuntimeException e) {
            running = false;
        }
        return running;
    }

    /** Degrees clockwise from north, or NaN if never known. */
    float heading() { return heading; }

    /** True if the heading came from moving within the last FRESH_MS. */
    boolean fresh() { return !Float.isNaN(heading) && SystemClock.elapsedRealtime() - headingMs < FRESH_MS; }

    /** N, NE, E ... for a heading in degrees. */
    static String point(float deg) {
        int i = Math.round(((deg % 360) + 360) % 360 / 45f) % 8;
        return POINTS[i];
    }

    @Override public void onLocationChanged(Location l) {
        if (l == null || !l.hasBearing() || !l.hasSpeed() || l.getSpeed() < MIN_SPEED_MS) return;
        heading = l.getBearing();
        headingMs = SystemClock.elapsedRealtime();
        sink.onHeading();
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
}
