package io.github.knoxlink.companion;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** Builders for the common pieces, so every screen looks the same. */
final class Ui {
    private Ui() {}

    static TextView text(Context c, Theme t, String s, float sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(t.sp(sp));
        v.setTextColor(color);
        v.setTypeface(bold ? t.bold() : t.regular());
        v.setIncludeFontPadding(false);
        return v;
    }

    static TextView label(Context c, Theme t, String s) {
        TextView v = text(c, t, s.toUpperCase(), 12.5f, t.text2, true);
        v.setLetterSpacing(0.08f);
        return v;
    }

    static TextView ellipsize(TextView v) {
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        return v;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout card(Context c, Theme t) {
        LinearLayout l = col(c);
        l.setBackground(t.card());
        l.setPadding(t.dp(14), t.dp(12), t.dp(14), t.dp(14));
        return l;
    }

    /** Card title row: label on the left, optional value on the right. */
    static TextView[] cardHead(Context c, Theme t, LinearLayout card, String title) {
        LinearLayout r = row(c);
        TextView l = label(c, t, title);
        TextView sub = text(c, t, "", 13, t.text3, true);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(sub);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = t.dp(10);
        card.addView(r, lp);
        return new TextView[]{l, sub};
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, -2, w);
    }

    static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams lp, int l, int t, int r, int b) {
        lp.setMargins(l, t, r, b);
        return lp;
    }

    static Drawable pressable(Theme t, int color, float radiusDp) {
        GradientDrawable g = t.round(color, radiusDp);
        GradientDrawable mask = t.round(0xFFFFFFFF, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(Theme.alpha(t.text, 0.12f)), g, mask);
    }

    static ImageView icon(Context c, Theme t, String name, int color, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(t.icon(name, color));
        v.setLayoutParams(new LinearLayout.LayoutParams(t.dp(sizeDp), t.dp(sizeDp)));
        return v;
    }

    /** A small coloured tag ("Rotten", "In hand"). kind: "", "bad", "warn", "good", "acc". */
    static TextView tag(Context c, Theme t, String s, String kind) {
        int fg = t.text2, bg = t.surface3;
        switch (kind) {
            case "bad": fg = t.bad; bg = Theme.alpha(t.bad, 0.18f); break;
            case "warn": fg = t.warn; bg = Theme.alpha(t.warn, 0.18f); break;
            case "good": fg = t.good; bg = Theme.alpha(t.good, 0.18f); break;
            case "acc": fg = t.accent; bg = Theme.alpha(t.accent, 0.2f); break;
            default: break;
        }
        TextView v = text(c, t, s, 11.5f, fg, true);
        v.setBackground(t.round(bg, 6));
        v.setPadding(t.dp(7), t.dp(3), t.dp(7), t.dp(3));
        return v;
    }

    /** A big tappable action row for sheets. */
    static TextView action(Context c, Theme t, String iconName, String s) {
        TextView v = text(c, t, s, 16, t.text, true);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setMinHeight(t.dp(54));
        v.setPadding(t.dp(14), 0, t.dp(14), 0);
        v.setBackground(pressable(t, t.surface2, 14));
        if (iconName != null) {
            Drawable d = t.icon(iconName, t.accent);
            d.setBounds(0, 0, t.dp(22), t.dp(22));
            v.setCompoundDrawables(d, null, null, null);
            v.setCompoundDrawablePadding(t.dp(12));
        }
        return v;
    }

    static JSONObject args(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (Exception ignored) {
        }
        return o;
    }

    static View space(Context c, int h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }
}
