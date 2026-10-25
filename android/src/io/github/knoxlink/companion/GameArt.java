package io.github.knoxlink.companion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Views that rebuild pieces of the game's own HUD from its artwork: the digital watch, the car
 * dashboard and the skill squares. Each draws a plain fallback until the artwork has arrived.
 */
final class GameArt {
    private GameArt() {}

    /** Loads a set of images and redraws the view as each one arrives. */
    private static final class Images {
        final Map<String, Bitmap> map = new HashMap<>();
        final View owner;

        Images(View owner) {
            this.owner = owner;
        }

        void media(ArtCache art, String key, String path) {
            if (map.containsKey(key)) return;
            map.put(key, null);
            art.media(path, b -> { map.put(key, b); owner.invalidate(); });
        }

        void pack(ArtCache art, String key, String tex) {
            if (map.containsKey(key)) return;
            map.put(key, null);
            art.icon(tex, b -> { map.put(key, b); owner.invalidate(); });
        }

        Bitmap get(String key) {
            return map.get(key);
        }
    }

    // =================================================================== watch

    /** The in-game digital watch (media/ui/ClockAssets): time, AM/PM, temperature and date, LCD style. */
    static final class Watch extends View {
        private static final int LIT = 0xFF64C8D2;      // the game's displayColour (100, 200, 210)
        private static final int GHOST = 0x80282828;    // its ghostColour (40, 40, 40, 128)
        private final Images img = new Images(this);
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final PorterDuffColorFilter litF = new PorterDuffColorFilter(LIT, PorterDuff.Mode.SRC_IN);
        private final PorterDuffColorFilter ghostF = new PorterDuffColorFilter(GHOST, PorterDuff.Mode.SRC_IN);
        private final RectF dst = new RectF();
        private int hour = -1, minute, month, day;
        private double temp = Double.NaN;
        private boolean h24, fahrenheit;
        private final Theme t;
        private float scale;

        Watch(Context c, Theme t, ArtCache art) {
            super(c);
            this.t = t;
            String base = "ui/ClockAssets/";
            img.media(art, "bg", base + "ClockLargeBackground.png");
            for (int i = 0; i < 10; i++) {
                img.media(art, "L" + i, base + "ClockDigitsLarge" + i + ".png");
                img.media(art, "M" + i, base + "ClockDigitsMedium" + i + ".png");
            }
            img.media(art, "colon", base + "ClockDivideLarge.png");
            img.media(art, "am", base + "ClockAmLarge.png");
            img.media(art, "pm", base + "ClockPmLarge.png");
            img.media(art, "dot", base + "ClockDigitsMediumDot.png");
            img.media(art, "deg", base + "ClockDigitsMediumE.png");
            img.media(art, "C", base + "ClockDigitsMediumC.png");
            img.media(art, "F", base + "ClockDigitsMediumF.png");
            img.media(art, "minus", base + "ClockDigitsMediumMinus.png");
            img.media(art, "slash", base + "DateDivideMedium.png");
        }

        void set(int hour, int minute, int month, int day, double tempC, boolean h24, boolean fahrenheit) {
            this.hour = hour;
            this.minute = minute;
            this.month = month;
            this.day = day;
            this.temp = tempC;
            this.h24 = h24;
            this.fahrenheit = fahrenheit;
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            int height = MeasureSpec.getSize(h);
            setMeasuredDimension((int) (height * 156f / 62f), height);
        }

        /** Draws an image at background-pixel coordinates (x, y). */
        private float put(Canvas c, String key, float x, float y, PorterDuffColorFilter f) {
            Bitmap b = img.get(key);
            if (b == null) return 0;
            p.setColorFilter(f);
            dst.set(x * scale, y * scale, (x + b.getWidth()) * scale, (y + b.getHeight()) * scale);
            c.drawBitmap(b, null, dst, p);
            return b.getWidth();
        }

