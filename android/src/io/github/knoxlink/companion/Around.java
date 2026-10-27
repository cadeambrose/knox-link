package io.github.knoxlink.companion;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * What's around you: the containers the game's loot window lists (the floor, shelves, a trunk,
 * a body...), the bodies a few steps away, and what's in them; then the things around you with a
 * menu of their own (doors, windows, curtains, furniture). Tap an item to take it, hold it for
 * everything else; "Take all" empties a container into your inventory as far as you can carry.
 */
final class Around extends ScrollView {
    private final Host host;
    private final Theme t;
    private final Screens.Gear gear;
    private final LinearLayout col;
    private String sig = "";
    private JSONObject data;   // the last "loot" message

    Around(Context c, Host host, Screens.Gear gear) {
        super(c);
        this.host = host;
        this.gear = gear;
        t = host.theme();
        setVerticalScrollBarEnabled(false);
        col = Ui.col(c);
        col.setPadding(t.dp(12), t.dp(4), t.dp(12), t.dp(16));
        addView(col);
        col.addView(empty(c));
    }

    private View empty(Context c) {
        LinearLayout box = Ui.col(c);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(t.dp(20), t.dp(36), t.dp(20), t.dp(20));
        box.addView(Ui.icon(c, t, "box", t.text3, 36));
        box.addView(Ui.text(c, t, "Nothing around you", 17, t.text, true), Ui.margins(Ui.lp(-2, -2), 0, t.dp(12), 0, t.dp(6)));
        TextView s = Ui.text(c, t, "Walk up to shelves, cupboards, cars or bodies. Whatever the game's loot window shows appears here, ready to take.", 13.5f, t.text3, false);
        s.setGravity(Gravity.CENTER);
        box.addView(s);
        return box;
    }

    /** Draws it again: what's picked to move changed. */
    void redraw() {
        sig = "";
        if (data != null) loot(data);
    }

    /** Room under the list, so the move bar doesn't cover the last items. */
    void setBottomRoom(int px) {
        col.setPadding(t.dp(12), t.dp(4), t.dp(12), px);
    }

    /** An item around you by its id (a stack's first one), or null. */
    JSONObject item(long id) {
        if (data == null) return null;
        JSONArray list = Screens.Json.arr(data.opt("list"));
        for (int i = 0; i < list.length(); i++) {
            JSONObject cont = list.optJSONObject(i);
            JSONArray items = cont == null ? null : Screens.Json.arr(cont.opt("items"));
            for (int k = 0; items != null && k < items.length(); k++) {
                JSONObject it = items.optJSONObject(k);
                if (it != null && it.optLong("id") == id) return it;
            }
        }
        return null;
    }

    void loot(JSONObject d) {
        data = d;
        String s = d.toString() + gear.picking();
        if (s.equals(sig)) return;
        sig = s;
        Context c = getContext();
        col.removeAllViews();
        JSONArray list = Screens.Json.arr(d.opt("list"));
        // the one the game has open first, then the ones with something in them; empty ones last
        java.util.List<JSONObject> order = new java.util.ArrayList<>();
        for (int i = 0; i < list.length(); i++) if (list.optJSONObject(i) != null) order.add(list.optJSONObject(i));
        order.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        int shown = 0;
        for (JSONObject cont : order) {
            col.addView(container(c, cont), Ui.margins(Ui.lp(-1, -2), 0, shown == 0 ? 0 : t.dp(12), 0, 0));
            shown++;
        }
        JSONArray objs = Screens.Json.arr(d.opt("objs"));
        if (objs.length() > 0) {
            col.addView(things(c, objs), Ui.margins(Ui.lp(-1, -2), 0, shown == 0 ? 0 : t.dp(12), 0, 0));
            shown++;
        }
        if (shown == 0) col.addView(empty(c));
    }

    private static int rank(JSONObject cont) {
        if (cont.optBoolean("sel")) return 0;
        if (cont.optBoolean("corpse")) return 2;   // bodies a few steps away, nearest first (the list's own order)
        return Screens.Json.arr(cont.opt("items")).length() > 0 ? (cont.optBoolean("floor") ? 3 : 1) : 4;
    }

