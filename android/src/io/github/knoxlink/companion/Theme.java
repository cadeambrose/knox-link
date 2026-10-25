package io.github.knoxlink.companion;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

/** Colours, sizes and icons. Matches the web app's design tokens so both look the same. */
final class Theme {
    final String name;
    final int bg, surface, surface2, surface3, line, text, text2, text3, accent, accentInk, accentSoft, good, warn, bad;
    final float textScale;
    private final Context ctx;
    private final float density;

    private Theme(Context ctx, String name, float textScale, int... c) {
        this.ctx = ctx;
        this.name = name;
        // The layout is designed for about 600x520 dp; smaller screens (a handheld's second
        // screen) scale everything down a little, larger ones a little up, so it always fits.
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        float wDp = dm.widthPixels / dm.density, hDp = dm.heightPixels / dm.density;
        float fit = Math.max(0.78f, Math.min(1.15f, Math.min(wDp / 600f, hDp / 520f)));
        this.textScale = textScale * fit;
        this.density = dm.density * fit;
        bg = c[0]; surface = c[1]; surface2 = c[2]; surface3 = c[3]; line = c[4]; text = c[5]; text2 = c[6];
        text3 = c[7]; accent = c[8]; accentInk = c[9]; accentSoft = c[10]; good = c[11]; warn = c[12]; bad = c[13];
    }

    static Theme of(Context ctx, String name, String scale) {
        float ts = "s".equals(scale) ? 0.9f : "l".equals(scale) ? 1.15f : 1f;
        switch (name) {
            case "light":
                return new Theme(ctx, name, ts, 0xFFEEF0F3, 0xFFFFFFFF, 0xFFF4F5F7, 0xFFE8EBEF, 0xFFDDE1E6, 0xFF171A1F,
                        0xFF4E5662, 0xFF8A929D, 0xFFD98A12, 0xFFFFFFFF, 0xFFEDC78C, 0xFF1F9D63, 0xFFD98A12, 0xFFD63B3B);
            case "oled":
                return new Theme(ctx, name, ts, 0xFF000000, 0xFF0B0C0E, 0xFF131519, 0xFF1B1E23, 0xFF22262D, 0xFFECEEF1,
                        0xFFA7AEB8, 0xFF6F7782, 0xFFF2A93B, 0xFF1A1206, 0xFF8A6431, 0xFF46C288, 0xFFF2A93B, 0xFFEF5D5D);
            default:
                return new Theme(ctx, "dark", ts, 0xFF0D0F12, 0xFF15181D, 0xFF1C2027, 0xFF242932, 0xFF2A3039, 0xFFECEEF1,
                        0xFFA7AEB8, 0xFF6F7782, 0xFFF2A93B, 0xFF1A1206, 0xFF8A6431, 0xFF46C288, 0xFFF2A93B, 0xFFEF5D5D);
        }
    }

    boolean isLight() {
        return "light".equals(name);
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    float dpf(float v) {
        return v * density;
    }

    /** Text size in sp, already scaled by the user's text size setting. */
    float sp(float v) {
        return v * textScale;
    }

    Typeface bold() {
        return Typeface.create("sans-serif", Typeface.BOLD);
    }

    Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    Typeface regular() {
        return Typeface.create("sans-serif", Typeface.NORMAL);
    }

    GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dpf(radiusDp));
        return g;
    }

    GradientDrawable card() {
        GradientDrawable g = round(surface, 16);
        g.setStroke(dp(1), line);
        return g;
    }

    /** A tinted copy of one of the shared icons (res/drawable/ic_*.xml). */
    Drawable icon(String name, int color) {
        Resources r = ctx.getResources();
        int id = r.getIdentifier("ic_" + name, "drawable", ctx.getPackageName());
        if (id == 0) id = r.getIdentifier("ic_box", "drawable", ctx.getPackageName());
        Drawable d = r.getDrawable(id, null).mutate();
        d.setTint(color);
        return d;
    }

    static float px(Context c, float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, c.getResources().getDisplayMetrics());
    }

    /** Colour between a and b. */
    static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff, aa = (a >>> 24);
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff, ba = (b >>> 24);
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    static int alpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0xFFFFFF);
    }
}
