package io.github.knoxlink.companion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * The armoury: the character's weapons and their stats, and a gunsmith for the guns, in the spirit of
 * the old Call of Duty loadouts and BattleBit's weapon customization: the gun big in the middle, its
 * attachment slots around it, a part picked, the stats moving. Melee weapons and throwables get the
 * same stage with their own numbers. Data from KnoxLink_Weapons.lua ("weapons"); the parts go on and off
 * through the game's own actions.
 */
final class WeaponsScreen extends FrameLayout {
    private final Host host;
    private final Theme t;
    private final ScrollView listScroll, detailScroll;
    private final LinearLayout list, detail;
    private JSONObject data;
    private String sig = "";
    // what's open: a weapon's id and its kind ("gun", "melee", "throw"); the gunsmith's slot and previewed part
    private long openId = -1;
    private String openKind;
    private int slotSel = 0, partSel = -2;   // partSel: -1 "None", -2 what's on the gun, else an index in the slot's parts
    // the row of parts keeps its place when the page is drawn again
    private HorizontalScrollView tilesView;
    private int tilesX;

    private static final String[][] SLOT_NAMES = {{"Scope", "Optic"}, {"Canon", "Barrel"}, {"RecoilPad", "Recoil pad"}, {"Sling", "Sling"}};

    WeaponsScreen(Context c, Host host) {
        super(c);
        this.host = host;
        t = host.theme();
        listScroll = new ScrollView(c);
        listScroll.setVerticalScrollBarEnabled(false);
        list = Ui.col(c);
        list.setPadding(t.dp(12), t.dp(12), t.dp(12), t.dp(20));
        listScroll.addView(list);
        addView(listScroll, new LayoutParams(-1, -1));
        detailScroll = new ScrollView(c);
        detailScroll.setVerticalScrollBarEnabled(false);
        detail = Ui.col(c);
        detail.setPadding(t.dp(12), t.dp(8), t.dp(12), t.dp(20));
        detailScroll.addView(detail);
        detailScroll.setVisibility(GONE);
        addView(detailScroll, new LayoutParams(-1, -1));
        list.addView(Ui.text(c, t, "Looking at your weapons…", 14, t.text3, false));
    }

    static String slotName(String k) {
        for (String[] s : SLOT_NAMES) if (s[0].equals(k)) return s[1];
        return k;
    }

    void weapons(JSONObject d) {
        String s = d.toString();
        if (s.equals(sig)) return;
        sig = s;
        data = d;
        renderList();
        if (openId >= 0) {
            if (tilesView != null) tilesX = tilesView.getScrollX();
            renderDetail();
        }
    }

    private void pick(int part, HorizontalScrollView row) {
        partSel = part;
        tilesX = row.getScrollX();
        renderDetail();
    }

    /** Back: from a weapon to the list. True when it went back. */
    boolean back() {
        if (detailScroll.getVisibility() != VISIBLE) return false;
        openId = -1;
        detailScroll.setVisibility(GONE);
        listScroll.setVisibility(VISIBLE);
        return true;
    }

    // ------------------------------------------------------------------ the list

    private void renderList() {
        Context c = getContext();
        list.removeAllViews();
        JSONObject d = data;
        if (d == null) return;
        LinearLayout head = Ui.row(c);
        head.addView(Ui.text(c, t, "Weapons", 20, t.text, true), Ui.weight(1));
        boolean sd = d.optBoolean("screwdriver");
        TextView chip = Ui.tag(c, t, sd ? "✓ Screwdriver" : "No screwdriver", sd ? "good" : "warn");
        chip.setOnClickListener(v -> host.toast(sd ? "You have a screwdriver: attachments can go on and off."
                : "You need a screwdriver to change attachments, like in the game.", !sd));
        head.addView(chip);
        list.addView(head, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(12)));