    /** The things around you that have a menu of their own; one tap opens the game's menu for it. */
    private View things(Context c, JSONArray objs) {
        LinearLayout box = Ui.col(c);
        box.setBackground(t.card());
        LinearLayout head = Ui.row(c);
        head.setPadding(t.dp(12), t.dp(10), t.dp(10), t.dp(10));
        head.addView(Ui.icon(c, t, "hand", t.text3, 26), Ui.lp(t.dp(30), t.dp(30)));
        head.addView(Ui.text(c, t, "Things around you", 16, t.text, true), Ui.margins(Ui.weight(1), t.dp(10), 0, 0, 0));
        box.addView(head);
        for (int i = 0; i < objs.length(); i++) {
            JSONObject o = objs.optJSONObject(i);
            if (o == null) continue;
            View line = new View(c);
            line.setBackgroundColor(t.lineSoft);
            box.addView(line, Ui.lp(-1, t.dp(1)));
            LinearLayout r = Ui.row(c);
            r.setMinimumHeight(t.dp(50));
            r.setPadding(t.dp(16), t.dp(8), t.dp(14), t.dp(8));
            r.setBackground(Ui.pressable(t, 0x00000000, 0));
            String name = o.optString("n");
            r.addView(Ui.ellipsize(Ui.text(c, t, name, 15, t.text, true)), Ui.weight(1));
            r.addView(Ui.text(c, t, Fmt.near(o.optDouble("d")), 13, t.text3, true));
            r.setOnClickListener(v -> GameMenu.open(c, host, name,
                    Ui.args("kind", "obj", "x", o.optInt("x"), "y", o.optInt("y"), "z", o.optInt("z"), "i", o.optInt("i"), "n", name)));
            box.addView(r);
        }
        return box;
    }

