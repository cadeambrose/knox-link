package io.github.knoxlink.companion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * When a character dies, their run on one card (KnoxLink_Legacy.lua sends it on "recap"): how long
 * they lasted, how it ended, what they did, the way they walked and their last photo. It can be
 * saved as a picture, to keep or to share.
 */
final class Recap {
    private Recap() {}

    private static final int PAPER = 0xFFE9E3CD, INK = 0xFF2B2620, INK_SOFT = 0xFF6B6050, RED = 0xFFA60E0E, GREEN = 0xFF3E7C47;
    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August",
            "September", "October", "November", "December"};
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Shows a character's recap once (a screen that connects later gets the same one again: not shown twice). */
    static void maybeShow(Context c, Host host, JSONObject r) {
        String id = r == null ? "" : r.optString("id");
        if (id.isEmpty()) return;
        Set<String> seen = new HashSet<>(host.prefs().getStringSet("recapsSeen", new HashSet<>()));
        if (seen.contains(id)) return;
        seen.add(id);
        host.prefs().edit().putStringSet("recapsSeen", seen).apply();
        show(c, host, r);
    }

    static String lasted(double hours) {
        int days = (int) (hours / 24), h = (int) (hours % 24);
        String d = days == 1 ? "1 day" : days + " days";
        if (days == 0) return h == 1 ? "1 hour" : h + " hours";
        return h == 0 ? d : d + ", " + (h == 1 ? "1 hour" : h + " hours");
    }

    static String ending(String cause) {
        if (cause == null || cause.isEmpty()) return null;
        switch (cause) {
            case "zombies": return "Killed by the dead";
            case "the infection": return "Taken by the infection";
            case "starvation": return "Starved";
            case "thirst": return "Died of thirst";
            default: return "Died of " + cause;
        }
    }

    private static String date(JSONObject d) {
        if (d == null || !d.has("y")) return null;
        return MONTHS[Math.max(0, Math.min(11, d.optInt("mo", 1) - 1))] + " " + d.optInt("d") + ", " + d.optInt("y");
    }

    /** The way walked, in squares (about metres). */
    static double walked(int[] p) {
        double s = 0;
        for (int i = 2; p != null && i + 1 < p.length; i += 2) s += Math.hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1]);
        return s;
    }

    static void show(Context c, Host host, JSONObject r) {
        Theme t = host.theme();
        LinearLayout sheet = Ui.col(c);
        LinearLayout card = Ui.col(c);
        card.setBackground(t.round(PAPER, 10));
        card.setPadding(t.dp(18), t.dp(16), t.dp(18), t.dp(16));
        TextView name = Ui.text(c, t, r.optString("name"), 24, INK, true);
        name.setTypeface(Typeface.create("serif", Typeface.BOLD));
        card.addView(name);
        String from = date(r.optJSONObject("start")), to = date(r.optJSONObject("died"));
        String sub = (r.optString("prof", "").isEmpty() ? "" : r.optString("prof") + " · ") + "survived " + lasted(r.optDouble("hours"));
        card.addView(Ui.text(c, t, sub, 14, INK_SOFT, false), Ui.margins(Ui.lp(-1, -2), 0, t.dp(4), 0, 0));
        if (from != null && to != null) card.addView(Ui.text(c, t, from + " – " + to, 13, INK_SOFT, false), Ui.margins(Ui.lp(-1, -2), 0, t.dp(2), 0, 0));
        String end = ending(r.optString("cause", null));
        if (end != null) {
            TextView e = Ui.text(c, t, end + ".", 15, RED, true);
            e.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
            card.addView(e, Ui.margins(Ui.lp(-1, -2), 0, t.dp(8), 0, 0));
        }

        // the numbers
        LinearLayout stats = Ui.row(c);
        int dayN = Math.max(1, (int) (r.optDouble("hours") / 24) + 1);
        stats.addView(stat(c, t, String.valueOf(dayN), dayN == 1 ? "day" : "days"), Ui.weight(1));
        stats.addView(stat(c, t, String.valueOf(r.optInt("kills")), r.optInt("kills") == 1 ? "kill" : "kills"), Ui.weight(1));
        TextView walkedNum = Ui.text(c, t, "…", 22, INK, true);
        LinearLayout walkedBox = statBox(c, t, walkedNum, "walked");
        stats.addView(walkedBox, Ui.weight(1));
        card.addView(stats, Ui.margins(Ui.lp(-1, -2), 0, t.dp(14), 0, 0));

        // the way they went
        TrailView trail = new TrailView(c, t);
        card.addView(trail, Ui.margins(Ui.lp(-1, t.dp(190)), 0, t.dp(12), 0, 0));
        final int[][] points = {null};
        final Bitmap[] photo = {null};
        new Thread(() -> {
            byte[] raw = Photos.fetch(host.apiUrl("/api/trail?id=" + Uri.encode(r.optString("id"))));
            int[] p = null;
            try {
                JSONArray a = raw == null ? null : new JSONObject(new String(raw, "UTF-8")).optJSONArray("p");
                if (a != null) {
                    p = new int[a.length() - a.length() % 2];
                    for (int i = 0; i < p.length; i++) p[i] = a.optInt(i);
                }
            } catch (Exception ignored) {
            }
            final int[] pts = p;
            main.post(() -> {
                points[0] = pts;
                trail.setPoints(pts);
                walkedNum.setText(pts == null || pts.length < 4 ? "–" : Fmt.dist(walked(pts)));
            });
        }, "knoxlink-recap").start();

        JSONArray best = r.optJSONArray("best");
        if (best != null && best.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < best.length(); i++) {
                JSONObject b = best.optJSONObject(i);
                if (b != null) sb.append(sb.length() > 0 ? " · " : "").append(b.optString("n")).append(' ').append(b.optInt("l"));
            }
            card.addView(Ui.text(c, t, "Best at: " + sb, 13.5f, INK, false), Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));
        }
        JSONArray moments = r.optJSONArray("moments");
        for (int i = 0; moments != null && i < moments.length(); i++) {
            JSONObject m = moments.optJSONObject(i);
            if (m == null) continue;
            TextView tv = Ui.text(c, t, "Day " + m.optInt("d") + "  ·  " + m.optString("s"), 13.5f, INK_SOFT, false);
            tv.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            card.addView(tv, Ui.margins(Ui.lp(-1, -2), 0, t.dp(i == 0 ? 10 : 4), 0, 0));
        }
        String ph = r.optString("ph", "");
        if (!ph.isEmpty()) {
            ImageView iv = new ImageView(c);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(iv, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));
            Photos.load(host, ph, 1200, b -> {
                photo[0] = b;
                if (b != null) iv.setImageBitmap(b);
            });
        }
        sheet.addView(card, Ui.lp(-1, -2));

        TextView save = Ui.action(c, t, "camera", "Save this card as a picture");
        save.setOnClickListener(v -> {
            Bitmap card2 = render(r, points[0], photo[0]);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            card2.compress(Bitmap.CompressFormat.PNG, 100, png);
            card2.recycle();
            String where = Photos.savePicture(c, "KnoxLink-" + r.optString("name").replaceAll("[^A-Za-z0-9]+", "-") + "-" + r.optString("id") + ".png",
                    "image/png", png.toByteArray());
            host.toast(where != null ? "Saved to Pictures/Knox Link" : "Couldn't save it", where == null);
        });
        sheet.addView(save, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, t.dp(8)));
        TextView close = Ui.action(c, t, "x", "Close");
        close.setOnClickListener(v -> host.closeSheet());
        sheet.addView(close);
        host.openSheet(sheet);
    }

    private static View stat(Context c, Theme t, String value, String label) {
        return statBox(c, t, Ui.text(c, t, value, 22, INK, true), label);
    }

    private static LinearLayout statBox(Context c, Theme t, TextView value, String label) {
        LinearLayout b = Ui.col(c);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        value.setTypeface(Typeface.create("serif", Typeface.BOLD));
        b.addView(value);
        b.addView(Ui.text(c, t, label, 12, INK_SOFT, false), Ui.margins(Ui.lp(-2, -2), 0, t.dp(2), 0, 0));
        return b;
    }

    /** The way walked, drawn small: start in green, the end in red. */
    static final class TrailView extends View {
        private int[] pts;
        private final Theme t;

        TrailView(Context c, Theme t) {
            super(c);
            this.t = t;
        }

        void setPoints(int[] p) {
            pts = p;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            drawTrail(canvas, new RectF(0, 0, getWidth(), getHeight()), pts, t.dpf(1));
        }
    }

    static void drawTrail(Canvas canvas, RectF box, int[] pts, float dp) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFDDD5BC);
        canvas.drawRoundRect(box, 8 * dp, 8 * dp, p);
        if (pts == null || pts.length < 4) {
            p.setColor(INK_SOFT);
            p.setTextSize(13 * dp);
            p.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(pts == null ? "…" : "They didn't go far.", box.centerX(), box.centerY(), p);
            return;
        }
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (int i = 0; i + 1 < pts.length; i += 2) {
            x0 = Math.min(x0, pts[i]);
            x1 = Math.max(x1, pts[i]);
            y0 = Math.min(y0, pts[i + 1]);
            y1 = Math.max(y1, pts[i + 1]);
        }
        float pad = 14 * dp;
        float w = Math.max(20, x1 - x0), h = Math.max(20, y1 - y0);
        float s = Math.min((box.width() - 2 * pad) / w, (box.height() - 2 * pad) / h);
        float ox = box.left + (box.width() - w * s) / 2 - x0 * s, oy = box.top + (box.height() - h * s) / 2 - y0 * s;
        // a faint grid, a square every 100 squares
        p.setColor(0x22000000);
        p.setStrokeWidth(dp);
        float step = 100 * s;
        if (step > 12 * dp) {
            // the first line inside the box, then every step (lines at world multiples of 100)
            float gx0 = ox + (float) Math.ceil((box.left - ox) / step) * step, gy0 = oy + (float) Math.ceil((box.top - oy) / step) * step;
            for (float gx = gx0; gx < box.right; gx += step) canvas.drawLine(gx, box.top, gx, box.bottom, p);
            for (float gy = gy0; gy < box.bottom; gy += step) canvas.drawLine(box.left, gy, box.right, gy, p);
        }
        Path path = new Path();
        path.moveTo(ox + pts[0] * s, oy + pts[1] * s);
        for (int i = 2; i + 1 < pts.length; i += 2) path.lineTo(ox + pts[i] * s, oy + pts[i + 1] * s);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(2.5f * dp);
        p.setColor(0xCC8A2B1E);
        canvas.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(GREEN);
        canvas.drawCircle(ox + pts[0] * s, oy + pts[1] * s, 5 * dp, p);
        float ex = ox + pts[pts.length - 2] * s, ey = oy + pts[pts.length - 1] * s;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3 * dp);
        p.setColor(RED);
        float r = 5 * dp;
        canvas.drawLine(ex - r, ey - r, ex + r, ey + r, p);
        canvas.drawLine(ex - r, ey + r, ex + r, ey - r, p);
    }

    /** The card as a picture to keep: 1080 wide, on the journal's paper. */
    static Bitmap render(JSONObject r, int[] trail, Bitmap photo) {
        int w = 1080;
        float u = 2.6f;   // "dp" in the picture
        int photoH = photo == null ? 0 : (int) (photo.getHeight() * (w - 120f) / photo.getWidth());
        int h = 1000 + photoH + (photo == null ? 0 : 40);
        JSONArray moments = r.optJSONArray("moments");
        h += (moments == null ? 0 : moments.length()) * 46;
        JSONArray best = r.optJSONArray("best");
        StringBuilder bestText = new StringBuilder();
        for (int i = 0; best != null && i < best.length(); i++) {
            JSONObject b = best.optJSONObject(i);
            if (b != null) bestText.append(bestText.length() > 0 ? " · " : "").append(b.optString("n")).append(' ').append(b.optInt("l"));
        }
        if (bestText.length() > 0) h += 50;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(PAPER);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        float y = 70;
        tp.setColor(INK);
        tp.setTypeface(Typeface.create("serif", Typeface.BOLD));
        tp.setTextSize(64);
        c.drawText(r.optString("name"), 60, y + 40, tp);
        y += 80;
        tp.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        tp.setTextSize(32);
        tp.setColor(INK_SOFT);
        String sub = (r.optString("prof", "").isEmpty() ? "" : r.optString("prof") + " · ") + "survived " + lasted(r.optDouble("hours"));
        c.drawText(sub, 60, y + 20, tp);
        y += 46;
        String from = date(r.optJSONObject("start")), to = date(r.optJSONObject("died"));
        if (from != null && to != null) {
            c.drawText(from + " – " + to, 60, y + 20, tp);
            y += 46;
        }
        String end = ending(r.optString("cause", null));
        if (end != null) {
            tp.setColor(RED);
            tp.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
            tp.setTextSize(38);
            c.drawText(end + ".", 60, y + 30, tp);
            y += 60;
        }
        // numbers
        int dayN = Math.max(1, (int) (r.optDouble("hours") / 24) + 1);
        String[][] nums = {
                {String.valueOf(dayN), dayN == 1 ? "day" : "days"},
                {String.valueOf(r.optInt("kills")), r.optInt("kills") == 1 ? "kill" : "kills"},
                {trail == null || trail.length < 4 ? "–" : Fmt.dist(walked(trail)), "walked"}};
        for (int i = 0; i < 3; i++) {
            float cx = 60 + (w - 120) * (i + 0.5f) / 3;
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setColor(INK);
            tp.setTypeface(Typeface.create("serif", Typeface.BOLD));
            tp.setTextSize(58);
            c.drawText(nums[i][0], cx, y + 70, tp);
            tp.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            tp.setTextSize(28);
            tp.setColor(INK_SOFT);
            c.drawText(nums[i][1], cx, y + 110, tp);
        }
        tp.setTextAlign(Paint.Align.LEFT);
        y += 140;
        drawTrail(c, new RectF(60, y, w - 60, y + 420), trail, u);
        y += 450;
        if (bestText.length() > 0) {
            tp.setColor(INK);
            tp.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            tp.setTextSize(30);
            String s = "Best at: " + bestText;
            c.drawText(s, 0, tp.breakText(s, true, w - 120, null), 60, y + 28, tp);
            y += 50;
        }
        for (int i = 0; moments != null && i < moments.length(); i++) {
            JSONObject m = moments.optJSONObject(i);
            if (m == null) continue;
            tp.setColor(INK_SOFT);
            tp.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            tp.setTextSize(30);
            StaticLayout sl = StaticLayout.Builder.obtain("Day " + m.optInt("d") + "  ·  " + m.optString("s"), 0,
                    ("Day " + m.optInt("d") + "  ·  " + m.optString("s")).length(), tp, w - 120).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(1).setEllipsize(android.text.TextUtils.TruncateAt.END).build();
            c.save();
            c.translate(60, y);
            sl.draw(c);
            c.restore();
            y += 46;
        }
        if (photo != null) {
            y += 20;
            Paint pp = new Paint(Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(photo, null, new RectF(60, y, w - 60, y + photoH), pp);
            y += photoH;
        }
        tp.setColor(0x80000000);
        tp.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        tp.setTextSize(24);
        tp.setTextAlign(Paint.Align.RIGHT);
        c.drawText("Project Zomboid · Knox Link", w - 60, h - 30, tp);
        return bmp;
    }
}
