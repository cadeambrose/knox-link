package io.github.knoxlink.companion;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * The radar's sheet (a tap on the top bar's radar): the same radar larger, what its marks mean,
 * and its settings: how far it reaches, and whether it buzzes or beeps for a zombie close behind.
 */
final class RadarSheet {
    private RadarSheet() {}

    static void open(Context c, Host host, RadarView.Model model) {
        Theme t = host.theme();
        LinearLayout sheet = Ui.col(c);
        sheet.addView(Ui.text(c, t, "Danger radar", 20, t.text, true));
        TextView sub = Ui.text(c, t, model.summary(), 13.5f, t.text3, false);
        sheet.addView(sub, Ui.margins(Ui.lp(-1, -2), 0, t.dp(4), 0, t.dp(14)));

        RadarView radar = new RadarView(c, host, model, true);
        radar.setSummary(sub);
        View legend = legend(c, t);
        // side by side where the sheet is wide enough (a handheld's second screen), else one under the other
        int width = Math.min(t.dp(560), c.getResources().getDisplayMetrics().widthPixels) - t.dp(36);
        int size = t.dp(232);
        if (width >= size + t.dp(220)) {
            LinearLayout r = Ui.row(c);
            r.addView(radar, Ui.lp(size, size));
            r.addView(legend, Ui.margins(Ui.weight(1), t.dp(20), 0, 0, 0));
            sheet.addView(r);
        } else {
            LinearLayout.LayoutParams lp = Ui.lp(size, size);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            sheet.addView(radar, lp);
            sheet.addView(legend, Ui.margins(Ui.lp(-1, -2), 0, t.dp(16), 0, 0));
        }
        sheet.addView(settings(c, host, model), Ui.margins(Ui.lp(-1, -2), 0, t.dp(18), 0, 0));
        host.openSheet(sheet);
    }

    private static View legend(Context c, Theme t) {
        LinearLayout col = Ui.col(c);
        Object[][] rows = {
                {"seen", t.text2, "Wandering"},
                {"seen", t.warn, "Coming for you"},
                {"seen", t.bad, "Attacking you"},
                {"heard", t.text2, "Heard, not seen"},
                {"lost", t.text2, "Lost track of it"},
                {"near", t.warn, "Close behind you"},
        };
        for (int i = 0; i < rows.length; i++) {
            LinearLayout r = Ui.row(c);
            RadarView.Swatch s = new RadarView.Swatch(c, t, (String) rows[i][0], (Integer) rows[i][1]);
            s.setBackground(t.round(t.bg, 8));
            r.addView(s, Ui.lp(t.dp(28), t.dp(28)));
            r.addView(Ui.ellipsize(Ui.text(c, t, (String) rows[i][2], 14.5f, t.text, false)), Ui.margins(Ui.weight(1), t.dp(12), 0, 0, 0));
            col.addView(r, Ui.margins(Ui.lp(-1, -2), 0, i == 0 ? 0 : t.dp(7), 0, 0));
        }
        TextView note = Ui.text(c, t, "Only what your character sees or hears: walls, the dark and traits like Eagle Eyed or Hard of Hearing all count.",
                12.5f, t.text3, false);
        note.setLineSpacing(0, 1.15f);
        col.addView(note, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));
        return col;
    }

    private static View settings(Context c, Host host, RadarView.Model model) {
        Theme t = host.theme();
        LinearLayout group = Ui.col(c);
        group.setBackground(t.round(t.surface2, 14));
        group.setPadding(t.dp(14), t.dp(4), t.dp(10), t.dp(4));

        // how far it reaches; the game only looks that far
        LinearLayout r0 = Ui.row(c);
        r0.setMinimumHeight(t.dp(52));
        r0.addView(Ui.text(c, t, "Range", 15, t.text, true), Ui.weight(1));
        LinearLayout seg = Ui.row(c);
        seg.setBackground(t.round(t.surface3, 12));
        seg.setPadding(t.dp(4), t.dp(4), t.dp(4), t.dp(4));
        int cur = model.range();
        for (int range : RadarView.RANGES) {
            TextView b = Ui.text(c, t, label(range), 14, t.text2, true);
            b.setGravity(Gravity.CENTER);
            b.setMinWidth(t.dp(56));
            b.setPadding(t.dp(10), t.dp(9), t.dp(10), t.dp(9));
            b.setOnClickListener(v -> {
                model.setRange(range);
                choose(t, seg, b);
            });
            seg.addView(b);
            if (range == cur) choose(t, seg, b);
        }
        r0.addView(seg);
        group.addView(r0);

        boolean appVibrates = host.prefs().getBoolean("vibrate", true);
        toggle(c, t, host, group, "Vibrate when one is close behind", appVibrates ? null : "Vibration is off in Settings",
                RadarView.PREF_VIBRATE, true, null);
        toggle(c, t, host, group, "Sound pulses", "A beep that quickens as one closes in behind you",
                RadarView.PREF_SOUND, false, model::soundChanged);
        return group;
    }

    private static String label(int squares) {
        // a square is a metre
        return Fmt.imperial ? Math.round(squares * 1.0936f) + " yd" : squares + " m";
    }

    private static void choose(Theme t, LinearLayout seg, TextView chosen) {
        for (int i = 0; i < seg.getChildCount(); i++) {
            TextView x = (TextView) seg.getChildAt(i);
            boolean on = x == chosen;
            x.setTextColor(on ? t.text : t.text2);
            x.setBackground(on ? t.round(t.isLight() ? 0xFFFFFFFF : t.surface, 9) : null);
        }
    }

    private static void toggle(Context c, Theme t, Host host, LinearLayout group, String label, String hint, String key,
                               boolean def, Runnable changed) {
        LinearLayout r = Ui.row(c);
        r.setMinimumHeight(t.dp(52));
        LinearLayout txt = Ui.col(c);
        txt.setPadding(0, t.dp(8), 0, t.dp(8));
        txt.addView(Ui.text(c, t, label, 15, t.text, true));
        if (hint != null) txt.addView(Ui.text(c, t, hint, 12.5f, t.text3, false), Ui.margins(Ui.lp(-1, -2), 0, t.dp(3), 0, 0));
        r.addView(txt, Ui.margins(Ui.weight(1), 0, 0, t.dp(10), 0));
        Switch s = new Switch(c);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{0xFFFFFFFF, 0xFFFFFFFF}));
        s.setTrackTintList(new ColorStateList(states, new int[]{t.accent, t.line}));
        s.setChecked(host.prefs().getBoolean(key, def));
        s.setOnCheckedChangeListener((v, on) -> {
            host.prefs().edit().putBoolean(key, on).apply();
            if (changed != null) changed.run();
        });
        r.setOnClickListener(v -> s.toggle());
        r.addView(s);
        group.addView(r);
    }
}