    private View container(Context c, JSONObject cont) {
        LinearLayout box = Ui.col(c);
        box.setBackground(t.card());
        JSONArray items = Screens.Json.arr(cont.opt("items"));
        LinearLayout head = Ui.row(c);
        head.setPadding(t.dp(12), t.dp(10), t.dp(10), t.dp(10));
        ImageView ic = new ImageView(c);
        ic.setImageDrawable(t.icon(cont.optBoolean("floor") ? "layers" : "box", t.text3));
        // the loot window's own container icon: a loose file under media/, or a picture in the texture packs
        String path = cont.optString("ic", "");
        if (path.contains("/")) host.art().media(path, b -> { if (b != null) ic.setImageBitmap(b); });
        else if (!path.isEmpty()) host.art().icon(path, b -> { if (b != null) ic.setImageBitmap(b); });
        head.addView(ic, Ui.lp(t.dp(30), t.dp(30)));
        LinearLayout ht = Ui.col(c);
        ht.addView(Ui.ellipsize(Ui.text(c, t, cont.optString("n"), 16, t.text, true)));
        double cap = cont.optDouble("cap", 0);
        String weight = Fmt.num(cont.optDouble("w")) + (cap > 0 ? " / " + Fmt.num(cap) : "");
        if (cont.optBoolean("corpse")) {
            double away = cont.optDouble("d");
            weight = (away < 1.2 ? "Next to you" : Fmt.near(away) + " away") + " · " + Fmt.num(cont.optDouble("w"));
        }
        ht.addView(Ui.text(c, t, items.length() == 0 ? "Empty" : weight, 12.5f, t.text3, true), Ui.margins(Ui.lp(-2, -2), 0, t.dp(2), 0, 0));
        head.addView(ht, Ui.margins(Ui.weight(1), t.dp(10), 0, t.dp(8), 0));
        if (items.length() > 0 && gear.picking()) {
            // moving items: pick the whole container in one tap
            java.util.List<Long> ids = new java.util.ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it != null) ids.add(it.optLong("id"));
            }
            head.addView(gear.pickAllButton(c, ids));
        } else if (items.length() > 0) {
            TextView all = Ui.text(c, t, "Take all", 13.5f, t.accent, true);
            all.setPadding(t.dp(12), t.dp(8), t.dp(12), t.dp(8));
            all.setBackground(Ui.pressable(t, Theme.alpha(t.accent, 0.14f), 10));
            final int k = cont.optInt("k");
            all.setOnClickListener(v -> host.command("takeAll", Ui.args("k", k), "Taking"));
            head.addView(all);
        }
        box.addView(head);
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            View line = new View(c);
            line.setBackgroundColor(t.lineSoft);
            box.addView(line, Ui.lp(-1, t.dp(1)));
            box.addView(itemRow(c, it, cont));
        }
        return box;
    }

    private View itemRow(Context c, JSONObject it, JSONObject cont) {
        LinearLayout r = Ui.row(c);
        r.setMinimumHeight(t.dp(54));
        r.setPadding(t.dp(12), t.dp(7), t.dp(14), t.dp(7));
        r.setBackground(Ui.pressable(t, 0x00000000, 0));
        long id = it.optLong("id");
        boolean picked = gear.picking() && gear.isPicked(id);
        if (picked) {
            // picked to move: the tick takes the picture's place
            FrameLayout tick = new FrameLayout(c);
            tick.setBackground(t.round(t.accent, 11));
            ImageView iv = new ImageView(c);
            int pad = t.dp(8);
            iv.setPadding(pad, pad, pad, pad);
            iv.setImageDrawable(t.icon("check", t.accentInk));
            tick.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            r.addView(tick, Ui.lp(t.dp(38), t.dp(38)));
        } else {
            r.addView(Screens.itemIcon(c, host, it.optString("ic"), Screens.Gear.fallbackIcon(it), 38));
        }
        LinearLayout txt = Ui.col(c);
        int cnt = it.optInt("cnt", 1);
        txt.addView(Ui.ellipsize(Ui.text(c, t, it.optString("n") + (cnt > 1 ? "  ×" + cnt : ""), 15, t.text, true)));
        List<String[]> tags = Screens.Gear.tags(it);
        if (!tags.isEmpty()) {
            Widgets.Flow f = new Widgets.Flow(c, t.dp(6), t.dp(4));
            for (String[] x : tags) f.addView(Ui.tag(c, t, x[0], x[1]));
            txt.addView(f, Ui.margins(Ui.lp(-1, -2), 0, t.dp(4), 0, 0));
        }
        r.addView(txt, Ui.margins(Ui.weight(1), t.dp(12), 0, t.dp(8), 0));
        r.addView(Ui.text(c, t, Fmt.num(it.optDouble("w")), 13, t.text3, true));
        if (gear.picking()) {
            r.setOnClickListener(v -> gear.togglePick(id));
            return r;
        }
        // one tap takes one (a stack asks how many); holding it opens everything else
        r.setOnClickListener(v -> {
            if (cnt <= 1) host.command("take", Ui.args("id", it.optLong("id")), "Taking");
            else itemSheet(c, it, cont);
        });
        r.setOnLongClickListener(v -> {
            itemSheet(c, it, cont);
            return true;
        });
        return r;
    }

    private void itemSheet(Context c, JSONObject it, JSONObject cont) {
        int cnt = it.optInt("cnt", 1);
        LinearLayout sheet = Ui.col(c);
        LinearLayout head = Ui.row(c);
        head.addView(Screens.itemIcon(c, host, it.optString("ic"), Screens.Gear.fallbackIcon(it), 52));
        head.addView(Ui.text(c, t, it.optString("n") + (cnt > 1 ? "  ×" + cnt : ""), 19, t.text, true), Ui.margins(Ui.weight(1), t.dp(14), 0, 0, 0));
        sheet.addView(head, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(6)));
        sheet.addView(Ui.text(c, t, "In " + cont.optString("n"), 13.5f, t.text3, false), Ui.margins(Ui.lp(-2, -2), 0, 0, 0, t.dp(14)));
        if (it.has("lg")) {
            // a diary someone left behind: it can be read where it lies
            TextView rd = Ui.action(c, t, "book", "Read the diary");
            rd.setOnClickListener(x -> { host.closeSheet(); Diaries.read(host, it.optString("lg")); });
            sheet.addView(rd, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
        }
        TextView one = Ui.action(c, t, "hand", cnt > 1 ? "Take one" : "Take");
        one.setOnClickListener(x -> { host.command("take", Ui.args("id", it.optLong("id")), "Taking"); host.closeSheet(); });
        sheet.addView(one, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
        if (cnt > 1) {
            TextView all = Ui.action(c, t, "bag", "Take all " + cnt);
            all.setOnClickListener(x -> { host.command("take", Ui.args("ids", it.optJSONArray("ids")), "Taking"); host.closeSheet(); });
            sheet.addView(all, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
        }
        // straight into a bag instead of your pockets
        Map<String, String> targets = gear.moveTargets(it.optLong("id"), null, cont.optInt("k"));
        targets.remove("inv");
        gear.addMoveTo(c, sheet, targets, Screens.Gear.idsOf(it), "Moving");
        String name = it.optString("n");
        TextView more = Ui.action(c, t, "more", "All actions…");
        more.setOnClickListener(x -> GameMenu.open(c, host, name, Ui.args("kind", "item", "ids", Screens.Gear.idsOf(it))));
        sheet.addView(more);
        host.openSheet(sheet);
    }
}
