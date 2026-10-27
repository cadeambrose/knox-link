package io.github.knoxlink.companion;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The tab screens. Each builds its views once and updates them from the game's messages. */
final class Screens {
    private Screens() {}

    static int meterColor(Theme t, double v, boolean highIsGood) {
        double bad = highIsGood ? 1 - v : v;
        if (bad > 0.75) return t.bad;
        if (bad > 0.45) return t.warn;
        return highIsGood ? t.good : t.text3;
    }

    /** An item's own icon from the game in a rounded box (a plain icon until it arrives). */
    static View itemIcon(Context c, Host host, String tex, String fallback, int sizeDp) {
        Theme t = host.theme();
        FrameLayout box = new FrameLayout(c);
        box.setBackground(t.round(t.surface3, 11));
        ImageView iv = new ImageView(c);
        int pad = t.dp(sizeDp > 48 ? 6 : 4);
        iv.setPadding(pad, pad, pad, pad);
        iv.setImageDrawable(t.icon(fallback, t.text3));
        box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        if (tex != null && !tex.isEmpty()) {
            Bitmap hit = host.art().peek("i", tex);
            if (hit != null) iv.setImageBitmap(hit);
            else host.art().icon(tex, b -> { if (b != null) iv.setImageBitmap(b); });
        }
        box.setLayoutParams(new LinearLayout.LayoutParams(t.dp(sizeDp), t.dp(sizeDp)));
        return box;
    }

