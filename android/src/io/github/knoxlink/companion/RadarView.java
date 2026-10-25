package io.github.knoxlink.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The danger radar: the zombies around the character as far as the character knows (the game's
 * "radar" channel, KnoxLink_Radar.lua), drawn round the character with the view pointing up.
 *
 * Small enough for the top bar; a tap opens the larger radar with its legend and settings
 * (RadarSheet), which shows the same {@link Model}. Marks glide between the game's updates at up
 * to 30 frames a second and the view stops drawing once nothing moves; heard marks breathe and
 * lost ones fade at a slower pace. A zombie close behind lights the rim on its side and buzzes
 * once (and beeps, if the player turned that on).
 */
final class RadarView extends View {
    static final String PREF_RANGE = "radarRange", PREF_VIBRATE = "radarVibrate", PREF_SOUND = "radarSound";
    static final int[] RANGES = {10, 20, 30};
    private static final float LOST_S = 6f;           // how long the game keeps a lost zombie (LOST_MS)
    private static final long STALE_MS = 8000;        // no word from the game: the radar dims and shows nothing
    private static final long FAST_MS = 33, SLOW_MS = 90;

    private final Host host;
    private final Theme t;
    private final Model model;
    private final boolean big;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path path = new Path();
    private boolean wanted = true, framePending;
    private TextView summary;
    private String summaryShown;
    private final Runnable frame = () -> {
        framePending = false;
        invalidate();
    };

    /** The radar for the top bar. */
    RadarView(Context c, Host host) {
        this(c, host, null, false);
    }

