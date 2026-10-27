package io.github.knoxlink.companion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** Small custom views shared by the screens. */
final class Widgets {
    private Widgets() {}

    /** A rounded progress bar that eases to its new value. */
    static final class Bar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float value, shown = -1;
        private int track, color;
        /** The game's bars have square ends. */
        private final boolean square;

        Bar(Context c, int track) {
            super(c);
            this.track = track;
            square = c instanceof Host && ((Host) c).theme().pz;
        }

        void set(double v, int color) {
            value = (float) Math.max(0, Math.min(1, v));
            this.color = color;
            if (shown < 0) shown = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth(), rad = square ? 0 : h / 2;
            r.set(0, 0, w, h);
            p.setColor(track);
            c.drawRoundRect(r, rad, rad, p);
            shown += (value - shown) * 0.2f;
            if (Math.abs(value - shown) > 0.002f) postInvalidateOnAnimation();
            else shown = value;
            if (shown > 0.001f) {
                r.set(0, 0, square ? Math.max(1, w * shown) : Math.max(h, w * shown), h);
                p.setColor(color);
                c.drawRoundRect(r, rad, rad, p);
            }
        }
    }

    /**
     * A turning arc for "waiting". The stock spinner redraws at the display's full rate, which the
     * app's CPU drawing pays for while it sits waiting; fifteen frames a second look the same.
     */
    static final class Spinner extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Spinner(Context c, int color, float strokePx) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(strokePx);
            p.setColor(color);
        }

        @Override
        protected void onDraw(Canvas c) {
            float inset = p.getStrokeWidth();
            r.set(inset, inset, getWidth() - inset, getHeight() - inset);
            long t = android.os.SystemClock.uptimeMillis();
            float start = (t % 1100) * 360f / 1100;
            float sweep = 60 + 200 * (float) Math.abs(Math.sin(t / 900.0));
            c.drawArc(r, start, sweep, false, p);
            if (isShown()) postInvalidateDelayed(66);
        }
    }

    /** Ten skill level segments: solid up to the level, lighter for progress into the next one. */
    static final class SkillBar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int level;
        private float progress;
        private final Theme t;

        SkillBar(Context c, Theme t) {
            super(c);
            this.t = t;
        }

        void set(int level, double progress) {
            this.level = level;
            this.progress = (float) progress;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float gap = t.dpf(3), h = getHeight();
            float w = (getWidth() - gap * 9) / 10f;
            for (int i = 0; i < 10; i++) {
                float x = i * (w + gap);
                r.set(x, 0, x + w, h);
                p.setColor(t.surface3);
                c.drawRoundRect(r, t.dpf(3), t.dpf(3), p);
                if (i < level) {
                    p.setColor(t.accent);
                    c.drawRoundRect(r, t.dpf(3), t.dpf(3), p);
                } else if (i == level && level < 10 && progress > 0) {
                    r.set(x, 0, x + w * progress, h);
                    p.setColor(t.accentSoft);
                    c.drawRoundRect(r, t.dpf(3), t.dpf(3), p);
                }
            }
        }
    }

    /** A 270-degree speedometer with the speed in the middle. */
    static final class Speedo extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Theme t;
        private float frac, shown;
        private String speed = "0", unit = "KM/H";

        Speedo(Context c, Theme t) {
            super(c);
            this.t = t;
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        void set(double kmh, double max) {
            frac = (float) Math.max(0, Math.min(1, kmh / Math.max(60, max)));
            speed = Long.toString(Fmt.speed(kmh));
            unit = Fmt.speedUnit();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float size = Math.min(getWidth(), getHeight());
            float sw = size * 0.075f;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float rad = size / 2 - sw;
            r.set(cx - rad, cy - rad, cx + rad, cy + rad);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(sw);
            p.setColor(t.surface3);
            c.drawArc(r, 135, 270, false, p);
            shown += (frac - shown) * 0.2f;
            if (Math.abs(frac - shown) > 0.002f) postInvalidateOnAnimation();
            if (shown > 0.003f) {
                p.setColor(t.accent);
                c.drawArc(r, 135, 270 * shown, false, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(t.text);
            p.setTypeface(t.bold());
            p.setTextSize(size * 0.26f);
            c.drawText(speed, cx, cy + size * 0.06f, p);
            p.setColor(t.text3);
            p.setTextSize(size * 0.07f);
            p.setLetterSpacing(0.12f);
            c.drawText(unit, cx, cy + size * 0.19f, p);
            p.setLetterSpacing(0f);
        }
    }

    /** A moodle drawn like the game draws it: the coloured round background, then the icon. */
    static final class Moodle extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF r = new RectF();
        private Bitmap bg, icon;
        private int color;

        Moodle(Context c) {
            super(c);
        }

        void set(Bitmap bg, Bitmap icon, int color) {
            this.bg = bg;
            this.icon = icon;
            this.color = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            r.set(0, 0, getWidth(), getHeight());
            if (bg != null) {
                p.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
                c.drawBitmap(bg, null, r, p);
                p.setColorFilter(null);
            } else {
                p.setColor(color);
                c.drawOval(r, p);
            }
            if (icon != null) c.drawBitmap(icon, null, r, p);
        }
    }

    /** The game's mechanics overlay for a car: base image plus each part tinted by its condition. */
    static final class CarOverlay extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF r = new RectF();
        private final Map<String, Bitmap> art = new HashMap<>();
        private final Map<String, Integer> cond = new HashMap<>();
        private Bitmap base;
        private String prefix = "";
        private ArtCache cache;
        private Theme t;

        CarOverlay(Context c) {
            super(c);
        }

        void setup(Theme t, ArtCache cache) {
            this.t = t;
            this.cache = cache;
        }

        void set(JSONObject mech) {
            if (mech == null || cache == null) {
                setVisibility(GONE);
                return;
            }
            setVisibility(VISIBLE);
            String pre = mech.optString("prefix");
            if (!pre.equals(prefix)) {
                prefix = pre;
                base = null;
                art.clear();
                cache.media("ui/vehicles/mechanic overlay/" + pre + "base.png", b -> { base = b; invalidate(); });
            }
            cond.clear();
            JSONArray parts = mech.optJSONArray("parts");
            for (int i = 0; parts != null && i < parts.length(); i++) {
                JSONObject o = parts.optJSONObject(i);
                if (o == null) continue;
                final String img = o.optString("img");
                cond.put(img, o.optInt("c", 100));
                if (!art.containsKey(img)) {
                    art.put(img, null);
                    cache.media("ui/vehicles/mechanic overlay/" + pre + img + ".png", b -> {
                        if (b != null) art.put(img, b);
                        invalidate();
                    });
                }
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (base == null || t == null) return;
            float s = Math.min(getWidth() / (float) base.getWidth(), getHeight() / (float) base.getHeight());
            float w = base.getWidth() * s, h = base.getHeight() * s;
            r.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
            p.setColorFilter(null);
            p.setAlpha(t.isLight() ? 255 : 210);
            c.drawBitmap(base, null, r, p);
            p.setAlpha(255);
            for (Map.Entry<String, Bitmap> e : art.entrySet()) {
                if (e.getValue() == null) continue;
                Integer cv = cond.get(e.getKey());
                if (cv == null) continue;
                // the game's colours: green when fine, through yellow, to red; missing parts dark
                int col = cv < 0 ? 0xFF3A3F47 : cv >= 70 ? 0xFF46C288 : cv >= 40 ? 0xFFE8C24A : cv >= 15 ? 0xFFE0823C : 0xFFEF5D5D;
                p.setColorFilter(new PorterDuffColorFilter(col, PorterDuff.Mode.SRC_IN));
                c.drawBitmap(e.getValue(), null, r, p);
            }
            p.setColorFilter(null);
        }
    }

    /** Lays children out left to right and wraps to new lines (tags, chips). */
    static final class Flow extends ViewGroup {
        private final int gapX, gapY;

        Flow(Context c, int gapX, int gapY) {
            super(c);
            this.gapX = gapX;
            this.gapY = gapY;
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            int max = MeasureSpec.getSize(wms);
            boolean bounded = MeasureSpec.getMode(wms) != MeasureSpec.UNSPECIFIED;
            int x = 0, y = 0, lineH = 0, widest = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                // a fixed size in the child's layout params wins; otherwise it takes what it needs
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                int ws = lp != null && lp.width > 0 ? MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY)
                        : MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST);
                int hs = lp != null && lp.height > 0 ? MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
                        : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                v.measure(ws, hs);
                int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
                if (bounded && x > 0 && x + w > max) {
                    x = 0;
                    y += lineH + gapY;
                    lineH = 0;
                }
                x += w + gapX;
                widest = Math.max(widest, x - gapX);
                lineH = Math.max(lineH, h);
            }
            setMeasuredDimension(bounded ? max : widest, y + lineH);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l, x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
                if (x > 0 && x + w > max) {
                    x = 0;
                    y += lineH + gapY;
                    lineH = 0;
                }
                v.layout(x, y, x + w, y + h);
                x += w + gapX;
                lineH = Math.max(lineH, h);
            }
        }
    }
}
