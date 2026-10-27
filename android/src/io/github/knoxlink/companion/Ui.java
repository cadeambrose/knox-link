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
        if (t.pz) return text(c, t, s, 13.5f, t.text2, true);   // the game writes its headings as they are
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
        if (t.pz) l.setPadding(t.dp(12), t.dp(12), t.dp(12), t.dp(12));
        else l.setPadding(t.dp(14), t.dp(12), t.dp(14), t.dp(14));
        return l;
    }

    /**
     * Card title row: label on the left, optional value on the right. In the game's look it is the game's
     * window title bar across the top of the panel, the title in the middle.
     */
    static TextView[] cardHead(Context c, Theme t, LinearLayout card, String title) {
        if (t.pz) {
            // the title centred in what the note on the right (health, a count...) leaves free, so they never overlap
            LinearLayout bar = row(c);
            bar.setBackground(t.titleBar());
            TextView l = text(c, t, title, 12.5f, 0xFFFFFFFF, true);
            l.setGravity(Gravity.CENTER);
            ellipsize(l);
            bar.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
            TextView sub = text(c, t, "", 12, t.text3, true);
            bar.addView(sub, new LinearLayout.LayoutParams(-2, -2));
            bar.setPadding(t.dp(8), 0, t.dp(8), 0);
            // across the whole panel, just inside its border: drawn over the panel's padding, so that mustn't clip
            card.setClipToPadding(false);
            int in = t.dp(1);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, t.dp(22));
            lp.setMargins(-card.getPaddingLeft() + in, -card.getPaddingTop() + in, -card.getPaddingRight() + in, t.dp(10));
            card.addView(bar, lp);
            return new TextView[]{l, sub};
        }
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

    /**
     * A sheet's title. In the game's look it is a window's title bar across the top of the sheet (the sheet then
     * opens without its grip); otherwise a big title, or a back row when {@code back} is given.
     */
    static void sheetTitle(Context c, Theme t, LinearLayout sheet, String title, View.OnClickListener back) {
        if (t.pz) {
            sheet.setTag("titled");
            android.widget.FrameLayout bar = new android.widget.FrameLayout(c);
            bar.setBackground(t.titleBar());
            TextView l = text(c, t, title, 14, 0xFFFFFFFF, true);
            l.setGravity(Gravity.CENTER);
            l.setPadding(t.dp(40), 0, t.dp(40), 0);
            ellipsize(l);
            bar.addView(l, new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
            if (back != null) {
                TextView b = text(c, t, "‹", 22, 0xFFFFFFFF, true);
                b.setGravity(Gravity.CENTER);
                b.setOnClickListener(back);
                bar.addView(b, new android.widget.FrameLayout.LayoutParams(t.dp(44), -1, Gravity.START));
            }
            // across the whole sheet: its sides are 18 dp in (MainActivity.openSheet), drawn over that padding
            sheet.setClipToPadding(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, t.dp(30));
            lp.setMargins(-t.dp(18) + t.dp(1), t.dp(1), -t.dp(18) + t.dp(1), t.dp(12));
            sheet.addView(bar, lp);
            return;
        }
        if (back != null) {
            TextView b = text(c, t, "‹  " + title, 17, t.text, true);
            b.setPadding(0, t.dp(4), 0, t.dp(10));
            b.setOnClickListener(back);
            sheet.addView(b);
        } else {
            sheet.addView(text(c, t, title, 20, t.text, true), margins(lp(-2, -2), 0, 0, 0, t.dp(12)));
        }
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
        if (t.pz) return pzPressable(t, color);
        GradientDrawable g = t.round(color, radiusDp);
        GradientDrawable mask = t.round(0xFFFFFFFF, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(Theme.alpha(t.text, 0.12f)), g, mask);
    }

    /**
     * The game's look for anything tappable: square, a thin border, and its mouse-over highlight (white at
     * 10 %) while pressed, no ripple. The main action (accent) and chosen chips (accent tints) get the game's
     * orange-brown selection with an accent border; buttons and rows a dim border; panels the panel border.
     */
    private static Drawable pzPressable(Theme t, int color) {
        int fill = color, border = 0;
        boolean accentish = (color & 0xFFFFFF) == (t.accent & 0xFFFFFF) && (color >>> 24) != 0;
        if (accentish) {
            fill = t.selection;
            border = t.accent;
        } else if (color == t.surface2) {
            border = 0xFF3A3A3A;
        } else if (color == t.surface) {
            border = t.line;
        }
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        int pressed = (fill >>> 24) == 0 ? 0x1AFFFFFF : Theme.mix(fill, 0xFFFFFFFF, 0.1f) | 0xFF000000;
        s.addState(new int[]{android.R.attr.state_pressed}, box(t, pressed, border));
        s.addState(new int[]{}, box(t, fill, border));
        return s;
    }

    private static GradientDrawable box(Theme t, int fill, int border) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (border != 0) g.setStroke(t.dp(1), border);
        return g;
    }

    /** The game's button (ISButton): black, a light grey border, white text; the main one in the selection colours. */
    static Drawable button(Theme t, boolean primary) {
        if (!t.pz) return pressable(t, primary ? t.accent : t.surface2, 12);
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{-android.R.attr.state_enabled}, box(t, 0xFF0D0D0D, 0xFF3A3A3A));
        s.addState(new int[]{android.R.attr.state_pressed}, box(t, primary ? Theme.mix(t.selection, 0xFFFFFFFF, 0.1f) : 0xFF4D4D4D,
                primary ? t.accent : t.buttonLine));
        s.addState(new int[]{}, box(t, primary ? t.selection : 0xFF000000, primary ? t.accent : t.buttonLine));
        return s;
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
        if (t.pz) {
            // square, with a thin border in its colour
            GradientDrawable g = new GradientDrawable();
            g.setColor("".equals(kind) ? 0xFF161616 : Theme.alpha(fg, 0.15f));
            g.setStroke(t.dp(1), "".equals(kind) ? 0xFF4A4A4A : Theme.alpha(fg, 0.6f));
            v.setBackground(g);
        } else {
            v.setBackground(t.round(bg, 6));
        }
        v.setPadding(t.dp(7), t.dp(3), t.dp(7), t.dp(3));
        return v;
    }

    /** A big tappable action row for sheets: in the game's look, one of its buttons. */
    static TextView action(Context c, Theme t, String iconName, String s) {
        TextView v = text(c, t, s, 16, t.text, true);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setMinHeight(t.dp(54));
        v.setPadding(t.dp(14), 0, t.dp(14), 0);
        v.setBackground(t.pz ? button(t, false) : pressable(t, t.surface2, 14));
        if (iconName != null) {
            Drawable d = t.icon(iconName, t.accent);
            d.setBounds(0, 0, t.dp(22), t.dp(22));
            v.setCompoundDrawables(d, null, null, null);
            v.setCompoundDrawablePadding(t.dp(12));
        }
        return v;
    }

    /**
     * In the game's look a switch is its tick box (ISTickBox): a black square with a grey border, a white tick when
     * on. The switch keeps working as before; only its picture changes (an invisible thumb over a box-shaped track).
     */
    static void tickBox(Theme t, android.widget.Switch s) {
        if (!t.pz) return;
        final int size = t.dp(24);
        s.setThumbTintList(null);
        s.setTrackTintList(null);
        s.setShowText(false);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setColor(0x00000000);
        thumb.setSize(size / 2, size);
        s.setThumbDrawable(thumb);
        s.setSwitchMinWidth(size);
        s.setTrackDrawable(new Drawable() {
            private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Path tick = new android.graphics.Path();
            private boolean on;

            @Override
            public void draw(android.graphics.Canvas c) {
                android.graphics.Rect b = getBounds();
                float x = b.centerX() - size / 2f, y = b.centerY() - size / 2f, line = t.dpf(1);
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setColor(0xFF000000);
                c.drawRect(x, y, x + size, y + size, p);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(line);
                p.setColor(on ? t.buttonLine : 0xFF8C8C8C);
                c.drawRect(x + line / 2, y + line / 2, x + size - line / 2, y + size - line / 2, p);
                if (on) {
                    tick.rewind();
                    tick.moveTo(x + size * 0.22f, y + size * 0.52f);
                    tick.lineTo(x + size * 0.42f, y + size * 0.72f);
                    tick.lineTo(x + size * 0.80f, y + size * 0.28f);
                    p.setColor(0xFFFFFFFF);
                    p.setStrokeWidth(t.dpf(3));
                    p.setStrokeCap(android.graphics.Paint.Cap.SQUARE);
                    c.drawPath(tick, p);
                }
            }

            @Override
            public boolean isStateful() {
                return true;
            }

            @Override
            protected boolean onStateChange(int[] state) {
                boolean now = false;
                for (int st : state) if (st == android.R.attr.state_checked) now = true;
                if (now == on) return false;
                on = now;
                invalidateSelf();
                return true;
            }

            @Override public int getIntrinsicWidth() { return size; }
            @Override public int getIntrinsicHeight() { return size; }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter f) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        });
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