    /** big: the sheet's radar, drawing the top bar's model larger, with distances on its rings. */
    RadarView(Context c, Host host, Model shared, boolean big) {
        super(c);
        this.host = host;
        this.t = host.theme();
        this.big = big;
        this.model = shared != null ? shared : new Model(c, host);
        line.setStyle(Paint.Style.STROKE);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(t.medium());
        label.setTextSize(t.sp(10.5f) * c.getResources().getDisplayMetrics().scaledDensity);
        setContentDescription("Danger radar");
        if (!big) {
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.OVAL);
            mask.setColor(0xFFFFFFFF);
            setForeground(new RippleDrawable(ColorStateList.valueOf(Theme.alpha(t.text, 0.16f)), null, mask));
            setOnClickListener(v -> openSheet());
        }
    }

    /** A "radar" message from the game; null clears the radar (another game, or the link went down). */
    void update(JSONObject radar) {
        if (radar == null) model.clear();
        else model.update(radar);
    }

    /** Whether the radar should show at all (e.g. only in game). Hidden, it doesn't buzz or beep either. */
    void setVisibleWanted(boolean on) {
        if (on == wanted) return;
        wanted = on;
        model.alerts = on;
        animate().cancel();
        if (on) {
            setVisibility(VISIBLE);
            setAlpha(0f);
            animate().alpha(1f).setDuration(160).start();
            kick();
        } else {
            setVisibility(GONE);
            model.quiet();
        }
    }

    /** The larger radar, its legend and settings, in a sheet. */
    void openSheet() {
        RadarSheet.open(getContext(), host, model);
    }

    Model model() {
        return model;
    }

    /** The sheet's line under its title: what the radar shows right now. */
    void setSummary(TextView v) {
        summary = v;
        summaryShown = null;
        kick();
    }

    /** New data: draw now; onDraw decides the pace from there. */
    void kick() {
        removeCallbacks(frame);
        framePending = false;
        invalidate();
        if (summary != null) {
            String s = model.summary();
            if (!s.equals(summaryShown)) {
                summaryShown = s;
                summary.setText(s);
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!model.views.contains(this)) model.views.add(this);
        kick();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        model.views.remove(this);
        removeCallbacks(frame);
        framePending = false;
        if (!big) model.quiet();
    }

    @Override
    protected void onMeasure(int wms, int hms) {
        int want = big ? t.dp(260) : t.dp(46);
        int w = size(want, wms), h = size(want, hms);
        int s = Math.min(w, h);
        setMeasuredDimension(s, s);
    }

    private static int size(int want, int spec) {
        int mode = MeasureSpec.getMode(spec), size = MeasureSpec.getSize(spec);
        if (mode == MeasureSpec.EXACTLY) return size;
        if (mode == MeasureSpec.AT_MOST) return Math.min(want, size);
        return want;
    }

    // ------------------------------------------------------------------ drawing

    private int color(String b) {
        return "attack".equals(b) ? t.bad : "chase".equals(b) ? t.warn : t.text2;
    }

    /** Distance rings, in squares: the close-behind zone where it fits, then even steps. */
    private static float[] rings(int range) {
        if (range <= 10) return new float[]{3, 5};
        if (range <= 20) return new float[]{3, 10};
        return new float[]{10, 20};
    }

    private static String distance(float squares) {
        // a square is a metre
        return Fmt.imperial ? Math.round(squares * 1.0936f) + " yd" : Math.round(squares) + " m";
    }

    @Override
    protected void onDraw(Canvas c) {
        Model m = model;
        long now = SystemClock.uptimeMillis();
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float rim = Math.min(getWidth(), getHeight()) / 2f - t.dpf(big ? 2 : 1);
        if (rim <= 0) return;
        boolean live = m.updateAt != 0 && now - m.updateAt < STALE_MS;
        int range = m.range();
        float scale = rim / range;
        float k = m.progress(now);
        boolean fast = live && k < 1, slow = false;
        float yaw = m.yaw(k);
        double ya = Math.toRadians(yaw);
        float cos = (float) Math.cos(ya), sin = (float) Math.sin(ya);

        // the disc, the view straight up, and the distance rings
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(t.bg);
        c.drawCircle(cx, cy, rim, fill);
        float cone = Math.max(10, Math.min(180, m.cone));
        oval.set(cx - rim, cy - rim, cx + rim, cy + rim);
        fill.setColor(Theme.alpha(t.text, live ? (t.isLight() ? 0.07f : 0.06f) : 0.03f));
        c.drawArc(oval, -90 - cone, cone * 2, true, fill);
        line.setStrokeWidth(Math.max(1, t.dpf(big ? 1 : 0.8f)));
        for (float ring : rings(range)) {
            float rr = ring * scale;
            boolean danger = ring <= 3;
            if (danger && rr < t.dpf(6)) continue;   // too tight round the character to read
            line.setColor(danger ? Theme.alpha(t.bad, live ? 0.3f : 0.15f) : Theme.alpha(t.text3, live ? 0.32f : 0.18f));
            c.drawCircle(cx, cy, rr, line);
            if (big && !danger) ringLabel(c, cx, cy + rr, distance(ring));
        }
        if (big) ringLabel(c, cx, cy + rim, distance(range));

        // the zombies: lost underneath, then heard, then seen
        if (live) {
            float base = t.dpf(big ? 5f : 2.5f);
            float breath = (float) (0.5 + 0.5 * Math.sin(now * (2 * Math.PI / 1400.0)));
            float sinceUpdate = (now - m.updateAt) / 1000f;
            for (int pass = 0; pass < 3; pass++) {
                for (Dot d : m.dots.values()) {
                    int layer = "lost".equals(d.s) ? 0 : "heard".equals(d.s) ? 1 : 2;
                    if (layer != pass) continue;
                    float x = d.fromX + (d.toX - d.fromX) * k, y = d.fromY + (d.toY - d.fromY) * k;
                    if (x * x + y * y > range * range * 1.02f) continue;
                    float alpha = 1f;
                    long born = now - d.bornAt;
                    if (born < 180) {
                        alpha *= Math.max(0, born) / 180f;
                        fast = true;
                    }
                    if (d.goneAt != 0) {
                        float left = 1 - (now - d.goneAt) / 250f;
                        if (left <= 0) continue;
                        alpha *= left;
                        fast = true;
                    }
                    if (layer == 0) {
                        float left = 1 - (d.age + sinceUpdate) / LOST_S;
                        if (left <= 0) continue;
                        alpha *= 0.75f * left;
                        slow = true;
                    } else if (layer == 1) {
                        slow = true;
                    }
                    float r = base;
                    if (d.floor != 0) {
                        r *= 0.75f;   // a floor up or down
                        alpha *= 0.7f;
                    }
                    float fwd = x * cos + y * sin, right = -x * sin + y * cos;
                    mark(c, fill, line, t, cx + right * scale, cy - fwd * scale, r, d.s, color(d.b), alpha, breath, big);
                }
            }
        }

        // close behind: the rim lights up on that side, with an arrow pointing at it
        if (live && m.near && now - m.nearAt < Model.NEAR_STALE_MS) {
            float ang = m.nearA;
            Dot nd = m.dots.get(m.nearId);
            if (nd != null) {
                float x = nd.fromX + (nd.toX - nd.fromX) * k, y = nd.fromY + (nd.toY - nd.fromY) * k;
                float fwd = x * cos + y * sin, right = -x * sin + y * cos;
                if (fwd != 0 || right != 0) ang = (float) Math.toDegrees(Math.atan2(right, fwd));
            }
            long since = now - m.nearSince;
            float glow = 1f;
            if (since < 900) {
                glow = 0.45f + 0.55f * (float) Math.abs(Math.cos(since * (3 * Math.PI / 900.0)));
                fast = true;
            }
            edge(c, fill, line, path, oval, t, cx, cy, rim, ang, "attack".equals(m.nearB) ? t.bad : t.warn, glow, big);
        }

        // the character, facing up
        float ph = t.dpf(big ? 7f : 3.6f), pw = ph * 0.78f;
        path.rewind();
        path.moveTo(cx, cy - ph);
        path.lineTo(cx + pw, cy + ph * 0.75f);
        path.lineTo(cx, cy + ph * 0.3f);
        path.lineTo(cx - pw, cy + ph * 0.75f);
        path.close();
        fill.setColor(live ? t.text : Theme.alpha(t.text, 0.45f));
        c.drawPath(path, fill);

        line.setStrokeWidth(Math.max(1, t.dpf(1)));
        line.setColor(t.line);
        c.drawCircle(cx, cy, rim, line);

        // the next frame: smooth while marks glide, slower for breathing and fading, none when still
        if (!framePending && wanted && isShown()) {
            long delay = fast ? FAST_MS : slow ? SLOW_MS : -1;
            if (delay < 0 && live) delay = m.updateAt + STALE_MS - now + 50;   // to dim when the game goes quiet
            if (delay >= 0) {
                framePending = true;
                postDelayed(frame, delay);
            }
        }
    }

    private void ringLabel(Canvas c, float x, float ringBottom, String s) {
        label.setColor(Theme.alpha(t.text3, 0.9f));
        c.drawText(s, x, ringBottom - t.dpf(4), label);
    }

    /** One mark: seen is a solid dot, heard a hollow ring that breathes, lost a dot that fades. */
    static void mark(Canvas c, Paint fill, Paint line, Theme t, float x, float y, float r, String s, int color,
                     float alpha, float breath, boolean big) {
        alpha = Math.max(0, Math.min(1, alpha));
        fill.setStyle(Paint.Style.FILL);
        if ("lost".equals(s)) {
            fill.setColor(Theme.alpha(color, alpha));
            c.drawCircle(x, y, r * 0.8f, fill);
        } else if ("heard".equals(s)) {
            float rr = r * (1.05f + 0.22f * breath);
            fill.setColor(Theme.alpha(color, alpha * 0.16f));
            c.drawCircle(x, y, rr, fill);
            line.setStrokeWidth(t.dpf(big ? 1.8f : 1.2f));
            line.setColor(Theme.alpha(color, alpha * (0.72f + 0.28f * (1 - breath))));
            c.drawCircle(x, y, rr, line);
        } else {
            fill.setColor(Theme.alpha(t.bg, alpha * 0.9f));
            c.drawCircle(x, y, r + t.dpf(big ? 1.5f : 0.9f), fill);
            fill.setColor(Theme.alpha(color, alpha));
            c.drawCircle(x, y, r, fill);
        }
    }

    /** The rim lit up at angle (degrees from up, clockwise), with an arrow pointing out at it. */
    static void edge(Canvas c, Paint fill, Paint line, Path path, RectF oval, Theme t, float cx, float cy, float rim,
                     float angle, int color, float glow, boolean big) {
        float sw = t.dpf(big ? 4f : 2.4f);
        float ar = rim - sw / 2f;
        oval.set(cx - ar, cy - ar, cx + ar, cy + ar);
        line.setStrokeWidth(sw);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(Theme.alpha(color, 0.95f * glow));
        c.drawArc(oval, angle - 90 - 24, 48, false, line);
        line.setStrokeCap(Paint.Cap.BUTT);
        // a narrow arrow (twice as long as it's wide) so its point reads at a glance
        float tip = rim - sw - t.dpf(big ? 2f : 0.8f), back = tip - t.dpf(big ? 11f : 5.6f), half = t.dpf(big ? 5f : 2.8f);
        c.save();
        c.rotate(angle, cx, cy);
        path.rewind();
        path.moveTo(cx, cy - tip);
        path.lineTo(cx + half, cy - back);
        path.lineTo(cx - half, cy - back);
        path.close();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Theme.alpha(color, glow));
        c.drawPath(path, fill);
        c.restore();
    }

    /** A legend sample: one kind of mark, or the close-behind rim (kind "near"). */
    static final class Swatch extends View {
        private final Theme t;
        private final String kind;
        private final int color;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF oval = new RectF();

        Swatch(Context c, Theme t, String kind, int color) {
            super(c);
            this.t = t;
            this.kind = kind;
            this.color = color;
            line.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if ("near".equals(kind)) {
                float rim = Math.min(cx, cy) - t.dpf(1);
                // the bottom of a small rim: behind you
                edge(c, fill, line, path, oval, t, cx, cy - rim * 0.55f, rim * 1.35f, 180, color, 1f, false);
            } else {
                mark(c, fill, line, t, cx, cy, t.dpf(4.5f), kind, color, "lost".equals(kind) ? 0.5f : 1f, 0.35f, true);
            }
        }
    }

    // ------------------------------------------------------------------ what the radar knows

    /** One zombie on the radar. Positions are squares from the character on the world's axes. */
    static final class Dot {
        final int id;
        float fromX, fromY, toX, toY;   // where it was drawn when the last update came, and where it is now
        String s = "seen", b = "idle";
        int floor;
        float age;                      // seconds since lost, as of the last update
        long bornAt, goneAt;            // first shown; left the game's list (fades out), 0 while on it
        boolean present;

        Dot(int id) {
            this.id = id;
        }
    }

    /**
     * The game's radar between its updates, shared by the top bar's radar and the sheet's, so both
     * glide the same way. It also buzzes and beeps, once for both.
     */
    static final class Model {
        static final long NEAR_STALE_MS = 4000;   // the game repeats itself every 3 s when nothing changes
        private static final long QUIET_MS = 5000; // a zombie behind you again after this long is news again

        final Map<Integer, Dot> dots = new LinkedHashMap<>();
        final List<RadarView> views = new ArrayList<>();
        long updateAt;                 // uptime of the last update; 0 = none yet
        float interval = 200;          // ms between updates, smoothed: how long a mark takes to glide
        float yawFrom, yawTo;          // degrees; yawTo is yawFrom plus the shortest turn
        float cone = 72;
        int gameRange;
        boolean near;
        float nearA, nearD;
        int nearId = Integer.MIN_VALUE;
        String nearB = "idle";
        long nearAt, nearSince = -100000;
        boolean alerts = true;         // the radar is on show: buzz and beep for it

        private final Context ctx;
        private final Host host;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Map<Integer, Long> nearSeen = new HashMap<>();
        private long lastBuzzAt = -100000, askedAt = -100000;
        private ToneGenerator tone;
        private boolean pulsing;
        private final Runnable pulse = this::pulseNow;
        private final Runnable releaseTone = () -> {
            if (tone != null) {
                tone.release();
                tone = null;
            }
        };

        Model(Context c, Host host) {
            this.ctx = c.getApplicationContext();
            this.host = host;
        }

        int range() {
            int r = host.prefs().getInt(PREF_RANGE, 20);
            return r == 10 || r == 30 ? r : 20;
        }

        /** The player picked a range: the game is told (it only looks that far), the radars redraw. */
        void setRange(int r) {
            host.prefs().edit().putInt(PREF_RANGE, r).apply();
            long now = SystemClock.uptimeMillis();
            if (updateAt != 0 && now - updateAt < STALE_MS && gameRange != r) {
                askedAt = now;
                host.command("radar", Ui.args("r", r), null);
            }
            for (RadarView v : views) v.kick();
        }

        float progress(long now) {
            if (updateAt == 0) return 1f;
            return Math.max(0f, Math.min(1f, (now - updateAt) / interval));
        }

        float yaw(float k) {
            return yawFrom + (yawTo - yawFrom) * k;
        }

        void update(JSONObject o) {
            if (o == null) return;
            if (Looper.myLooper() != Looper.getMainLooper()) {
                main.post(() -> update(o));
                return;
            }
            long now = SystemClock.uptimeMillis();
            boolean fresh = updateAt == 0 || now - updateAt > STALE_MS;   // nothing to glide from
            float k = progress(now);
            // where everything is drawn right now is where its next glide starts
            for (Dot d : dots.values()) {
                d.fromX += (d.toX - d.fromX) * k;
                d.fromY += (d.toY - d.fromY) * k;
                d.toX = d.fromX;
                d.toY = d.fromY;
                d.present = false;
            }
            float shown = yaw(k);
            double y = o.optDouble("yaw", Double.NaN);
            if (Double.isNaN(y)) {
                yawFrom = yawTo = shown;
            } else if (fresh) {
                yawFrom = yawTo = (float) y;
            } else {
                yawFrom = shown;
                yawTo = shown + wrap((float) y - shown);
            }
            if (Math.abs(yawFrom) > 720) {
                float turns = 360f * Math.round(yawFrom / 360f);
                yawFrom -= turns;
                yawTo -= turns;
            }
            if (!fresh) interval = Math.max(120, Math.min(350, interval * 0.7f + Math.min(now - updateAt, 400) * 0.3f));
            cone = (float) o.optDouble("c", cone);
            gameRange = o.optInt("r", gameRange);

            JSONArray z = arr(o.opt("z"));
            for (int i = 0; i < z.length(); i++) {
                JSONObject e = z.optJSONObject(i);
                if (e == null) continue;
                int id = e.optInt("i", -(i + 1));
                float x = (float) e.optDouble("x", 0), yy = (float) e.optDouble("y", 0);
                Dot d = dots.get(id);
                if (d == null) {
                    d = new Dot(id);
                    d.fromX = x;
                    d.fromY = yy;
                    d.bornAt = now;
                    dots.put(id, d);
                } else if (fresh) {
                    d.fromX = x;
                    d.fromY = yy;
                }
                d.toX = x;
                d.toY = yy;
                d.present = true;
                d.goneAt = 0;
                d.s = e.optString("s", "seen");
                d.b = e.optString("b", "idle");
                d.floor = e.optInt("f", 0);
                d.age = (float) e.optDouble("a", 0);
            }
            for (Iterator<Dot> it = dots.values().iterator(); it.hasNext(); ) {
                Dot d = it.next();
                if (d.present) continue;
                if (d.goneAt == 0 && !fresh) d.goneAt = now;   // left the list: fades out
                else if (fresh || now - d.goneAt > 260) it.remove();
            }
            updateAt = now;

            JSONObject n = o.optJSONObject("near");
            near = n != null;
            if (near) {
                nearId = n.optInt("i", Integer.MIN_VALUE);
                nearA = (float) n.optDouble("a", 180);
                nearD = (float) n.optDouble("d", 3);
                nearB = n.optString("b", "idle");
                nearAt = now;
                // a new close-behind event: this zombie wasn't behind you a moment ago
                Long last = nearSeen.put(nearId, now);
                if (last == null || now - last > QUIET_MS) {
                    nearSince = now;
                    if (alerts && now - lastBuzzAt > 1200) {
                        lastBuzzAt = now;
                        vibrate("attack".equals(nearB));
                    }
                }
                if (alerts && host.prefs().getBoolean(PREF_SOUND, false)) startPulse();
            }
            nearSeen.values().removeIf(at -> now - at > QUIET_MS * 2);

            // the game looks as far as the player asked (it forgets after a restart)
            int want = range();
            if (gameRange != 0 && gameRange != want && now - askedAt > 30000) {
                askedAt = now;
                host.command("radar", Ui.args("r", want), null);
            }
            for (RadarView v : new ArrayList<>(views)) v.kick();
        }

        /** Forgets everything until the game speaks again. */
        void clear() {
            dots.clear();
            updateAt = 0;
            near = false;
            gameRange = 0;
            nearSeen.clear();
            quiet();
            for (RadarView v : new ArrayList<>(views)) v.kick();
        }

        /** "2 seen · 1 heard", for the sheet. */
        String summary() {
            long now = SystemClock.uptimeMillis();
            if (updateAt == 0) return "Waiting for the game";
            if (now - updateAt > STALE_MS) return "No word from the game";
            int range = range(), seen = 0, heard = 0;
            for (Dot d : dots.values()) {
                if (!d.present || d.toX * d.toX + d.toY * d.toY > range * range * 1.02f) continue;
                if ("seen".equals(d.s)) seen++;
                else if ("heard".equals(d.s)) heard++;
            }
            String s = seen == 0 && heard == 0 ? "Nothing seen or heard nearby"
                    : seen > 0 && heard > 0 ? seen + " seen · " + heard + " heard"
                    : seen > 0 ? seen + " seen" : heard + " heard";
            if (near && now - nearAt < NEAR_STALE_MS) s += " · one close behind you";
            return s;
        }

        /** Stops the beeps (the radar was hidden or the sound turned off). */
        void quiet() {
            main.removeCallbacks(pulse);
            pulsing = false;
            main.removeCallbacks(releaseTone);
            releaseTone.run();
        }

        /** The sound setting changed. */
        void soundChanged() {
            if (!host.prefs().getBoolean(PREF_SOUND, false)) quiet();
            else if (near && alerts && SystemClock.uptimeMillis() - nearAt < NEAR_STALE_MS) startPulse();
        }

        private void vibrate(boolean strong) {
            SharedPreferences p = host.prefs();
            // the app's own vibration setting, then the radar's
            if (!p.getBoolean("vibrate", true) || !p.getBoolean(PREF_VIBRATE, true)) return;
            android.os.Vibrator v = (android.os.Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            long[] pattern = strong ? new long[]{0, 140, 90, 200} : new long[]{0, 70, 60, 110};
            try {
                v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1));
            } catch (Exception ignored) {
            }
        }

        private void startPulse() {
            if (pulsing) return;
            pulsing = true;
            main.removeCallbacks(releaseTone);
            main.post(pulse);
        }

        /** A beep like a parking sensor's: quicker the closer the zombie behind you is. */
        private void pulseNow() {
            pulsing = false;
            long now = SystemClock.uptimeMillis();
            if (!near || !alerts || now - nearAt > NEAR_STALE_MS || !host.prefs().getBoolean(PREF_SOUND, false)) {
                main.removeCallbacks(releaseTone);
                main.postDelayed(releaseTone, 5000);
                return;
            }
            try {
                if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 55);
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 60);
            } catch (RuntimeException e) {
                tone = null;   // no audio for us right now
                return;
            }
            float f = Math.max(0f, Math.min(1f, (nearD - 1f) / 2f));
            pulsing = true;
            main.postDelayed(pulse, (long) (260 + 640 * f));
        }

        private static float wrap(float a) {
            return a - 360f * (float) Math.floor((a + 180f) / 360f);
        }

        /** Lists from Lua arrive as {} when empty. */
        private static JSONArray arr(Object o) {
            if (o instanceof JSONArray) return (JSONArray) o;
            return new JSONArray();
        }
    }
}
