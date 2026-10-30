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

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.text.SimpleDateFormat;

/**
 * Foreground service that records the car data feed to a file while it runs, for developing the
 * dash. READ-ONLY: it only ever calls register (txn 1) and unregister (txn 2) of IMcuService;
 * never any tx/write.
 *
 * Privacy: it records no location. Logs live in the app's private storage (not /sdcard), one file
 * per day (so a file never holds more than a day; a busy day rolls at ROLL_BYTES keeping one older
 * part), and day files older than KEEP_DAYS are deleted at start and at every change of day.
 * Logs from older versions (on shared storage, with GPS) are deleted. Uninstalling deletes them all.
 */
public class CaptureService extends Service {

    /** Sent by the logger's own screen to stop recording. */
    static final String ACTION_STOP = "app.binnacle.logger.STOP";
    static final long ROLL_BYTES = 10L * 1024 * 1024;
    static final int KEEP_DAYS = 7;
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private static final String TAG = "BinnacleLogger";
    private static final String DESCRIPTOR = "com.mcu.iinterface.aidl.IMcuService";
    private static final String SVC_ACTION = "com.xbs.canapp.action.mcuservice";
    private static final String SVC_PKG = "com.xbs.canapp";
    private static final String CH = "capture";

    private static int[] props() { int[] a = new int[128]; for (int i=0;i<a.length;i++) a[i]=i; return a; }
    private static final int[] PROP_IDS = props();

    static volatile boolean running;

    private final SimpleDateFormat ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private HandlerThread ht;
    private Handler handler;
    private Messenger messenger;
    private IBinder binder;
    private boolean registered;
    private FileWriter w;
    private File logFile;
    private String logDay;

    /** Today's log file name, e.g. binnacle_2026-10-31.txt. */
    static String todayName() { synchronized (DAY) { return "binnacle_" + DAY.format(new Date()) + ".txt"; } }

    /** Deletes the logs older versions kept: shared storage (with GPS) and the old private fallback. */
    static void removeLegacy(Context c) {
        File ext = c.getExternalFilesDir(null);
        if (ext != null) //noinspection ResultOfMethodCallIgnored
            new File(ext, "binnacle_log.txt").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(c.getFilesDir(), "binnacle_log.txt").delete();
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder s) { binder = s; log("service connected: " + n); register(); }
        @Override public void onServiceDisconnected(ComponentName n) { binder = null; registered = false; log("service disconnected"); }
    };

    /** The private folder holding the logs. */
    static File logDir(Context c) { return new File(c.getFilesDir(), "logs"); }

    /** Deletes log files older than KEEP_DAYS. */
    static void prune(Context c) {
        File[] files = logDir(c).listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600_000L;
        for (File f : files) if (f.lastModified() < cutoff) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        StateReceiver.announce(this, true);       // the dash shows a "recording" badge
        ht = new HandlerThread("cap"); ht.start();
        handler = new Handler(ht.getLooper()) {
            @Override public void handleMessage(Message msg) { record(msg); }
        };
        messenger = new Messenger(handler);
        removeLegacy(this);
        prune(this);
        openLog();
        startForegroundNotif();
        log("CaptureService started (read-only). Capturing codes 0-127. No location is recorded.");
        try {
            Intent i = new Intent(SVC_ACTION); i.setPackage(SVC_PKG);
            boolean ok = bindService(i, conn, Context.BIND_AUTO_CREATE);
            log("bindService returned " + ok);
        } catch (Throwable t) { log("bind err: " + t); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void startForegroundNotif() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CH, "Car data logger", NotificationManager.IMPORTANCE_LOW));
            }
            Notification.Builder b = (Build.VERSION.SDK_INT >= 26) ? new Notification.Builder(this, CH) : new Notification.Builder(this);
            b.setContentTitle("Binnacle logger is recording")
             .setContentText("Car data only, no location. Kept " + KEEP_DAYS + " days.")
             .setSmallIcon(android.R.drawable.ic_menu_info_details)
             .setOngoing(true);
            startForeground(1, b.build());
        } catch (Throwable t) { Log.w(TAG, "fg notif err: " + t); }
    }

    private void openLog() {
        try {
            File dir = logDir(this);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("can't create " + dir);
            logDay = todayName();
            logFile = new File(dir, logDay);
            w = new FileWriter(logFile, true);
            w.write("\n==== capture-service session " + new Date() + " ====\n"); w.flush();
        } catch (Throwable t) { w = null; Log.w(TAG, "log open err: " + t); }
    }

    /**
     * A new day: close yesterday's file, delete files past KEEP_DAYS, start today's. Past ROLL_BYTES
     * within a day: the current file becomes the day's one older part (replacing it).
     */
    private void rollIfNeeded() {
        if (logFile == null) return;
        if (!todayName().equals(logDay)) {
            try { w.close(); } catch (Throwable ignored) {}
            prune(this);
            openLog();
            return;
        }
        if (logFile.length() < ROLL_BYTES) return;
        try {
            w.close();
            File old = new File(logFile.getParentFile(), logDay.replace(".txt", ".1.txt"));
            //noinspection ResultOfMethodCallIgnored
            old.delete();
            //noinspection ResultOfMethodCallIgnored
            logFile.renameTo(old);
            w = new FileWriter(logFile, true);
        } catch (Throwable t) { w = null; Log.w(TAG, "log roll err: " + t); }
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

    private synchronized void log(String s) {
        String line = ts.format(new Date()) + "  " + s;
        Log.d(TAG, line);
        try { if (w != null) { w.write(line + "\n"); w.flush(); rollIfNeeded(); } } catch (Throwable ignored) {}
    }

    @Override public void onDestroy() {
        running = false;
        try { StateReceiver.announce(this, false); } catch (Throwable ignored) {}
        try { unregister(); } catch (Throwable ignored) {}
        try { unbindService(conn); } catch (Throwable ignored) {}
        try { if (w != null) { w.flush(); w.close(); } } catch (Throwable ignored) {}
        try { if (ht != null) ht.quitSafely(); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
