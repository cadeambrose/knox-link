package io.github.knoxlink.companion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * The body, drawn with the game's own health-panel silhouette (media/ui/defense): the base figure
 * plus one overlay per body part, tinted by the part's state. Falls back to simple shapes until
 * the artwork has arrived. In "look" mode the same figure is painted the way the character looks:
 * skin, hair, and the colour of whatever they wear on each part.
 */
final class BodyView extends View {
    interface Listener {
        /** A body part was tapped; null = the space around the figure. */
        void onPart(String key);
    }

    private boolean look;
    private final Map<String, Integer> lookColors = new HashMap<>();
    private int skin = 0xFFD5A27C, hair = 0xFF4A3424;
    private boolean headCovered;

    /** Paints the figure as the character looks (true) or by injuries (false). */
    void setLook(boolean on) {
        look = on;
        invalidate();
    }

    boolean isLook() {
        return look;
    }

    /** Colour per body part from what's worn on it; parts not in the map show skin. */
    void setLookColors(Map<String, Integer> parts, int skinColor, int hairColor, boolean headIsCovered) {
        lookColors.clear();
        lookColors.putAll(parts);
        skin = skinColor;
        hair = hairColor;
        headCovered = headIsCovered;
        if (look) invalidate();
    }

    /** BodyPartType name -> image suffix in media/ui/defense, and a fallback rectangle (200x425 box). */
    static final String[][] PARTS = {
            {"Head", "head", "75,8,125,64"}, {"Neck", "neck", "88,64,112,77"},
            {"Torso_Upper", "upper_body", "62,79,138,152"}, {"Torso_Lower", "lower_body", "66,154,134,204"},
            {"Groin", "groin", "70,206,130,236"},
            {"UpperArm_R", "upper_arms_right", "35,83,59,149"}, {"UpperArm_L", "upper_arms_left", "141,83,165,149"},
            {"ForeArm_R", "lower_arms_right", "29,151,53,215"}, {"ForeArm_L", "lower_arms_left", "147,151,171,215"},
            {"Hand_R", "hand_right", "25,217,52,248"}, {"Hand_L", "hand_left", "148,217,175,248"},
            {"UpperLeg_R", "upper_legs_right", "70,238,98,317"}, {"UpperLeg_L", "upper_legs_left", "102,238,130,317"},
            {"LowerLeg_R", "lower_legs_right", "72,319,97,393"}, {"LowerLeg_L", "lower_legs_left", "103,319,128,393"},
            {"Foot_R", "feet_right", "64,395,97,417"}, {"Foot_L", "feet_left", "103,395,136,417"},
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Map<String, Bitmap> art = new HashMap<>();
    private final Map<String, Integer> tint = new HashMap<>();
    private final RectF box = new RectF();
    private Bitmap base;
    private String sex = "";
    private String selected;
    private boolean bleeding;
    private Theme theme;
    private Listener listener;
    private ArtCache cache;

    BodyView(Context ctx) {
        super(ctx);
    }

    void setup(Theme t, ArtCache c, Listener l) {
        theme = t;
        cache = c;
        listener = l;
        invalidate();
    }

    void setSex(boolean female) {
        String s = female ? "female" : "male";
        if (s.equals(sex) || cache == null) return;
        sex = s;
        base = null;
        art.clear();
        cache.media("ui/defense/" + s + "_base.png", b -> { base = b; invalidate(); });
        for (String[] p : PARTS) {
            final String key = p[0];
            String img = p[1];
            // the game's male pictures have chest and belly the wrong way round ("lower_body" is the chest)
            if ("male".equals(s) && "upper_body".equals(img)) img = "lower_body";
            else if ("male".equals(s) && "lower_body".equals(img)) img = "upper_body";
            cache.media("ui/defense/" + s + "_" + img + ".png", b -> {
                if (b != null) art.put(key, b);
                invalidate();
            });
        }
    }

    void setSelected(String key) {
        selected = key;
        invalidate();
    }

    /** Colour per part from the body message. */
    void update(org.json.JSONArray parts) {
        tint.clear();
        bleeding = false;
        for (int i = 0; parts != null && i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p == null) continue;
            String k = p.optString("k");
            Integer c = colorFor(p);
            if (c != null) tint.put(k, c);
            if (p.optBoolean("bleed")) bleeding = true;
        }
        invalidate();
    }

    private Integer colorFor(JSONObject p) {
        if (p.optBoolean("bleed")) return 0xFFEF5D5D;
        if (p.optBoolean("band")) return p.optBoolean("dirty") ? 0xFFB9A888 : 0xFFE6E9EE;
        double hp = p.optDouble("hp", 100);
        boolean wound = p.optBoolean("scratch") || p.optBoolean("cut") || p.optBoolean("deep") || p.optBoolean("bite")
                || p.optBoolean("burn") || p.optBoolean("fracture") || p.optBoolean("glass") || p.optBoolean("bullet");
        if (hp < 40) return 0xFFC0503A;
        if (hp < 75) return 0xFFC98A2E;
        if (hp < 100 || wound) return 0xFFC9B04A;
        return null;
    }

