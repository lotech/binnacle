package app.binnacle;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Apps and dash settings for the 1920x720 panel: section tabs down one side (the right by default,
 * for a right-hand-drive car; Display tab swaps it), big tiles on the other (an app grid, or setting cards with tap-sized segmented choices and switches) instead of
 * full-width Android list rows. Everything is focusable, so the steering wheel's scroll / OK can
 * reach it, and focus shows as an accent outline.
 *
 * Settings are this app's own SharedPreferences (same keys as before); nothing here is sent to
 * the car. The CarPlay layout is shown read-only (see StockScreens).
 */
public class HubActivity extends Activity {

    private static final String[] TABS = {"Apps", "Display", "Camera", "Car", "Advanced"};

    private SharedPreferences prefs;
    private FrameLayout content;
    private LinearLayout rail;
    private boolean menuRightBuilt;
    private final List<TextView> tabViews = new ArrayList<>();
    private int tab;
    private float textScaleBuilt;
    private boolean lightBuilt;
    private List<Entry> apps;                 // loaded once per screen (icons are slow on the unit)

    private static final class Entry {
        final String label;
        final Drawable icon;
        final Intent intent;
        final String pkg;
        Entry(String label, Drawable icon, Intent intent, String pkg) { this.label = label; this.icon = icon; this.intent = intent; this.pkg = pkg; }
    }