        /** A digit with its unlit "8" behind it, like an LCD. */
        private float digit(Canvas c, String size, int d, float x, float y) {
            put(c, size + 8, x, y, ghostF);
            if (d >= 0) put(c, size + d, x, y, litF);
            Bitmap b = img.get(size + 8);
            return b == null ? (size.equals("L") ? 18 : 9) : b.getWidth();
        }

        @Override
        protected void onDraw(Canvas c) {
            Bitmap bg = img.get("bg");
            scale = getHeight() / 62f;
            if (bg == null || img.get("L8") == null) {
                // plain text until the watch artwork arrives
                Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
                tp.setColor(t.text);
                tp.setTextSize(getHeight() * 0.42f);
                tp.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                c.drawText(hour < 0 ? "--:--" : Fmt.clock(hour, minute), 0, getHeight() * 0.62f, tp);
                return;
            }
            p.setColorFilter(null);
            dst.set(0, 0, getWidth(), getHeight());
            c.drawBitmap(bg, null, dst, p);
            // row 1: time (the game's large-watch offsets: ux 3, uy 3, digit spacing 2, colon spacing 3)
            float x = 3, y = 3;
            int h = hour < 0 ? -1 : (h24 ? hour : ((hour + 11) % 12) + 1);
            x += digit(c, "L", h < 0 ? -1 : (h >= 10 || h24 ? h / 10 : -1), x, y) + 2;
            x += digit(c, "L", h < 0 ? -1 : h % 10, x, y) + 3;
            put(c, "colon", x, y, ghostF);
            if (hour >= 0) put(c, "colon", x, y, litF);
            x += 6 + 3;
            x += digit(c, "L", hour < 0 ? -1 : minute / 10, x, y) + 2;
            x += digit(c, "L", hour < 0 ? -1 : minute % 10, x, y) + 3;
            if (!h24) {
                put(c, "am", x, y + 5, hour >= 0 && hour < 12 ? litF : ghostF);
                put(c, "pm", x, y + 18, hour >= 12 ? litF : ghostF);
            }
            // row 2: temperature, then the date from the game's dateOffset (65)
            y = 3 + 34 + 5;
            x = 3;
            if (!Double.isNaN(temp)) {
                double tv = fahrenheit ? temp * 9 / 5 + 32 : temp;
                int tenths = (int) Math.round(Math.abs(tv) * 10);
                if (tv < 0) x += put(c, "minus", x - 2, y, litF) + 1;
                int whole = tenths / 10;
                if (whole >= 10) x += digit(c, "M", (whole / 10) % 10, x, y) + 1;
                x += digit(c, "M", whole % 10, x, y) + 2;
                x += put(c, "dot", x, y, litF) + 2;
                x += digit(c, "M", tenths % 10, x, y) + 2;
                x += put(c, "deg", x, y, litF) + 1;
                put(c, fahrenheit ? "F" : "C", x, y, litF);
            }
            if (month > 0) {
                x = 3 + 65 + 12;
                boolean us = !h24;   // month/day where the 12-hour clock is used, day/month elsewhere
                int a = us ? month : day, b = us ? day : month;
                x += digit(c, "M", a / 10, x, y) + 1;
                x += digit(c, "M", a % 10, x, y) + 2;
                x += put(c, "slash", x, y, litF) + 2;
                x += digit(c, "M", b / 10, x, y) + 1;
                digit(c, "M", b % 10, x, y);
            }
        }
    }

    // =================================================================== dashboard

    /** The in-game car dashboard (ISVehicleDashboard.lua): gauges with needles and the warning lights. */
    static final class Dashboard extends View {
        private final Images img = new Images(this);
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF dst = new RectF();
        private final Theme t;
        private JSONObject v;
        private float scale, ox, oy;
        private float speedShown = -1, rpmShown = -1, fuelShown = -1;

