package io.github.knoxlink.companion;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

/**
 * Colours, sizes and icons. Matches the web app's design tokens so both look the same.
 * The dark themes are the game's own UI: black panels with thin grey borders and square corners, its
 * title bars, buttons and orange-brown selection (values from ISPanel, ISButton, ISCollapsableWindow and
 * ISScrollingListBox). The light theme keeps the app's own look.
 */
final class Theme {
    final String name;
    final int bg, surface, surface2, surface3, line, text, text2, text3, accent, accentInk, accentSoft, good, warn, bad;
    /** The game's look (square, bordered); false for the light theme. */
    final boolean pz;
    /** Dividers inside a panel; the border of the game's buttons; the selected thing's fill (the game's selection). */
    final int lineSoft, buttonLine, selection;
    final float textScale;
    private final Context ctx;
    private final float density;

    private Theme(Context ctx, String name, float textScale, int... c) {
        this.ctx = ctx;
        this.name = name;
        Fonts.init(ctx);
        // The layout is designed for about 600x520 dp; smaller screens (a handheld's second
        // screen) scale everything down a little, larger ones a little up, so it always fits.
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        float wDp = dm.widthPixels / dm.density, hDp = dm.heightPixels / dm.density;
        float fit = Math.max(0.78f, Math.min(1.15f, Math.min(wDp / 600f, hDp / 520f)));
        this.textScale = textScale * fit;
        this.density = dm.density * fit;
        bg = c[0]; surface = c[1]; surface2 = c[2]; surface3 = c[3]; line = c[4]; text = c[5]; text2 = c[6];
        text3 = c[7]; accent = c[8]; accentInk = c[9]; accentSoft = c[10]; good = c[11]; warn = c[12]; bad = c[13];
        pz = !"light".equals(name);
        lineSoft = pz ? 0xFF2E2E2E : line;
        buttonLine = pz ? 0xFFB3B3B3 : line;
        // the game's selection: {0.7, 0.35, 0.15} at 30 % over a panel
        selection = pz ? mix(surface, 0xFFB35926, 0.3f) : alpha(accent, 0.18f);
    }

    static Theme of(Context ctx, String name, String scale) {
        float ts = "s".equals(scale) ? 0.9f : "l".equals(scale) ? 1.15f : 1f;
        switch (name) {
            case "light":
                return new Theme(ctx, name, ts, 0xFFEEF0F3, 0xFFFFFFFF, 0xFFF4F5F7, 0xFFE8EBEF, 0xFFDDE1E6, 0xFF171A1F,
                        0xFF4E5662, 0xFF8A929D, 0xFFD98A12, 0xFFFFFFFF, 0xFFEDC78C, 0xFF1F9D63, 0xFFD98A12, 0xFFD63B3B);
            case "oled":
                return new Theme(ctx, name, ts, 0xFF000000, 0xFF000000, 0xFF141414, 0xFF262626, 0xFF666666, 0xFFE6E6E6,
                        0xFFB3B3B3, 0xFF808080, 0xFFD7783A, 0xFFFFFFFF, 0xFF8A4A22, 0xFF6DBE5A, 0xFFE3B341, 0xFFD9483B);
            default:
                return new Theme(ctx, "dark", ts, 0xFF101010, 0xFF050505, 0xFF161616, 0xFF262626, 0xFF666666, 0xFFE6E6E6,
                        0xFFB3B3B3, 0xFF808080, 0xFFD7783A, 0xFFFFFFFF, 0xFF8A4A22, 0xFF6DBE5A, 0xFFE3B341, 0xFFD9483B);
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

    // The game writes everything in Noto Sans SemiBold (Fonts: shipped with the app); headings get its Bold.
    Typeface bold() {
        return pz ? Fonts.ui(true) : Typeface.create("sans-serif", Typeface.BOLD);
    }

    Typeface medium() {
        return pz ? Fonts.ui(false) : Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    Typeface regular() {
        return pz ? Fonts.ui(false) : Typeface.create("sans-serif", Typeface.NORMAL);
    }

    /** A filled shape; in the game's look every corner is square (only circles, from {@link #circle}, stay round). */
    GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(pz ? 0 : dpf(radiusDp));
        return g;
    }

    GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** A panel: the game's black window fill with its thin grey border. */
    GradientDrawable card() {
        GradientDrawable g = round(surface, 16);
        g.setStroke(dp(1), line);
        return g;
    }

    /** The game's window title bar (its Panel_TitleBar texture's gradient), with a dark line under it. */
    android.graphics.drawable.Drawable titleBar() {
        return new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint p = new android.graphics.Paint(), l = new android.graphics.Paint();
            private int top = Integer.MIN_VALUE, bottom;

            @Override
            public void draw(android.graphics.Canvas c) {
                android.graphics.Rect b = getBounds();
                if (top != b.top || bottom != b.bottom) {
                    top = b.top;
                    bottom = b.bottom;
                    p.setShader(new android.graphics.LinearGradient(0, b.top, 0, b.bottom,
                            new int[]{0xFF3D3D3D, 0xFF2E2E2E, 0xFF1A1A1A, 0xFF151515, 0xFF151515, 0xFF1F1F1F},
                            new float[]{0f, 0.2f, 0.4f, 0.55f, 0.75f, 1f}, android.graphics.Shader.TileMode.CLAMP));
                    l.setColor(0xFF000000);
                }
                c.drawRect(b, p);
                c.drawRect(b.left, b.bottom - Math.max(1, dp(1) / 2), b.right, b.bottom, l);
            }

            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter f) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
        };
    }

    /** A choice in a row of choices: the game's tab (chosen or not); in the app's look a raised pill when chosen. */
    android.graphics.drawable.Drawable seg(boolean on) {
        if (pz) return tab(on);
        return on ? round(isLight() ? 0xFFFFFFFF : surface, 9) : null;
    }

    /** The row holding the choices: nothing in the game's look (its tabs sit side by side), a well in the app's. */
    void segBox(android.view.View row, int wellColor) {
        if (pz) {
            row.setBackground(null);
            row.setPadding(0, 0, 0, 0);
        } else {
            row.setBackground(round(wellColor, 12));
            row.setPadding(dp(4), dp(4), dp(4), dp(4));
        }
    }

    /** The game's tabs (XpSystemUI tab_selected / tab_unselected): grey gradients, the chosen one lighter. */
    android.graphics.drawable.Drawable tab(boolean selected) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, selected
                ? new int[]{0xFF343434, 0xFF696969, 0xFF5A5A5A, 0xFF2F2F2F, 0xFF1C1C1C}
                : new int[]{0xFF141414, 0xFF2A2A2A, 0xFF232323, 0xFF161616, 0xFF0B0B0B});
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
