package app.binnacle.logger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.util.Log;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.text.SimpleDateFormat;

/**
 * Foreground service that captures the CAN feed to a file independently of any visible UI,
 * so it keeps logging while driving (CarPlay / normal dash in front). READ-ONLY: it only ever
 * calls register (txn 1) and unregister (txn 2) of IMcuService; never any tx/write.
 */
public class CaptureService extends Service {

    private static final String TAG = "BinnacleLogger";
    private static final String DESCRIPTOR = "com.mcu.iinterface.aidl.IMcuService";
    private static final String SVC_ACTION = "com.xbs.canapp.action.mcuservice";
    private static final String SVC_PKG = "com.xbs.canapp";
    private static final String CH = "capture";

    private static int[] props() { int[] a = new int[128]; for (int i=0;i<a.length;i++) a[i]=i; return a; }
    private static final int[] PROP_IDS = props();

    private final SimpleDateFormat ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private HandlerThread ht;
    private Handler handler;
    private Messenger messenger;
    private IBinder binder;
    private boolean registered;
    private FileWriter w;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder s) { binder = s; log("service connected: " + n); register(); }
        @Override public void onServiceDisconnected(ComponentName n) { binder = null; registered = false; log("service disconnected"); }
    };

    @Override public void onCreate() {
        super.onCreate();
        ht = new HandlerThread("cap"); ht.start();
        handler = new Handler(ht.getLooper()) {
            @Override public void handleMessage(Message msg) { record(msg); }
        };
        messenger = new Messenger(handler);
        openLog();
        startForegroundNotif();
        log("CaptureService started (read-only, background). Capturing codes 0-127.");
        if (w != null) log("Logging to the app's external files dir (pull over ADB).");
        startGps();
        try {
            Intent i = new Intent(SVC_ACTION); i.setPackage(SVC_PKG);
            boolean ok = bindService(i, conn, Context.BIND_AUTO_CREATE);
            log("bindService returned " + ok);
        } catch (Throwable t) { log("bind err: " + t); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }

    private void startForegroundNotif() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CH, "CAN capture", NotificationManager.IMPORTANCE_LOW));
            }
            Notification.Builder b = (Build.VERSION.SDK_INT >= 26) ? new Notification.Builder(this, CH) : new Notification.Builder(this);
            b.setContentTitle("Binnacle logger (read-only)")
             .setContentText("Recording CAN data to file")
             .setSmallIcon(android.R.drawable.ic_menu_info_details)
             .setOngoing(true);
            startForeground(1, b.build());
        } catch (Throwable t) { Log.w(TAG, "fg notif err: " + t); }
    }

    private void openLog() {
        try {
            File dir = getExternalFilesDir(null); if (dir == null) dir = getFilesDir();
            File f = new File(dir, "binnacle_log.txt");
            w = new FileWriter(f, true);
            w.write("\n==== capture-service session " + new Date() + " ====\n"); w.flush();
        } catch (Throwable t) { w = null; Log.w(TAG, "log open err: " + t); }
    }

    private void register() {
        if (binder == null) return;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR); d.writeInt(1); messenger.writeToParcel(d, 0); d.writeIntArray(PROP_IDS);
            binder.transact(1, d, r, 0); r.readException(); registered = r.readInt() != 0;
            log("regPropIdList ok=" + registered);
        } catch (Throwable t) { log("register err: " + t); } finally { r.recycle(); d.recycle(); }
    }

    private void unregister() {
        if (binder == null || !registered) return;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try { d.writeInterfaceToken(DESCRIPTOR); d.writeInt(1); messenger.writeToParcel(d, 0); binder.transact(2, d, r, 0); r.readException(); }
        catch (Throwable ignored) {} finally { r.recycle(); d.recycle(); registered = false; }
    }

    private static String fmt(Object v) {
        if (v == null) return "null";
        if (v instanceof byte[]) { byte[] a=(byte[])v; StringBuilder h=new StringBuilder("bytes["+a.length+"]:"); for (byte x:a) h.append(String.format("%02x", x&0xff)); return h.toString(); }
        if (v instanceof int[])    return "int"+Arrays.toString((int[])v);
        if (v instanceof short[])  return "short"+Arrays.toString((short[])v);
        if (v instanceof long[])   return "long"+Arrays.toString((long[])v);
        if (v instanceof boolean[])return "bool"+Arrays.toString((boolean[])v);
        if (v instanceof float[])  return "float"+Arrays.toString((float[])v);
        if (v instanceof Object[]) return Arrays.deepToString((Object[])v);
        return String.valueOf(v);
    }

    private void record(Message msg) {
        try {
            Bundle b = msg.getData();
            StringBuilder sb = new StringBuilder("[code").append(msg.what).append("] ");
            if (b != null) {
                try { b.setClassLoader(Object.class.getClassLoader()); } catch (Throwable ignored) {}
                ArrayList<String> keys = new ArrayList<String>(b.keySet()); java.util.Collections.sort(keys);
                boolean first = true;
                for (String k : keys) { if (!first) sb.append(", "); first = false; sb.append(k).append('=').append(fmt(b.get(k))); }
            } else sb.append("(no data)");
            log(sb.toString());
        } catch (Throwable t) { log("record err: " + t); }
    }

    private void log(String s) {
        String line = ts.format(new Date()) + "  " + s;
        Log.i(TAG, line);
        try { if (w != null) { w.write(line + "\n"); w.flush(); } } catch (Throwable ignored) {}
    }

    // ---- GPS survey: what the unit's GNSS supplies (rate, accuracy, bearing, speed, altitude,
    // satellites, dropouts). Receive-only, nothing to do with the car. Each fix is logged as [gps]
    // (position to 5 decimals, ~1 m); satellite counts as [gnss] at most every 5 s.
    private LocationManager lm;
    private long lastGnssLog;
    private final LocationListener gpsListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            log(String.format(Locale.US, "[gps] lat=%.5f lon=%.5f acc=%s alt=%s spd=%s brg=%s brgAcc=%s spdAcc=%s fixAgeMs=%d",
                l.getLatitude(), l.getLongitude(),
                l.hasAccuracy() ? String.format(Locale.US, "%.1f", l.getAccuracy()) : "-",
                l.hasAltitude() ? String.format(Locale.US, "%.1f", l.getAltitude()) : "-",
                l.hasSpeed() ? String.format(Locale.US, "%.2f", l.getSpeed()) : "-",
                l.hasBearing() ? String.format(Locale.US, "%.1f", l.getBearing()) : "-",
                Build.VERSION.SDK_INT >= 26 && l.hasBearingAccuracy() ? String.format(Locale.US, "%.1f", l.getBearingAccuracyDegrees()) : "-",
                Build.VERSION.SDK_INT >= 26 && l.hasSpeedAccuracy() ? String.format(Locale.US, "%.2f", l.getSpeedAccuracyMetersPerSecond()) : "-",
                (System.currentTimeMillis() - l.getTime())));
        }
        @Override public void onStatusChanged(String p, int st, Bundle e) { log("[gps] status " + p + "=" + st); }
        @Override public void onProviderEnabled(String p) { log("[gps] provider enabled " + p); }
        @Override public void onProviderDisabled(String p) { log("[gps] provider disabled " + p); }
    };
    private Object gnssCallback;

    private void startGps() {
        try {
            lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) { log("[gps] no LocationManager"); return; }
            log("[gps] providers=" + lm.getAllProviders() + " gpsEnabled=" + lm.isProviderEnabled(LocationManager.GPS_PROVIDER));
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, gpsListener, ht.getLooper());
            if (Build.VERSION.SDK_INT >= 24) {
                GnssStatus.Callback cb = new GnssStatus.Callback() {
                    @Override public void onStarted() { log("[gnss] started"); }
                    @Override public void onStopped() { log("[gnss] stopped"); }
                    @Override public void onFirstFix(int ttffMillis) { log("[gnss] first fix ttff=" + ttffMillis + "ms"); }
                    @Override public void onSatelliteStatusChanged(GnssStatus s) {
                        long now = System.currentTimeMillis();
                        if (now - lastGnssLog < 5000) return;
                        lastGnssLog = now;
                        int used = 0; float best = 0;
                        for (int i = 0; i < s.getSatelliteCount(); i++) {
                            if (s.usedInFix(i)) used++;
                            best = Math.max(best, s.getCn0DbHz(i));
                        }
                        log("[gnss] sats=" + s.getSatelliteCount() + " used=" + used + " bestCn0=" + best);
                    }
                };
                gnssCallback = cb;
                lm.registerGnssStatusCallback(cb, new Handler(ht.getLooper()));
            }
            log("[gps] listening");
        } catch (SecurityException e) {
            log("[gps] no location permission (adb shell pm grant app.binnacle.logger android.permission.ACCESS_FINE_LOCATION)");
        } catch (Throwable t) { log("[gps] err: " + t); }
    }

    @Override public void onDestroy() {
        try { if (lm != null) { lm.removeUpdates(gpsListener); if (Build.VERSION.SDK_INT >= 24 && gnssCallback != null) lm.unregisterGnssStatusCallback((GnssStatus.Callback) gnssCallback); } } catch (Throwable ignored) {}
        try { unregister(); } catch (Throwable ignored) {}
        try { unbindService(conn); } catch (Throwable ignored) {}
        try { if (w != null) { w.flush(); w.close(); } } catch (Throwable ignored) {}
        try { if (ht != null) ht.quitSafely(); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