        Dashboard(Context c, Theme t, ArtCache art) {
            super(c);
            this.t = t;
            for (String s : new String[]{"dashboard", "vehicle_spedometer", "rj-vehicle_engineguage", "rj-vehicle_fuelguage-full",
                    "rj-vehicle_fuelguage-low", "rj-vehicle_fuelguage-empty", "icon_enginetrouble_light", "icon_lowbattery_light",
                    "icon_doorslocked_light", "icon_headlights_light", "icon_heating_light", "ignition", "ignition_key_on",
                    "ignition_key_off", "ignition_hotwired", "speedregulator_light"}) {
                img.pack(art, s, s);
            }
            needle.setStrokeCap(Paint.Cap.ROUND);
            needle.setColor(0xFFE0442E);
            text.setColor(0xFFFFFFFF);
            text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            text.setTextAlign(Paint.Align.CENTER);
        }

        void set(JSONObject vehicle) {
            v = vehicle;
            invalidate();
        }

        boolean ready() {
            return img.get("dashboard") != null;
        }

        @Override
        protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            setMeasuredDimension(width, (int) (width * 121f / 552f));
        }

        private void put(Canvas c, String key, float x, float y) {
            Bitmap b = img.get(key);
            if (b == null) return;
            dst.set(ox + x * scale, oy + y * scale, ox + (x + b.getWidth()) * scale, oy + (y + b.getHeight()) * scale);
            c.drawBitmap(b, null, dst, p);
        }

        /** A warning light: coloured square behind the icon (grey = off, green = fine, red/amber = trouble). */
        private void light(Canvas c, String key, float x, float y, int color) {
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(color);
            dst.set(ox + x * scale, oy + y * scale, ox + (x + 20) * scale, oy + (y + 20) * scale);
            c.drawRoundRect(dst, 3 * scale, 3 * scale, bg);
            put(c, key, x, y);
        }

        private void gauge(Canvas c, float x, float y, float pivotX, float pivotY, float len, float value, float width) {
            double a = Math.toRadians(180 - 180 * Math.max(0, Math.min(1, value)));
            float cx = ox + (x + pivotX) * scale, cy = oy + (y + pivotY) * scale;
            needle.setStrokeWidth(width * scale);
            c.drawLine(cx, cy, cx + (float) Math.cos(a) * len * scale, cy - (float) Math.sin(a) * len * scale, needle);
        }

        private static float ease(float shown, float target) {
            return shown < 0 ? target : shown + (target - shown) * 0.2f;
        }

