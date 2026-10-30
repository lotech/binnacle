package app.binnacle.logger;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The logger's own screen: what it records, whether it's running, how much is stored, and
 * Start / Stop / Delete logs / Export logs. Recording runs in CaptureService.
 */
public class MainActivity extends Activity {
    private TextView status;
    private Button startStop;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView about = new TextView(this);
        about.setTextSize(15f);
        about.setText("Binnacle logger records the car data the head unit publishes (speed, gear, doors, "
                + "battery, lights and so on) to a file, for developing the dash. It is read-only: it never "
                + "sends anything to the car.\n\nIt does not record location. Logs stay in this app's private "
                + "storage, are deleted after " + CaptureService.KEEP_DAYS + " days, and are all removed if "
                + "you uninstall this app.");
        root.addView(about);

        status = new TextView(this);
        status.setTextSize(15f);
        status.setPadding(0, 32, 0, 16);
        root.addView(status);

        startStop = button(root, "");
        startStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, CaptureService.class);
                if (CaptureService.running) {
                    startService(i.setAction(CaptureService.ACTION_STOP));
                } else if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(i);
                } else {
                    startService(i);
                }
                status.postDelayed(new Runnable() { @Override public void run() { refresh(); } }, 500);
            }
        });
        button(root, "Delete logs now").setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                File[] files = CaptureService.logDir(MainActivity.this).listFiles();
                int n = 0;
                if (files != null) for (File f : files) if (!CaptureService.running || !f.getName().equals(CaptureService.LOG_NAME)) { if (f.delete()) n++; }
                Toast.makeText(MainActivity.this, n + " log file(s) deleted" + (CaptureService.running ? " (stop to delete the current one)" : ""), Toast.LENGTH_SHORT).show();
                refresh();
            }
        });
        button(root, "Export logs to Downloads").setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { export(); }
        });
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        CaptureService.prune(this);
        refresh();
    }

    private Button button(LinearLayout root, String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        root.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private void refresh() {
        long bytes = 0;
        File[] files = CaptureService.logDir(this).listFiles();
        if (files != null) for (File f : files) bytes += f.length();
        status.setText((CaptureService.running ? "Recording." : "Not recording.")
                + "  Stored: " + (bytes < 1024 * 1024 ? (bytes / 1024) + " KB" : (bytes / (1024 * 1024)) + " MB"));
        startStop.setText(CaptureService.running ? "Stop recording" : "Start recording");
    }

    /** Copies the logs to the shared Downloads folder, only when asked (Android 10+). */
    private void export() {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Export needs Android 10; use adb instead", Toast.LENGTH_LONG).show();
            return;
        }
        File[] files = CaptureService.logDir(this).listFiles();
        int n = 0;
        if (files != null) {
            for (File f : files) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, "binnacle-" + f.getName());
                v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) continue;
                try (InputStream in = new FileInputStream(f); OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) continue;
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    n++;
                } catch (Exception e) {
                    Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }
        Toast.makeText(this, n + " log file(s) exported to Downloads", Toast.LENGTH_LONG).show();
    }
}
