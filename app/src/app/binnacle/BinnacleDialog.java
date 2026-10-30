package app.binnacle;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A pop-up in Binnacle's own look (panel colours, the compass icon, large rounded buttons that
 * the steering wheel can focus) instead of the stock Android alert. Light and dark follow Style.
 */
final class BinnacleDialog {
    interface Choice { void chosen(int index); }

    private BinnacleDialog() {}

    /**
     * Shows title + message with one button per label (left to right); choice gets the index.
     * Cancelable by BACK or tapping outside only if cancelable.
     */
    static Dialog show(Activity a, String title, String message, String[] labels, boolean cancelable, final Choice choice) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(cancelable);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(a, 28);
        box.setPadding(pad, dp(a, 24), pad, dp(a, 22));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Style.PANEL);
        bg.setStroke(dp(a, 1), Style.LINE);
        bg.setCornerRadius(dp(a, 22));
        box.setBackground(bg);

        ImageView icon = new ImageView(a);
        icon.setImageResource(R.drawable.ic_launcher);
        box.addView(icon, new LinearLayout.LayoutParams(dp(a, 56), dp(a, 56)));

        TextView t = text(a, title, 24, Style.FG);
        t.setTypeface(Style.DISPLAY);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(a, 12), 0, dp(a, 4));
        box.addView(t);
        TextView m = text(a, message, 17, Style.MUTED);
        m.setGravity(Gravity.CENTER);
        m.setPadding(0, 0, 0, dp(a, 18));
        box.addView(m);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView b = text(a, labels[i], 18, Style.FG);
            b.setGravity(Gravity.CENTER);
            b.setFocusable(true);
            b.setClickable(true);
            b.setBackground(button(a));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    d.dismiss();
                    choice.chosen(index);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(a, 56), 1f);
            lp.setMargins(dp(a, 6), 0, dp(a, 6), 0);
            row.addView(b, lp);
            if (i == 0) b.requestFocus();
        }
        box.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(dp(a, 520), LinearLayout.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.55f);
        }
        d.show();
        return d;
    }

    /** Rounded button: panel fill, accent outline when focused by the wheel, lighter while pressed. */
    private static StateListDrawable button(Activity a) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, shape(a, Style.LINE, 0));
        s.addState(new int[]{android.R.attr.state_focused}, shape(a, Style.PANEL2, Style.ACCENT));
        s.addState(new int[]{}, shape(a, Style.PANEL2, 0));
        return s;
    }

    private static GradientDrawable shape(Activity a, int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(a, 14));
        if (stroke != 0) g.setStroke(dp(a, 3), stroke);
        return g;
    }

    private static TextView text(Activity a, String s, float sp, int colour) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(colour);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * Style.textScale);
        return t;
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