        section(c, "Guns");
        JSONArray guns = Screens.Json.arr(d.opt("guns"));
        if (guns.length() == 0) list.addView(none(c, "No guns on you."));
        for (int i = 0; i < guns.length(); i++) list.addView(card(c, guns.optJSONObject(i), "gun"), cardLp());
        section(c, "Melee");
        JSONArray melee = Screens.Json.arr(d.opt("melee"));
        if (melee.length() == 0) list.addView(none(c, "No melee weapons on you."));
        for (int i = 0; i < melee.length(); i++) list.addView(card(c, melee.optJSONObject(i), "melee"), cardLp());
        section(c, "Throwables");
        JSONArray th = Screens.Json.arr(d.opt("throw"));
        if (th.length() == 0) list.addView(none(c, "Nothing to throw."));
        String group = null;
        for (int i = 0; i < th.length(); i++) {
            JSONObject o = th.optJSONObject(i);
            if (o == null) continue;
            String kind = "lethal".equals(o.optString("kind")) ? "Lethal" : "Tactical";
            if (!kind.equals(group)) {
                group = kind;
                list.addView(Ui.text(c, t, kind, 13, t.text2, true), Ui.margins(Ui.lp(-2, -2), t.dp(2), t.dp(4), 0, t.dp(6)));
            }
            list.addView(throwRow(c, o), cardLp());
        }
        JSONArray seen = Screens.Json.arr(d.opt("seen"));
        if (seen.length() > 0) {
            section(c, "Seen elsewhere");
            for (int i = 0; i < seen.length(); i++) list.addView(seenRow(c, seen.optJSONObject(i)), cardLp());
        }
    }

    private LinearLayout.LayoutParams cardLp() {
        return Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8));
    }

    private void section(Context c, String s) {
        list.addView(Ui.label(c, t, s), Ui.margins(Ui.lp(-1, -2), t.dp(2), t.dp(10), 0, t.dp(8)));
    }

    private TextView none(Context c, String s) {
        TextView v = Ui.text(c, t, s, 14, t.text3, false);
        v.setPadding(t.dp(4), 0, 0, t.dp(6));
        return v;
    }

    /** The game's icon of an item, big and crisp (pixel art), on a dark panel. */
    private View artPanel(Context c, String ic, int wDp, int hDp) {
        FrameLayout f = new FrameLayout(c);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{0xFF2A2F37, 0xFF121418});
        g.setCornerRadius(t.pz ? 0 : t.dpf(12));
        if (t.pz) g.setStroke(t.dp(1), 0xFF3A3A3A);
        f.setBackground(g);
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(t.dp(8), t.dp(6), t.dp(8), t.dp(6));
        f.addView(iv, new LayoutParams(-1, -1));
        crisp(iv, ic);
        f.setLayoutParams(new LinearLayout.LayoutParams(t.dp(wDp), t.dp(hDp)));
        return f;
    }

    private void crisp(ImageView iv, String ic) {
        iv.setImageDrawable(t.icon("weapon", t.text3));
        if (ic == null || ic.isEmpty()) return;
        Bitmap hit = host.art().peek("i", ic);
        if (hit != null) iv.setImageDrawable(pixels(hit));
        else host.art().icon(ic, b -> { if (b != null) iv.setImageDrawable(pixels(b)); });
    }

    private android.graphics.drawable.BitmapDrawable pixels(Bitmap b) {
        android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(getResources(), b);
        d.setFilterBitmap(false);
        d.setAntiAlias(false);
        return d;
    }

    private View card(Context c, JSONObject w, String kind) {
        if (w == null) return new View(c);
        LinearLayout card = Ui.row(c);
        card.setGravity(Gravity.TOP);
        card.setBackground(Ui.pressable(t, t.surface, 14));
        card.setPadding(t.dp(10), t.dp(10), t.dp(12), t.dp(10));
        card.addView(artPanel(c, w.optString("ic"), 112, 74));
        LinearLayout col = Ui.col(c);
        LinearLayout top = Ui.row(c);
        top.addView(Ui.ellipsize(Ui.text(c, t, w.optString("n"), 16, w.optBoolean("broken") ? t.bad : t.text, true)), Ui.weight(1));
        String where = "p".equals(w.optString("eq")) ? "In your hands" : "s".equals(w.optString("eq")) ? "Off hand" : w.optString("slot", "");
        if (!where.isEmpty()) top.addView(Ui.tag(c, t, where, w.has("eq") ? "acc" : ""), Ui.margins(Ui.lp(-2, -2), t.dp(6), 0, 0, 0));
        col.addView(top);
        JSONObject st = w.optJSONObject("stats");
        JSONObject max = data == null ? null : data.optJSONObject("max");
        if ("gun".equals(kind)) {
            JSONObject a = w.optJSONObject("ammo");
            if (a != null) col.addView(Ui.ellipsize(Ui.text(c, t, ammoShort(a), 12.5f, t.text3, false)), Ui.margins(Ui.lp(-1, -2), 0, t.dp(3), 0, 0));
            // the attachments, one small tile a slot
            LinearLayout slots = Ui.row(c);
            JSONArray sl = Screens.Json.arr(w.opt("slots"));
            for (int i = 0; i < sl.length(); i++) {
                JSONObject s = sl.optJSONObject(i);
                JSONObject part = s == null ? null : s.optJSONObject("part");
                View tile = part != null ? Screens.itemIcon(c, host, part.optString("ic"), "plus", 26) : Screens.itemIcon(c, host, null, "plus", 26);
                tile.setAlpha(part != null ? 1f : 0.45f);
                slots.addView(tile, Ui.margins(new LinearLayout.LayoutParams(t.dp(26), t.dp(26)), 0, 0, t.dp(5), 0));
            }
            if (sl.length() > 0) col.addView(slots, Ui.margins(Ui.lp(-2, -2), 0, t.dp(6), 0, 0));
            JSONObject gm = max == null ? null : max.optJSONObject("gun");
            if (st != null) {
                col.addView(mini(c, "Damage", st.optDouble("dmg") / nz(gm, "dmg", 2.2)), Ui.margins(Ui.lp(-1, -2), 0, t.dp(6), 0, 0));
                col.addView(mini(c, "Range", st.optDouble("range") / nz(gm, "range", 40)));
                col.addView(mini(c, "Accuracy", st.optDouble("hit") / 100.0));
            }
        } else {
            if (w.has("cond")) col.addView(mini(c, "Condition", w.optDouble("cond")), Ui.margins(Ui.lp(-1, -2), 0, t.dp(6), 0, 0));
            if (w.has("sharp")) col.addView(mini(c, "Sharpness", w.optDouble("sharp") / Math.max(0.01, w.optDouble("sharpMax", 1))));
            if (w.has("head")) col.addView(mini(c, "Head", w.optDouble("head") / Math.max(1, w.optDouble("headMax", 1))));
            JSONObject mm = max == null ? null : max.optJSONObject("melee");
            if (st != null) {
                col.addView(mini(c, "Damage", st.optDouble("dmg") / 3));
                col.addView(mini(c, "Range", st.optDouble("range") / nz(mm, "range", 1.5)));
            }
        }
        card.addView(col, Ui.margins(Ui.weight(1), t.dp(12), 0, 0, 0));
        long id = w.optLong("id");
        card.setOnClickListener(v -> open(id, kind));
        return card;
    }

    private static double nz(JSONObject o, String k, double fallback) {
        double v = o == null ? 0 : o.optDouble(k, 0);
        return v > 0 ? v : fallback;
    }

    private View mini(Context c, String label, double v) {
        LinearLayout r = Ui.row(c);
        TextView l = Ui.text(c, t, label, 11, t.text3, true);
        r.addView(l, new LinearLayout.LayoutParams(t.dp(68), -2));
        Widgets.Bar bar = new Widgets.Bar(c, t.surface3);
        bar.set(v, v >= 0.66 ? t.good : v >= 0.33 ? t.accent : t.warn);
        r.addView(bar, new LinearLayout.LayoutParams(0, t.dp(5), 1));
        r.setPadding(0, t.dp(2), 0, t.dp(2));
        return r;
    }

    private String ammoShort(JSONObject a) {
        StringBuilder s = new StringBuilder();
        s.append(a.optInt("n")).append(" / ").append(a.optInt("max"));
        if (a.optInt("mags") > 0) s.append(" · ").append(a.optInt("mags")).append(a.optInt("mags") == 1 ? " magazine" : " magazines");
        s.append(" · ").append(a.optInt("spare")).append(a.optInt("spare") == 1 ? " round" : " rounds");
        return s.toString();
    }

    private View throwRow(Context c, JSONObject o) {
        LinearLayout r = Ui.row(c);
        r.setBackground(Ui.pressable(t, t.surface, 14));
        r.setPadding(t.dp(10), t.dp(8), t.dp(12), t.dp(8));
        r.addView(artPanel(c, o.optString("ic"), 56, 44));
        LinearLayout col = Ui.col(c);
        int n = o.optInt("cnt", 1);
        col.addView(Ui.ellipsize(Ui.text(c, t, o.optString("n") + (n > 1 ? "  ×" + n : ""), 15, t.text, true)));
        col.addView(Ui.ellipsize(Ui.text(c, t, effects(o), 12.5f, t.text3, false)), Ui.margins(Ui.lp(-1, -2), 0, t.dp(3), 0, 0));
        r.addView(col, Ui.margins(Ui.weight(1), t.dp(12), 0, 0, 0));
        long id = o.optLong("id");
        r.setOnClickListener(v -> open(id, "throw"));
        return r;
    }

    private static String effects(JSONObject o) {
        StringBuilder s = new StringBuilder();
        String[][] e = {{"fire", "Fire"}, {"blast", "Blast"}, {"smoke", "Smoke"}, {"noise", "Noise"}};
        for (String[] x : e) {
            int v = o.optInt(x[0]);
            if (v > 0) s.append(s.length() > 0 ? "   " : "").append(x[1]).append(" · ").append(v).append(" m");
        }
        return s.length() > 0 ? s.toString() : "Throw it";
    }

    private View seenRow(Context c, JSONObject o) {
        LinearLayout r = Ui.row(c);
        r.setBackground(Ui.pressable(t, t.surface, 14));
        r.setPadding(t.dp(10), t.dp(8), t.dp(12), t.dp(8));
        r.addView(Screens.itemIcon(c, host, o.optString("ic"), "weapon", 36));
        LinearLayout col = Ui.col(c);
        int n = o.optInt("c", 1);
        col.addView(Ui.ellipsize(Ui.text(c, t, o.optString("n") + (n > 1 ? "  ×" + n : ""), 15, t.text, true)));
        col.addView(Ui.ellipsize(Ui.text(c, t, whereLine(o), 12.5f, t.text3, false)), Ui.margins(Ui.lp(-1, -2), 0, t.dp(3), 0, 0));
        r.addView(col, Ui.margins(Ui.weight(1), t.dp(10), 0, t.dp(8), 0));
        r.addView(Ui.text(c, t, far(o), 13, t.text2, true));
        r.setOnClickListener(v -> host.showOnMap((float) o.optDouble("x") + 0.5f, (float) o.optDouble("y") + 0.5f, 3f));
        return r;
    }

    static String whereLine(JSONObject o) {
        java.util.List<String> w = new java.util.ArrayList<>();
        for (String k : new String[]{"b", "rm", "town"}) if (!o.optString(k, "").isEmpty()) w.add(o.optString(k));
        if (o.optInt("z") != 0) w.add(o.optInt("z") > 0 ? "upstairs" : "downstairs");
        w.add("seen " + SeenSheet.ago(o.optDouble("h", 0)));
        return TextUtils.join(" · ", w);
    }

    static String far(JSONObject o) {
        double d = o.optDouble("d", 0);
        return d < 3 ? "Here" : (d < 30 ? Fmt.near(d) : Fmt.dist(d)) + (o.has("dir") ? " " + o.optString("dir") : "");
    }

    // ------------------------------------------------------------------ one weapon

    private void open(long id, String kind) {
        openId = id;
        openKind = kind;
        slotSel = 0;
        partSel = -2;
        tilesX = 0;
        tilesView = null;
        renderDetail();
        listScroll.setVisibility(GONE);
        detailScroll.setVisibility(VISIBLE);
        detailScroll.scrollTo(0, 0);
    }

    private JSONObject find(String key, long id) {
        JSONArray a = data == null ? new JSONArray() : Screens.Json.arr(data.opt(key));
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optLong("id") == id) return o;
        }
        return null;
    }

    private void renderDetail() {
        Context c = getContext();
        detail.removeAllViews();
        JSONObject w = find("gun".equals(openKind) ? "guns" : "melee".equals(openKind) ? "melee" : "throw", openId);
        // the bar at the top: back to the list
        LinearLayout bar = Ui.row(c);
        TextView back = Ui.text(c, t, "‹  Weapons", 15, t.accent, true);
        back.setPadding(t.dp(4), t.dp(8), t.dp(12), t.dp(8));
        back.setOnClickListener(v -> back());
        bar.addView(back);
        detail.addView(bar);
        if (w == null) {
            detail.addView(Ui.text(c, t, "That weapon isn't on you anymore.", 14, t.text3, false), Ui.margins(Ui.lp(-2, -2), t.dp(4), t.dp(8), 0, 0));
            return;
        }
        LinearLayout title = Ui.row(c);
        title.addView(Ui.ellipsize(Ui.text(c, t, w.optString("n"), 20, t.text, true)), Ui.weight(1));
        if (w.has("cond")) title.addView(Ui.tag(c, t, "Condition " + Math.round(w.optDouble("cond") * 100) + "%",
                w.optDouble("cond") < 0.34 ? "bad" : w.optDouble("cond") < 0.67 ? "warn" : "good"));
        detail.addView(title, Ui.margins(Ui.lp(-1, -2), t.dp(2), 0, 0, t.dp(8)));
        if ("gun".equals(openKind)) gunsmith(c, w);
        else if ("melee".equals(openKind)) meleeView(c, w);
        else throwView(c, w);
    }

    private void gunsmith(Context c, JSONObject g) {
        JSONArray slots = Screens.Json.arr(g.opt("slots"));
        if (slotSel >= slots.length()) slotSel = 0;
        Stage stage = new Stage(c, g.optString("ic"));
        for (int i = 0; i < slots.length(); i++) {
            JSONObject s = slots.optJSONObject(i);
            JSONObject part = s.optJSONObject("part");
            stage.slot(s.optString("k"), slotName(s.optString("k")), part == null ? null : part.optString("ic"), part == null ? "Empty" : part.optString("n"));
        }
        stage.selected = slotSel;
        stage.listener = i -> {
            slotSel = i;
            partSel = -2;
            tilesX = 0;
            renderDetail();
        };
        detail.addView(stage, Ui.lp(-1, -2));

        JSONObject slot = slots.optJSONObject(slotSel);
        JSONObject on = slot == null ? null : slot.optJSONObject("part");
        JSONArray fits = new JSONArray();
        int onAt = -1;
        JSONArray listed = slot == null ? new JSONArray() : Screens.Json.arr(slot.opt("fits"));
        for (int i = 0; i < listed.length(); i++) {
            JSONObject f = listed.optJSONObject(i);
            if (f == null) continue;
            if (on != null && f.optString("t").equals(on.optString("t"))) onAt = fits.length();
            fits.put(f);
        }
        if (on != null && onAt < 0) {
            // a part the game doesn't list for this gun: it's on it all the same
            onAt = fits.length();
            fits.put(on);
        }
        if (partSel == -2 || partSel >= fits.length()) partSel = onAt;   // what's on the gun is picked first
        JSONObject picked = partSel >= 0 ? fits.optJSONObject(partSel) : null;
        boolean current = partSel == onAt;
        boolean sd = data != null && data.optBoolean("screwdriver");

        if (slot != null) {
            detail.addView(Ui.label(c, t, slotName(slot.optString("k"))), Ui.margins(Ui.lp(-1, -2), t.dp(4), t.dp(12), 0, t.dp(8)));
            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout tiles = Ui.row(c);
            tiles.setGravity(Gravity.TOP);
            tiles.addView(tile(c, null, "None", on == null ? "On the gun" : "", partSel == -1, () -> pick(-1, hs)));
            for (int i = 0; i < fits.length(); i++) {
                JSONObject f = fits.optJSONObject(i);
                final int k = i;
                String badge;
                if (i == onAt) badge = "On the gun";
                else if (f.optInt("have") > 0) badge = "You have it";
                else if (f.optJSONObject("at") != null) badge = "Seen · " + far(f.optJSONObject("at"));
                else badge = "Not found yet";
                View tl = tile(c, f.optString("ic"), f.optString("n"), badge, partSel == k, () -> pick(k, hs));
                if ("Not found yet".equals(badge)) tl.setAlpha(0.5f);
                tiles.addView(tl);
            }
            hs.addView(tiles);
            detail.addView(hs, Ui.lp(-1, -2));
            tilesView = hs;
            int x = tilesX;
            if (x > 0) hs.post(() -> hs.scrollTo(x, 0));
        } else {
            detail.addView(Ui.text(c, t, "No attachments fit this gun.", 14, t.text3, false), Ui.margins(Ui.lp(-2, -2), t.dp(4), t.dp(10), 0, 0));
        }

        // the stats, with what the picked part would change
        JSONObject st = g.optJSONObject("stats");
        JSONObject gm = data == null || data.optJSONObject("max") == null ? null : data.optJSONObject("max").optJSONObject("gun");
        if (st != null) {
            JSONObject pre = preview(st, on == null ? null : on.optJSONObject("mods"), partSel == -1 ? new JSONObject()
                    : picked == null ? null : picked.optJSONObject("mods"));
            LinearLayout box = Ui.card(c, t);
            Ui.cardHead(c, t, box, "Stats");
            double dmax = nz(gm, "dmg", 2.2), rmax = nz(gm, "range", 40), nmax = nz(gm, "noise", 200);
            box.addView(stat(c, "Damage", st, pre, "dmg", v -> v / dmax, v -> fmt(v, 2), false));
            box.addView(stat(c, "Range", st, pre, "range", v -> v / rmax, v -> fmt(v, 1), false));
            box.addView(stat(c, "Accuracy", st, pre, "hit", v -> v / 100, v -> Math.round(v) + "%", false));
            // the game's guns: aiming time 25 to 65 (an x8 scope adds 20), recoil delay 11 to 34, reload time 25 to 30
            box.addView(stat(c, "Aim speed", st, pre, "aim", v -> (90 - v) / 80, v -> "aim time " + Math.round(v), true));
            box.addView(stat(c, "Recoil control", st, pre, "recoil", v -> (40 - v) / 35, v -> "recoil " + fmt(v, 1), true));
            box.addView(stat(c, "Reload speed", st, pre, "reload", v -> (40 - v) / 25, v -> "reload time " + Math.round(v), true));
            box.addView(stat(c, "Sight range", st, pre, "sight", v -> v / 25, v -> fmt(v, 1), false));
            box.addView(stat(c, "Noise", st, pre, "noise", v -> v / nmax, v -> String.valueOf(Math.round(v)), true));
            box.addView(stat(c, "Handling", st, pre, "weight", v -> 1 - v / 6, v -> "weight " + fmt(v, 1), true));
            detail.addView(box, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));
        }

        // the button for the picked part
        if (slot != null && current) {
            TextView hint = Ui.text(c, t, fits.length() == 0 ? "Nothing else fits here." : "Pick a part to see what it changes.", 13.5f, t.text3, false);
            hint.setGravity(Gravity.CENTER);
            detail.addView(hint, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));
        } else if (slot != null) {
            String label;
            Runnable act = null;
            boolean enabled = true;
            String k = slot.optString("k");
            if (partSel == -1) {
                if (!sd) {
                    label = "Needs a screwdriver";
                    enabled = false;
                } else {
                    label = "Take off " + on.optString("n");
                    act = () -> host.command("weaponDetach", Ui.args("w", g.optLong("id"), "k", k), "Taking off");
                }
            } else if (picked != null && picked.optInt("have") > 0) {
                if (!sd) {
                    label = "Needs a screwdriver";
                    enabled = false;
                } else {
                    label = (on == null ? "Attach " : "Swap to ") + picked.optString("n");
                    act = () -> host.command("weaponAttach", Ui.args("w", g.optLong("id"), "p", picked.optLong("id")), on == null ? "Attaching" : "Swapping");
                }
            } else if (picked != null && picked.optJSONObject("at") != null) {
                JSONObject at = picked.optJSONObject("at");
                label = "Show where it is";
                act = () -> host.showOnMap((float) at.optDouble("x") + 0.5f, (float) at.optDouble("y") + 0.5f, 3f);
                TextView where = Ui.text(c, t, picked.optString("n") + ": " + whereLine(at), 13, t.text3, false);
                detail.addView(where, Ui.margins(Ui.lp(-1, -2), t.dp(2), t.dp(10), 0, 0));
            } else {
                label = "Not found yet";
                enabled = false;
            }
            TextView btn = Ui.text(c, t, label, 16, enabled ? t.accentInk : t.text3, true);
            btn.setGravity(Gravity.CENTER);
            btn.setMinHeight(t.dp(50));
            btn.setBackground(Ui.button(t, enabled));
            if (act != null) {
                Runnable a = act;
                btn.setOnClickListener(v -> a.run());
            }
            detail.addView(btn, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));
        }

        // the rounds
        JSONObject a = g.optJSONObject("ammo");
        if (a != null) {
            StringBuilder s = new StringBuilder("In the gun: ").append(a.optInt("n")).append(" / ").append(a.optInt("max"));
            if (a.optBoolean("chambered")) s.append(" (one in the chamber)");
            if (a.has("mag")) s.append("\nMagazine: ").append(a.optString("mag")).append(a.optBoolean("magIn") ? " (in the gun)" : " (not in the gun)");
            s.append("\nCarried: ");
            if (a.has("mag")) s.append(a.optInt("mags")).append(a.optInt("mags") == 1 ? " spare magazine · " : " spare magazines · ");
            s.append(a.optInt("spare")).append(a.optInt("spare") == 1 ? " round" : " rounds").append(a.has("round") ? " of " + a.optString("round") : "");
            LinearLayout box = Ui.card(c, t);
            Ui.cardHead(c, t, box, "Ammo");
            TextView tv = Ui.text(c, t, s.toString(), 14, t.text2, false);
            tv.setLineSpacing(t.dp(3), 1f);
            box.addView(tv);
            detail.addView(box, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));
        }
        actions(c, g, "All actions…");
    }

    /**
     * The gun's numbers with the picked part instead of the slot's, the way the game works them out: a part's
     * numbers are added to the gun's, and an optic's sight range replaces the gun's own. Null when nothing is picked.
     */
    private static JSONObject preview(JSONObject st, JSONObject onMods, JSONObject newMods) {
        if (newMods == null) return null;
        JSONObject p = new JSONObject();
        try {
            for (String k : new String[]{"dmg", "aim", "reload", "recoil", "hit", "range", "weight"})
                p.put(k, st.optDouble(k, 0) - (onMods == null ? 0 : onMods.optDouble(k, 0)) + newMods.optDouble(k, 0));
            if (newMods.optBoolean("optic")) p.put("sight", newMods.optDouble("sight", 0));
            else if (onMods != null && onMods.optBoolean("optic") && st.has("sight0")) p.put("sight", st.optDouble("sight0"));
        } catch (Exception ignored) {
        }
        return p;
    }

    private interface F {
        double apply(double v);
    }

    private interface S {
        String apply(double v);
    }

    private static String fmt(double v, int places) {
        String s = String.format(Locale.US, "%." + places + "f", v);
        return s.indexOf('.') < 0 ? s : s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** One stat: its bar, and with a part picked, the new number and the gain in green or the loss in red. lowerBetter: for the raw numbers. */
    private View stat(Context c, String label, JSONObject st, JSONObject pre, String key, F norm, S text, boolean lowerBetter) {
        double cur = st.optDouble(key, 0);
        boolean changed = pre != null && pre.has(key) && Math.abs(pre.optDouble(key) - cur) > 1e-6;
        double next = changed ? pre.optDouble(key) : cur;
        StatBar b = new StatBar(c, t, label, text.apply(cur), changed ? text.apply(next) : null, clamp(norm.apply(cur)), clamp(norm.apply(next)),
                changed, changed && ((next < cur) == lowerBetter));
        b.setLayoutParams(Ui.margins(Ui.lp(-1, t.dp(34)), 0, t.dp(2), 0, t.dp(2)));
        return b;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private void meleeView(Context c, JSONObject w) {
        detail.addView(new Stage(c, w.optString("ic")), Ui.lp(-1, -2));
        JSONObject st = w.optJSONObject("stats");
        JSONObject mm = data == null || data.optJSONObject("max") == null ? null : data.optJSONObject("max").optJSONObject("melee");
        LinearLayout box = Ui.card(c, t);
        Ui.cardHead(c, t, box, "Stats");
        if (st != null) {
            // the game's melee weapons: damage up to 3 (a katana's 8 is the odd one out), speed 0.7 to 1.4, knockback
            // up to 1, hitting up to 3 or 4 at once; a hit wears them 1 time in 1 to 200 (most 2 to 20), so that bar goes by tens
            double rmax = nz(mm, "range", 1.5), kmax = nz(mm, "knock", 1);
            box.addView(stat(c, "Damage", st, null, "dmg", v -> v / 3, v -> fmt(v, 2), false));
            box.addView(stat(c, "Range", st, null, "range", v -> v / rmax, v -> fmt(v, 2), false));
            box.addView(stat(c, "Speed", st, null, "speed", v -> (v - 0.6) / 0.8, v -> fmt(v, 2), false));
            box.addView(stat(c, "Critical", st, null, "crit", v -> v / 100, v -> Math.round(v) + "%", false));
            box.addView(stat(c, "Knockback", st, null, "knock", v -> v / kmax, v -> fmt(v, 2), false));
            box.addView(stat(c, "Crowd", st, null, "crowd", v -> v / 4, v -> "hits up to " + Math.round(v), false));
            box.addView(stat(c, "Durability", st, null, "endure", v -> Math.log(Math.max(1, v)) / Math.log(60), v -> "wears 1 hit in " + Math.round(v), false));
        }
        detail.addView(box, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));
        LinearLayout wear = Ui.card(c, t);
        Ui.cardHead(c, t, wear, "Wear");
        if (w.has("cond")) wear.addView(level(c, "Condition", w.optDouble("cond")));
        if (w.has("head")) wear.addView(level(c, "Head", w.optDouble("head") / Math.max(1, w.optDouble("headMax", 1))));
        if (w.has("sharp")) wear.addView(level(c, "Sharpness", w.optDouble("sharp") / Math.max(0.01, w.optDouble("sharpMax", 1))));
        detail.addView(wear, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));
        actions(c, w, "Repair or sharpen…");
    }

    private View level(Context c, String label, double v) {
        double cv = clamp(v);
        StatBar b = new StatBar(c, t, label, Math.round(cv * 100) + "%", null, cv, cv, false, false);
        b.setLayoutParams(Ui.margins(Ui.lp(-1, t.dp(34)), 0, t.dp(2), 0, t.dp(2)));
        return b;
    }

    private void throwView(Context c, JSONObject o) {
        detail.addView(new Stage(c, o.optString("ic")), Ui.lp(-1, -2));
        LinearLayout box = Ui.card(c, t);
        Ui.cardHead(c, t, box, "lethal".equals(o.optString("kind")) ? "Lethal" : "Tactical");
        String[][] e = {{"fire", "Fire"}, {"blast", "Blast"}, {"smoke", "Smoke"}, {"noise", "Noise"}};
        for (String[] x : e) {
            int v = o.optInt(x[0]);
            if (v > 0) box.addView(level(c, x[1] + " · " + v + " m", Math.min(1, v / 20.0)));
        }
        if (o.optInt("power") > 0) box.addView(level(c, "Power · " + o.optInt("power"), Math.min(1, o.optInt("power") / 100.0)));
        int n = o.optInt("cnt", 1);
        box.addView(Ui.text(c, t, n == 1 ? "You carry one." : "You carry " + n + ".", 14, t.text2, false), Ui.margins(Ui.lp(-2, -2), 0, t.dp(6), 0, 0));
        detail.addView(box, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));
        actions(c, o, "All actions…");
    }

    private void actions(Context c, JSONObject w, String more) {
        LinearLayout row = Ui.row(c);
        long id = w.optLong("id");
        String name = w.optString("n");
        TextView equip = Ui.action(c, t, "hand", "p".equals(w.optString("eq")) ? "In your hands" : "Equip");
        equip.setOnClickListener(v -> host.command("swap", Ui.args("id", id), "Equipping"));
        row.addView(equip, Ui.weight(1));
        TextView all = Ui.action(c, t, "more", more);
        all.setOnClickListener(v -> GameMenu.open(c, host, name, Ui.args("kind", "item", "ids", new JSONArray().put(id))));
        row.addView(all, Ui.margins(Ui.weight(1), t.dp(8), 0, 0, 0));
        detail.addView(row, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));
    }

    private interface Click {
        void run();
    }

    /** A part to pick: its icon, its name, where it is. */
    private View tile(Context c, String ic, String name, String badge, boolean selected, Click click) {
        LinearLayout v = Ui.col(c);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        android.graphics.drawable.GradientDrawable bg = t.round(t.surface, 12);
        if (selected) bg.setStroke(t.dp(2), t.accent);
        v.setBackground(bg);
        v.setPadding(t.dp(6), t.dp(8), t.dp(6), t.dp(8));
        v.addView(ic == null ? Screens.itemIcon(c, host, null, "x", 42) : Screens.itemIcon(c, host, ic, "box", 42));
        TextView n = Ui.text(c, t, name, 12, t.text, true);
        n.setGravity(Gravity.CENTER);
        n.setMaxLines(2);
        n.setEllipsize(TextUtils.TruncateAt.END);
        v.addView(n, Ui.margins(new LinearLayout.LayoutParams(t.dp(84), -2), 0, t.dp(6), 0, 0));
        if (badge != null && !badge.isEmpty()) {
            boolean good = badge.startsWith("You have") || badge.startsWith("On the");
            TextView b = Ui.text(c, t, badge, 10.5f, good ? t.good : badge.startsWith("Seen") ? t.accent : t.text3, true);
            b.setGravity(Gravity.CENTER);
            b.setMaxLines(2);
            v.addView(b, Ui.margins(new LinearLayout.LayoutParams(t.dp(84), -2), 0, t.dp(4), 0, 0));
        }
        v.setOnClickListener(x -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(t.dp(96), -2);
        lp.rightMargin = t.dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    // ------------------------------------------------------------------ the stage and the bars

    interface SlotListener {
        void onSlot(int i);
    }

    /** The workbench: the weapon big in the middle, and around it its attachment slots, joined to it by thin lines. */
    final class Stage extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), art = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private Bitmap gun;
        private final java.util.List<String[]> slots = new java.util.ArrayList<>();   // key, name, icon, part name
        private final java.util.Map<Integer, Bitmap> icons = new java.util.HashMap<>();
        private final float[][] nodes = new float[4][];
        int selected = -1;
        SlotListener listener;

        Stage(Context c, String ic) {
            super(c);
            art.setFilterBitmap(false);   // pixel art stays crisp
            text.setTextAlign(Paint.Align.CENTER);
            if (ic != null && !ic.isEmpty()) {
                Bitmap hit = host.art().peek("i", ic);
                if (hit != null) gun = hit;
                else host.art().icon(ic, b -> { gun = b; invalidate(); });
            }
        }

        void slot(String key, String name, String ic, String part) {
            int i = slots.size();
            slots.add(new String[]{key, name, ic, part});
            if (ic != null && !ic.isEmpty()) {
                Bitmap hit = host.art().peek("i", ic);
                if (hit != null) icons.put(i, hit);
                else host.art().icon(ic, b -> { if (b != null) { icons.put(i, b); invalidate(); } });
            }
        }

        @Override
        protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            setMeasuredDimension(width, Math.min(t.dp(slots.isEmpty() ? 200 : 280), (int) (width * (slots.isEmpty() ? 0.4f : 0.55f))));
        }

        /** Where a slot's node goes: optic above, barrel at the muzzle (right), recoil pad at the stock (left), sling below. */
        private float[] place(String key, float w, float h) {
            switch (key) {
                case "Scope": return new float[]{w / 2, h * 0.17f};
                case "Canon": return new float[]{w * 0.87f, h * 0.5f};
                case "RecoilPad": return new float[]{w * 0.13f, h * 0.5f};
                default: return new float[]{w / 2, h * 0.83f};
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            r.set(0, 0, w, h);
            p.setStyle(Paint.Style.FILL);
            // a workbench lit from the middle; in the game's look neutral greys in one of its panels
            p.setShader(new RadialGradient(w / 2, h / 2, Math.max(w, h) * 0.7f, t.pz ? 0xFF2E2E2E : 0xFF2C323B,
                    t.pz ? 0xFF080808 : 0xFF0E1013, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, t.pz ? 0 : t.dpf(16), t.pz ? 0 : t.dpf(16), p);
            p.setShader(null);
            if (t.pz) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(t.dpf(1));
                p.setColor(t.line);
                c.drawRect(t.dpf(0.5f), t.dpf(0.5f), w - t.dpf(0.5f), h - t.dpf(0.5f), p);
                p.setStyle(Paint.Style.FILL);
            }
            // a faint grid, like a cutting mat
            p.setColor(0x10FFFFFF);
            p.setStrokeWidth(1);
            float step = t.dpf(22);
            for (float x = step; x < w; x += step) c.drawLine(x, t.dpf(6), x, h - t.dpf(6), p);
            for (float y = step; y < h; y += step) c.drawLine(t.dpf(6), y, w - t.dpf(6), y, p);
            // the weapon
            float gw = w * (slots.isEmpty() ? 0.55f : 0.46f), gh = h * (slots.isEmpty() ? 0.8f : 0.42f);
            RectF g = new RectF(w / 2 - gw / 2, h / 2 - gh / 2, w / 2 + gw / 2, h / 2 + gh / 2);
            if (gun != null) {
                float s = Math.min(g.width() / gun.getWidth(), g.height() / gun.getHeight());
                float bw = gun.getWidth() * s, bh = gun.getHeight() * s;
                RectF dst = new RectF(w / 2 - bw / 2, h / 2 - bh / 2, w / 2 + bw / 2, h / 2 + bh / 2);
                // a soft light under it
                p.setShader(new RadialGradient(w / 2, h / 2, Math.max(bw, bh) * 0.6f, 0x33F2A93B, 0x00F2A93B, Shader.TileMode.CLAMP));
                c.drawCircle(w / 2, h / 2, Math.max(bw, bh) * 0.6f, p);
                p.setShader(null);
                c.drawBitmap(gun, null, dst, art);
                g = dst;
            }
            // the slots
            float tile = t.dpf(54);
            for (int i = 0; i < slots.size() && i < 4; i++) {
                String[] s = slots.get(i);
                float[] at = place(s[0], w, h);
                nodes[i] = at;
                boolean sel = i == selected;
                // the line to the gun
                float tx = "Scope".equals(s[0]) ? w / 2 : "Canon".equals(s[0]) ? g.right : "RecoilPad".equals(s[0]) ? g.left : w / 2;
                float ty = "Scope".equals(s[0]) ? g.top : "Sling".equals(s[0]) ? g.bottom : h / 2;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(t.dpf(1.5f));
                p.setColor(sel ? t.accent : 0x66FFFFFF);
                float fx = at[0], fy = at[1];
                if ("Scope".equals(s[0])) fy += tile / 2;
                else if ("Sling".equals(s[0])) fy -= tile / 2;
                else if ("Canon".equals(s[0])) fx -= tile / 2;
                else fx += tile / 2;
                c.drawLine(fx, fy, tx, ty, p);
                c.drawCircle(tx, ty, t.dpf(3), p);
                // the tile
                r.set(at[0] - tile / 2, at[1] - tile / 2, at[0] + tile / 2, at[1] + tile / 2);
                p.setStyle(Paint.Style.FILL);
                p.setColor(sel ? (t.pz ? t.selection : 0xFF3A3020) : (t.pz ? 0xE6080808 : 0xCC1C2027));
                c.drawRoundRect(r, t.pz ? 0 : t.dpf(12), t.pz ? 0 : t.dpf(12), p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(t.dpf(sel ? 2.5f : 1.2f));
                p.setColor(sel ? t.accent : 0x55FFFFFF);
                c.drawRoundRect(r, t.pz ? 0 : t.dpf(12), t.pz ? 0 : t.dpf(12), p);
                Bitmap ic = icons.get(i);
                if (ic != null) {
                    float s2 = Math.min((tile - t.dpf(12)) / ic.getWidth(), (tile - t.dpf(12)) / ic.getHeight());
                    float bw = ic.getWidth() * s2, bh = ic.getHeight() * s2;
                    c.drawBitmap(ic, null, new RectF(at[0] - bw / 2, at[1] - bh / 2, at[0] + bw / 2, at[1] + bh / 2), art);
                } else {
                    p.setColor(0x88FFFFFF);
                    p.setStrokeWidth(t.dpf(2));
                    c.drawLine(at[0] - t.dpf(8), at[1], at[0] + t.dpf(8), at[1], p);
                    c.drawLine(at[0], at[1] - t.dpf(8), at[0], at[1] + t.dpf(8), p);
                }
                // its name above, the part's under
                text.setTypeface(t.bold());
                text.setTextSize(t.dpf(10.5f));
                text.setColor(sel ? t.accent : 0xCCFFFFFF);
                c.drawText(s[1].toUpperCase(Locale.ROOT), at[0], at[1] - tile / 2 - t.dpf(5), text);
                text.setTypeface(t.regular());
                text.setTextSize(t.dpf(11));
                text.setColor(0xB3FFFFFF);
                String part = TextUtils.ellipsize(s[3], new android.text.TextPaint(text), t.dpf(110), TextUtils.TruncateAt.END).toString();
                c.drawText(part, at[0], at[1] + tile / 2 + t.dpf(14), text);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (slots.isEmpty() || listener == null) return false;
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                float best = t.dpf(48);
                int hit = -1;
                for (int i = 0; i < slots.size() && i < 4; i++) {
                    if (nodes[i] == null) continue;
                    float d = (float) Math.hypot(e.getX() - nodes[i][0], e.getY() - nodes[i][1]);
                    if (d < best) {
                        best = d;
                        hit = i;
                    }
                }
                if (hit >= 0) listener.onSlot(hit);
            }
            return true;
        }
    }

    /** A stat: its name and number over a bar; with a preview, "old → new" and the gain (green) or the loss (red) on the bar. */
    static final class StatBar extends View {
        private final Theme t;
        private final String label, value, nextValue;
        private final float cur, next;
        private final boolean changed, better;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        StatBar(Context c, Theme t, String label, String value, String nextValue, double cur, double next, boolean changed, boolean better) {
            super(c);
            this.t = t;
            this.label = label;
            this.value = value;
            this.nextValue = nextValue;
            this.cur = (float) cur;
            this.next = (float) next;
            this.changed = changed;
            this.better = better;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), barH = t.dpf(7), y = h - barH - t.dpf(2);
            p.setTextSize(t.dpf(t.sp(12.5f)));
            p.setTypeface(t.bold());
            p.setColor(t.text2);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(label, 0, y - t.dpf(6), p);
            p.setTextAlign(Paint.Align.RIGHT);
            p.setTypeface(t.regular());
            float right = w;
            if (changed && nextValue != null) {
                p.setColor(better ? t.good : t.bad);
                p.setTypeface(t.bold());
                c.drawText(nextValue, right, y - t.dpf(6), p);
                right -= p.measureText(nextValue);
                p.setTypeface(t.regular());
                p.setColor(t.text3);
                c.drawText(value + "  →  ", right, y - t.dpf(6), p);
            } else {
                p.setColor(t.text3);
                c.drawText(value, right, y - t.dpf(6), p);
            }
            r.set(0, y, w, y + barH);
            p.setColor(t.surface3);
            c.drawRoundRect(r, t.pz ? 0 : barH / 2, t.pz ? 0 : barH / 2, p);
            float lo = Math.min(cur, next), hi = Math.max(cur, next);
            int base = t.accent;
            r.set(0, y, Math.max(barH, w * (changed ? lo : cur)), y + barH);
            p.setColor(base);
            if (cur > 0.001f || changed) c.drawRoundRect(r, t.pz ? 0 : barH / 2, t.pz ? 0 : barH / 2, p);
            if (changed && hi - lo > 0.001f) {
                // the change: green when it's better, red when it's worse
                p.setColor(better ? t.good : t.bad);
                r.set(w * lo, y, w * hi, y + barH);
                c.drawRect(r, p);
            }
        }
    }
}