    /**
     * Pinned first, in this order, then a divider and every other app A-Z (the owner, 31 Oct):
     * Binnacle itself (back to the dash), Android settings, the drive logger, the stock dash.
     */
    private static final String[] PINNED = {"", "com.android.settings", "app.binnacle.logger", "com.lotech.tesladash", "com.xbs.launcher1"};
    private int pinnedCount;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = DashApp.get(this).prefs();
        textScaleBuilt = Style.textScale;
        lightBuilt = Style.light;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Style.BG);

        rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(dp(14), dp(12), dp(12), dp(8));
        rail.setBackgroundColor(Style.PANEL);
        TextView back = tabButton("‹  Dash");
        back.setTextColor(Style.MUTED);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        rail.addView(back, tabParams());
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            TextView t = tabButton(TABS[i]);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showTab(index); }
            });
            tabViews.add(t);
            rail.addView(t, tabParams());
        }
        ScrollView railScroll = new ScrollView(this);     // six 48 dp rows fit 360 dp; scrolls on a shorter screen
        railScroll.setBackgroundColor(Style.PANEL);
        railScroll.addView(rail);
        content = new FrameLayout(this);
        content.setPadding(dp(18), dp(18), dp(18), dp(12));
        // menu on the driver's side: right by default (right-hand drive), left as an option
        boolean menuRight = !"left".equals(prefs.getString("menu_side", "right"));
        menuRightBuilt = menuRight;
        LinearLayout.LayoutParams railLp = new LinearLayout.LayoutParams(dp(190), ViewGroup.LayoutParams.MATCH_PARENT);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        if (menuRight) {
            root.addView(content, contentLp);
            root.addView(railScroll, railLp);
        } else {
            root.addView(railScroll, railLp);
            root.addView(content, contentLp);
        }
        setContentView(root);

        showTab(state != null ? state.getInt("tab", 0) : 0);
        tabViews.get(tab).requestFocus();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    }

    /**
     * Steering wheel: scroll down/up walks focus through tabs and tiles in reading order (handled
     * here, before a ScrollView would turn it into a one-column jump), OK clicks. The first press
     * after touching lands on the current tab instead of being lost.
     */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if ((code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP) && event.getAction() == KeyEvent.ACTION_DOWN) {
            View f = getCurrentFocus();
            if (f == null || !f.isFocused()) {
                tabViews.get(tab).requestFocus();
                return true;
            }
            // tabs first, then the content, whichever side the menu is on
            ArrayList<View> order = new ArrayList<>(rail.getFocusables(View.FOCUS_FORWARD));
            order.addAll(content.getFocusables(View.FOCUS_FORWARD));
            int i = order.indexOf(f);
            int j = i < 0 ? 0 : i + (code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            if (j >= 0 && j < order.size()) order.get(j).requestFocus();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Auto can switch the theme while this screen is open: rebuild in the new palette. */
    private final DashApp.Listener themeListener = new DashApp.Listener() {
        @Override public void onCarChanged(int code) {
            if (code == DashApp.THEME_CHANGED && lightBuilt != Style.light) recreate();
            if (code == DashApp.PACKS_CHANGED && tab == CAR_TAB) showTab(CAR_TAB);   // whichever Car images card is showing now
            // the car reports its colour (code 9) after the card was built: rebuild it for that colour
            if (tab == CAR_TAB && packCardColour != DashApp.get(HubActivity.this).car.carColour) showTab(CAR_TAB);
        }
    };

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Car model or driving side changed: rebuild the Car tab so Car images shows (and fetches) that set. */
    private final SharedPreferences.OnSharedPreferenceChangeListener sideListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                    if (("drive_side".equals(key) || "car_model".equals(key)) && tab == CAR_TAB) showTab(CAR_TAB);
                }
            };

    @Override protected void onStart() {
        super.onStart();
        DashApp.get(this).addListener(themeListener);
        prefs.registerOnSharedPreferenceChangeListener(sideListener);
    }

    @Override protected void onStop() {
        DashApp.get(this).removeListener(themeListener);
        prefs.unregisterOnSharedPreferenceChangeListener(sideListener);
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        if (textScaleBuilt != Style.textScale) { recreate(); return; }   // text size changed: rebuild at the new size
        if (lightBuilt != Style.light) { recreate(); return; }           // theme changed meanwhile (Auto)
        if (tab == 1 || tab == CAR_TAB) showTab(tab);                      // CarPlay layout / Home app may have changed meanwhile
        else if (tab >= 4) showTab(tab);                                   // back from the uninstaller: logger state
    }

    /** The Car tab: car model, driving side, car images, tyres, range. */
    private static final int CAR_TAB = 3;

    private void showTab(int i) {
        // rebuilding the same tab (a download finished, the side changed): keep the scroll position
        ScrollView old = i == tab ? pageScroll() : null;
        final int keepScroll = old != null ? old.getScrollY() : 0;
        tab = i;
        for (int t = 0; t < tabViews.size(); t++) {
            TextView v = tabViews.get(t);
            v.setSelected(t == i);
            v.setTextColor(t == i ? Style.FG : Style.MUTED);
        }
        content.removeAllViews();
        switch (i) {
            case 0: content.addView(appsGrid()); break;
            case 1: content.addView(cards(          // most used first; the rest below the fold
                    homeCard(),
                    choiceCard("Theme", "Auto: follows the car's screen", "theme", "dark",
                            new String[]{"auto", "light", "dark"}, new String[]{"Auto", "Light", "Dark"}),
                    choiceCard("Text size", null, "text_size", "100",
                            new String[]{"100", "115", "130"}, new String[]{"100%", "115%", "130%"}),
                    choiceCard("Menu side", null, "menu_side", "right",
                            new String[]{"left", "right"}, new String[]{"Left", "Right"}),
                    btAutoConnectCard(),
                    actionCard("End CarPlay", null,
                            "End CarPlay", LocalShell.Command.END_CARPLAY),
                    infoCard("CarPlay layout", carplayLayoutText()))); break;
            case 2: content.addView(cards(
                    switchCard("Camera in the dash", "Forward camera in the centre window", "dash_camera", false),
                    switchCard("Open stock camera on Drive", "Stock camera screen instead", "auto_camera", false))); break;
            case CAR_TAB: content.addView(cards(
                    choiceCard("Car model", null, "car_model", "3",
                            new String[]{"3", "y"}, new String[]{"Model 3", "Model Y"}),
                    choiceCard("Driving side", null, "drive_side", "rhd",
                            new String[]{"lhd", "rhd"}, new String[]{"Left", "Right"}),
                    carPicturesCard(),
                    choiceCard("Units", null, "units", "auto",
                            new String[]{"auto", "metric", "imperial"}, new String[]{"Auto", "Metric", "Imperial"}),
                    choiceCard("Tyre pressure", null, "tyre_unit", "psi",
                            new String[]{"psi", "bar"}, new String[]{"psi", "bar"}),
                    stepperCard("Range at 100%", "For the km estimate under the battery %", "full_range_km", 410, 150, 700, 5, "km"),
                    switchCard("Rainbow road on Autopilot", null, "autopilot_rainbow", false))); break;
            default: content.addView(cards(
                    switchCard("Show raw CAN values", "Latest raw fields over the centre view, for in-car checks", "raw_overlay", false),
                    switchCard("Demo data", "Simulated drive instead of the car feed", "demo", false),
                    loggerCards(),
                    resetCard(),
                    infoCard("About", "Binnacle, a read-only dash for this unit.\n"
                            + "Model 3: 3D car model by Ameer Studio (Sketchfab), CC BY 4.0, modified "
                            + "(recoloured, mirrored for right-hand drive, posed, rendered).\n"
                            + "Model Y: based on \"2021 Tesla Model Y\" by tonielpro520 (Sketchfab), CC BY 4.0, modified "
                            + "(recoloured, doors separated and hinged, mirrored for right-hand drive, posed, rendered)."))); break;
        }
        final ScrollView sv = pageScroll();
        if (keepScroll > 0 && sv != null) {
            sv.post(new Runnable() { @Override public void run() { sv.scrollTo(0, keepScroll); } });
        }
    }

    // ---- apps ----

    private View appsGrid() {
        if (apps == null) apps = loadApps();
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(6);
        for (int i = 0; i < apps.size(); i++) {
            if (i == pinnedCount && pinnedCount > 0 && pinnedCount < apps.size()) grid.addView(divider(), dividerParams());
            Entry e = apps.get(i);
            final Intent intent = e.intent;
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(6), dp(12), dp(6), dp(10));
            tile.setBackground(bareBackground());   // just icon + name; a shape only when focused / pressed
            tile.setFocusable(true);
            tile.setClickable(true);
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(e.icon);
            tile.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
            TextView label = text(e.label, 15, Style.FG);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(0, dp(8), 0, 0);
            tile.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tile.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (Launch.start(HubActivity.this, new Intent(intent), true)) finish();
                }
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = dp(118);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            grid.addView(tile, lp);
        }
        return scroller(grid);
    }

    private List<Entry> loadApps() {
        PackageManager pm = getPackageManager();
        List<Entry> found = new ArrayList<>();
        boolean stockListed = false;
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            String pkg = ri.activityInfo.packageName;
            boolean self = getPackageName().equals(pkg);
            String label = self ? "Binnacle" : pkg.startsWith("app.binnacle.logger") || pkg.equals("com.lotech.tesladash") ? "Logger"
                    : String.valueOf(ri.loadLabel(pm));
            if (pkg.equals(Launch.STOCK_DASH.getPackageName())) stockListed = true;
            found.add(new Entry(label, ri.loadIcon(pm),
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(new ComponentName(pkg, ri.activityInfo.name)), self ? "" : pkg));
        }
        if (!stockListed) {
            try {
                pm.getActivityInfo(Launch.STOCK_DASH, 0);
                found.add(new Entry("Stock dash", getDrawable(R.drawable.ic_stock_dash),
                        new Intent(Intent.ACTION_MAIN).setComponent(Launch.STOCK_DASH), Launch.STOCK_DASH.getPackageName()));
            } catch (PackageManager.NameNotFoundException ignored) {}
        } else {
            for (int i = 0; i < found.size(); i++) {      // our own name and icon for it (its own is a generic one)
                Entry e = found.get(i);
                if (e.pkg.equals(Launch.STOCK_DASH.getPackageName()))
                    found.set(i, new Entry("Stock dash", getDrawable(R.drawable.ic_stock_dash), e.intent, e.pkg));
            }
        }
        List<Entry> list = new ArrayList<>();
        for (String pin : PINNED) {
            for (int i = 0; i < found.size(); i++) {
                if (found.get(i).pkg.equals(pin)) { list.add(found.remove(i)); break; }
            }
        }
        pinnedCount = list.size();
        Collections.sort(found, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return a.label.compareToIgnoreCase(b.label); }
        });
        list.addAll(found);
        return list;
    }

    /** A thin line across the whole grid between the pinned apps and the rest. */
    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Style.LINE);
        return v;
    }

    private GridLayout.LayoutParams dividerParams() {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(0, 6, 1f));
        lp.width = 0;
        lp.height = Math.max(1, dp(1));
        lp.setMargins(dp(12), dp(6), dp(12), dp(6));
        return lp;
    }

    /** Grey out a card whose setting has no effect right now (it stays readable). */
    private View enabledIf(boolean enabled, View card) {
        card.setAlpha(enabled ? 1f : 0.4f);
        setEnabledDeep(card, enabled);
        return card;
    }

    private static void setEnabledDeep(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setFocusable(enabled && v.isClickable());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) setEnabledDeep(g.getChildAt(i), enabled);
        }
    }

    // ---- setting cards ----

    /** Two columns of cards, scrolling if they don't fit. */
    /** Button height in settings cards (was 52: about half again bigger than needed). */
    private static final int BUTTON_DP = 38;

    private View cards(View... cards) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        for (View c : cards) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            grid.addView(c, lp);
        }
        if (cards.length % 2 == 1) {              // keep a lone last card half width
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = 1;
            grid.addView(new View(this), lp);
        }
        return scroller(grid);
    }

    /** The scrolling page on screen (inside its scroller() frame), or null. */
    private ScrollView pageScroll() {
        View v = content.getChildCount() > 0 ? content.getChildAt(0) : null;
        if (v instanceof FrameLayout && ((FrameLayout) v).getChildCount() > 0) v = ((FrameLayout) v).getChildAt(0);
        return v instanceof ScrollView ? (ScrollView) v : null;
    }

    /**
     * A settings page that shows when there's more below: a scrollbar that never fades, and a
     * "More below" pill at the bottom while the page can still scroll down.
     */
    private View scroller(View page) {
        final ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(true);
        sv.setScrollbarFadingEnabled(false);
        sv.setScrollBarSize(dp(6));
        // the bar sits in its own gutter at the right edge, clear of the right-hand cards
        sv.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        sv.setPadding(0, 0, dp(16), 0);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            GradientDrawable thumb = new GradientDrawable();
            thumb.setColor(Style.MUTED);
            thumb.setCornerRadius(dp(3));
            sv.setVerticalScrollbarThumbDrawable(thumb);
        }
        sv.addView(page);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final TextView more = text("More below  \u25BE", 15, Style.FG);
        more.setPadding(dp(18), dp(6), dp(18), dp(6));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Style.PANEL2);
        pill.setStroke(dp(1), Style.LINE);
        pill.setCornerRadius(dp(16));
        more.setBackground(pill);
        frame.addView(more, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        // only before the first scroll: once you've scrolled you know there's more
        final boolean[] scrolled = {false};
        final Runnable update = new Runnable() {
            @Override public void run() {
                if (sv.getScrollY() > dp(8)) scrolled[0] = true;
                more.setVisibility(!scrolled[0] && sv.canScrollVertically(1) ? View.VISIBLE : View.GONE);
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                @Override public void onScrollChange(View v, int x, int y, int ox, int oy) { update.run(); }
            });
        } else {
            sv.getViewTreeObserver().addOnScrollChangedListener(new android.view.ViewTreeObserver.OnScrollChangedListener() {
                @Override public void onScrollChanged() { update.run(); }
            });
        }
        sv.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) { sv.post(update); }
        });
        return frame;
    }

    private LinearLayout card(String title, String summary) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(8), dp(14), dp(10));   // compact: most settings above the fold (the owner)
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Style.PANEL);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Style.LINE);
        card.setBackground(bg);
        TextView t = text(title, 16, Style.FG);
        if (summary == null) t.setPadding(0, 0, 0, dp(4));
        card.addView(t);
        if (summary != null) {
            TextView s = text(summary, 12, Style.MUTED);
            s.setPadding(0, 0, 0, dp(2));
            card.addView(s);
        }
        return card;
    }

    /** A row of segmented choices (each a big tap target). */
    private View choiceCard(String title, String summary, final String key, String def, final String[] values, String[] labels) {
        LinearLayout card = card(title, summary);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final List<TextView> buttons = new ArrayList<>();
        String current = prefs.getString(key, def);
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            TextView b = chip(labels[i]);
            b.setSelected(value.equals(current));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.edit().putString(key, value).apply();
                    for (TextView o : buttons) o.setSelected(o == v);
                    // text size: rebuild the whole hub (rail included) at the new size once DashApp has applied it
                    if ("text_size".equals(key)) v.post(new Runnable() {
                        @Override public void run() { if (textScaleBuilt != Style.textScale) recreate(); }
                    });
                    if ("theme".equals(key)) v.post(new Runnable() {     // DashApp applied it: redraw this screen too
                        @Override public void run() { if (lightBuilt != Style.light) recreate(); }
                    });
                    if ("menu_side".equals(key) && ("right".equals(value) != menuRightBuilt)) v.post(new Runnable() {
                        @Override public void run() { recreate(); }
                    });
                }
            });
            buttons.add(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1);
            lp.setMargins(i == 0 ? 0 : dp(8), 0, 0, 0);
            row.addView(b, lp);
        }
        card.addView(row);
        return card;
    }

    /** On / off as one big chip. */
    static final String LOGGER_PKG = "app.binnacle.logger", OLD_LOGGER_PKG = "com.lotech.tesladash";

    /**
     * The optional drive logger, a separate developer app installed by hand (never by Binnacle):
     * whether it is installed, an opt-in "start with the dash" switch (off by default), and
     * Uninstall, which opens Android's own uninstall dialog and so wipes its logs. An old
     * location-recording logger, if present, is offered for removal.
     */
    private View loggerCards() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        if (installed(OLD_LOGGER_PKG)) {
            box.addView(uninstallCard("Old drive logger found",
                    "An older logger that also recorded location. Uninstall it to delete its logs.", OLD_LOGGER_PKG));
        }
        if (installed(LOGGER_PKG)) {
            box.addView(switchCard("Drive logger: start with the dash",
                    "Records car data, never location, to its own private storage; kept 7 days", "logger_autostart", false));
            box.addView(uninstallCard("Remove drive logger", "Uninstalling also deletes all its logs", LOGGER_PKG));
        } else {
            box.addView(infoCard("Drive logger (optional)", "A developer tool that records car data, never location. "
                    + "Not installed, and Binnacle never installs it. To add it: adb install BinnacleLogger.apk "
                    + "from github.com/lotech/binnacle/releases"));
        }
        return box;
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private View uninstallCard(String title, String summary, final String pkg) {
        LinearLayout card = card(title, summary);
        TextView b = chip("Uninstall");
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + pkg)));
                } catch (RuntimeException e) {
                    Toast.makeText(HubActivity.this, "Couldn't open the uninstaller", Toast.LENGTH_SHORT).show();
                }
            }
        });
        card.addView(b, new LinearLayout.LayoutParams(dp(150), dp(BUTTON_DP)));
        return card;
    }

    /**
     * Whether Binnacle is the Home app, and a Set as Default button that opens Android's own Home
     * app picker (an app can't make itself the default). The Display tab is rebuilt on return
     * (onResume), so the card shows the new state.
     */
    private View homeCard() {
        final boolean isDefault = isDefaultHome();
        LinearLayout card = card("Home app", isDefault ? null : "The stock dash stays installed");
        TextView b = chip(isDefault ? "Default" : "Set as Default");
        b.setSelected(isDefault);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openHomePicker(); }
        });
        card.addView(b, new LinearLayout.LayoutParams(dp(200), dp(BUTTON_DP)));
        return card;
    }

    private boolean isDefaultHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    /** Android's Home app setting, else the default-apps screen, else Settings itself. */
    private void openHomePicker() {
        String[] actions = {Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS};
        for (String a : actions) {
            try {
                startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException ignored) {
                // not on this ROM: try the next
            }
        }
        Toast.makeText(this, "Couldn't open the Home app setting", Toast.LENGTH_LONG).show();
    }

    /**
     * Reset settings: clears Binnacle's own settings (theme, car model, driving side, ...) and
     * restarts the dash, so the first-start questions come back. Downloaded car images stay.
     * Remove Binnacle: Android's uninstaller for this app; with Binnacle gone the unit falls
     * back to the stock dash, which was never removed. Both ask first.
     */
    private View resetCard() {
        LinearLayout card = card("Reset or remove", null);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView reset = chip("Reset settings");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BinnacleDialog.show(HubActivity.this, "Reset settings?",
                        "Binnacle's settings go back to how they started and the dash restarts.\nDownloaded car images are kept.",
                        new String[]{"Cancel", "Reset"}, true, new BinnacleDialog.Choice() {
                            @Override public void chosen(int index) { if (index == 1) resetSettings(); }
                        });
            }
        });
        TextView remove = chip("Remove Binnacle");
        remove.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BinnacleDialog.show(HubActivity.this, "Remove Binnacle?",
                        "The unit goes back to the stock dash, which is still installed.\nBinnacle's settings and downloaded car images are deleted with it.",
                        new String[]{"Cancel", "Remove"}, true, new BinnacleDialog.Choice() {
                            @Override public void chosen(int index) {
                                if (index != 1) return;
                                try {
                                    startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + getPackageName())));
                                } catch (RuntimeException e) {
                                    Toast.makeText(HubActivity.this, "Couldn't open the uninstaller", Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1);
        lp.setMargins(0, 0, dp(12), 0);
        row.addView(reset, lp);
        row.addView(remove, new LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1));
        card.addView(row);
        return card;
    }

    /** Clears every setting, then restarts the app at the dash so nothing keeps the old values. */
    private void resetSettings() {
        prefs.edit().clear().commit();
        Intent dash = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (dash != null) startActivity(dash.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        System.exit(0);   // the activity start brings the process back with fresh settings
    }

    private View switchCard(String title, String summary, final String key, boolean def) {
        LinearLayout card = card(title, summary);
        final TextView b = chip(prefs.getBoolean(key, def) ? "On" : "Off");
        b.setSelected(prefs.getBoolean(key, def));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean now = !b.isSelected();
                prefs.edit().putBoolean(key, now).apply();
                b.setSelected(now);
                b.setText(now ? "On" : "Off");
            }
        });
        card.addView(b, new LinearLayout.LayoutParams(dp(150), dp(BUTTON_DP)));
        return card;
    }

    /** − value + for a number kept as a string preference (as the old EditText setting stored it). */
    private View stepperCard(String title, String summary, final String key, int def, final int min, final int max, final int step, final String unit) {
        LinearLayout card = card(title, summary);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int start;
        try {
            start = Integer.parseInt(prefs.getString(key, String.valueOf(def)).trim());
        } catch (RuntimeException e) {
            start = def;
        }
        final int[] value = {Math.max(min, Math.min(max, start))};
        if (value[0] != start) prefs.edit().putString(key, String.valueOf(value[0])).apply();   // what's shown is what's used
        final TextView shown = text(value[0] + " " + unit, 22, Style.FG);
        shown.setGravity(Gravity.CENTER);
        TextView minus = chip("−"), plus = chip("+");
        View.OnClickListener change = new View.OnClickListener() {
            @Override public void onClick(View v) {
                value[0] = Math.max(min, Math.min(max, value[0] + (v.getTag() == null ? -step : step)));
                prefs.edit().putString(key, String.valueOf(value[0])).apply();
                shown.setText(value[0] + " " + unit);
            }
        };
        plus.setTag("+");
        minus.setOnClickListener(change);
        plus.setOnClickListener(change);
        row.addView(minus, new LinearLayout.LayoutParams(dp(72), dp(BUTTON_DP)));
        row.addView(shown, new LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1));
        row.addView(plus, new LinearLayout.LayoutParams(dp(72), dp(BUTTON_DP)));
        card.addView(row);
        return card;
    }

    /**
     * The unit's own Bluetooth auto-connect switch (same setting as the stock Car settings >
     * Bluetooth switch; local to the unit, never sent to the car). Read and written through
     * LocalShell, since a normal app may not write Settings.System.
     */
    /**
     * Car images for the car's colour (from the car) and the driving side: built in, downloaded,
     * or a Download button that fetches the pack from the project's GitHub release (CarPacks).
     */
    /** The car colour the Car images card was built for (-1: not reported yet). */
    private int packCardColour = Integer.MIN_VALUE;

    private View carPicturesCard() {
        final DashApp app = DashApp.get(this);
        int c = app.car.carColour;
        packCardColour = c;
        if (c < 0) {                                   // not reported yet: don't guess a colour to fetch
            LinearLayout card = card("Car images", "Waiting for the car to report its colour");
            TextView wait = chip("…");
            wait.setEnabled(false);
            card.addView(wait, new LinearLayout.LayoutParams(dp(150), dp(BUTTON_DP)));
            return card;
        }
        final String colour = c >= 0 && c < CarPacks.COLOURS.length ? CarPacks.COLOURS[c] : "silver";
        final String hand = app.artSet();   // the tab rebuilds when the model or side changes
        String what = (app.modelY() ? "Model Y, " : "Model 3, ") + colour
                + (hand.endsWith("lhd") ? ", left-hand drive" : ", right-hand drive");
        final LinearLayout card = card("Car images", what);
        final TextView b = chip("…");
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                boolean bundled = CarPacks.bundled(HubActivity.this, hand, colour);
                boolean got = CarPacks.downloaded(HubActivity.this, hand, colour);
                boolean busy = CarPacks.downloading(hand, colour);
                b.setEnabled(!busy && (!bundled || got));
                b.setSelected(bundled || got);
                b.setText(busy ? "Downloading…" : got ? "Remove" : bundled ? "Built in" : "Download");
            }
        };
        refresh.run();
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (CarPacks.downloaded(HubActivity.this, hand, colour)) {
                    CarPacks.remove(HubActivity.this, hand, colour);
                    DashApp.get(HubActivity.this).reloadArt();
                    refresh.run();
                    return;
                }
                b.setEnabled(false);
                b.setText("Downloading…");
                new Thread(new Runnable() {
                    @Override public void run() {
                        String err = null;
                        try {
                            CarPacks.download(HubActivity.this, hand, colour, new CarPacks.Progress() {
                                @Override public void onProgress(final long bytes) {
                                    main.post(new Runnable() {
                                        @Override public void run() { b.setText(Math.max(1, bytes >> 20) + " MB…"); }
                                    });
                                }
                            });
                        } catch (Exception e) {
                            err = e.getMessage();
                        }
                        final String problem = err;
                        // through the main looper, not the button: the card may be gone by now
                        // (tab changed, settings closed), but the dash must still pick the pack up
                        main.post(new Runnable() {
                            @Override public void run() {
                                if (problem == null) app.reloadArt();
                                else Toast.makeText(app, "Couldn't download: " + problem, Toast.LENGTH_LONG).show();
                                app.notifyChanged(DashApp.PACKS_CHANGED);   // refreshes the card on screen, even a newer one
                            }
                        });
                    }
                }, "car-pack").start();
            }
        });
        card.addView(b, new LinearLayout.LayoutParams(dp(150), dp(BUTTON_DP)));
        return card;
    }

    private View btAutoConnectCard() {
        LinearLayout card = card("Bluetooth auto-connect", "Reconnect the phone when the car wakes");
        final TextView b = chip("…");
        b.setEnabled(false);
        runShell(LocalShell.Command.BT_AUTOCONNECT_GET, b, null);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean now = !b.isSelected();
                runShell(now ? LocalShell.Command.BT_AUTOCONNECT_ON : LocalShell.Command.BT_AUTOCONNECT_OFF, b, now);
            }
        });
        card.addView(b, new LinearLayout.LayoutParams(dp(150), dp(BUTTON_DP)));
        return card;
    }

    /** Runs cmd off the main thread; shows On / Off on b from the reply (a GET) or from `set`. */
    private void runShell(final LocalShell.Command cmd, final TextView b, final Boolean set) {
        b.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                Boolean on = set;
                String err = null;
                try {
                    String out = LocalShell.run(cmd);
                    if (on == null) on = out.startsWith("true");
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final Boolean shown = on;
                final String problem = err;
                b.post(new Runnable() {
                    @Override public void run() {
                        b.setEnabled(true);
                        if (problem != null) { b.setText(set == null ? "Couldn't read" : "Couldn't change"); return; }
                        b.setSelected(shown);
                        b.setText(shown ? "On" : "Off");
                    }
                });
            }
        }, "local-shell").start();
    }

    /** A card with one button that runs a fixed LocalShell command off the main thread. */
    private View actionCard(String title, String summary, String label, final LocalShell.Command cmd) {
        LinearLayout card = card(title, summary);
        final TextView b = chip(label);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                b.setEnabled(false);
                b.setText("Working…");
                new Thread(new Runnable() {
                    @Override public void run() {
                        String result;
                        try {
                            LocalShell.run(cmd);
                            result = "Done";
                        } catch (Exception e) {
                            result = "Couldn't: " + e.getMessage();
                        }
                        final String shown = result;
                        b.post(new Runnable() {
                            @Override public void run() {
                                b.setText(shown);
                                b.setEnabled(true);
                            }
                        });
                    }
                }, "local-shell").start();
            }
        });
        card.addView(b, new LinearLayout.LayoutParams(-1, dp(BUTTON_DP)));
        return card;
    }

    private View infoCard(String title, String body) {
        return card(title, body);
    }

    private String carplayLayoutText() {
        String now = StockScreens.carplayLayout(this);
        return (now == null ? "Current: unknown" : "Current: " + now)
                + ". Recommended split: CarPlay on the left, so the speed sits on the right, away from the car's own speedo"
                + " (or Full width). Change it in the stock dash: Settings, Screen display mode; the unit restarts to apply it.";
    }

    // ---- building blocks ----

    private TextView tabButton(String label) {
        TextView t = text(label, 20, Style.MUTED);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(16), 0, dp(12), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(states(Style.PANEL, Style.PANEL2, Style.LINE, 10));
        return t;
    }

    private LinearLayout.LayoutParams tabParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.setMargins(0, 0, 0, dp(4));
        return lp;
    }

    private TextView chip(String label) {
        TextView b = text(label, 15, Style.FG);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(states(Style.PANEL2, 0xff1f5b80, Style.LINE, 12));
        // white on the blue selected fill in both themes (the light theme's dark text was unreadable on it)
        b.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_selected}, {}}, new int[]{0xffffffff, Style.FG}));
        return b;
    }

    /** No tile at rest; an accent outline when the wheel focuses it, a light fill while pressed. */
    private Drawable bareBackground() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused, android.R.attr.state_pressed}, shape(Style.PANEL2, Style.ACCENT, 14, 3));
        s.addState(new int[]{android.R.attr.state_focused}, shape(0x00000000, Style.ACCENT, 14, 3));
        s.addState(new int[]{android.R.attr.state_pressed}, shape(Style.PANEL2, 0, 14, 0));
        s.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0x00000000));
        return s;
    }

    /** Rounded background: normal / selected / pressed fills, and an accent outline when focused. */
    private Drawable states(int normal, int selected, int pressed, int radiusDp) {
        StateListDrawable s = new StateListDrawable();
        // focus is an outline only, so a focused chip never looks like the chosen one
        s.addState(new int[]{android.R.attr.state_focused, android.R.attr.state_selected}, shape(selected, Style.ACCENT, radiusDp, 3));
        s.addState(new int[]{android.R.attr.state_focused}, shape(normal, Style.ACCENT, radiusDp, 3));
        s.addState(new int[]{android.R.attr.state_pressed}, shape(pressed, 0, radiusDp, 0));
        s.addState(new int[]{android.R.attr.state_selected}, shape(selected, 0, radiusDp, 0));
        s.addState(new int[]{}, shape(normal, Style.LINE, radiusDp, 1));
        return s;
    }

    private GradientDrawable shape(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private TextView text(String s, float sp, int colour) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(colour);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * Style.textScale);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
