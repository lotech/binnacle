package app.binnacle.logger;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

/**
 * Thin UI: starts the background CaptureService (read-only) and tells the user they can leave /
 * drive. The actual capture runs in CaptureService so it keeps logging with no UI on screen.
 */
public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        TextView tv = new TextView(this);
        tv.setTextSize(15f);
        tv.setPadding(32, 48, 32, 32);
        try {
            Intent i = new Intent(this, CaptureService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            tv.setText("Binnacle logger is running in the background (read-only).\n\n"
                + "You can leave this screen and drive normally — it keeps recording to a file.\n\n"
                + "Come back when done and it'll be pulled over ADB.");
        } catch (Throwable t) {
            tv.setText("Failed to start capture: " + t);
        }
        setContentView(tv);
    }
}