    /** Swap weapons like the game's hotbar: everything you carry, in-hand and belt/back first. */
    static void weaponSheet(Context c, Host host, JSONObject outfit) {
        Theme t = host.theme();
        LinearLayout sheet = Ui.col(c);
        sheet.addView(Ui.text(c, t, "Weapons", 20, t.text, true));
        JSONArray list = Json.arr(outfit.opt("weapons"));
        JSONObject current = null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject w = list.optJSONObject(i);
            if (w != null && "p".equals(w.optString("eq"))) current = w;
        }
        sheet.addView(Ui.text(c, t, current == null ? "Your hands are free." : "In your hands: " + current.optString("n"), 13.5f, t.text3, false),
                Ui.margins(Ui.lp(-2, -2), 0, t.dp(4), 0, t.dp(12)));
        LinearLayout top = Ui.row(c);
        TextView best = Ui.action(c, t, "weapon", "Best one");
        best.setOnClickListener(v -> { host.command("quick", Ui.args("a", "weapon"), "Equipping"); host.closeSheet(); });
        top.addView(best, Ui.weight(1));
        if (current != null) {
            final long id = current.optLong("id");
            TextView away = Ui.action(c, t, "hand", "Put it away");
            away.setOnClickListener(v -> { host.command("unequip", Ui.args("id", id), "Putting away"); host.closeSheet(); });
            top.addView(away, Ui.margins(Ui.weight(1), t.dp(8), 0, 0, 0));
        }
        sheet.addView(top, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(12)));
        int shown = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject w = list.optJSONObject(i);
            if (w == null || "p".equals(w.optString("eq"))) continue;
            LinearLayout r = Ui.row(c);
            r.setBackground(Ui.pressable(t, t.surface2, 14));
            r.setPadding(t.dp(10), t.dp(8), t.dp(12), t.dp(8));
            r.addView(itemIcon(c, host, w.optString("ic"), "weapon", 44));
            LinearLayout txt = Ui.col(c);
            txt.addView(Ui.ellipsize(Ui.text(c, t, w.optString("n"), 15.5f, w.optBoolean("broken") ? t.text3 : t.text, true)));
            Widgets.Flow tags = new Widgets.Flow(c, t.dp(6), t.dp(4));
            if (w.has("slot")) tags.addView(Ui.tag(c, t, w.optString("slot"), "acc"));
            else if (w.has("bag")) tags.addView(Ui.tag(c, t, "In " + w.optString("bag"), ""));
            if ("s".equals(w.optString("eq"))) tags.addView(Ui.tag(c, t, "Off hand", "acc"));
            if (w.optBoolean("two")) tags.addView(Ui.tag(c, t, "Two-handed", ""));
            if (w.optBoolean("ranged")) tags.addView(Ui.tag(c, t, w.optInt("ammo") + "/" + w.optInt("ammoMax"), w.optInt("ammo") == 0 ? "warn" : ""));
            if (w.optBoolean("broken")) tags.addView(Ui.tag(c, t, "Broken", "bad"));
            else if (w.has("cond")) {
                double cd = w.optDouble("cond");
                tags.addView(Ui.tag(c, t, Fmt.pct(cd) + "%", cd < 0.25 ? "bad" : cd < 0.5 ? "warn" : "good"));
            }
            txt.addView(tags, Ui.margins(Ui.lp(-1, -2), 0, t.dp(4), 0, 0));
            r.addView(txt, Ui.margins(Ui.weight(1), t.dp(12), 0, 0, 0));
            final long id = w.optLong("id");
            r.setOnClickListener(v -> { host.command("swap", Ui.args("id", id), "Equipping"); host.closeSheet(); });
            sheet.addView(r, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
            shown++;
        }
        if (shown == 0) sheet.addView(Ui.text(c, t, current == null ? "You carry no weapons." : "No other weapons on you.", 14.5f, t.text2, false));
        host.openSheet(sheet);
    }

    // =================================================================== Vitals

    static final class Vitals extends LinearLayout {
        private static final String[][] METERS = {
                {"hp", "Health", "1"}, {"endu", "Endurance", "1"}, {"hunger", "Hunger", "0"}, {"thirst", "Thirst", "0"},
                {"fatigue", "Tired", "0"}, {"stress", "Stress", "0"}, {"panic", "Panic", "0"}, {"pain", "Pain", "0"},
                {"boredom", "Bored", "0"}, {"unhappy", "Unhappy", "0"}, {"sick", "Sick", "0"}, {"wet", "Wet", "0"}};
        /** MoodleType -> icon in media/ui/Moodles/128 */
        static final Map<String, String> MOODLE_ICON = new HashMap<>();

        static {
            String[][] m = {{"ENDURANCE", "Mood_Exhausted"}, {"TIRED", "Mood_Sleepy"}, {"HUNGRY", "Status_Hunger"},
                    {"PANIC", "Mood_Panicked"}, {"SICK", "Mood_Nauseous"}, {"BORED", "Mood_Bored"}, {"UNHAPPY", "Mood_Sad"},
                    {"BLEEDING", "Status_Bleeding"}, {"WET", "Status_Wet"}, {"HAS_A_COLD", "Mood_Ill"}, {"ANGRY", "Mood_Angry"},
                    {"STRESS", "Mood_Stressed"}, {"THIRST", "Status_Thirst"}, {"INJURED", "Status_InjuredMinor"},
                    {"PAIN", "Mood_Pained"}, {"HEAVY_LOAD", "Status_HeavyLoad"}, {"DRUNK", "Mood_Drunk"}, {"DEAD", "Mood_Dead"},
                    {"ZOMBIE", "Mood_Zombified"}, {"HYPERTHERMIA", "Status_TemperatureHot"},
                    {"HYPOTHERMIA", "Status_TemperatureLow"}, {"WINDCHILL", "Status_Windchill"},
                    {"CANT_SPRINT", "Status_MovementRestricted"}, {"UNCOMFORTABLE", "Mood_Discomfort"},
                    {"NOXIOUS_SMELL", "Mood_NoxiousSmell"}, {"FOOD_EATEN", "Status_Hunger"}};
            for (String[] e : m) MOODLE_ICON.put(e[0], e[1]);
        }

        private final Host host;
        private final Theme t;
        private final BodyView body;
        private final TextView bodyHp, tempLabel, moodleCount;
        private final TextView bodyTitle, hint;
        private final LinearLayout bodyCard, lookInfo;
        private final View legend, flipBtn;
        private JSONObject outfit, lastCore;
        private boolean flipping;
        private final ImageView avatarView;
        private boolean avatarOff;
        private float avatarAngle = 90, downX, downY;
        private boolean dragging;
        private long lastTurnSent;
        private final Map<String, Widgets.Bar> bars = new HashMap<>();
        private final Map<String, TextView> values = new HashMap<>();
        private final LinearLayout moodles;
        private View condCard;
        private String moodleSig = "";
        private Bitmap moodleBg;
        private JSONObject bodyData;
        private String selected;

        Vitals(Context c, Host host) {
            super(c);
            this.host = host;
            t = host.theme();
            setOrientation(HORIZONTAL);
            setPadding(t.dp(12), t.dp(12), t.dp(12), t.dp(12));

            LinearLayout bodyCard = Ui.card(c, t);
            this.bodyCard = bodyCard;
            TextView[] head = Ui.cardHead(c, t, bodyCard, "Body");
            bodyTitle = head[0];
            bodyHp = head[1];
            ImageView flipBtn = new ImageView(c);
            flipBtn.setImageDrawable(t.icon("shirt", t.text2));
            flipBtn.setScaleType(ImageView.ScaleType.CENTER);
            flipBtn.setBackground(Ui.pressable(t, t.surface2, 10));
            flipBtn.setOnClickListener(v -> flip());
            this.flipBtn = flipBtn;
            ((LinearLayout) bodyTitle.getParent()).addView(flipBtn, Ui.margins(Ui.lp(t.dp(34), t.dp(30)), t.dp(8), 0, 0, 0));
            body = new BodyView(c);
            body.setup(t, host.art(), this::tapBody);
            // the live 3D character sits over the silhouette in look mode (the silhouette is the fallback)
            FrameLayout figure = new FrameLayout(c);
            figure.addView(body, new FrameLayout.LayoutParams(-1, -1));
            avatarView = new ImageView(c);
            avatarView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            avatarView.setVisibility(GONE);
            avatarView.setOnTouchListener(this::touchAvatar);
            figure.addView(avatarView, new FrameLayout.LayoutParams(-1, -1));
            bodyCard.addView(figure, new LayoutParams(-1, 0, 1));
            Widgets.Flow legend = new Widgets.Flow(c, t.dp(10), t.dp(4));
            legend.addView(legendItem(c, t.surface3, "Healthy"));
            legend.addView(legendItem(c, 0xFFC98A2E, "Hurt"));
            legend.addView(legendItem(c, 0xFFEF5D5D, "Bleeding"));
            legend.addView(legendItem(c, 0xFFE6E9EE, "Bandaged"));
            this.legend = legend;
            bodyCard.addView(legend, Ui.margins(Ui.lp(-1, -2), 0, t.dp(8), 0, 0));
            // in look mode: what protects you, and what you wear that isn't on a body part
            lookInfo = Ui.col(c);
            lookInfo.setVisibility(GONE);
            bodyCard.addView(lookInfo, Ui.margins(Ui.lp(-1, -2), 0, t.dp(8), 0, 0));
            TextView hint = Ui.text(c, t, "Tap the body to see your character", 11.5f, t.text3, false);
            hint.setGravity(Gravity.CENTER);
            this.hint = hint;
            bodyCard.addView(hint, Ui.margins(Ui.lp(-1, -2), 0, t.dp(6), 0, 0));
            addView(bodyCard, new LayoutParams(0, -1, 0.36f));

            ScrollView side = new ScrollView(c);
            side.setVerticalScrollBarEnabled(false);
            LinearLayout sideCol = Ui.col(c);
            side.addView(sideCol);
            LayoutParams sp = new LayoutParams(0, -1, 0.64f);
            sp.leftMargin = t.dp(12);
            addView(side, sp);

            // one tap: the game picks the right item, like the player would
            LinearLayout quick = Ui.card(c, t);
            Ui.cardHead(c, t, quick, "Quick actions");
            Widgets.Flow qf = new Widgets.Flow(c, t.dp(8), t.dp(8));
            for (String[] q : QUICK) qf.addView(quickTile(c, q), Ui.lp(t.dp(66), t.dp(64)));
            // Project Viewpoint's camera, only while that mod runs
            viewTile = quickTile(c, VIEW);
            viewTile.setVisibility(GONE);
            qf.addView(viewTile, Ui.lp(t.dp(66), t.dp(64)));
            quick.addView(qf);
            sideCol.addView(quick, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(12)));

            LinearLayout cond = Ui.card(c, t);
            condCard = cond;
            tempLabel = Ui.cardHead(c, t, cond, "Condition")[1];
            LinearLayout grid = null;
            for (int i = 0; i < METERS.length; i++) {
                String[] m = METERS[i];
                boolean big = i < 2;
                LinearLayout cell = Ui.col(c);
                LinearLayout r = Ui.row(c);
                TextView name = Ui.ellipsize(Ui.text(c, t, m[1], big ? 16 : 14, t.text2, true));
                TextView val = Ui.text(c, t, "–", big ? 16 : 14, t.text, true);
                r.addView(name, Ui.weight(1));
                r.addView(val);
                cell.addView(r);
                Widgets.Bar bar = new Widgets.Bar(c, t.surface3);
                cell.addView(bar, Ui.margins(Ui.lp(-1, t.dp(big ? 11 : 7)), 0, t.dp(6), 0, 0));
                bars.put(m[0], bar);
                values.put(m[0], val);
                if (big) {
                    cond.addView(cell, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(12)));
                } else {
                    if ((i - 2) % 2 == 0) {
                        grid = Ui.row(c);
                        cond.addView(grid, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(10)));
                    }
                    LayoutParams lp = Ui.weight(1);
                    if ((i - 2) % 2 == 0) lp.rightMargin = t.dp(14);
                    grid.addView(cell, lp);
                }
            }
            sideCol.addView(cond);

            LinearLayout mood = Ui.card(c, t);
            moodleCount = Ui.cardHead(c, t, mood, "Moodles")[1];
            moodles = Ui.col(c);
            mood.addView(moodles);
            sideCol.addView(mood, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));

            host.art().media("ui/Moodles/128/_Moodles_BGsolid.png", b -> {
                moodleBg = b;
                moodleSig = "";
            });
            if (host.prefs().getBoolean("bodyLook", false)) setLookMode(true);
        }

        /** action, label, icon, toast while it runs */
        static final String[][] QUICK = {
                {"bandage", "Bandage", "bandage", "Bandaging"}, {"eat", "Eat", "food", "Eating"},
                {"drink", "Drink", "drink", "Drinking"}, {"pills", "Painkiller", "pill", "Taking a painkiller"},
                {"light", "Light", "light", "Light"}, {"weapon", "Weapon", "weapon", "Equipping"},
                {"sit", "Rest", "rest", "Rest"}};
        static final String[] VIEW = {"view", "3D view", "eye", null};

        private View quickTile(Context c, String[] q) {
            LinearLayout b = Ui.col(c);
            b.setGravity(Gravity.CENTER);
            b.setBackground(Ui.pressable(t, t.surface2, 14));
            ImageView icon = Ui.icon(c, t, q[2], t.accent, 24);
            b.addView(icon);
            TextView l = Ui.ellipsize(Ui.text(c, t, q[1], 12, t.text2, true));
            b.addView(l, Ui.margins(Ui.lp(-2, -2), 0, t.dp(5), 0, 0));
            if ("sit".equals(q[0])) {
                restIcon = icon;
                restLabel = l;
            }
            if ("view".equals(q[0])) viewLabel = l;
            b.setOnClickListener(v -> {
                // weapons get a picker: the game's hotbar swap, or the best one in one more tap
                if ("weapon".equals(q[0]) && outfit != null) weaponSheet(getContext(), host, outfit);
                else if ("sit".equals(q[0]) && bedNear) confirmSleep();
                else if ("view".equals(q[0])) host.command("view", Ui.args(), null);
                else host.command("quick", Ui.args("a", q[0]), q[3]);
            });
            return b;
        }

        private View viewTile;
        private TextView viewLabel;

        /** The tile names the view a tap switches to, like the game's Back wheel. */
        private void setView(JSONObject view) {
            if (viewTile == null) return;
            viewTile.setVisibility(view == null ? GONE : VISIBLE);
            if (view == null) return;
            String next = view.optString("n");
            viewLabel.setText("first".equals(next) ? "1st person" : "third".equals(next) ? "3rd person" : "Top-down");
        }

        private ImageView restIcon;
        private TextView restLabel;
        private boolean bedNear;

        /** Near a bed, "Rest" becomes "Sleep". */
        private void setBedNear(boolean near) {
            if (near == bedNear || restIcon == null) return;
            bedNear = near;
            restIcon.setImageDrawable(t.icon(near ? "moon" : "rest", t.accent));
            restLabel.setText(near ? "Sleep" : "Rest");
        }

        private void confirmSleep() {
            Context c = getContext();
            LinearLayout sheet = Ui.col(c);
            sheet.addView(Ui.text(c, t, "Go to bed?", 20, t.text, true));
            sheet.addView(Ui.text(c, t, "Your character walks to the nearest bed and sleeps until rested, unless something wakes them. Not while zombies are around.", 14, t.text3, false),
                    Ui.margins(Ui.lp(-1, -2), 0, t.dp(6), 0, t.dp(14)));
            TextView yes = Ui.action(c, t, "moon", "Sleep");
            yes.setOnClickListener(v -> { host.command("quick", Ui.args("a", "sleep"), "Sleep"); host.closeSheet(); });
            sheet.addView(yes, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
            TextView rest = Ui.action(c, t, "rest", "Just rest here");
            rest.setOnClickListener(v -> { host.command("quick", Ui.args("a", "sit"), "Rest"); host.closeSheet(); });
            sheet.addView(rest);
            host.openSheet(sheet);
        }

        // ------------------------------------------------------------ look mode

        /** Flips the figure between injuries and how the character looks, like turning a card. */
        void flip() {
            if (flipping) return;
            flipping = true;
            bodyCard.setCameraDistance(t.dpf(1600));
            bodyCard.animate().rotationY(90).setDuration(130).withEndAction(() -> {
                setLookMode(!body.isLook());
                bodyCard.setRotationY(-90);
                bodyCard.animate().rotationY(0).setDuration(130).withEndAction(() -> flipping = false).start();
            }).start();
        }

        // ------------------------------------------------------------ the live character

        /** True while the live picture should be fetched (look mode on this tab). */
        boolean wantsAvatar() {
            return body.isLook() && !avatarOff;
        }

        /** A new frame of the character; null = none yet. */
        void avatarFrame(Bitmap b) {
            if (b == null || !body.isLook()) return;
            avatarView.setImageBitmap(b);
            if (avatarView.getVisibility() != VISIBLE) {
                avatarView.setVisibility(VISIBLE);
                body.setVisibility(INVISIBLE);
            }
        }

        /** This game can't draw the character for us: the painted silhouette stands in. */
        void avatarUnavailable() {
            avatarOff = true;
            avatarView.setVisibility(GONE);
            body.setVisibility(VISIBLE);
        }

        /** Drag sideways to turn the character; tap a part of them to see what they wear there. */
        private boolean touchAvatar(View v, android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    dragging = false;
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - downX;
                    if (!dragging && Math.abs(dx) > t.dp(8)) dragging = true;
                    if (dragging) {
                        avatarAngle = ((avatarAngle - dx * 0.8f) % 360 + 360) % 360;
                        downX = e.getX();
                        long now = System.currentTimeMillis();
                        if (now - lastTurnSent > 90) {
                            lastTurnSent = now;
                            host.command("avatarTurn", Ui.args("deg", Math.round(avatarAngle)), null);
                        }
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                    if (dragging) {
                        host.command("avatarTurn", Ui.args("deg", Math.round(avatarAngle)), null);
                    } else {
                        String part = avatarPart(v, e.getX(), e.getY());
                        if (part == null) flip();
                        else openOutfitPart(part);
                    }
                    return true;
                default:
                    return true;
            }
        }

        /** Which body part a tap on the picture is on (the model stands centred, head at the top). */
        private String avatarPart(View v, float x, float y) {
            // the picture keeps its 1:2 shape inside the view
            float vw = v.getWidth(), vh = v.getHeight();
            float s = Math.min(vw / 240f, vh / 480f);
            float iw = 240 * s, ih = 480 * s;
            float fx = (x - (vw - iw) / 2) / iw, fy = (y - (vh - ih) / 2) / ih;
            if (fx < 0.1f || fx > 0.9f || fy < 0.03f || fy > 0.99f) return null;
            boolean facing = avatarAngle > 0 && avatarAngle < 180;
            boolean leftOfPicture = fx < 0.5f;
            // facing you, their right hand is on your left
            String side = (leftOfPicture == facing) ? "_R" : "_L";
            float off = Math.abs(fx - 0.5f);
            if (fy < 0.16f) return off < 0.12f ? "Head" : null;
            if (fy < 0.21f) return off < 0.1f ? "Neck" : null;
            if (fy < 0.62f && off > 0.17f) {
                if (fy > 0.48f) return "Hand" + side;
                return fy > 0.36f ? "ForeArm" + side : "UpperArm" + side;
            }
            if (fy < 0.36f) return "Torso_Upper";
            if (fy < 0.47f) return "Torso_Lower";
            if (fy < 0.53f) return "Groin";
            if (fy < 0.71f) return "UpperLeg" + side;
            if (fy < 0.9f) return "LowerLeg" + side;
            return "Foot" + side;
        }

        private void setLookMode(boolean on) {
            body.setLook(on);
            if (!on) {
                avatarView.setVisibility(GONE);
                body.setVisibility(VISIBLE);
            }
            host.prefs().edit().putBoolean("bodyLook", on).apply();
            host.resubscribe();
            bodyTitle.setText(on ? "OUTFIT" : "BODY");
            ((ImageView) flipBtn).setImageDrawable(t.icon(on ? "vitals" : "shirt", t.text2));
            legend.setVisibility(on ? GONE : VISIBLE);
            lookInfo.setVisibility(on ? VISIBLE : GONE);
            hint.setText(on ? "Drag to turn · tap a part to see what's on it" : "Tap the body to see your character");
            if (on) {
                refreshLookInfo();
            } else if (bodyData != null) {
                bodyHp.setText(bodyData.has("hp") ? bodyData.optInt("hp") + "% health" : "");
            }
        }

        private void tapBody(String key) {
            if (key == null) { flip(); return; }
            if (body.isLook()) { openOutfitPart(key); return; }
            if (treatable(key)) openPart(key);
            else flip();
        }

        /** Something to see or do on this part in the health view. */
        private boolean treatable(String key) {
            JSONObject p = partData(key);
            if (p == null) return false;
            return p.optBoolean("bleed") || p.optBoolean("scratch") || p.optBoolean("cut") || p.optBoolean("deep")
                    || p.optBoolean("bite") || p.optBoolean("burn") || p.optBoolean("fracture") || p.optBoolean("glass")
                    || p.optBoolean("bullet") || p.optBoolean("band") || p.optBoolean("infected") || p.optDouble("hp", 100) < 100;
        }

        private JSONObject partData(String key) {
            if (bodyData == null) return null;
            JSONArray parts = Json.arr(bodyData.opt("parts"));
            for (int i = 0; i < parts.length(); i++) {
                JSONObject o = parts.optJSONObject(i);
                if (o != null && key.equals(o.optString("k"))) return o;
            }
            return null;
        }

        private String partName(String key) {
            JSONObject p = partData(key);
            if (p != null && !p.optString("n").isEmpty()) return p.optString("n");
            return key.replace("_L", " (left)").replace("_R", " (right)").replace('_', ' ');
        }

        /** What the character wears and carries (the "outfit" message). */
        void outfit(JSONObject d) {
            outfit = d;
            recolor();
        }

        private void recolor() {
            if (outfit == null) return;
            JSONArray worn = Json.arr(outfit.opt("worn"));
            Map<String, Integer> colors = new HashMap<>();
            boolean headCovered = false;
            for (int i = 0; i < worn.length(); i++) {   // inner layers first: the outer one wins
                JSONObject e = worn.optJSONObject(i);
                if (e == null) continue;
                JSONArray parts = Json.arr(e.opt("parts"));
                if (parts.length() == 0) continue;
                int color = itemColor(e);
                for (int k = 0; k < parts.length(); k++) {
                    String part = parts.optString(k);
                    colors.put(part, color);
                    if ("Head".equals(part)) headCovered = true;
                }
            }
            body.setLookColors(colors, hexColor(outfit.optString("skin"), 0xFFD5A27C), hexColor(outfit.optString("hair"), 0xFF4A3424), headCovered);
            if (body.isLook()) refreshLookInfo();
        }

        static int hexColor(String hex, int fallback) {
            try {
                return hex == null || hex.length() != 6 ? fallback : 0xFF000000 | Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        /** The colour the game tints it in, or the main colour of its icon. */
        private int itemColor(JSONObject e) {
            if (e.has("c")) return hexColor(e.optString("c"), 0xFF8A8F98);
            String tex = e.optString("ic");
            Integer known = ICON_COLOR.get(tex);
            if (known != null) return known;
            if (!tex.isEmpty()) {
                host.art().icon(tex, b -> {
                    if (b == null) return;
                    ICON_COLOR.put(tex, iconColor(b));
                    recolor();
                });
            }
            return 0xFF8A8F98;
        }

        static final Map<String, Integer> ICON_COLOR = new HashMap<>();

        /** Average of an icon's mid-tone pixels (outlines and highlights left out). */
        static int iconColor(Bitmap b) {
            long r = 0, g = 0, bl = 0, n = 0;
            int step = Math.max(1, Math.min(b.getWidth(), b.getHeight()) / 24);
            for (int y = 0; y < b.getHeight(); y += step) {
                for (int x = 0; x < b.getWidth(); x += step) {
                    int p = b.getPixel(x, y);
                    if ((p >>> 24) < 160) continue;
                    int pr = (p >> 16) & 0xff, pg = (p >> 8) & 0xff, pb = p & 0xff;
                    int luma = (pr * 3 + pg * 6 + pb) / 10;
                    if (luma < 28 || luma > 245) continue;
                    r += pr;
                    g += pg;
                    bl += pb;
                    n++;
                }
            }
            if (n == 0) return 0xFF8A8F98;
            return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (bl / n);
        }

        private void refreshLookInfo() {
            Context c = getContext();
            lookInfo.removeAllViews();
            if (outfit == null) {
                lookInfo.addView(Ui.text(c, t, "Looking at your clothes…", 12.5f, t.text3, false));
                return;
            }
            JSONObject prot = outfit.optJSONObject("prot");
            double bite = 0, scratch = 0;
            int n = 0;
            if (prot != null) {
                java.util.Iterator<String> it = prot.keys();
                while (it.hasNext()) {
                    JSONObject p = prot.optJSONObject(it.next());
                    if (p == null) continue;
                    bite += p.optDouble("b");
                    scratch += p.optDouble("s");
                    n++;
                }
            }
            bodyHp.setText("");
            if (n > 0) {
                Widgets.Flow f = new Widgets.Flow(c, t.dp(6), t.dp(6));
                f.addView(Ui.tag(c, t, "Bite protection " + Math.round(bite / n) + "%", bite / n >= 30 ? "good" : ""));
                f.addView(Ui.tag(c, t, "Scratch protection " + Math.round(scratch / n) + "%", scratch / n >= 50 ? "good" : ""));
                lookInfo.addView(f);
            }
            // a bag, a belt, a watch: worn, but not on a body part
            JSONArray worn = Json.arr(outfit.opt("worn"));
            Widgets.Flow extra = new Widgets.Flow(c, t.dp(6), t.dp(6));
            for (int i = 0; i < worn.length(); i++) {
                JSONObject e = worn.optJSONObject(i);
                if (e == null || Json.arr(e.opt("parts")).length() > 0) continue;
                TextView chip = Ui.tag(c, t, e.optString("n"), "");
                chip.setOnClickListener(v -> openWorn(e));
                extra.addView(chip);
            }
            if (extra.getChildCount() > 0) lookInfo.addView(extra, Ui.margins(Ui.lp(-1, -2), 0, t.dp(6), 0, 0));
        }

        private void openOutfitPart(String key) {
            Context c = getContext();
            body.setSelected(key);
            selected = key;
            LinearLayout sheet = Ui.col(c);
            sheet.addView(Ui.text(c, t, partName(key), 20, t.text, true));
            JSONObject prot = outfit == null ? null : outfit.optJSONObject("prot");
            JSONObject pp = prot == null ? null : prot.optJSONObject(key);
            String sub = pp == null ? "" : "Protection: bite " + pp.optInt("b") + "% · scratch " + pp.optInt("s") + "%";
            sheet.addView(Ui.text(c, t, sub, 13.5f, t.text3, false), Ui.margins(Ui.lp(-2, -2), 0, t.dp(4), 0, t.dp(12)));
            JSONArray worn = outfit == null ? new JSONArray() : Json.arr(outfit.opt("worn"));
            int shown = 0;
            for (int i = worn.length() - 1; i >= 0; i--) {   // outermost first
                JSONObject e = worn.optJSONObject(i);
                if (e == null) continue;
                JSONArray parts = Json.arr(e.opt("parts"));
                boolean on = false;
                for (int k = 0; k < parts.length(); k++) if (key.equals(parts.optString(k))) on = true;
                if (!on) continue;
                sheet.addView(wornRow(c, e), Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
                shown++;
            }
            if (shown == 0) sheet.addView(Ui.text(c, t, "Nothing on it.", 14.5f, t.text2, false), Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(10)));
            if (key.startsWith("Hand")) {
                JSONObject hands = lastCore == null ? null : lastCore.optJSONObject("hands");
                JSONObject held = hands == null ? null : hands.optJSONObject(key.endsWith("_R") ? "p" : "s");
                if (hands != null && key.endsWith("_L") && hands.optBoolean("both")) held = hands.optJSONObject("p");
                sheet.addView(Ui.label(c, t, "In this hand"), Ui.margins(Ui.lp(-2, -2), t.dp(2), t.dp(8), 0, t.dp(8)));
                sheet.addView(Ui.text(c, t, held == null ? "Nothing" : held.optString("n"), 15.5f, t.text, true), Ui.margins(Ui.lp(-1, -2), t.dp(2), 0, 0, t.dp(10)));
                if (outfit != null) {
                    TextView sw = Ui.action(c, t, "weapon", "Switch weapon…");
                    sw.setOnClickListener(v -> weaponSheet(c, host, outfit));
                    sheet.addView(sw, Ui.lp(-1, -2));
                }
            }
            host.openSheet(sheet);
        }

        private View wornRow(Context c, JSONObject e) {
            LinearLayout r = Ui.row(c);
            r.setBackground(Ui.pressable(t, t.surface2, 14));
            r.setPadding(t.dp(10), t.dp(8), t.dp(12), t.dp(8));
            r.addView(itemIcon(c, host, e.optString("ic"), "shirt", 40));
            LinearLayout txt = Ui.col(c);
            txt.addView(Ui.ellipsize(Ui.text(c, t, e.optString("n"), 15.5f, t.text, true)));
            Widgets.Flow tags = new Widgets.Flow(c, t.dp(6), t.dp(4));
            for (String[] x : wornTags(e)) tags.addView(Ui.tag(c, t, x[0], x[1]));
            if (tags.getChildCount() > 0) txt.addView(tags, Ui.margins(Ui.lp(-1, -2), 0, t.dp(4), 0, 0));
            r.addView(txt, Ui.margins(Ui.weight(1), t.dp(12), 0, 0, 0));
            r.setOnClickListener(v -> openWorn(e));
            return r;
        }

        static List<String[]> wornTags(JSONObject e) {
            List<String[]> out = new ArrayList<>();
            if (e.has("cond")) {
                double cd = e.optDouble("cond");
                out.add(new String[]{"Condition " + Fmt.pct(cd) + "%", cd < 0.25 ? "bad" : cd < 0.5 ? "warn" : ""});
            }
            if (e.optInt("holes") > 0) out.add(new String[]{e.optInt("holes") + (e.optInt("holes") == 1 ? " hole" : " holes"), "warn"});
            if (e.optInt("bite") > 0) out.add(new String[]{"Bite protection " + e.optInt("bite") + "%", ""});
            if (e.optInt("scratch") > 0) out.add(new String[]{"Scratch protection " + e.optInt("scratch") + "%", ""});
            if (e.has("wet")) out.add(new String[]{"Wet", "warn"});
            if (e.optBoolean("blood")) out.add(new String[]{"Bloody", "bad"});
            return out;
        }

        private void openWorn(JSONObject e) {
            Context c = getContext();
            LinearLayout sheet = Ui.col(c);
            LinearLayout head = Ui.row(c);
            head.addView(itemIcon(c, host, e.optString("ic"), "shirt", 56));
            head.addView(Ui.text(c, t, e.optString("n"), 20, t.text, true), Ui.margins(Ui.weight(1), t.dp(14), 0, 0, 0));
            sheet.addView(head, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(12)));
            Widgets.Flow tags = new Widgets.Flow(c, t.dp(6), t.dp(6));
            for (String[] x : wornTags(e)) tags.addView(Ui.tag(c, t, x[0], x[1]));
            if (tags.getChildCount() > 0) sheet.addView(tags, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(14)));
            TextView off = Ui.action(c, t, "shirt", "Take it off");
            off.setOnClickListener(v -> { host.command("takeoff", Ui.args("id", e.optLong("id")), "Taking off"); host.closeSheet(); });
            sheet.addView(off);
            host.openSheet(sheet);
        }

        private View legendItem(Context c, int color, String s) {
            LinearLayout r = Ui.row(c);
            View sw = new View(c);
            sw.setBackground(t.round(color, 4));
            r.addView(sw, Ui.lp(t.dp(12), t.dp(12)));
            TextView tv = Ui.text(c, t, s, 12, t.text3, false);
            r.addView(tv, Ui.margins(Ui.lp(-2, -2), t.dp(6), 0, 0, 0));
            return r;
        }

        void core(JSONObject core) {
            lastCore = core;
            JSONObject pp = core.optJSONObject("p");
            setBedNear(pp != null && pp.optBoolean("bed"));
            setView(core.optJSONObject("view"));
            JSONObject b = core.optJSONObject("bars");
            // Immersion sends no numbers for needs: then the moodles say it, like in the game
            boolean numbers = b != null && b.has("hunger");
            condCard.setVisibility(numbers ? VISIBLE : GONE);
            if (numbers) {
                for (String[] m : METERS) {
                    double v = Math.max(0, Math.min(1, b.optDouble(m[0], 0)));
                    int col = meterColor(t, v, "1".equals(m[2]));
                    bars.get(m[0]).set(v, col);
                    TextView tv = values.get(m[0]);
                    tv.setText(Fmt.pct(v) + "%");
                    tv.setTextColor(col == t.text3 || col == t.good ? t.text : col);
                }
                double temp = b.optDouble("temp", 0);
                tempLabel.setText(temp > 0 ? "Body " + Fmt.bodyTemp(temp) : "");
            }
            JSONObject p = core.optJSONObject("p");
            if (p != null) body.setSex(p.optBoolean("female"));
            JSONArray list = Json.arr(core.opt("m"));
            StringBuilder sig = new StringBuilder(moodleBg == null ? "-" : "+");
            for (int i = 0; i < list.length(); i++) {
                JSONObject m = list.optJSONObject(i);
                if (m != null) sig.append(m.optString("id")).append(m.optInt("l"));
            }
            if (sig.toString().equals(moodleSig)) return;
            moodleSig = sig.toString();
            moodleCount.setText(list.length() > 0 ? String.valueOf(list.length()) : "");
            moodles.removeAllViews();
            Context c = getContext();
            if (list.length() == 0) {
                moodles.addView(Ui.text(c, t, "Feeling fine.", 14, t.text3, false));
                return;
            }
            for (int i = 0; i < list.length(); i++) {
                JSONObject m = list.optJSONObject(i);
                if (m == null) continue;
                moodles.addView(moodleRow(c, m), Ui.margins(Ui.lp(-1, -2), 0, i == 0 ? 0 : t.dp(8), 0, 0));
            }
        }

        private View moodleRow(Context c, JSONObject m) {
            int level = m.optInt("l");
            int good = m.optInt("g");
            LinearLayout r = Ui.row(c);
            r.setBackground(t.round(t.surface2, 12));
            r.setPadding(t.dp(8), t.dp(8), t.dp(12), t.dp(8));
            Widgets.Moodle tile = new Widgets.Moodle(c);
            int color = moodleColor(good, level);
            tile.set(moodleBg, null, color);
            String id = m.optString("id");
            String icon = MOODLE_ICON.get(id);
            if ("INJURED".equals(id) && level >= 3) icon = "Status_InjuredMajor";
            if (icon != null) {
                host.art().media("ui/Moodles/128/" + icon + ".png", b -> tile.set(moodleBg, b, color));
            }
            r.addView(tile, Ui.lp(t.dp(42), t.dp(42)));
            LinearLayout txt = Ui.col(c);
            txt.addView(Ui.text(c, t, m.optString("n"), 15, t.text, true));
            String d = m.optString("d");
            if (!d.isEmpty()) {
                TextView dv = Ui.text(c, t, d, 12.5f, t.text3, false);
                dv.setMaxLines(2);
                txt.addView(dv, Ui.margins(Ui.lp(-1, -2), 0, t.dp(3), 0, 0));
            }
            r.addView(txt, Ui.margins(Ui.weight(1), t.dp(12), 0, t.dp(8), 0));
            LinearLayout pips = Ui.row(c);
            for (int i = 1; i <= 4; i++) {
                View dot = new View(c);
                dot.setBackground(t.round(i <= level ? color : t.surface3, 5));
                pips.addView(dot, Ui.margins(Ui.lp(t.dp(9), t.dp(9)), t.dp(4), 0, 0, 0));
            }
            r.addView(pips);
            return r;
        }

        /** The game's moodle colours: greens for good moodles, yellow through red for bad ones. */
        static int moodleColor(int goodBad, int level) {
            int l = Math.max(1, Math.min(4, level)) - 1;
            int[] bad = {0xFFE3D36E, 0xFFE8A84C, 0xFFDA6941, 0xFFB93434};
            int[] good = {0xFFB8DC6E, 0xFF92CF62, 0xFF66B956, 0xFF409C46};
            if (goodBad == 2) return bad[l];
            if (goodBad == 1) return good[l];
            return 0xFFB0B4BA;
        }

        void body(JSONObject d) {
            bodyData = d;
            body.update(Json.arr(d.opt("parts")));
            if (!body.isLook()) bodyHp.setText(d.has("hp") ? d.optInt("hp") + "% health" : "");
            if (selected != null) openPart(selected, true);
        }

        private void openPart(String key) {
            openPart(key, false);
        }

        // the open body part's sheet: where its treatments go, which part, and what they were last
        private LinearLayout treatBox;
        private int treatPart = -1;
        private String treatSig = "";

        private void openPart(String key, boolean refresh) {
            if (bodyData == null) return;
            JSONObject p = partData(key);
            if (p == null) return;
            if (refresh) {   // the facts stay steady while the sheet is open; the treatments follow what you have
                showTreatments(p);
                return;
            }
            selected = key;
            body.setSelected(key);
            Context c = getContext();
            LinearLayout sheet = Ui.col(c);
            sheet.addView(Ui.text(c, t, p.optString("n"), 20, t.text, true));
            String sub = p.optInt("hp") + "% healthy" + (p.has("pain") ? " · pain " + p.optInt("pain") : "");
            sheet.addView(Ui.text(c, t, sub, 13.5f, t.text3, false), Ui.margins(Ui.lp(-2, -2), 0, t.dp(4), 0, t.dp(12)));
            sheet.addView(woundFacts(c, p), Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(14)));
            treatPart = p.optInt("i");
            treatSig = "";
            treatBox = Ui.col(c);
            sheet.addView(treatBox, Ui.lp(-1, -2));
            showTreatments(p);
            host.command("healthPart", Ui.args("part", treatPart), null);
            host.openSheet(sheet);
        }

        /** What's wrong with a body part, in the game's own words (its health panel's lines). */
        private Widgets.Flow woundFacts(Context c, JSONObject p) {
            Widgets.Flow facts = new Widgets.Flow(c, t.dp(6), t.dp(6));
            JSONArray lines = Json.arr(p.opt("lines"));
            for (int i = 0; i < lines.length(); i++) {
                JSONObject l = lines.optJSONObject(i);
                if (l != null) facts.addView(Ui.tag(c, t, l.optString("t"), l.optString("k")));
            }
            if (lines.length() == 0) {
                List<String[]> w = wounds(p);   // an older game add-on: the plain facts
                if (w.isEmpty()) facts.addView(Ui.tag(c, t, "No wounds", "good"));
                for (String[] x : w) facts.addView(Ui.tag(c, t, x[0], x[1]));
            }
            return facts;
        }

        /** The treatments the game's health panel offers on the open part, in treating order. */
        private void showTreatments(JSONObject p) {
            if (treatBox == null) return;
            JSONObject sel = bodyData.optJSONObject("sel");
            boolean known = sel != null && sel.optInt("i", -1) == treatPart;
            JSONArray list = known ? Json.arr(sel.opt("t")) : new JSONArray();
            String sig = known ? list.toString() : "?";
            if (sig.equals(treatSig)) return;
            treatSig = sig;
            Context c = getContext();
            treatBox.removeAllViews();
            if (!known) {
                treatBox.addView(Ui.text(c, t, "Looking at what you have…", 14, t.text3, false));
                return;
            }
            if (list.length() == 0) {
                treatBox.addView(Ui.text(c, t, treatable(p.optString("k")) ? "You have nothing to treat this with." : "Nothing to treat here.",
                        14, t.text3, false));
                return;
            }
            treatBox.addView(Ui.label(c, t, "Treat"), Ui.margins(Ui.lp(-2, -2), t.dp(2), 0, 0, t.dp(8)));
            for (int k = 0; k < list.length(); k++) {
                JSONObject e = list.optJSONObject(k);
                if (e == null) continue;
                String what = e.optString("a"), with = e.optString("i", "");
                LinearLayout r = Ui.row(c);
                r.setBackground(Ui.pressable(t, t.surface2, 14));
                r.setPadding(t.dp(10), t.dp(8), t.dp(12), t.dp(8));
                r.setMinimumHeight(t.dp(56));
                r.addView(itemIcon(c, host, e.optString("ic", ""), "bandage", 40));
                LinearLayout txt = Ui.col(c);
                txt.addView(Ui.text(c, t, what, 15.5f, t.text, true));
                if (!with.isEmpty()) txt.addView(Ui.ellipsize(Ui.text(c, t, with, 13, t.text3, false)), Ui.margins(Ui.lp(-1, -2), 0, t.dp(2), 0, 0));
                r.addView(txt, Ui.margins(Ui.weight(1), t.dp(12), 0, 0, 0));
                r.setOnClickListener(v -> {
                    host.command("treat", Ui.args("part", treatPart, "a", what, "i", with), what);
                    host.closeSheet();
                });
                treatBox.addView(r, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
            }
        }

        void sheetClosed() {
            if (treatBox != null) host.command("healthPart", Ui.args("part", -1), null);
            treatBox = null;
            selected = null;
            body.setSelected(null);
        }

        static List<String[]> wounds(JSONObject p) {
            List<String[]> w = new ArrayList<>();
            if (p.optBoolean("bleed")) w.add(new String[]{"Bleeding", "bad"});
            if (p.optBoolean("bite")) w.add(new String[]{"Bite", "bad"});
            if (p.optBoolean("deep")) w.add(new String[]{"Deep wound", "bad"});
            if (p.optBoolean("glass")) w.add(new String[]{"Glass shard", "bad"});
            if (p.optBoolean("bullet")) w.add(new String[]{"Bullet", "bad"});
            if (p.optBoolean("infected")) w.add(new String[]{"Infected", "bad"});
            if (p.optBoolean("fracture")) w.add(new String[]{p.optBoolean("splint") ? "Fracture (splinted)" : "Fracture", "warn"});
            if (p.optBoolean("burn")) w.add(new String[]{"Burn", "warn"});
            if (p.optBoolean("cut")) w.add(new String[]{"Laceration", "warn"});
            if (p.optBoolean("scratch")) w.add(new String[]{"Scratch", "warn"});
            if (p.optBoolean("stitch")) w.add(new String[]{"Stitched", "good"});
            if (p.optBoolean("band")) w.add(new String[]{p.optBoolean("dirty") ? "Dirty bandage" : "Bandaged", p.optBoolean("dirty") ? "warn" : "good"});
            return w;
        }
    }

    // =================================================================== Gear

    static final class Gear extends LinearLayout {
        private final Host host;
        private final Theme t;
        private final LinearLayout handsRow, containers, chips, weaponsCard, weaponsRow, segRow, onYouCol;
        private String weaponsSig = "";
        private final Widgets.Bar weightBar;
        private final TextView weightText;
        private String filter = "all";
        private JSONObject last, lootData, outfitData;
        private final Map<Long, JSONObject> items = new HashMap<>();
        private final ScrollView onYou;
        private final Around around;
        private final CraftView craft;
        private String seg = "on";
        private static final String[][] SEGMENTS = {{"on", "On you"}, {"around", "Around you"}, {"craft", "Craft"}};

        Gear(Context c, Host host) {
            super(c);
            this.host = host;
            t = host.theme();
            setOrientation(VERTICAL);

            // On you · Around you · Craft
            segRow = Ui.row(c);
            segRow.setBackground(t.round(t.surface2, 12));
            segRow.setPadding(t.dp(4), t.dp(4), t.dp(4), t.dp(4));
            for (String[] s : SEGMENTS) {
                TextView b = Ui.text(c, t, s[1], 14, t.text2, true);
                b.setGravity(Gravity.CENTER);
                b.setPadding(0, t.dp(9), 0, t.dp(9));
                b.setTag(s[0]);
                b.setOnClickListener(v -> setSegment((String) v.getTag()));
                segRow.addView(b, Ui.weight(1));
            }
            addView(segRow, Ui.margins(Ui.lp(-1, -2), t.dp(12), t.dp(12), t.dp(12), t.dp(8)));
            FrameLayout body = new FrameLayout(c);
            addView(body, new LayoutParams(-1, 0, 1));

            onYou = new ScrollView(c);
            onYou.setVerticalScrollBarEnabled(false);
            LinearLayout col = Ui.col(c);
            col.setPadding(t.dp(12), t.dp(4), t.dp(12), t.dp(16));
            onYouCol = col;
            onYou.addView(col);
            body.addView(onYou, new FrameLayout.LayoutParams(-1, -1));
            around = new Around(c, host, this);
            body.addView(around, new FrameLayout.LayoutParams(-1, -1));
            craft = new CraftView(c, host);
            body.addView(craft, new FrameLayout.LayoutParams(-1, -1));

            handsRow = Ui.row(c);
            col.addView(handsRow, Ui.lp(-1, -2));

            // the game's hotbar: belt loops, holsters, back straps. One tap draws or puts away.
            weaponsCard = Ui.card(c, t);
            TextView[] wh = Ui.cardHead(c, t, weaponsCard, "Hotbar");
            wh[1].setText("Tap to draw");
            HorizontalScrollView ws = new HorizontalScrollView(c);
            ws.setHorizontalScrollBarEnabled(false);
            weaponsRow = Ui.row(c);
            ws.addView(weaponsRow);
            weaponsCard.addView(ws, Ui.lp(-1, -2));
            weaponsCard.setVisibility(GONE);
            col.addView(weaponsCard, Ui.margins(Ui.lp(-1, -2), 0, t.dp(10), 0, 0));

            LinearLayout w = Ui.card(c, t);
            weightText = Ui.cardHead(c, t, w, "Weight")[1];
            weightBar = new Widgets.Bar(c, t.surface3);
            w.addView(weightBar, Ui.lp(-1, t.dp(11)));
            col.addView(w, Ui.margins(Ui.lp(-1, -2), 0, t.dp(10), 0, 0));

            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            chips = Ui.row(c);
            hs.addView(chips);
            String[][] f = {{"all", "All"}, {"food", "Food & drink"}, {"medical", "Medical"}, {"weapon", "Weapons"},
                    {"tool", "Tools"}, {"clothing", "Clothing"}, {"other", "Other"}};
            for (String[] x : f) {
                TextView chip = Ui.text(c, t, x[1], 14, t.text2, true);
                chip.setPadding(t.dp(15), t.dp(9), t.dp(15), t.dp(9));
                chip.setTag(x[0]);
                chip.setOnClickListener(v -> {
                    filter = (String) v.getTag();
                    styleChips();
                    if (last != null) gear(last);
                });
                chips.addView(chip, Ui.margins(Ui.lp(-2, -2), 0, 0, t.dp(8), 0));
            }
            styleChips();
            col.addView(hs, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, t.dp(6)));
            buildPicking(c);
            FrameLayout.LayoutParams bar = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            bar.setMargins(t.dp(12), 0, t.dp(12), t.dp(12));
            body.addView(pickBar, bar);

            containers = Ui.col(c);
            containers.addView(Ui.text(c, t, "Opening your bags…", 14, t.text3, false));
            col.addView(containers, Ui.lp(-1, -2));
            setSegment(host.prefs().getString("gearSeg", "on"));
        }

        String segment() {
            return seg;
        }

        void setSegment(String s) {
            // moving items works on you and around you (straight from a shelf into a bag)
            if ("craft".equals(s) && selecting) setSelecting(false);
            seg = s;
            host.prefs().edit().putString("gearSeg", s).apply();
            onYou.setVisibility("on".equals(s) ? VISIBLE : GONE);
            around.setVisibility("around".equals(s) ? VISIBLE : GONE);
            craft.setVisibility("craft".equals(s) ? VISIBLE : GONE);
            for (int i = 0; i < segRow.getChildCount(); i++) {
                TextView b = (TextView) segRow.getChildAt(i);
                boolean on = s.equals(b.getTag());
                b.setTextColor(on ? t.text : t.text2);
                b.setBackground(on ? t.round(t.isLight() ? 0xFFFFFFFF : t.surface, 9) : null);
            }
            host.resubscribe();
        }

        /** The containers around you (the "loot" message). */
        void loot(JSONObject d) {
            lootData = d;
            around.loot(d);
            JSONArray list = Json.arr(d.opt("list"));
            int withItems = 0;
            for (int i = 0; i < list.length(); i++) {
                JSONObject cont = list.optJSONObject(i);
                if (cont != null && !cont.optBoolean("floor") && Json.arr(cont.opt("items")).length() > 0) withItems++;
            }
            TextView b = (TextView) segRow.getChildAt(1);
            b.setText(withItems > 0 ? "Around you · " + withItems : "Around you");
            updatePickBar();   // the containers around you are places to move things to
        }

        void craft(JSONObject d) {
            craft.craft(d);
        }

        private void styleChips() {
            for (int i = 0; i < chips.getChildCount(); i++) {
                TextView v = (TextView) chips.getChildAt(i);
                if (v.getTag() == null) continue;   // the Select switch keeps its own look
                boolean on = filter.equals(v.getTag());
                if (on) {
                    v.setBackground(t.round(t.text, 99));
                    v.setTextColor(t.bg);
                } else {
                    android.graphics.drawable.GradientDrawable g = t.round(t.surface, 99);
                    g.setStroke(t.dp(1), t.line);
                    v.setBackground(g);
                    v.setTextColor(t.text2);
                }
            }
        }

        void core(JSONObject core) {
            JSONObject h = core.optJSONObject("hands");
            boolean both = h != null && h.optBoolean("both");
            handsRow.removeAllViews();
            handsRow.addView(hand("Main hand", h == null ? null : h.optJSONObject("p"), both), Ui.weight(1));
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.leftMargin = t.dp(10);
            handsRow.addView(hand("Off hand", both || h == null ? null : h.optJSONObject("s"), false), lp);
            JSONObject b = core.optJSONObject("bars");
            if (b != null && b.optDouble("weightMax", 0) > 0) {
                double r = b.optDouble("weight") / b.optDouble("weightMax");
                weightText.setText(Fmt.num(b.optDouble("weight")) + " / " + Fmt.num(b.optDouble("weightMax")));
                weightBar.set(Math.min(1, r), r > 1 ? t.bad : r > 0.8 ? t.warn : t.good);
            }
        }

        private View hand(String label, JSONObject it, boolean both) {
            Context c = getContext();
            LinearLayout card = Ui.row(c);
            card.setBackground(t.card());
            card.setPadding(t.dp(12), t.dp(10), t.dp(12), t.dp(10));
            FrameLayout iconBox = new FrameLayout(c);
            iconBox.setBackground(t.round(t.surface2, 12));
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = t.dp(5);
            iv.setPadding(pad, pad, pad, pad);
            iconBox.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            card.addView(iconBox, Ui.lp(t.dp(46), t.dp(46)));
            LinearLayout txt = Ui.col(c);
            TextView l = Ui.text(c, t, label.toUpperCase(Locale.ROOT), 11, t.text3, true);
            l.setLetterSpacing(0.07f);
            txt.addView(l);
            if (it == null) {
                iv.setImageDrawable(t.icon("hand", t.accent));
                txt.addView(Ui.text(c, t, "Empty", 16, t.text3, false), Ui.margins(Ui.lp(-2, -2), 0, t.dp(3), 0, 0));
            } else {
                setIcon(iv, it.optString("ic"), "hand");
                txt.addView(Ui.ellipsize(Ui.text(c, t, it.optString("n"), 16, t.text, true)), Ui.margins(Ui.lp(-1, -2), 0, t.dp(3), 0, 0));
                List<String> sub = new ArrayList<>();
                if (both) sub.add("Both hands");
                if (it.has("cond")) sub.add("Condition " + Fmt.pct(it.optDouble("cond")) + "%");
                if (it.has("ammo")) sub.add("Ammo " + it.optInt("ammo") + "/" + it.optInt("ammoMax") + (it.optBoolean("chambered") ? " +1" : ""));
                if (it.has("use")) sub.add(Fmt.pct(it.optDouble("use")) + "% left");
                if (!sub.isEmpty()) txt.addView(Ui.ellipsize(Ui.text(c, t, String.join(" · ", sub), 12.5f, t.text2, false)), Ui.margins(Ui.lp(-1, -2), 0, t.dp(3), 0, 0));
                final long id = it.optLong("id");
                card.setOnClickListener(v -> { if (items.containsKey(id)) openItem(id); });
            }
            card.addView(txt, Ui.margins(Ui.weight(1), t.dp(12), 0, 0, 0));
            return card;
        }

        /** The hotbar strip (and the way to every other weapon), from the "outfit" message. */
        void outfit(JSONObject d) {
            outfitData = d;
            JSONArray slots = Json.arr(d.opt("hotbar"));
            JSONArray weapons = Json.arr(d.opt("weapons"));
            String sig = slots.toString() + weapons.length();
            if (sig.equals(weaponsSig)) return;
            weaponsSig = sig;
            weaponsRow.removeAllViews();
            weaponsCard.setVisibility(slots.length() == 0 && weapons.length() == 0 ? GONE : VISIBLE);
            Context c = getContext();
            for (int i = 0; i < slots.length(); i++) {
                JSONObject s = slots.optJSONObject(i);
                if (s == null) continue;
                boolean has = s.has("id"), held = s.optBoolean("eq");
                LinearLayout tile = Ui.col(c);
                tile.setGravity(Gravity.CENTER_HORIZONTAL);
                tile.setPadding(t.dp(6), t.dp(6), t.dp(6), t.dp(6));
                android.graphics.drawable.GradientDrawable bg = t.round(held ? Theme.alpha(t.accent, 0.16f) : t.surface2, 14);
                if (held) bg.setStroke(t.dp(2), t.accent);
                else if (!has) bg.setStroke(t.dp(1), t.line);
                tile.setBackground(bg);
                View icon = itemIcon(c, host, has ? s.optString("ic") : "", "weapon", 44);
                icon.setAlpha(has ? 1f : 0.25f);
                tile.addView(icon);
                TextView name = Ui.ellipsize(Ui.text(c, t, s.optString("n"), 11, held ? t.accent : has ? t.text2 : t.text3, true));
                name.setGravity(Gravity.CENTER);
                tile.addView(name, Ui.margins(Ui.lp(t.dp(64), -2), 0, t.dp(5), 0, 0));
                Widgets.Bar bar = new Widgets.Bar(c, t.surface3);
                double cd = s.optDouble("cond", 0);
                bar.set(has && s.has("cond") ? cd : 0, cd < 0.25 ? t.bad : cd < 0.5 ? t.warn : t.good);
                bar.setVisibility(has && s.has("cond") ? VISIBLE : INVISIBLE);
                tile.addView(bar, Ui.margins(Ui.lp(t.dp(44), t.dp(4)), 0, t.dp(4), 0, 0));
                final int slot = s.optInt("i");
                if (has) tile.setOnClickListener(v -> host.command("hotbar", Ui.args("i", slot), held ? "Putting away" : "Drawing"));
                weaponsRow.addView(tile, Ui.margins(Ui.lp(-2, -2), 0, 0, t.dp(8), 0));
            }
            if (weapons.length() > 0) {
                // every weapon you carry, bags included: the game's hotbar swap
                LinearLayout more = Ui.col(c);
                more.setGravity(Gravity.CENTER);
                more.setPadding(t.dp(6), t.dp(6), t.dp(6), t.dp(6));
                more.setBackground(Ui.pressable(t, t.surface2, 14));
                more.addView(Ui.icon(c, t, "weapon", t.accent, 26));
                TextView l = Ui.text(c, t, "All weapons", 11, t.text2, true);
                l.setGravity(Gravity.CENTER);
                more.addView(l, Ui.margins(Ui.lp(t.dp(64), -2), 0, t.dp(8), 0, 0));
                more.setOnClickListener(v -> weaponSheet(c, host, outfitData));
                weaponsRow.addView(more, Ui.lp(-2, t.dp(84)));
            }
        }

        private void setIcon(ImageView iv, String tex, String fallback) {
            iv.setImageDrawable(t.icon(fallback, t.text3));
            if (tex == null || tex.isEmpty()) return;
            Bitmap hit = host.art().peek("i", tex);
            if (hit != null) {
                iv.setImageBitmap(hit);
                return;
            }
            iv.setTag(tex);
            host.art().icon(tex, b -> {
                if (b != null && tex.equals(iv.getTag())) iv.setImageBitmap(b);
            });
        }

        static String group(JSONObject it) {
            String c = it.optString("c").toLowerCase(Locale.ROOT);
            if (it.has("food") || it.has("fluid") || c.matches(".*(food|drink|cook).*")) return "food";
            if (c.matches(".*(firstaid|medic|health).*")) return "medical";
            if (c.matches(".*(weapon|ammo|firearm|explosive).*") && !c.contains("part")) return "weapon";
            if (c.matches(".*(tool|electronic|light|camping|fishing|garden|carpentry|mechanic|farming|trap).*")) return "tool";
            if (c.matches(".*(cloth|accessor|bag|container|protect|wear).*")) return "clothing";
            return "other";
        }

        static String fallbackIcon(JSONObject it) {
            switch (group(it)) {
                case "food": return it.has("fluid") && !it.has("food") ? "drink" : "food";
                case "medical": return "medical";
                case "weapon": return "weapon";
                case "tool": return "tool";
                case "clothing": return "shirt";
                default: return "box";
            }
        }

        static List<String[]> tags(JSONObject it) {
            List<String[]> out = new ArrayList<>();
            if (it.optBoolean("eq")) out.add(new String[]{"In hand", "acc"});
            if (it.optBoolean("worn")) out.add(new String[]{it.optString("loc", "").isEmpty() ? "Worn" : it.optString("loc"), ""});
            JSONObject f = it.optJSONObject("food");
            if (f != null) {
                if (f.optBoolean("rotten")) out.add(new String[]{"Rotten", "bad"});
                else if (f.optBoolean("stale")) out.add(new String[]{"Stale", "warn"});
                if (f.optBoolean("burnt")) out.add(new String[]{"Burnt", "bad"});
                else if (f.optBoolean("cooked")) out.add(new String[]{"Cooked", "good"});
                if (f.optBoolean("frozen")) out.add(new String[]{"Frozen", ""});
                int hu = f.optInt("hunger"), th = f.optInt("thirst");
                if (hu != 0) out.add(new String[]{"Hunger " + (hu > 0 ? "+" : "") + hu, ""});
                if (th != 0) out.add(new String[]{"Thirst " + (th > 0 ? "+" : "") + th, ""});
            }
            JSONObject fl = it.optJSONObject("fluid");
            if (fl != null) out.add(new String[]{(fl.has("n") ? fl.optString("n") + " " : "") + Fmt.num(fl.optDouble("amt")) + "/" + Fmt.num(fl.optDouble("cap")) + " L", fl.optDouble("amt") <= 0 ? "warn" : ""});
            if (it.has("use")) out.add(new String[]{Fmt.pct(it.optDouble("use")) + "% left", it.optDouble("use") < 0.2 ? "warn" : ""});
            if (it.has("cond")) {
                double cd = it.optDouble("cond");
                out.add(new String[]{"Condition " + Fmt.pct(cd) + "%", cd < 0.25 ? "bad" : cd < 0.5 ? "warn" : ""});
            }
            if (it.has("ammo")) out.add(new String[]{it.optInt("ammo") + "/" + it.optInt("ammoMax"), it.optInt("ammo") == 0 ? "warn" : ""});
            return out;
        }

        // which of your containers each item is in (0 = your inventory, else the bag's item id)
        private final Map<Long, Long> inContainer = new HashMap<>();

        void gear(JSONObject d) {
            last = d;
            items.clear();
            inContainer.clear();
            Context c = getContext();
            containers.removeAllViews();
            JSONArray conts = Json.arr(d.opt("containers"));
            int shown = 0;
            for (int ci = 0; ci < conts.length(); ci++) {
                JSONObject cont = conts.optJSONObject(ci);
                if (cont == null) continue;
                long cid = cont.optLong("id");
                JSONArray list = Json.arr(cont.opt("items"));
                List<JSONObject> hands = new ArrayList<>(), worn = new ArrayList<>(), rows = new ArrayList<>();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject it = list.optJSONObject(i);
                    if (it == null) continue;
                    items.put(it.optLong("id"), it);
                    inContainer.put(it.optLong("id"), cid);
                    if (!filter.equals("all") && !group(it).equals(filter)) continue;
                    // your inventory splits into what's in your hands, what you wear and what you carry
                    if (cid == 0 && it.optBoolean("eq")) hands.add(it);
                    else if (cid == 0 && it.optBoolean("worn")) worn.add(it);
                    else rows.add(it);
                }
                if (cid == 0) {
                    if (!hands.isEmpty()) shown += section(c, "In your hands", "hand", null, hands, shown);
                    if (!worn.isEmpty()) shown += section(c, "Wearing", "shirt", null, worn, shown);
                    double cap = cont.optDouble("cap", 0);
                    if (!rows.isEmpty() || filter.equals("all")) {
                        shown += section(c, "Carrying", "gear", cont.optString("ic", null),
                                Fmt.num(cont.optDouble("w")) + (cap > 0 ? " / " + Fmt.num(cap) : ""), rows, shown);
                    }
                } else if (!rows.isEmpty() || filter.equals("all")) {
                    double cap = cont.optDouble("cap", 0);
                    shown += section(c, cont.optString("n"), cont.optBoolean("ring") ? "key" : "bag", cont.optString("ic", null),
                            Fmt.num(cont.optDouble("w")) + (cap > 0 ? " / " + Fmt.num(cap) : ""), rows, shown);
                }
            }
            if (shown == 0) containers.addView(Ui.text(c, t, "Nothing here.", 14, t.text3, false));
            updatePickBar();
        }

        /**
         * The game's own picture for a place (its inventory button, a bag's or key ring's icon, the loot
         * window's floor or body picture): a file under media/ when it has a path, else a texture-pack
         * picture. The line icon shows until it has loaded, or when there is none.
         */
        ImageView placeIcon(Context c, String tex, String fallback, int color, int dp) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(t.icon(fallback, color));
            iv.setLayoutParams(new LinearLayout.LayoutParams(t.dp(dp), t.dp(dp)));
            if (tex == null || tex.isEmpty()) return iv;
            Bitmap hit = host.art().peek(tex.contains("/") ? "m" : "i", tex);
            if (hit != null) {
                iv.setImageBitmap(hit);
                return iv;
            }
            iv.setTag(tex);
            ArtCache.Cb got = b -> { if (b != null && tex.equals(iv.getTag())) iv.setImageBitmap(b); };
            if (tex.contains("/")) host.art().media(tex, got);
            else host.art().icon(tex, got);
            return iv;
        }

        /** One section of what you have on you: a heading and its items in a card. Returns 1. */
        private int section(Context c, String title, String icon, String right, List<JSONObject> rows, int before) {
            return section(c, title, icon, null, right, rows, before);
        }

        private int section(Context c, String title, String icon, String tex, String right, List<JSONObject> rows, int before) {
            LinearLayout head = Ui.row(c);
            head.setPadding(t.dp(4), t.dp(4), t.dp(4), t.dp(8));
            head.addView(tex == null ? Ui.icon(c, t, icon, t.text3, 20) : placeIcon(c, tex, icon, t.text3, 26));
            head.addView(Ui.text(c, t, title, 16, t.text, true), Ui.margins(Ui.weight(1), t.dp(10), 0, 0, 0));
            if (selecting) {
                // the whole section in one tap (what you wear stays: it comes off first)
                List<Long> ids = new ArrayList<>();
                for (JSONObject it : rows) if (!it.optBoolean("worn")) ids.add(it.optLong("id"));
                if (!ids.isEmpty()) head.addView(pickAllButton(c, ids), Ui.margins(Ui.lp(-2, -2), t.dp(8), 0, right != null ? t.dp(10) : 0, 0));
            }
            if (right != null) head.addView(Ui.text(c, t, right, 13, t.text3, true));
            containers.addView(head, Ui.margins(Ui.lp(-1, -2), 0, before == 0 ? 0 : t.dp(14), 0, 0));
            LinearLayout box = Ui.col(c);
            box.setBackground(t.card());
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) {
                    View line = new View(c);
                    line.setBackgroundColor(t.line);
                    box.addView(line, Ui.lp(-1, t.dp(1)));
                }
                box.addView(itemRow(c, rows.get(i)));
            }
            if (rows.isEmpty()) {
                TextView e = Ui.text(c, t, "Empty", 14, t.text3, false);
                e.setPadding(t.dp(16), t.dp(18), t.dp(16), t.dp(18));
                box.addView(e);
            }
            containers.addView(box);
            return 1;
        }

        /**
         * Where an item could go, for "Move to": your inventory, each bag you carry, the containers
         * around you and the floor. key -> label; skips the place it is in (and a bag into itself).
         */
        Map<String, String> moveTargets(long itemId, Long fromContainer, Integer fromNear) {
            Map<String, String> out = new java.util.LinkedHashMap<>();
            if (fromContainer == null || fromContainer != 0) out.put("inv", "Inventory");
            if (last != null) {
                JSONArray conts = Json.arr(last.opt("containers"));
                for (int i = 0; i < conts.length(); i++) {
                    JSONObject cont = conts.optJSONObject(i);
                    if (cont == null || cont.optLong("id") == 0) continue;
                    long bag = cont.optLong("id");
                    if (bag == itemId || (fromContainer != null && fromContainer == bag)) continue;
                    out.put("bag:" + bag, cont.optString("n"));
                }
            }
            if (lootData != null && fromNear == null) {
                JSONArray list = Json.arr(lootData.opt("list"));
                Map<String, Integer> named = new HashMap<>();   // three "Drawer"s become Drawer, Drawer 2, Drawer 3
                for (int i = 0; i < list.length(); i++) {
                    JSONObject cont = list.optJSONObject(i);
                    if (cont == null || cont.optBoolean("corpse")) continue;
                    if (cont.optBoolean("floor")) {
                        out.put("floor", "Floor");
                        continue;
                    }
                    String n = cont.optString("n");
                    int k = named.containsKey(n) ? named.get(n) + 1 : 1;
                    named.put(n, k);
                    out.put("near:" + cont.optInt("k"), k > 1 ? n + " " + k : n);
                }
            }
            return out;
        }

        /** "Move to" buttons for a sheet: one tap moves the item (or every one of a stack). */
        void addMoveTo(Context c, LinearLayout sheet, Map<String, String> targets, JSONArray ids, String what) {
            if (targets.isEmpty()) return;
            sheet.addView(Ui.label(c, t, "Move to"), Ui.margins(Ui.lp(-2, -2), t.dp(2), t.dp(6), 0, t.dp(8)));
            Widgets.Flow f = new Widgets.Flow(c, t.dp(8), t.dp(8));
            for (Map.Entry<String, String> e : targets.entrySet()) {
                TextView b = Ui.text(c, t, e.getValue(), 14, t.text, true);
                b.setPadding(t.dp(14), t.dp(10), t.dp(14), t.dp(10));
                b.setBackground(Ui.pressable(t, t.surface2, 12));
                String to = e.getKey();
                b.setOnClickListener(v -> {
                    host.command("move", Ui.args("ids", ids, "to", to), what);
                    host.closeSheet();
                });
                f.addView(b);
            }
            sheet.addView(f, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(12)));
        }

        static JSONArray idsOf(JSONObject it) {
            JSONArray ids = it.optJSONArray("ids");
            if (ids != null && ids.length() > 0) return ids;
            JSONArray one = new JSONArray();
            one.put(it.optLong("id"));
            return one;
        }

        private View itemRow(Context c, JSONObject it) {
            LinearLayout r = Ui.row(c);
            r.setMinimumHeight(t.dp(58));
            r.setPadding(t.dp(12), t.dp(8), t.dp(14), t.dp(8));
            r.setBackground(Ui.pressable(t, 0x00000000, 0));
            FrameLayout iconBox = new FrameLayout(c);
            iconBox.setBackground(t.round(t.surface2, 11));
            ImageView iv = new ImageView(c);
            int pad = t.dp(4);
            iv.setPadding(pad, pad, pad, pad);
            iconBox.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            setIcon(iv, it.optString("ic"), fallbackIcon(it));
            r.addView(iconBox, Ui.lp(t.dp(40), t.dp(40)));
            LinearLayout txt = Ui.col(c);
            String name = it.optString("n") + (it.optInt("cnt", 1) > 1 ? "  ×" + it.optInt("cnt") : "");
            txt.addView(Ui.ellipsize(Ui.text(c, t, name, 15.5f, t.text, true)));
            List<String[]> tg = tags(it);
            if (!tg.isEmpty()) {
                Widgets.Flow f = new Widgets.Flow(c, t.dp(6), t.dp(4));
                for (String[] x : tg) f.addView(Ui.tag(c, t, x[0], x[1]));
                txt.addView(f, Ui.margins(Ui.lp(-1, -2), 0, t.dp(4), 0, 0));
            }
            r.addView(txt, Ui.margins(Ui.weight(1), t.dp(12), 0, t.dp(8), 0));
            r.addView(Ui.text(c, t, Fmt.num(it.optDouble("w")), 13, t.text3, true));
            final long id = it.optLong("id");
            if (selecting) {
                // picking several: a tap ticks it, the icon shows the tick
                boolean on = picked.contains(id);
                iconBox.setBackground(t.round(on ? t.accent : t.surface2, 11));
                if (on) {
                    iv.setTag(null);   // the item's picture, still on its way, doesn't replace the tick
                    iv.setImageDrawable(t.icon("check", t.accentInk));
                }
                if (it.optBoolean("worn")) {
                    r.setAlpha(0.45f);
                    r.setOnClickListener(v -> host.toast("Take it off first to move it", true));
                } else {
                    r.setOnClickListener(v -> togglePick(id));
                }
            } else {
                r.setOnClickListener(v -> openItem(id));
                r.setOnLongClickListener(v -> { openItem(id); return true; });
            }
            return r;
        }

        // ------------------------------------------------------------ moving many items at once

        // "Move items": tap the items (or "All" for a whole bag or container), then tap where they go in
        // the bar at the bottom. The bar stays for the next batch until "Done". Works around you too.
        private boolean selecting;
        private final java.util.LinkedHashSet<Long> picked = new java.util.LinkedHashSet<>();
        private LinearLayout pickBar, pickTargets;
        private TextView pickCount, selectBtn;
        private String targetsSig = "";

        boolean picking() {
            return selecting;
        }

        boolean isPicked(long id) {
            return picked.contains(id);
        }

        /** Picks or unpicks an item (a stack is one row and moves whole). */
        void togglePick(long id) {
            if (!picked.remove(id)) picked.add(id);
            pickChanged();
        }

        /** "All" on a section: picks every item in it, or none if they all were. */
        View pickAllButton(Context c, List<Long> ids) {
            boolean all = picked.containsAll(ids);
            TextView b = Ui.text(c, t, all ? "None" : "All", 13.5f, t.accent, true);
            b.setPadding(t.dp(12), t.dp(7), t.dp(12), t.dp(7));
            b.setBackground(Ui.pressable(t, Theme.alpha(t.accent, 0.14f), 10));
            b.setOnClickListener(v -> {
                if (picked.containsAll(ids)) picked.removeAll(ids);
                else picked.addAll(ids);
                pickChanged();
            });
            return b;
        }

        private void pickChanged() {
            if (last != null) gear(last);
            around.redraw();
            updatePickBar();
        }

        /** The "Move items" switch and the bar of places to move what's picked (built once, over the list). */
        private void buildPicking(Context c) {
            selectBtn = Ui.text(c, t, "Move items", 14, t.accent, true);
            selectBtn.setPadding(t.dp(15), t.dp(9), t.dp(15), t.dp(9));
            selectBtn.setBackground(Ui.pressable(t, Theme.alpha(t.accent, 0.14f), 99));
            selectBtn.setOnClickListener(v -> setSelecting(!selecting));
            chips.addView(selectBtn, 0, Ui.margins(Ui.lp(-2, -2), 0, 0, t.dp(8), 0));

            pickBar = Ui.col(c);
            android.graphics.drawable.GradientDrawable bg = t.round(t.surface, 18);
            bg.setStroke(t.dp(1), t.line);
            pickBar.setBackground(bg);
            pickBar.setPadding(t.dp(14), t.dp(10), t.dp(6), t.dp(10));
            pickBar.setElevation(t.dpf(10));
            pickBar.setClickable(true);   // a tap between the buttons doesn't reach the list under it
            pickCount = Ui.text(c, t, "", 14, t.text, true);
            pickBar.addView(Ui.ellipsize(pickCount), Ui.margins(Ui.lp(-1, -2), t.dp(2), 0, t.dp(8), t.dp(8)));
            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            pickTargets = Ui.row(c);
            hs.addView(pickTargets);
            pickBar.addView(hs, Ui.lp(-1, -2));
            pickBar.setVisibility(GONE);
        }

        private void setSelecting(boolean on) {
            selecting = on;
            picked.clear();
            selectBtn.setText(on ? "Done" : "Move items");
            targetsSig = "";
            pickChanged();
        }

        /** The bar: how many are picked, and every place they can go (your bags, around you, the floor). */
        private void updatePickBar() {
            if (pickBar == null) return;
            pickBar.setVisibility(selecting ? VISIBLE : GONE);
            int room = selecting ? t.dp(136) : t.dp(16);   // the last items stay above the bar
            onYouCol.setPadding(t.dp(12), t.dp(4), t.dp(12), room);
            around.setBottomRoom(room);
            if (!selecting) return;
            int n = picked.size();
            pickCount.setText(n == 0 ? "Tap the items to move, then where they go"
                    : (n == 1 ? "1 picked" : n + " picked") + " · tap where they go");
            Map<String, String> targets = moveTargets(-1, null, null);
            String sig = targets.toString() + (n > 0);
            if (sig.equals(targetsSig)) return;
            targetsSig = sig;
            Context c = getContext();
            pickTargets.removeAllViews();
            for (Map.Entry<String, String> e : targets.entrySet()) {
                String to = e.getKey(), where = e.getValue();
                LinearLayout chip = Ui.row(c);
                chip.setGravity(Gravity.CENTER_VERTICAL);
                chip.setPadding(t.dp(12), t.dp(9), t.dp(14), t.dp(9));
                chip.setBackground(Ui.pressable(t, t.surface2, 12));
                ImageView pic = placeIcon(c, targetPicture(to), targetIcon(to), n > 0 ? t.accent : t.text3, 24);
                pic.setAlpha(n > 0 ? 1f : 0.5f);
                chip.addView(pic);
                chip.addView(Ui.text(c, t, where, 14, n > 0 ? t.text : t.text3, true), Ui.margins(Ui.lp(-2, -2), t.dp(8), 0, 0, 0));
                chip.setOnClickListener(v -> movePickedTo(to, where));
                pickTargets.addView(chip, Ui.margins(Ui.lp(-2, -2), 0, 0, t.dp(8), 0));
            }
        }

        /** The game's own picture for a place to move to: the inventory button, a bag's icon, the loot window's. */
        private String targetPicture(String to) {
            if (to.equals("inv")) return "Icon_InventoryBasic";
            if (to.startsWith("bag:") && last != null) {
                JSONArray conts = Json.arr(last.opt("containers"));
                for (int i = 0; i < conts.length(); i++) {
                    JSONObject cont = conts.optJSONObject(i);
                    if (cont != null && to.equals("bag:" + cont.optLong("id"))) return cont.optString("ic", null);
                }
            }
            if ((to.equals("floor") || to.startsWith("near:")) && lootData != null) {
                JSONArray list = Json.arr(lootData.opt("list"));
                for (int i = 0; i < list.length(); i++) {
                    JSONObject cont = list.optJSONObject(i);
                    if (cont == null) continue;
                    boolean hit = to.equals("floor") ? cont.optBoolean("floor") : to.equals("near:" + cont.optInt("k"));
                    if (hit) return cont.optString("ic", to.equals("floor") ? "Container_Floor" : null);
                }
            }
            return to.equals("floor") ? "Container_Floor" : null;
        }

        private String targetIcon(String to) {
            if (to.equals("inv")) return "gear";
            if (to.equals("floor")) return "layers";
            if (to.startsWith("near:")) return "box";
            if (last != null) {
                JSONArray conts = Json.arr(last.opt("containers"));
                for (int i = 0; i < conts.length(); i++) {
                    JSONObject cont = conts.optJSONObject(i);
                    if (cont != null && to.equals("bag:" + cont.optLong("id")) && cont.optBoolean("ring")) return "key";
                }
            }
            return "bag";
        }

        private void movePickedTo(String to, String where) {
            JSONArray ids = new JSONArray();
            int worn = 0;
            for (long id : picked) {
                JSONObject it = items.containsKey(id) ? items.get(id) : around.item(id);
                if (it == null) continue;
                if (it.optBoolean("worn")) {   // worn things come off first
                    worn++;
                    continue;
                }
                JSONArray all = idsOf(it);
                for (int i = 0; i < all.length(); i++) ids.put(all.opt(i));
            }
            if (ids.length() == 0) {
                host.toast(worn > 0 ? "Take worn things off first" : "Tap the items to move first", true);
                return;
            }
            host.command("move", Ui.args("ids", ids, "to", to), "Moving to " + where);
            picked.clear();
            pickChanged();
        }

        private void openItem(long id) {
            JSONObject it = items.get(id);
            if (it == null) return;
            Context c = getContext();
            LinearLayout sheet = Ui.col(c);
            LinearLayout head = Ui.row(c);
            FrameLayout iconBox = new FrameLayout(c);
            iconBox.setBackground(t.round(t.surface2, 14));
            ImageView iv = new ImageView(c);
            int pad = t.dp(6);
            iv.setPadding(pad, pad, pad, pad);
            iconBox.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            setIcon(iv, it.optString("ic"), fallbackIcon(it));
            head.addView(iconBox, Ui.lp(t.dp(56), t.dp(56)));
            LinearLayout ht = Ui.col(c);
            ht.addView(Ui.text(c, t, it.optString("n") + (it.optInt("cnt", 1) > 1 ? "  ×" + it.optInt("cnt") : ""), 20, t.text, true));
            ht.addView(Ui.text(c, t, it.optString("c") + " · weight " + Fmt.num(it.optDouble("w")), 13.5f, t.text3, false), Ui.margins(Ui.lp(-2, -2), 0, t.dp(4), 0, 0));
            head.addView(ht, Ui.margins(Ui.weight(1), t.dp(14), 0, 0, 0));
            sheet.addView(head, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(12)));
            List<String[]> tg = tags(it);
            if (!tg.isEmpty()) {
                Widgets.Flow f = new Widgets.Flow(c, t.dp(6), t.dp(6));
                for (String[] x : tg) f.addView(Ui.tag(c, t, x[0], x[1]));
                sheet.addView(f, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(14)));
            }
            JSONArray acts = Json.arr(it.opt("a"));
            int n = 0;
            for (int i = 0; i < acts.length(); i++) {
                String a = acts.optString(i);
                if (a.equals("eat") || a.equals("drink")) {
                    boolean eat = a.equals("eat");
                    sheet.addView(Ui.text(c, t, eat ? "Eat" : "Drink", 14, t.text2, true), Ui.margins(Ui.lp(-2, -2), t.dp(2), n > 0 ? t.dp(6) : 0, 0, t.dp(8)));
                    LinearLayout row = Ui.row(c);
                    String[][] portions = {{"1", "All"}, {"0.5", "Half"}, {"0.25", "Quarter"}};
                    for (int k = 0; k < portions.length; k++) {
                        final double portion = Double.parseDouble(portions[k][0]);
                        TextView b = Ui.action(c, t, null, portions[k][1]);
                        b.setGravity(Gravity.CENTER);
                        b.setOnClickListener(v -> { host.command(a, Ui.args("id", id, "portion", portion), eat ? "Eating" : "Drinking"); host.closeSheet(); });
                        LinearLayout.LayoutParams lp = Ui.weight(1);
                        if (k > 0) lp.leftMargin = t.dp(8);
                        row.addView(b, lp);
                    }
                    sheet.addView(row, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
                    n++;
                    continue;
                }
                String[] def = actionDef(a);
                if (def == null) continue;
                TextView b = Ui.action(c, t, def[0], def[1]);
                final String cmd = a.startsWith("equip") ? "equip" : a;
                final JSONObject args = Ui.args("id", id);
                try {
                    if (a.equals("equip1")) args.put("hand", "primary");
                    if (a.equals("equip2")) args.put("hand", "secondary");
                } catch (Exception ignored) {
                }
                b.setOnClickListener(v -> { host.command(cmd, args, def[2]); host.closeSheet(); });
                sheet.addView(b, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
                n++;
            }
            // anywhere else you can put it: your bags, the containers around you, the floor (not while worn)
            if (!it.optBoolean("worn")) addMoveTo(c, sheet, moveTargets(id, inContainer.get(id), null), idsOf(it), "Moving");
            // every action the game's own right-click menu has for it
            String name = it.optString("n");
            TextView more = Ui.action(c, t, "more", "All actions…");
            more.setOnClickListener(v -> GameMenu.open(c, host, name, Ui.args("kind", "item", "ids", idsOf(it))));
            sheet.addView(more, Ui.margins(Ui.lp(-1, -2), 0, n > 0 ? t.dp(4) : 0, 0, 0));
            host.openSheet(sheet);
        }

        private static String[] actionDef(String a) {
            switch (a) {
                case "equip1": return new String[]{"hand", "Equip in main hand", "Equipping"};
                case "equip2": return new String[]{"hand", "Equip in off hand", "Equipping"};
                case "unequip": return new String[]{"x", "Put away", "Putting away"};
                case "read": return new String[]{"book", "Read", "Reading"};
                case "pill": return new String[]{"pill", "Take one", "Taking"};
                case "wear": return new String[]{"shirt", "Wear", "Putting on"};
                case "takeoff": return new String[]{"shirt", "Take off", "Taking off"};
                default: return null;
            }
        }
    }

    // =================================================================== Skills

    static final class Skills extends ScrollView {
        private final Theme t;
        private final Host host;
        private final LinearLayout col;
        private String sig = "";

        Skills(Context c, Host host) {
            super(c);
            this.host = host;
            t = host.theme();
            setVerticalScrollBarEnabled(false);
            col = Ui.col(c);
            col.setPadding(t.dp(12), t.dp(12), t.dp(12), t.dp(16));
            col.addView(Ui.text(c, t, "Reading your skills…", 14, t.text3, false));
            addView(col);
        }

        void skills(JSONObject d) {
            String s = d.toString();
            if (s.equals(sig)) return;
            sig = s;
            Context c = getContext();
            col.removeAllViews();
            JSONArray groups = Json.arr(d.opt("groups"));
            for (int g = 0; g < groups.length(); g++) {
                JSONObject grp = groups.optJSONObject(g);
                if (grp == null) continue;
                LinearLayout card = Ui.card(c, t);
                Ui.cardHead(c, t, card, grp.optString("g"));
                JSONArray list = Json.arr(grp.opt("s"));
                for (int i = 0; i < list.length(); i++) {
                    JSONObject sk = list.optJSONObject(i);
                    if (sk == null) continue;
                    int lvl = sk.optInt("lvl");
                    double p = sk.optDouble("p", 0);
                    boolean zero = lvl == 0 && p == 0;
                    LinearLayout r = Ui.row(c);
                    TextView name = Ui.ellipsize(Ui.text(c, t, sk.optString("n"), 15, zero ? t.text3 : t.text, true));
                    r.addView(name, Ui.weight(1));
                    if (sk.has("mult")) r.addView(Ui.tag(c, t, "×" + Fmt.num(sk.optDouble("mult")), "acc"), Ui.margins(Ui.lp(-2, -2), 0, 0, t.dp(6), 0));
                    if (sk.has("boost")) r.addView(Ui.tag(c, t, "+" + sk.optInt("boost"), ""), Ui.margins(Ui.lp(-2, -2), 0, 0, t.dp(6), 0));
                    r.addView(Ui.text(c, t, String.valueOf(lvl), 16, zero ? t.text3 : t.text, true));
                    card.addView(r, Ui.margins(Ui.lp(-1, -2), 0, i == 0 ? 0 : t.dp(10), 0, t.dp(6)));
                    // the game's own skill squares
                    GameArt.SkillSquares bar = new GameArt.SkillSquares(c, t, host.art());
                    bar.set(lvl, p);
                    card.addView(bar, Ui.lp(-1, -2));
                }
                col.addView(card, Ui.margins(Ui.lp(-1, -2), 0, g == 0 ? 0 : t.dp(12), 0, 0));
            }
        }
    }

    // =================================================================== Car

    static final class Car extends ScrollView {
        private final Theme t;
        private final Host host;
        private final GameArt.Dashboard dash;
        private final Widgets.Speedo speedo;
        private final LinearLayout gearbox;
        private final ViewGroup pills;
        private final TextView engineChip, lightsChip, heaterChip, hornChip;
        private final TextView name, role, fuelText, engText, battText;
        private final Widgets.Bar fuel, engine, battery;
        private final View battRow, speedoCard;
        private final Widgets.CarOverlay overlay;
        private final LinearLayout overlayCard;

        Car(Context c, Host host) {
            super(c);
            this.host = host;
            t = host.theme();
            setVerticalScrollBarEnabled(false);
            LinearLayout page = Ui.col(c);
            page.setPadding(t.dp(12), t.dp(12), t.dp(12), t.dp(16));
            addView(page);

            // the game's own dashboard, needles and warning lights included
            LinearLayout dashCard = Ui.card(c, t);
            dashCard.setPadding(t.dp(8), t.dp(8), t.dp(8), t.dp(8));
            dashCard.setBackground(t.round(0xFF0B0C0E, 16));
            dash = new GameArt.Dashboard(c, t, host.art());
            dashCard.addView(dash, Ui.lp(-1, -2));
            page.addView(dashCard);

            LinearLayout row = Ui.row(c);
            row.setGravity(Gravity.TOP);
            page.addView(row, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));
            LinearLayout left = Ui.col(c);
            row.addView(left, Ui.weight(1.05f));

            // plain speedometer, shown only until the dashboard artwork has arrived
            LinearLayout sCard = Ui.card(c, t);
            sCard.setGravity(Gravity.CENTER_HORIZONTAL);
            speedo = new Widgets.Speedo(c, t);
            sCard.addView(speedo, Ui.lp(-1, t.dp(200)));
            gearbox = Ui.row(c);
            gearbox.setGravity(Gravity.CENTER);
            sCard.addView(gearbox, Ui.margins(Ui.lp(-1, -2), 0, t.dp(6), 0, t.dp(4)));
            speedoCard = sCard;
            left.addView(sCard, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(12)));

            LinearLayout right = Ui.col(c);
            LinearLayout.LayoutParams rlp = Ui.weight(1f);
            rlp.leftMargin = t.dp(12);
            row.addView(right, rlp);

            LinearLayout info = Ui.card(c, t);
            TextView[] h = Ui.cardHead(c, t, info, "Vehicle");
            name = h[0];
            role = h[1];
            fuel = new Widgets.Bar(c, t.surface3);
            engine = new Widgets.Bar(c, t.surface3);
            battery = new Widgets.Bar(c, t.surface3);
            fuelText = gauge(c, info, "fuel", "Fuel", fuel, 0);
            engText = gauge(c, info, "engine", "Engine", engine, t.dp(12));
            battText = gauge(c, info, "battery", "Battery", battery, t.dp(12));
            battRow = (View) battery.getParent();
            left.addView(info);

            // the controls double as the status: tap one to switch it, like the dashboard buttons
            LinearLayout st = Ui.card(c, t);
            Ui.cardHead(c, t, st, "Controls");
            pills = new Widgets.Flow(c, t.dp(8), t.dp(8));
            engineChip = chip(c, "engine");
            lightsChip = chip(c, "lights");
            heaterChip = chip(c, "heater");
            hornChip = chip(c, "horn");
            st.addView(pills);
            left.addView(st, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));

            overlayCard = Ui.card(c, t);
            Ui.cardHead(c, t, overlayCard, "Parts");
            overlay = new Widgets.CarOverlay(c);
            overlay.setup(t, host.art());
            overlayCard.addView(overlay, Ui.lp(-1, t.dp(300)));
            right.addView(overlayCard);
        }

        private TextView gauge(Context c, LinearLayout card, String icon, String label, Widgets.Bar bar, int top) {
            LinearLayout box = Ui.col(c);
            LinearLayout r = Ui.row(c);
            r.addView(Ui.icon(c, t, icon, t.text2, 20));
            r.addView(Ui.text(c, t, label, 15, t.text2, true), Ui.margins(Ui.weight(1), t.dp(10), 0, 0, 0));
            TextView v = Ui.text(c, t, "–", 17, t.text, true);
            r.addView(v);
            box.addView(r);
            box.addView(bar, Ui.margins(Ui.lp(-1, t.dp(11)), 0, t.dp(8), 0, 0));
            card.addView(box, Ui.margins(Ui.lp(-1, -2), 0, top, 0, 0));
            return v;
        }

        void core(JSONObject core) {
            JSONObject v = core.optJSONObject("veh");
            if (v == null) return;
            dash.set(v);
            speedoCard.setVisibility(dash.ready() ? GONE : VISIBLE);
            speedo.set(v.optDouble("spd"), v.optDouble("max", 160));
            gearbox.removeAllViews();
            String gear = v.optString("gear");
            for (String g : new String[]{"R", "N", "1", "2", "3", "4", "5"}) {
                TextView gv = Ui.text(getContext(), t, g, 15, g.equals(gear) ? t.accentInk : t.text3, true);
                gv.setGravity(Gravity.CENTER);
                gv.setBackground(t.round(g.equals(gear) ? t.accent : t.surface2, 10));
                gearbox.addView(gv, Ui.margins(Ui.lp(t.dp(36), t.dp(36)), t.dp(3), 0, t.dp(3), 0));
            }
            name.setText(v.optString("name", "Vehicle").toUpperCase(Locale.ROOT));
            role.setText(v.optBoolean("drv") ? "Driving" : "Passenger");
            double f = v.optDouble("fuel");
            fuelText.setText(Fmt.pct(f) + "%");
            fuel.set(f, f < 0.15 ? t.bad : f < 0.3 ? t.warn : t.good);
            double e = v.optDouble("eng", -1);
            engText.setText(e >= 0 ? Math.round(e) + "%" : "—");
            engine.set(Math.max(0, e) / 100, e < 30 ? t.bad : e < 60 ? t.warn : t.good);
            if (v.has("batt")) {
                battRow.setVisibility(VISIBLE);
                double b = v.optDouble("batt");
                battText.setText(Fmt.pct(b) + "%");
                battery.set(b, b < 0.2 ? t.bad : t.good);
            } else {
                battRow.setVisibility(GONE);
            }
            boolean driver = v.optBoolean("drv");
            boolean run = v.optBoolean("run");
            styleChip(engineChip, "engine", run ? "Engine on" : (v.optBoolean("keys") || v.optBoolean("hotwired") ? "Start engine" : "Engine off"), run, driver);
            styleChip(lightsChip, "light", v.optBoolean("lights") ? "Lights on" : "Lights off", v.optBoolean("lights"), driver);
            lightsChip.setVisibility(v.optBoolean("hasLights") ? VISIBLE : GONE);
            styleChip(heaterChip, "sun", v.optBoolean("heater") ? "Heater on" : "Heater off", v.optBoolean("heater"), true);
            heaterChip.setVisibility(v.optBoolean("hasHeater") ? VISIBLE : GONE);
            styleChip(hornChip, "horn", "Horn", false, driver);
            JSONObject mech = v.optJSONObject("mech");
            overlayCard.setVisibility(mech == null ? GONE : VISIBLE);
            overlay.set(mech);
        }

        private TextView chip(Context c, String control) {
            TextView p = Ui.text(c, t, "", 13.5f, t.text3, true);
            p.setPadding(t.dp(12), t.dp(9), t.dp(14), t.dp(9));
            p.setCompoundDrawablePadding(t.dp(7));
            String label = "horn".equals(control) ? null : control.substring(0, 1).toUpperCase(Locale.ROOT) + control.substring(1);
            p.setOnClickListener(v -> host.command("car", Ui.args("a", control), label));
            pills.addView(p, Ui.margins(Ui.lp(-2, -2), 0, 0, t.dp(8), 0));
            return p;
        }

        private void styleChip(TextView p, String icon, String text, boolean on, boolean enabled) {
            if (!text.contentEquals(p.getText()) || !Boolean.valueOf(on).equals(p.getTag())) {
                p.setText(text);
                p.setTag(on);
                p.setTextColor(on ? t.accent : t.text2);
                p.setBackground(Ui.pressable(t, on ? Theme.alpha(t.accent, 0.18f) : t.surface2, 99));
                android.graphics.drawable.Drawable d = t.icon(icon, on ? t.accent : t.text2);
                d.setBounds(0, 0, t.dp(18), t.dp(18));
                p.setCompoundDrawables(d, null, null, null);
            }
            p.setEnabled(enabled);
            p.setAlpha(enabled ? 1f : 0.45f);
        }
    }

    // =================================================================== Radio & chat

    static final class Comms extends LinearLayout {
        private static final String[] PHRASES = {"On my way", "Help!", "Zombies here!", "Wait for me", "Follow me", "Yes", "No", "Thanks"};
        private static final String[][] CHANNELS = {{"say", "Say"}, {"shout", "Shout"}, {"general", "General"},
                {"faction", "Faction"}, {"safehouse", "Safehouse"}};

        private final Host host;
        private final Theme t;
        private final LinearLayout seg, radioList, chatList, chatBox;
        private final ScrollView radioScroll, chatScroll;
        private final TextView segRadio, segChat, channelBtn;
        private final EditText input;
        private final FrameLayout forecastHolder;

        /** The last forecast heard, above the log (null hides it). */
        void setForecast(View card) {
            forecastHolder.removeAllViews();
            if (card != null) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2);
                lp.bottomMargin = t.dp(12);
                forecastHolder.addView(card, lp);
            }
        }
        private String mode = "radio", channel = "say", radioSig = "", chatSig = "";
        private boolean mp, canFaction, canSafehouse;

        Comms(Context c, Host host) {
            super(c);
            this.host = host;
            t = host.theme();
            setOrientation(VERTICAL);
            setPadding(t.dp(12), t.dp(12), t.dp(12), t.dp(10));

            seg = Ui.row(c);
            seg.setBackground(t.round(t.surface2, 12));
            seg.setPadding(t.dp(4), t.dp(4), t.dp(4), t.dp(4));
            segRadio = segButton(c, "radio", "Radio & TV");
            segChat = segButton(c, "chat", "Chat");
            seg.addView(segRadio, Ui.weight(1));
            seg.addView(segChat, Ui.weight(1));
            addView(seg, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(10)));
            seg.setVisibility(GONE);

            radioScroll = new ScrollView(c);
            radioScroll.setVerticalScrollBarEnabled(false);
            LinearLayout radioCol = Ui.col(c);
            forecastHolder = new FrameLayout(c);
            radioCol.addView(forecastHolder, Ui.lp(-1, -2));
            radioList = Ui.col(c);
            radioCol.addView(radioList, Ui.lp(-1, -2));
            radioScroll.addView(radioCol);
            addView(radioScroll, new LayoutParams(-1, 0, 1));
            radioList.addView(empty(c, "radio", "Nothing heard yet",
                    "Turn on a radio or a TV near your character. What they hear shows up here, and stays after a reload."));

            chatBox = Ui.col(c);
            chatScroll = new ScrollView(c);
            chatScroll.setVerticalScrollBarEnabled(false);
            chatList = Ui.col(c);
            chatScroll.addView(chatList);
            chatBox.addView(chatScroll, new LayoutParams(-1, 0, 1));
            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = Ui.row(c);
            for (String p : PHRASES) {
                TextView chip = Ui.text(c, t, p, 13.5f, t.text2, true);
                chip.setPadding(t.dp(13), t.dp(8), t.dp(13), t.dp(8));
                android.graphics.drawable.GradientDrawable g = t.round(t.surface, 99);
                g.setStroke(t.dp(1), t.line);
                chip.setBackground(g);
                chip.setOnClickListener(v -> send(p));
                chips.addView(chip, Ui.margins(Ui.lp(-2, -2), 0, 0, t.dp(8), 0));
            }
            hs.addView(chips);
            chatBox.addView(hs, Ui.margins(Ui.lp(-1, -2), 0, t.dp(8), 0, t.dp(8)));
            LinearLayout inputRow = Ui.row(c);
            channelBtn = Ui.text(c, t, "Say", 14, t.accent, true);
            channelBtn.setGravity(Gravity.CENTER);
            channelBtn.setBackground(Ui.pressable(t, Theme.alpha(t.accent, 0.16f), 12));
            channelBtn.setPadding(t.dp(12), 0, t.dp(12), 0);
            channelBtn.setOnClickListener(v -> pickChannel());
            inputRow.addView(channelBtn, Ui.lp(-2, t.dp(46)));
            input = new EditText(c);
            input.setHint("Message");
            input.setHintTextColor(t.text3);
            input.setTextColor(t.text);
            input.setTextSize(t.sp(15));
            input.setSingleLine(true);
            input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
            input.setBackground(t.round(t.surface2, 12));
            input.setPadding(t.dp(12), 0, t.dp(12), 0);
            input.setOnEditorActionListener((v, a, e) -> { send(input.getText().toString()); return true; });
            inputRow.addView(input, Ui.margins(new LayoutParams(0, t.dp(46), 1), t.dp(8), 0, t.dp(8), 0));
            ImageView go = new ImageView(c);
            go.setImageDrawable(t.icon("send", t.accentInk));
            go.setScaleType(ImageView.ScaleType.CENTER);
            go.setBackground(Ui.pressable(t, t.accent, 12));
            go.setOnClickListener(v -> send(input.getText().toString()));
            inputRow.addView(go, Ui.lp(t.dp(52), t.dp(46)));
            chatBox.addView(inputRow);
            addView(chatBox, new LayoutParams(-1, 0, 1));
            chatBox.setVisibility(GONE);
            chatList.addView(empty(c, "chat", "No messages yet", "Chat from other players shows up here. Tap a phrase or type to answer."));
        }

        private TextView segButton(Context c, String key, String label) {
            TextView b = Ui.text(c, t, label, 14, t.text2, true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, t.dp(9), 0, t.dp(9));
            b.setOnClickListener(v -> setMode(key));
            return b;
        }

        private View empty(Context c, String icon, String title, String text) {
            LinearLayout box = Ui.col(c);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(t.dp(20), t.dp(40), t.dp(20), t.dp(20));
            box.addView(Ui.icon(c, t, icon, t.text3, 36));
            TextView h = Ui.text(c, t, title, 17, t.text, true);
            box.addView(h, Ui.margins(Ui.lp(-2, -2), 0, t.dp(12), 0, t.dp(6)));
            TextView s = Ui.text(c, t, text, 13.5f, t.text3, false);
            s.setGravity(Gravity.CENTER);
            box.addView(s);
            return box;
        }

        String mode() {
            return mp ? mode : "radio";
        }

        void setMode(String m) {
            mode = m;
            boolean chat = mp && "chat".equals(m);
            radioScroll.setVisibility(chat ? GONE : VISIBLE);
            chatBox.setVisibility(chat ? VISIBLE : GONE);
            for (TextView b : new TextView[]{segRadio, segChat}) {
                boolean on = (b == segChat) == chat;
                b.setTextColor(on ? t.text : t.text2);
                b.setBackground(on ? t.round(t.isLight() ? 0xFFFFFFFF : t.surface, 9) : null);
            }
        }

        /** Multiplayer adds the chat. */
        void setMp(boolean on) {
            if (mp == on) return;
            mp = on;
            seg.setVisibility(on ? VISIBLE : GONE);
            setMode(on ? "chat" : "radio");
        }

        void radio(JSONObject d) {
            String s = d.toString();
            if (s.equals(radioSig)) return;
            radioSig = s;
            Context c = getContext();
            JSONArray list = Json.arr(d.opt("list"));
            radioList.removeAllViews();
            if (list.length() == 0) {
                radioList.addView(empty(c, "radio", "Nothing heard yet",
                        "Turn on a radio or a TV near your character. What they hear shows up here, and stays after a reload."));
                return;
            }
            for (int i = 0; i < list.length(); i++) {
                JSONObject e = list.optJSONObject(i);
                if (e != null) radioList.addView(broadcast(c, e), Ui.margins(Ui.lp(-1, -2), 0, i == 0 ? 0 : t.dp(10), 0, 0));
            }
            TextView clear = Ui.text(c, t, "Clear the log", 13.5f, t.text3, true);
            clear.setPadding(t.dp(12), t.dp(14), t.dp(12), t.dp(6));
            clear.setGravity(Gravity.CENTER);
            clear.setOnClickListener(v -> host.command("radioClear", new JSONObject(), "Log cleared"));
            radioList.addView(clear, Ui.lp(-1, -2));
        }

        private View broadcast(Context c, JSONObject e) {
            LinearLayout card = Ui.card(c, t);
            LinearLayout head = Ui.row(c);
            String kind = e.optString("k", "radio");
            String icon = "tv".equals(kind) ? "tv" : "walkie".equals(kind) ? "walkie" : "radio";
            head.addView(Ui.icon(c, t, icon, t.accent, 20));
            head.addView(Ui.ellipsize(Ui.text(c, t, station(e), 15, t.text, true)), Ui.margins(Ui.weight(1), t.dp(10), 0, t.dp(8), 0));
            JSONObject time = e.optJSONObject("t");
            if (time != null && time.optBoolean("w")) head.addView(Ui.text(c, t, Fmt.clock(time.optInt("h"), time.optInt("mi")), 12.5f, t.text3, true));
            card.addView(head, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
            JSONArray lines = Json.arr(e.opt("l"));
            for (int i = 0; i < lines.length(); i++) {
                TextView l = Ui.text(c, t, lines.optString(i), 14.5f, i == lines.length() - 1 ? t.text : t.text2, false);
                l.setLineSpacing(t.dp(2), 1f);
                card.addView(l, Ui.margins(Ui.lp(-1, -2), 0, i == 0 ? 0 : t.dp(6), 0, 0));
            }
            return card;
        }

        static String station(JSONObject e) {
            String kind = e.optString("k", "radio");
            String name = e.optString("s", "");
            double f = e.optDouble("f", 0);
            String freq = f > 1000 ? String.format(Locale.US, "%.1f MHz", f / 1000) : "";
            switch (kind) {
                case "tv": return name.isEmpty() ? "Television" : name;
                case "media": return name.isEmpty() ? "Recording" : name;
                case "walkie": return "Walkie-talkie" + (freq.isEmpty() ? "" : " · " + freq);
                default:
                    if (name.isEmpty()) return freq.isEmpty() ? "Radio" : freq;
                    return freq.isEmpty() ? name : name + " · " + freq;
            }
        }

        void chat(JSONObject d) {
            JSONObject can = d.optJSONObject("can");
            canFaction = can != null && can.optBoolean("faction");
            canSafehouse = can != null && can.optBoolean("safehouse");
            String s = d.toString();
            if (s.equals(chatSig)) return;
            chatSig = s;
            Context c = getContext();
            JSONArray list = Json.arr(d.opt("list"));
            chatList.removeAllViews();
            if (list.length() == 0) {
                chatList.addView(empty(c, "chat", "No messages yet", "Chat from other players shows up here. Tap a phrase or type to answer."));
                return;
            }
            // oldest at the top, newest just above the box, like a chat
            for (int i = list.length() - 1; i >= 0; i--) {
                JSONObject m = list.optJSONObject(i);
                if (m != null) chatList.addView(message(c, m), Ui.margins(Ui.lp(-1, -2), 0, t.dp(8), 0, 0));
            }
            chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
        }

        static int channelColor(Theme t, String k) {
            switch (k) {
                case "shout": return t.warn;
                case "faction": return t.good;
                case "safehouse": return 0xFF2A9D8F;
                case "whisper": return 0xFFB083E8;
                case "radio": return 0xFF5AA9E6;
                case "admin":
                case "server": return t.bad;
                case "general": return t.accent;
                default: return t.text2;
            }
        }

        private View message(Context c, JSONObject m) {
            boolean me = m.optBoolean("me");
            String k = m.optString("k", "say");
            LinearLayout box = Ui.col(c);
            box.setGravity(me ? Gravity.END : Gravity.START);
            String who = me ? "You" : m.optString("a", "");
            String label = (who.isEmpty() ? "" : who + " · ") + k.substring(0, 1).toUpperCase(Locale.ROOT) + k.substring(1);
            JSONObject time = m.optJSONObject("t");
            if (time != null && time.optBoolean("w")) label += " · " + Fmt.clock(time.optInt("h"), time.optInt("mi"));
            box.addView(Ui.text(c, t, label, 12, channelColor(t, k), true), Ui.margins(Ui.lp(-2, -2), t.dp(4), 0, t.dp(4), t.dp(4)));
            TextView b = Ui.text(c, t, m.optString("s"), 15, me ? t.accentInk : t.text, false);
            b.setLineSpacing(t.dp(2), 1f);
            b.setBackground(t.round(me ? t.accent : t.surface2, 14));
            b.setPadding(t.dp(12), t.dp(9), t.dp(12), t.dp(9));
            b.setMaxWidth(t.dp(380));
            box.addView(b, Ui.lp(-2, -2));
            return box;
        }

        private void pickChannel() {
            Context c = getContext();
            LinearLayout sheet = Ui.col(c);
            sheet.addView(Ui.text(c, t, "Send to", 20, t.text, true), Ui.margins(Ui.lp(-2, -2), 0, 0, 0, t.dp(12)));
            for (String[] ch : CHANNELS) {
                if ("faction".equals(ch[0]) && !canFaction) continue;
                if ("safehouse".equals(ch[0]) && !canSafehouse) continue;
                TextView a = Ui.action(c, t, "chat", ch[1] + ("shout".equals(ch[0]) ? "  ·  zombies hear it" : ""));
                a.setTextColor(channelColor(t, ch[0]) == t.text2 ? t.text : channelColor(t, ch[0]));
                a.setOnClickListener(v -> {
                    channel = ch[0];
                    channelBtn.setText(ch[1]);
                    host.closeSheet();
                });
                sheet.addView(a, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
            }
            host.openSheet(sheet);
        }

        private void send(String text) {
            String s = text == null ? "" : text.trim();
            if (s.isEmpty()) return;
            host.command("chat", Ui.args("s", s, "ch", channel), null);
            input.setText("");
        }
    }

    // =================================================================== Base

    static final class Base extends ScrollView {
        private final Host host;
        private final Theme t;
        private final LinearLayout col;
        private TextView distText;
        private JSONObject last, pos;
        private String sig = "";
        private boolean clock = true;

        Base(Context c, Host host) {
            super(c);
            this.host = host;
            t = host.theme();
            setVerticalScrollBarEnabled(false);
            col = Ui.col(c);
            col.setPadding(t.dp(12), t.dp(12), t.dp(12), t.dp(16));
            addView(col);
            col.addView(Ui.text(c, t, "Looking for your base…", 14, t.text3, false));
        }

        /** Player position and the world's rules, for the distance line and the "seen" wording. */
        void core(JSONObject core) {
            pos = core.optJSONObject("p");
            JSONObject rules = core.optJSONObject("rules");
            clock = rules == null || rules.optBoolean("clock", true);
            updateDistance();
        }

        private void updateDistance() {
            if (distText == null || last == null || pos == null || !last.optBoolean("set")) return;
            double dx = last.optDouble("x") - pos.optDouble("x"), dy = last.optDouble("y") - pos.optDouble("y");
            double d = Math.hypot(dx, dy);
            distText.setText(d < 25 ? "You're here" : Fmt.dist(d) + " " + Fmt.compass(dx, dy));
        }

        void base(JSONObject d) {
            last = d;
            String s = d.toString() + clock;
            if (s.equals(sig)) {
                updateDistance();
                return;
            }
            sig = s;
            Context c = getContext();
            col.removeAllViews();
            distText = null;
            if (!d.optBoolean("set")) {
                LinearLayout card = Ui.card(c, t);
                card.setGravity(Gravity.CENTER_HORIZONTAL);
                card.setPadding(t.dp(20), t.dp(24), t.dp(20), t.dp(20));
                card.addView(Ui.icon(c, t, "home", t.accent, 40));
                card.addView(Ui.text(c, t, "No base yet", 19, t.text, true), Ui.margins(Ui.lp(-2, -2), 0, t.dp(12), 0, t.dp(6)));
                TextView s1 = Ui.text(c, t, "Mark the place you live in. Knox Link keeps an eye on it: generators, rain collectors and crops, as you saw them the last time you were there.", 14, t.text2, false);
                s1.setGravity(Gravity.CENTER);
                card.addView(s1, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(16)));
                TextView set = Ui.action(c, t, "home", "Make this spot my base");
                set.setOnClickListener(v -> host.command("baseSet", new JSONObject(), "Base set"));
                card.addView(set, Ui.lp(-1, -2));
                card.addView(Ui.text(c, t, "Or press and hold anywhere on the map.", 13, t.text3, false), Ui.margins(Ui.lp(-2, -2), 0, t.dp(12), 0, 0));
                col.addView(card);
                return;
            }

            LinearLayout head = Ui.card(c, t);
            LinearLayout hr = Ui.row(c);
            FrameLayout ib = new FrameLayout(c);
            ib.setBackground(t.round(Theme.alpha(0xFF3E7C47, 0.22f), 14));
            ImageView hi = new ImageView(c);
            hi.setImageDrawable(t.icon("home", 0xFF5FB86B));
            hi.setScaleType(ImageView.ScaleType.CENTER);
            ib.addView(hi, new FrameLayout.LayoutParams(-1, -1));
            hr.addView(ib, Ui.lp(t.dp(50), t.dp(50)));
            LinearLayout ht = Ui.col(c);
            String name = d.optString("n", "");
            if (name.isEmpty()) name = d.optBoolean("safehouse") ? "Safehouse" : "Base";
            ht.addView(Ui.ellipsize(Ui.text(c, t, name, 19, t.text, true)));
            distText = Ui.text(c, t, "", 14, t.text2, true);
            ht.addView(distText, Ui.margins(Ui.lp(-2, -2), 0, t.dp(4), 0, 0));
            hr.addView(ht, Ui.margins(Ui.weight(1), t.dp(14), 0, 0, 0));
            head.addView(hr, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(14)));
            LinearLayout btns = Ui.row(c);
            TextView go = Ui.action(c, t, "pin", "Go there");
            go.setOnClickListener(v -> host.command("waypoint", Ui.args("x", d.optInt("x"), "y", d.optInt("y"), "label", "Base"), "Waypoint set"));
            btns.addView(go, Ui.weight(1));
            if (!d.optBoolean("safehouse")) {
                TextView move = Ui.action(c, t, "home", "Move here");
                move.setOnClickListener(v -> confirmMove(d));
                btns.addView(move, Ui.margins(Ui.weight(1), t.dp(8), 0, 0, 0));
                ImageView rm = new ImageView(c);
                rm.setImageDrawable(t.icon("trash", t.text2));
                rm.setScaleType(ImageView.ScaleType.CENTER);
                rm.setBackground(Ui.pressable(t, t.surface2, 14));
                rm.setOnClickListener(v -> confirmRemove());
                btns.addView(rm, Ui.margins(Ui.lp(t.dp(54), t.dp(54)), t.dp(8), 0, 0, 0));
            }
            head.addView(btns);
            col.addView(head);
            updateDistance();

            JSONObject seen = d.optJSONObject("seen");
            TextView when = Ui.text(c, t, seenText(d, seen), 13.5f, seen != null && seen.optBoolean("live") ? t.good : t.text3, true);
            when.setPadding(t.dp(4), 0, t.dp(4), 0);
            col.addView(when, Ui.margins(Ui.lp(-1, -2), 0, t.dp(14), 0, t.dp(10)));
            if (seen == null) return;

            LinearLayout power = Ui.card(c, t);
            LinearLayout pr = Ui.row(c);
            boolean on = seen.optBoolean("power");
            pr.addView(Ui.icon(c, t, "bolt", on ? t.accent : t.text3, 22));
            pr.addView(Ui.text(c, t, "Electricity", 15, t.text, true), Ui.margins(Ui.weight(1), t.dp(10), 0, 0, 0));
            pr.addView(Ui.tag(c, t, on ? "On" : "Off", on ? "good" : ""));
            power.addView(pr);
            col.addView(power);

            JSONArray gens = Json.arr(seen.opt("gens"));
            if (gens.length() > 0) {
                LinearLayout card = Ui.card(c, t);
                Ui.cardHead(c, t, card, gens.length() > 1 ? "Generators" : "Generator");
                for (int i = 0; i < gens.length(); i++) {
                    JSONObject g = gens.optJSONObject(i);
                    if (g == null) continue;
                    LinearLayout r = Ui.row(c);
                    r.addView(Ui.text(c, t, "Fuel", 14.5f, t.text2, true), Ui.weight(1));
                    r.addView(Ui.tag(c, t, g.optBoolean("on") ? "Running" : "Off", g.optBoolean("on") ? "good" : ""), Ui.margins(Ui.lp(-2, -2), 0, 0, t.dp(8), 0));
                    r.addView(Ui.text(c, t, g.optInt("fuel") + "%", 15, t.text, true));
                    card.addView(r, Ui.margins(Ui.lp(-1, -2), 0, i == 0 ? 0 : t.dp(14), 0, 0));
                    Widgets.Bar bar = new Widgets.Bar(c, t.surface3);
                    double f = g.optDouble("fuel") / 100;
                    bar.set(f, f < 0.15 ? t.bad : f < 0.35 ? t.warn : t.good);
                    card.addView(bar, Ui.margins(Ui.lp(-1, t.dp(10)), 0, t.dp(7), 0, t.dp(6)));
                    int cond = g.optInt("cond");
                    card.addView(Ui.text(c, t, "Condition " + cond + "%", 13, cond < 30 ? t.bad : t.text3, false));
                }
                col.addView(card, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));
            }

            JSONObject barrels = seen.optJSONObject("barrels");
            if (barrels != null) {
                LinearLayout card = Ui.card(c, t);
                TextView[] h = Ui.cardHead(c, t, card, "Rain collectors");
                h[1].setText(String.valueOf(barrels.optInt("n")));
                LinearLayout r = Ui.row(c);
                r.addView(Ui.icon(c, t, "water", 0xFF5AA9E6, 20));
                r.addView(Ui.text(c, t, "Water", 14.5f, t.text2, true), Ui.margins(Ui.weight(1), t.dp(10), 0, 0, 0));
                r.addView(Ui.text(c, t, Fmt.pct(barrels.optDouble("pct")) + "% full", 15, t.text, true));
                card.addView(r);
                Widgets.Bar bar = new Widgets.Bar(c, t.surface3);
                bar.set(barrels.optDouble("pct"), 0xFF5AA9E6);
                card.addView(bar, Ui.margins(Ui.lp(-1, t.dp(10)), 0, t.dp(8), 0, 0));
                col.addView(card, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));
            }

            JSONArray crops = Json.arr(seen.opt("crops"));
            if (crops.length() > 0) {
                LinearLayout card = Ui.card(c, t);
                Ui.cardHead(c, t, card, "Crops");
                for (int i = 0; i < crops.length(); i++) {
                    JSONObject g = crops.optJSONObject(i);
                    if (g == null) continue;
                    LinearLayout r = Ui.row(c);
                    r.addView(Ui.icon(c, t, "plant", t.good, 20));
                    r.addView(Ui.ellipsize(Ui.text(c, t, g.optString("n") + "  ×" + g.optInt("cnt"), 15, t.text, true)), Ui.margins(Ui.weight(1), t.dp(10), 0, 0, 0));
                    card.addView(r, Ui.margins(Ui.lp(-1, -2), 0, i == 0 ? 0 : t.dp(14), 0, 0));
                    Widgets.Flow tags = new Widgets.Flow(c, t.dp(6), t.dp(6));
                    if (g.optInt("ready") > 0) tags.addView(Ui.tag(c, t, g.optInt("ready") + " ready to harvest", "good"));
                    if (g.optInt("dry") > 0) tags.addView(Ui.tag(c, t, g.optInt("dry") + " need water", "warn"));
                    if (g.optInt("sick") > 0) tags.addView(Ui.tag(c, t, g.optInt("sick") + " sick", "bad"));
                    if (g.optInt("dead") > 0) tags.addView(Ui.tag(c, t, g.optInt("dead") + " dead", "bad"));
                    String words = g.optString("waterWord", "") + (g.has("healthWord") ? " · " + g.optString("healthWord") : "");
                    if (!words.isEmpty() && !words.startsWith(" ·")) tags.addView(Ui.tag(c, t, words, ""));
                    card.addView(tags, Ui.margins(Ui.lp(-1, -2), t.dp(30), t.dp(6), 0, 0));
                }
                col.addView(card, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));
            }
            if (gens.length() == 0 && barrels == null && crops.length() == 0) {
                TextView none = Ui.text(c, t, "No generators, rain collectors or crops around it yet.", 14, t.text3, false);
                none.setPadding(t.dp(4), t.dp(12), t.dp(4), 0);
                col.addView(none);
            }
        }

        /** "Live", or how long ago the character last saw it (rough without a watch). */
        private String seenText(JSONObject d, JSONObject seen) {
            if (seen == null) return "Go back there once and Knox Link will remember what you see.";
            if (seen.optBoolean("live")) return "You're here: this is live.";
            double h = d.optDouble("ago", 0);
            if (!clock) {
                if (h < 12) return "Seen earlier today";
                if (h < 36) return "Seen about a day ago";
                return "Seen " + Math.round(h / 24) + " days ago";
            }
            if (h < 1) return "Seen less than an hour ago";
            if (h < 48) return "Seen " + Math.round(h) + " hours ago";
            return "Seen " + Math.round(h / 24) + " days ago";
        }

        private void confirmMove(JSONObject d) {
            Context c = getContext();
            LinearLayout sheet = Ui.col(c);
            sheet.addView(Ui.text(c, t, "Move your base here?", 20, t.text, true));
            sheet.addView(Ui.text(c, t, "Knox Link will look after the place you're standing in instead, and forget what it saw at the old one.", 14, t.text3, false),
                    Ui.margins(Ui.lp(-1, -2), 0, t.dp(6), 0, t.dp(14)));
            TextView yes = Ui.action(c, t, "home", "Move it here");
            yes.setOnClickListener(v -> {
                JSONObject args = new JSONObject();
                if (!d.optString("n", "").isEmpty()) args = Ui.args("n", d.optString("n"));
                host.command("baseSet", args, "Base moved");
                host.closeSheet();
            });
            sheet.addView(yes, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
            TextView no = Ui.action(c, t, "x", "Keep it where it is");
            no.setOnClickListener(v -> host.closeSheet());
            sheet.addView(no);
            host.openSheet(sheet);
        }

        private void confirmRemove() {
            Context c = getContext();
            LinearLayout sheet = Ui.col(c);
            sheet.addView(Ui.text(c, t, "Forget this base?", 20, t.text, true));
            sheet.addView(Ui.text(c, t, "Only Knox Link forgets it. Nothing changes in your game.", 14, t.text3, false), Ui.margins(Ui.lp(-2, -2), 0, t.dp(6), 0, t.dp(14)));
            TextView yes = Ui.action(c, t, "trash", "Forget it");
            yes.setTextColor(t.bad);
            yes.setOnClickListener(v -> { host.command("baseClear", new JSONObject(), "Base forgotten"); host.closeSheet(); });
            sheet.addView(yes, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
            TextView no = Ui.action(c, t, "x", "Keep it");
            no.setOnClickListener(v -> host.closeSheet());
            sheet.addView(no);
            host.openSheet(sheet);
        }
    }

    // =================================================================== Settings

    static final class Settings extends ScrollView {
        interface Changed {
            void onSettingsChanged(boolean rebuild);

            void onServerChanged();

            void closeApp();
        }

        private final Host host;
        private final LinearLayout fpsRow, gameCard, modeRow;
        private final View guideRow, bodiesRow, restRow, fgRow;
        private final Switch guideSwitch, autoSwitch, bodiesSwitch, restSwitch, fgSwitch;
        private String shownMode = "";
        private static final String[][] MODES = {{"cool", "Cool"}, {"balanced", "Balanced"}, {"smooth", "Smooth"}};
        private final TextView tempText;
        private int shownCap = -1;
        private String shownTemps = "";
        // 30 is for the 3D view (Project Viewpoint) on a handheld: steady and cool
        private static final int[] CAPS = {30, 60, 90, 120};

        /** The game's frame-rate cap and the device's temperatures, from the core message. */
        void perf(JSONObject core) {
            JSONObject p = core == null ? null : core.optJSONObject("perf");
            Theme t = host.theme();
            if (p == null) return;
            gameCard.setVisibility(VISIBLE);
            guideRow.setVisibility(p.has("guide") ? VISIBLE : GONE);
            if (p.has("guide") && guideSwitch.isChecked() != p.optBoolean("guide")) {
                guideSwitch.setTag(Boolean.TRUE);   // shown from the game, not a tap
                guideSwitch.setChecked(p.optBoolean("guide"));
                guideSwitch.setTag(null);
            }
            String mode = p.optString("mode", "");
            ((View) modeRow.getParent()).setVisibility(p.has("mode") ? VISIBLE : GONE);
            ((View) autoSwitch.getParent()).setVisibility(p.has("mode") ? VISIBLE : GONE);
            if (!mode.equals(shownMode)) {
                shownMode = mode;
                for (int i = 0; i < modeRow.getChildCount(); i++) {
                    TextView b = (TextView) modeRow.getChildAt(i);
                    boolean on = MODES[i][0].equals(mode);
                    b.setTextColor(on ? t.text : t.text2);
                    b.setBackground(on ? t.round(t.isLight() ? 0xFFFFFFFF : t.surface, 9) : null);
                }
            }
            if (autoSwitch.isChecked() != p.optBoolean("auto")) {
                autoSwitch.setTag(Boolean.TRUE);
                autoSwitch.setChecked(p.optBoolean("auto"));
                autoSwitch.setTag(null);
            }
            bodiesRow.setVisibility(p.has("bodies") ? VISIBLE : GONE);   // only with Project Viewpoint
            if (p.has("bodies") && bodiesSwitch.isChecked() != p.optBoolean("bodies")) {
                bodiesSwitch.setTag(Boolean.TRUE);
                bodiesSwitch.setChecked(p.optBoolean("bodies"));
                bodiesSwitch.setTag(null);
            }
            restRow.setVisibility(p.has("rest") ? VISIBLE : GONE);
            if (p.has("rest") && restSwitch.isChecked() != p.optBoolean("rest")) {
                restSwitch.setTag(Boolean.TRUE);
                restSwitch.setChecked(p.optBoolean("rest"));
                restSwitch.setTag(null);
            }
            fgRow.setVisibility(p.has("fg") ? VISIBLE : GONE);   // only where Project Viewpoint's frame generation runs
            if (p.has("fg") && fgSwitch.isChecked() != p.optBoolean("fg")) {
                fgSwitch.setTag(Boolean.TRUE);
                fgSwitch.setChecked(p.optBoolean("fg"));
                fgSwitch.setTag(null);
            }
            int cap = p.optBoolean("unc") ? 0 : p.optInt("cap", 0);
            if (cap != shownCap) {
                shownCap = cap;
                for (int i = 0; i < fpsRow.getChildCount(); i++) {
                    TextView b = (TextView) fpsRow.getChildAt(i);
                    boolean on = CAPS[i] == cap;
                    b.setTextColor(on ? t.text : t.text2);
                    b.setBackground(on ? t.round(t.isLight() ? 0xFFFFFFFF : t.surface, 9) : null);
                }
            }
            List<String> parts = new ArrayList<>();
            if (p.has("cpu")) parts.add("Chip " + Fmt.temp(p.optDouble("cpu")));
            if (p.has("gpu")) parts.add("graphics " + Fmt.temp(p.optDouble("gpu")));
            if (p.has("bat")) parts.add("battery " + Fmt.temp(p.optDouble("bat")));
            if (p.has("held")) parts.add(0, "Held at " + p.optInt("held") + " fps to cool down");
            String s = parts.isEmpty() ? "" : String.join(" · ", parts);
            if (!s.equals(shownTemps)) {
                shownTemps = s;
                tempText.setText(s.isEmpty() ? "Higher frame rates look smoother but make the device warmer." : s);
                // a phone chip's hottest core runs at 80-90 under load; it slows itself down near 100
                double cpu = p.optDouble("cpu", 0);
                tempText.setTextColor(cpu >= 97 ? t.bad : cpu >= 92 ? t.warn : t.text3);
            }
        }

        Settings(Context c, Host host, Changed changed) {
            super(c);
            this.host = host;
            Theme t = host.theme();
            setVerticalScrollBarEnabled(false);
            LinearLayout col = Ui.col(c);
            col.setPadding(t.dp(12), t.dp(12), t.dp(12), t.dp(20));
            addView(col);

            // the game's own frame-rate cap, changed from here; and how warm the device is
            LinearLayout game = Ui.card(c, t);
            Ui.cardHead(c, t, game, "Game");
            // one tap: the frame rate (and how far the 3D furniture reaches) for cool, balanced or smooth play
            LinearLayout rm = Ui.row(c);
            rm.setMinimumHeight(t.dp(52));
            rm.addView(Ui.text(c, t, "Mode", 15, t.text, true), Ui.weight(1));
            modeRow = Ui.row(c);
            modeRow.setBackground(t.round(t.surface2, 12));
            modeRow.setPadding(t.dp(4), t.dp(4), t.dp(4), t.dp(4));
            for (String[] m : MODES) {
                TextView b = Ui.text(c, t, m[1], 14, t.text2, true);
                b.setGravity(Gravity.CENTER);
                b.setPadding(t.dp(12), t.dp(9), t.dp(12), t.dp(9));
                b.setOnClickListener(v -> host.command("perfMode", Ui.args("mode", m[0]), m[1]));
                modeRow.addView(b);
            }
            rm.addView(modeRow);
            rm.setVisibility(GONE);
            game.addView(rm);
            LinearLayout ra = Ui.row(c);
            ra.setMinimumHeight(t.dp(52));
            ra.addView(Ui.text(c, t, "Cool down automatically when hot", 15, t.text, true), Ui.weight(1));
            autoSwitch = switchView(c, t);
            autoSwitch.setOnCheckedChangeListener((v, on) -> {
                if (v.getTag() == null) host.command("perfMode", Ui.args("auto", on), null);
            });
            ra.addView(autoSwitch);
            ra.setVisibility(GONE);
            game.addView(ra);
            // Project Viewpoint's lighter presets draw dead bodies as flat pictures: the nearest few in 3D, cheaply
            LinearLayout rb = Ui.row(c);
            rb.setMinimumHeight(t.dp(56));
            LinearLayout rbText = Ui.col(c);
            rbText.addView(Ui.text(c, t, "Dead bodies in 3D", 15, t.text, true));
            rbText.addView(Ui.text(c, t, "The nearest few in front of you", 12.5f, t.text3, false), Ui.margins(Ui.lp(-2, -2), 0, t.dp(2), 0, 0));
            rb.addView(rbText, Ui.weight(1));
            bodiesSwitch = switchView(c, t);
            bodiesSwitch.setOnCheckedChangeListener((v, on) -> {
                if (v.getTag() == null) host.command("perfMode", Ui.args("bodies", on), on ? "Bodies in 3D" : "Flat bodies");
            });
            rb.addView(bodiesSwitch);
            rb.setVisibility(GONE);
            bodiesRow = rb;
            game.addView(rb);
            // a paused game keeps drawing the same picture at full speed: 30 fps until it runs again
            LinearLayout rr = Ui.row(c);
            rr.setMinimumHeight(t.dp(56));
            LinearLayout rrText = Ui.col(c);
            rrText.addView(Ui.text(c, t, "Rest while paused", 15, t.text, true));
            rrText.addView(Ui.text(c, t, "30 fps until you unpause, to stay cool", 12.5f, t.text3, false), Ui.margins(Ui.lp(-2, -2), 0, t.dp(2), 0, 0));
            rr.addView(rrText, Ui.weight(1));
            restSwitch = switchView(c, t);
            restSwitch.setOnCheckedChangeListener((v, on) -> {
                if (v.getTag() == null) host.command("perfMode", Ui.args("rest", on), null);
            });
            rr.addView(restSwitch);
            rr.setVisibility(GONE);
            restRow = rr;
            game.addView(rr);
            // Project Viewpoint's frame generation: a made-up frame between every two real ones
            LinearLayout rf = Ui.row(c);
            rf.setMinimumHeight(t.dp(56));
            LinearLayout rfText = Ui.col(c);
            rfText.addView(Ui.text(c, t, "Frame generation", 15, t.text, true));
            rfText.addView(Ui.text(c, t, "Smoother motion, a warmer device", 12.5f, t.text3, false), Ui.margins(Ui.lp(-2, -2), 0, t.dp(2), 0, 0));
            rf.addView(rfText, Ui.weight(1));
            fgSwitch = switchView(c, t);
            fgSwitch.setOnCheckedChangeListener((v, on) -> {
                if (v.getTag() == null) host.command("perfMode", Ui.args("fg", on), on ? "Frame generation on" : "Frame generation off");
            });
            rf.addView(fgSwitch);
            rf.setVisibility(GONE);
            fgRow = rf;
            game.addView(rf);
            LinearLayout r0 = Ui.row(c);
            r0.setMinimumHeight(t.dp(52));
            r0.addView(Ui.text(c, t, "Frame rate", 15, t.text, true), Ui.weight(1));
            fpsRow = Ui.row(c);
            fpsRow.setBackground(t.round(t.surface2, 12));
            fpsRow.setPadding(t.dp(4), t.dp(4), t.dp(4), t.dp(4));
            for (int cap : CAPS) {
                TextView b = Ui.text(c, t, String.valueOf(cap), 14, t.text2, true);
                b.setGravity(Gravity.CENTER);
                b.setMinWidth(t.dp(52));
                b.setPadding(t.dp(12), t.dp(9), t.dp(12), t.dp(9));
                b.setOnClickListener(v -> host.command("fps", Ui.args("cap", cap), null));
                fpsRow.addView(b);
            }
            r0.addView(fpsRow);
            game.addView(r0);
            // the game's own option: on a handheld the guide is awkward to close by touch
            LinearLayout rg = Ui.row(c);
            rg.setMinimumHeight(t.dp(52));
            rg.addView(Ui.text(c, t, "Survival guide when a save starts", 15, t.text, true), Ui.weight(1));
            guideSwitch = switchView(c, t);
            guideSwitch.setOnCheckedChangeListener((v, on) -> {
                if (v.getTag() == null) host.command("guide", Ui.args("show", on), null);
            });
            rg.addView(guideSwitch);
            rg.setVisibility(GONE);
            guideRow = rg;
            game.addView(rg);
            tempText = Ui.text(c, t, "", 12.5f, t.text3, false);
            game.addView(tempText, Ui.margins(Ui.lp(-1, -2), 0, t.dp(2), 0, 0));
            game.setVisibility(GONE);
            gameCard = game;
            col.addView(game, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(12)));

            LinearLayout disp = Ui.card(c, t);
            Ui.cardHead(c, t, disp, "Display");
            seg(c, t, host, disp, "Theme", "theme", new String[][]{{"dark", "Dark"}, {"oled", "Midnight"}, {"light", "Light"}}, "dark", changed, true);
            seg(c, t, host, disp, "Map", "map", new String[][]{{"paper", "Paper"}, {"dark", "Dark"}}, "paper", changed, false);
            seg(c, t, host, disp, "Text size", "scale", new String[][]{{"s", "S"}, {"m", "M"}, {"l", "L"}}, "m", changed, true);
            seg(c, t, host, disp, "Units", "units", new String[][]{{"metric", "Metric"}, {"imperial", "Imperial"}}, "imperial", changed, false);
            seg(c, t, host, disp, "Clock", "clock", new String[][]{{"12", "12-hour"}, {"24", "24-hour"}}, "12", changed, false);
            col.addView(disp);

            LinearLayout beh = Ui.card(c, t);
            Ui.cardHead(c, t, beh, "Behaviour");
            toggle(c, t, host, beh, "Alerts for bleeding, hunger and other dangers", "alerts", true, changed);
            toggle(c, t, host, beh, "Vibrate with alerts", "vibrate", true, changed);
            toggle(c, t, host, beh, "Open the car dashboard when you drive", "autoCar", true, changed);
            toggle(c, t, host, beh, "Map place names", "labels", true, changed);
            toggle(c, t, host, beh, "Keep this screen on", "wake", true, changed);
            col.addView(beh, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));

            // dual-screen handhelds: the game on the main screen, Knox Link on the other
            boolean hasGame = c.getPackageManager().getLaunchIntentForPackage(LaunchActivity.GAME) != null;
            if (LaunchActivity.otherDisplay(c, LaunchActivity.mainDisplay(c)) != null || hasGame) {
                LinearLayout dual = Ui.card(c, t);
                Ui.cardHead(c, t, dual, "Dual screen");
                toggle(c, t, host, dual, "Always open on the bottom screen", "bottom", true, changed);
                toggle(c, t, host, dual, "Knox Link's own keyboard (the game keeps the controls)", "ownKeyboard", true, changed);
                if (hasGame) {
                    toggle(c, t, host, dual, "Start the game on the top screen when Knox Link opens", "autoGame", true, changed);
                    toggle(c, t, host, dual, "Open Knox Link when the game starts", "watch", true, changed);
                    toggle(c, t, host, dual, "Close Knox Link when the game closes", "closeWithGame", true, changed);
                    // Android may stop a background app while the game loads (it needs a lot of memory),
                    // and then won't let the game watcher start again on its own
                    android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
                    if (pm != null && !pm.isIgnoringBatteryOptimizations(c.getPackageName())) {
                        TextView why = Ui.text(c, t, "So Knox Link stays ready while the game loads, let it run in the background.", 12.5f, t.text3, false);
                        dual.addView(why, Ui.margins(Ui.lp(-1, -2), 0, t.dp(6), 0, t.dp(8)));
                        TextView a = Ui.action(c, t, "chevron", "Let Knox Link stay ready");
                        a.setOnClickListener(v -> openSettings(c, new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:" + c.getPackageName()))));
                        dual.addView(a, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
                    }
                    if (!GameWatch.allowed(c)) {
                        TextView why = Ui.text(c, t, "For that, Android needs to let Knox Link see which app is in front and open over other apps.", 12.5f, t.text3, false);
                        dual.addView(why, Ui.margins(Ui.lp(-1, -2), 0, t.dp(6), 0, t.dp(8)));
                        if (!GameWatch.hasUsageAccess(c)) {
                            TextView a = Ui.action(c, t, "chevron", "Allow usage access");
                            a.setOnClickListener(v -> openSettings(c, new android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)));
                            dual.addView(a, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
                        }
                        if (!android.provider.Settings.canDrawOverlays(c)) {
                            TextView a = Ui.action(c, t, "chevron", "Allow display over other apps");
                            a.setOnClickListener(v -> openSettings(c, new android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:" + c.getPackageName()))));
                            dual.addView(a);
                        }
                    }
                }
                col.addView(dual, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));
            }

            LinearLayout conn = Ui.card(c, t);
            Ui.cardHead(c, t, conn, "Connection");
            conn.addView(Ui.text(c, t, "Game address", 15, t.text, true));
            TextView hint = Ui.text(c, t, "127.0.0.1:7878 when the game runs on this device. For a game on a PC, use the address shown in the game (F8).", 12.5f, t.text3, false);
            conn.addView(hint, Ui.margins(Ui.lp(-1, -2), 0, t.dp(4), 0, t.dp(8)));
            LinearLayout r = Ui.row(c);
            EditText addr = new EditText(c);
            addr.setText(host.prefs().getString("server", "127.0.0.1:7878"));
            addr.setTextColor(t.text);
            addr.setTextSize(t.sp(16));
            addr.setSingleLine(true);
            addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            addr.setBackground(t.round(t.surface2, 12));
            addr.setPadding(t.dp(12), t.dp(10), t.dp(12), t.dp(10));
            r.addView(addr, Ui.weight(1));
            TextView save = Ui.action(c, t, null, "Connect");
            save.setGravity(Gravity.CENTER);
            save.setBackground(Ui.pressable(t, t.accent, 12));
            save.setTextColor(t.accentInk);
            save.setOnClickListener(v -> {
                host.prefs().edit().putString("server", addr.getText().toString().trim()).apply();
                changed.onServerChanged();
                host.toast("Connecting to " + addr.getText().toString().trim(), false);
            });
            r.addView(save, Ui.margins(Ui.lp(t.dp(120), t.dp(48)), t.dp(10), 0, 0, 0));
            conn.addView(r);
            TextView pin = Ui.action(c, t, "x", "Enter a different PIN");
            pin.setOnClickListener(v -> {
                host.prefs().edit().remove("pin").apply();
                changed.onServerChanged();
            });
            conn.addView(pin, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));
            col.addView(conn, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));

            TextView close = Ui.action(c, t, "x", "Close Knox Link");
            close.setOnClickListener(v -> changed.closeApp());
            col.addView(close, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));

            TextView about =Ui.text(c, t, "Knox Link 1.0.0 · open source · artwork is read from your own copy of Project Zomboid · not affiliated with The Indie Stone", 12, t.text3, false);
            about.setGravity(Gravity.CENTER);
            col.addView(about, Ui.margins(Ui.lp(-1, -2), t.dp(8), t.dp(16), t.dp(8), 0));
        }

        private static void openSettings(Context c, android.content.Intent i) {
            try {
                c.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        }

        private static void seg(Context c, Theme t, Host host, LinearLayout card, String label, String key, String[][] opts,
                                String def, Changed changed, boolean rebuild) {
            LinearLayout r = Ui.row(c);
            r.setMinimumHeight(t.dp(52));
            r.addView(Ui.text(c, t, label, 15, t.text, true), Ui.weight(1));
            LinearLayout seg = Ui.row(c);
            seg.setBackground(t.round(t.surface2, 12));
            seg.setPadding(t.dp(4), t.dp(4), t.dp(4), t.dp(4));
            String cur = host.prefs().getString(key, def);
            for (String[] o : opts) {
                TextView b = Ui.text(c, t, o[1], 14, o[0].equals(cur) ? t.text : t.text2, true);
                b.setGravity(Gravity.CENTER);
                b.setMinWidth(t.dp(52));
                b.setPadding(t.dp(12), t.dp(9), t.dp(12), t.dp(9));
                if (o[0].equals(cur)) b.setBackground(t.round(t.isLight() ? 0xFFFFFFFF : t.surface, 9));
                b.setOnClickListener(v -> {
                    host.prefs().edit().putString(key, o[0]).apply();
                    for (int i = 0; i < seg.getChildCount(); i++) {
                        TextView x = (TextView) seg.getChildAt(i);
                        boolean on = x == b;
                        x.setTextColor(on ? t.text : t.text2);
                        x.setBackground(on ? t.round(t.isLight() ? 0xFFFFFFFF : t.surface, 9) : null);
                    }
                    changed.onSettingsChanged(rebuild);
                });
                seg.addView(b);
            }
            r.addView(seg);
            card.addView(r);
        }

        private static Switch switchView(Context c, Theme t) {
            Switch s = new Switch(c);
            int[][] states = {{android.R.attr.state_checked}, {}};
            s.setThumbTintList(new ColorStateList(states, new int[]{0xFFFFFFFF, 0xFFFFFFFF}));
            s.setTrackTintList(new ColorStateList(states, new int[]{t.accent, t.surface3}));
            return s;
        }

        private static void toggle(Context c, Theme t, Host host, LinearLayout card, String label, String key, boolean def, Changed changed) {
            LinearLayout r = Ui.row(c);
            r.setMinimumHeight(t.dp(52));
            r.addView(Ui.text(c, t, label, 15, t.text, true), Ui.weight(1));
            Switch s = switchView(c, t);
            s.setChecked(host.prefs().getBoolean(key, def));
            s.setOnCheckedChangeListener((v, on) -> {
                host.prefs().edit().putBoolean(key, on).apply();
                changed.onSettingsChanged(false);
            });
            r.addView(s);
            card.addView(r);
        }
    }

    /** Lists from Lua can arrive as {} when empty; this accepts both shapes. */
    static final class Json {
        static JSONArray arr(Object o) {
            if (o instanceof JSONArray) return (JSONArray) o;
            JSONArray a = new JSONArray();
            if (o instanceof JSONObject) {
                JSONObject j = (JSONObject) o;
                java.util.Iterator<String> it = j.keys();
                List<String> keys = new ArrayList<>();
                while (it.hasNext()) keys.add(it.next());
                keys.sort((x, y) -> {
                    try { return Integer.compare(Integer.parseInt(x), Integer.parseInt(y)); } catch (Exception e) { return x.compareTo(y); }
                });
                for (String k : keys) a.put(j.opt(k));
            }
            return a;
        }
    }

    static ViewGroup.LayoutParams match() {
        return new ViewGroup.LayoutParams(-1, -1);
    }
}
