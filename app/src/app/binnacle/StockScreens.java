package app.binnacle;

import android.content.Context;
import android.provider.Settings;

/**
 * Reads the unit's CarPlay layout (the stock "Screen display mode"). READ ONLY: the stock
 * car-setup app owns this setting (it writes system properties only a system app can set, then
 * asks for a restart), and the screen it lives on also holds settings the CAN app forwards to the
 * MCU (backlight, speaker level, camera trigger), so the dash doesn't open that screen either; it
 * tells the driver where to change it. tools/check_readonly.sh allows this one settings read here
 * and nowhere else.
 */
final class StockScreens {
    /** Stock key and values: 0 = "Right", 1 = "Left", 2 = "Full"; an absent key means 0, as in the stock app. */
    private static final String KEY_LAYOUT = "xbs_carmsgdisppost";

    private StockScreens() {}

    /** The current CarPlay layout as the stock dash names it, or null if it can't be read. */
    static String carplayLayout(Context c) {
        try {
            switch (Settings.System.getInt(c.getContentResolver(), KEY_LAYOUT, 0)) {
                case 0: return "Right (split)";
                case 1: return "Left (split)";
                case 2: return "Full width";
                default: return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }
}