    private void fit() {
        float w = getWidth(), h = getHeight();
        float aw = base != null ? base.getWidth() : 200, ah = base != null ? base.getHeight() : 425;
        float s = Math.min(w / aw, h / ah);
        float bw = aw * s, bh = ah * s;
        box.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (theme == null) return;
        fit();
        float pulse = bleeding ? (float) (0.5 + 0.5 * Math.sin(SystemClock.uptimeMillis() / 180.0)) : 0f;
        if (look && base != null && art.size() >= PARTS.length - 2) {
            paint.setColorFilter(new PorterDuffColorFilter(Theme.mix(skin, 0xFF000000, 0.3f), PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(base, null, box, paint);
            for (String[] p : PARTS) {
                Bitmap b = art.get(p[0]);
                if (b == null) continue;
                Integer c = lookColors.get(p[0]);
                int color = c == null ? skin : c;
                if (p[0].equals(selected)) color = Theme.mix(color, theme.accent, 0.5f);
                paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                canvas.drawBitmap(b, null, box, paint);
            }
            Bitmap head = art.get("Head");
            if (head != null && !headCovered) {
                // hair: the top of the head in the hair colour
                canvas.save();
                canvas.clipRect(box.left, box.top, box.right, box.top + box.height() * (30f / 425f));
                paint.setColorFilter(new PorterDuffColorFilter(hair, PorterDuff.Mode.SRC_IN));
                canvas.drawBitmap(head, null, box, paint);
                canvas.restore();
            }
            paint.setColorFilter(null);
            return;
        }
        if (base != null && art.size() >= PARTS.length - 2) {
            paint.setColorFilter(new PorterDuffColorFilter(theme.surface3, PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(base, null, box, paint);
            for (String[] p : PARTS) {
                Bitmap b = art.get(p[0]);
                if (b == null) continue;
                Integer c = tint.get(p[0]);
                int color = c == null ? Theme.mix(theme.surface3, theme.text3, 0.35f) : c;
                if (c != null && c == 0xFFEF5D5D) color = Theme.mix(0xFFEF5D5D, 0xFFFFB0A6, pulse);
                if (p[0].equals(selected)) color = theme.accent;
                paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                canvas.drawBitmap(b, null, box, paint);
            }
            paint.setColorFilter(null);
        } else {
            // simple shapes until the game's artwork has loaded
            float s = box.width() / 200f;
            for (String[] p : PARTS) {
                String[] r = p[2].split(",");
                RectF rr = new RectF(box.left + Float.parseFloat(r[0]) * s, box.top + Float.parseFloat(r[1]) * s,
                        box.left + Float.parseFloat(r[2]) * s, box.top + Float.parseFloat(r[3]) * s);
                Integer c = tint.get(p[0]);
                paint.setColor(p[0].equals(selected) ? theme.accent : c == null ? theme.surface3 : c);
                canvas.drawRoundRect(rr, 11 * s, 11 * s, paint);
            }
        }
        if (bleeding) postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        String hit = hit(e.getX(), e.getY());
        if (listener != null) {
            playSoundEffect(android.view.SoundEffectConstants.CLICK);
            listener.onPart(hit);
        }
        return true;
    }

    private String hit(float x, float y) {
        fit();
        if (!box.contains(x, y)) return null;
        if (base != null && !art.isEmpty()) {
            // the part whose own image has a visible pixel under the finger (a little tolerance around it)
            float bx = (x - box.left) / box.width(), by = (y - box.top) / box.height();
            String best = null;
            for (int tol = 0; tol <= 6 && best == null; tol += 3) {
                for (String[] p : PARTS) {
                    Bitmap b = art.get(p[0]);
                    if (b == null) continue;
                    int px = (int) (bx * b.getWidth()), py = (int) (by * b.getHeight());
                    for (int dy = -tol; dy <= tol && best == null; dy += Math.max(1, tol)) {
                        for (int dx = -tol; dx <= tol; dx += Math.max(1, tol)) {
                            int qx = px + dx, qy = py + dy;
                            if (qx < 0 || qy < 0 || qx >= b.getWidth() || qy >= b.getHeight()) continue;
                            if ((b.getPixel(qx, qy) >>> 24) > 40) {
                                best = p[0];
                                break;
                            }
                        }
                    }
                    if (best != null) break;
                }
            }
            return best;
        }
        float s = box.width() / 200f;
        for (String[] p : PARTS) {
            String[] r = p[2].split(",");
            if (x >= box.left + Float.parseFloat(r[0]) * s && x <= box.left + Float.parseFloat(r[2]) * s
                    && y >= box.top + Float.parseFloat(r[1]) * s && y <= box.top + Float.parseFloat(r[3]) * s) return p[0];
        }
        return null;
    }
}