        @Override
        protected void onDraw(Canvas c) {
            Bitmap bg = img.get("dashboard");
            if (bg == null || v == null) return;
            scale = Math.min(getWidth() / 552f, getHeight() / 121f);
            ox = (getWidth() - 552 * scale) / 2;
            oy = (getHeight() - 121 * scale) / 2;
            put(c, "dashboard", 0, 0);

            int grey = 0x99808080, good = 0xB346C288, bad = 0xB3EF5D5D, amber = 0xB3FF8019;
            boolean running = v.optBoolean("run");
            double eng = v.optDouble("eng", 100);
            light(c, "icon_enginetrouble_light", 105, 26, !running ? grey : eng < 25 ? bad : eng < 60 ? amber : good);
            double batt = v.optDouble("batt", -1);
            light(c, "icon_lowbattery_light", 137, 26, batt < 0 ? grey : batt < 0.1 ? bad : running ? good : grey);
            light(c, "icon_doorslocked_light", 169, 26, v.optBoolean("locked") ? good : grey);
            light(c, "icon_headlights_light", 352, 26, v.optBoolean("lights") ? good : grey);
            if (v.optBoolean("hasHeater")) light(c, "icon_heating_light", 411, 26, v.optBoolean("heater") ? good : grey);
            light(c, "speedregulator_light", 14, 93, v.optBoolean("regulator") ? good : grey);
            String ign = v.optBoolean("hotwired") ? "ignition_hotwired" : v.optBoolean("keys") ? (running ? "ignition_key_on" : "ignition_key_off") : "ignition";
            put(c, ign, 475, 60);

            float speed = (float) Math.min(1, Math.abs(v.optDouble("spd")) / 120.0);
            float rpm = running ? (float) Math.max(0, Math.min(1, (v.optDouble("rpm") - 1000) / 6000.0)) : 0;
            float fuel = (float) v.optDouble("fuel");
            speedShown = ease(speedShown, speed);
            rpmShown = ease(rpmShown, rpm);
            fuelShown = ease(fuelShown, fuel);
            put(c, "rj-vehicle_engineguage", 85, 55);
            gauge(c, 85, 55, 64, 60, 45, rpmShown, 2.2f);
            put(c, "vehicle_spedometer", 341, 55);
            gauge(c, 341, 55, 64, 60, 45, speedShown, 2.2f);
            String fuelTex = fuel < 0.05 ? "rj-vehicle_fuelguage-empty" : fuel < 0.25 ? "rj-vehicle_fuelguage-low" : "rj-vehicle_fuelguage-full";
            put(c, fuelTex, 236, 21);
            gauge(c, 236, 21, 40, 37, 25, fuelShown, 2f);
            // gear, where the game puts its "S" label
            text.setTextSize(15 * scale);
            c.drawText(v.optString("gear", ""), ox + 66 * scale, oy + 68 * scale, text);
            if (Math.abs(speedShown - speed) > 0.002f || Math.abs(rpmShown - rpm) > 0.002f || Math.abs(fuelShown - fuel) > 0.002f) {
                postInvalidateOnAnimation();
            }
        }
    }

    // =================================================================== skill squares

    /** Ten skill squares drawn with the game's SkillUnit textures: filled up to the level, partly filled for progress. */
    static final class SkillSquares extends View {
        private static Bitmap fill, border;
        private static boolean loading;
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final RectF dst = new RectF();
        private final Rect src = new Rect();
        private final Theme t;
        private int level;
        private float progress;

        SkillSquares(Context c, Theme t, ArtCache art) {
            super(c);
            this.t = t;
            if (fill == null && !loading) {
                loading = true;
                art.media("ui/SkillPanel/SkillUnit_fill.png", b -> { fill = b; invalidate(); });
                art.media("ui/SkillPanel/SkillUnit_border.png", b -> { border = b; invalidate(); });
            }
        }

        void set(int level, double progress) {
            this.level = level;
            this.progress = (float) progress;
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            float gap = t.dpf(4);
            setMeasuredDimension(width, (int) ((width - gap * 9) / 10f));
        }

        @Override
        protected void onDraw(Canvas c) {
            float gap = t.dpf(4);
            float s = (getWidth() - gap * 9) / 10f;
            PorterDuffColorFilter on = new PorterDuffColorFilter(t.accent, PorterDuff.Mode.MULTIPLY);
            PorterDuffColorFilter part = new PorterDuffColorFilter(t.accentSoft, PorterDuff.Mode.MULTIPLY);
            PorterDuffColorFilter edge = new PorterDuffColorFilter(t.isLight() ? 0xFF8A929D : 0xFF4A515B, PorterDuff.Mode.SRC_IN);
            for (int i = 0; i < 10; i++) {
                float x = i * (s + gap);
                dst.set(x, 0, x + s, s);
                if (fill != null && border != null) {
                    if (i < level) {
                        p.setColorFilter(on);
                        c.drawBitmap(fill, null, dst, p);
                    } else if (i == level && level < 10 && progress > 0) {
                        int w = Math.max(1, (int) (fill.getWidth() * progress));
                        src.set(0, 0, w, fill.getHeight());
                        dst.set(x, 0, x + s * progress, s);
                        p.setColorFilter(part);
                        c.drawBitmap(fill, src, dst, p);
                        dst.set(x, 0, x + s, s);
                    }
                    p.setColorFilter(i < level ? on : edge);
                    c.drawBitmap(border, null, dst, p);
                } else {
                    Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
                    q.setColor(i < level ? t.accent : t.surface3);
                    c.drawRoundRect(dst, s * 0.2f, s * 0.2f, q);
                }
            }
        }
    }
}
