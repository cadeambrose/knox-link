package io.github.knoxlink.companion;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * The game's own right-click menu for an item or a thing in the world, as a sheet: its options in
 * its words, greyed out with its reason when they can't be done now; a submenu opens in place.
 * The chosen option runs through the game's own code (the "menuDo" command).
 */
final class GameMenu {
    private GameMenu() {}

    /** target: what the menu is for, as the game add-on wants it ({kind: "item", id} or {kind: "obj", x, y, z, i}). */
    static void open(Context c, Host host, String title, JSONObject target) {
        Theme t = host.theme();
        LinearLayout sheet = Ui.col(c);
        Ui.sheetTitle(c, t, sheet, title, null);
        sheet.addView(Ui.text(c, t, "Asking the game…", 14, t.text3, false), Ui.margins(Ui.lp(-2, -2), 0, t.pz ? 0 : t.dp(10), 0, 0));
        host.openSheet(sheet);
        host.requestMenu(copy(target), menu -> show(c, host, target, new JSONArray(), Screens.Json.arr(menu.opt("opts")),
                menu.optString("title", title), null));
    }

    private static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o.toString());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void show(Context c, Host host, JSONObject target, JSONArray path, JSONArray opts, String title, Runnable back) {
        Theme t = host.theme();
        LinearLayout sheet = Ui.col(c);
        Ui.sheetTitle(c, t, sheet, title, back == null ? null : v -> back.run());
        if (opts.length() == 0) {
            sheet.addView(Ui.text(c, t, "Nothing to do with it right now.", 14, t.text3, false));
        }
        // in the game's look the options sit in one panel like its right-click menu (ISContextMenu: 0.1 grey,
        // a faint white border, a highlight under the finger); otherwise each is its own button
        LinearLayout menu = sheet;
        if (t.pz && opts.length() > 0) {
            menu = Ui.col(c);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(0xFF161616);
            g.setStroke(t.dp(1), 0xFF3A3A3A);
            menu.setBackground(g);
            menu.setPadding(t.dp(1), t.dp(1), t.dp(1), t.dp(1));
            sheet.addView(menu, Ui.lp(-1, -2));
        }
        // the n-th option of the same name is told apart as "name#n", the way the game add-on finds it again
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < opts.length(); i++) {
            JSONObject e = opts.optJSONObject(i);
            if (e == null) continue;
            String text = e.optString("t");
            int n = seen.containsKey(text) ? seen.get(text) + 1 : 1;
            seen.put(text, n);
            String step = n > 1 ? text + "#" + n : text;
            boolean sub = e.has("s");
            Object d = e.opt("d");
            boolean off = d != null && !Boolean.FALSE.equals(d);
            String why = d instanceof String ? (String) d : null;

            LinearLayout r = Ui.row(c);
            r.setBackground(Ui.pressable(t, t.pz ? 0x00000000 : t.surface2, 14));
            r.setPadding(t.dp(12), t.dp(10), t.dp(12), t.dp(10));
            r.setMinimumHeight(t.dp(t.pz ? 48 : 52));
            String ic = e.optString("ic", "");
            if (!ic.isEmpty()) r.addView(Screens.itemIcon(c, host, ic, "box", 34), Ui.margins(Ui.lp(-2, -2), 0, 0, t.dp(12), 0));
            LinearLayout txt = Ui.col(c);
            txt.addView(Ui.text(c, t, text, 15.5f, off ? t.text3 : t.text, true));
            if (why != null) {
                TextView w = Ui.text(c, t, why, 12.5f, t.text3, false);
                w.setMaxLines(3);
                txt.addView(w, Ui.margins(Ui.lp(-1, -2), 0, t.dp(2), 0, 0));
            }
            r.addView(txt, Ui.weight(1));
            if (sub) {
                TextView more = Ui.text(c, t, "›", 22, t.text3, true);
                more.setGravity(Gravity.CENTER);
                r.addView(more, Ui.margins(Ui.lp(-2, -2), t.dp(8), 0, 0, 0));
            }
            JSONArray here = new JSONArray();
            for (int k = 0; k < path.length(); k++) here.put(path.opt(k));
            here.put(step);
            r.setOnClickListener(v -> {
                if (sub) {
                    show(c, host, target, here, Screens.Json.arr(e.opt("s")), text,
                            () -> show(c, host, target, path, opts, title, back));
                } else if (off) {
                    host.toast(why != null ? why : "That can't be done now", true);
                } else {
                    JSONObject args = copy(target);
                    try {
                        args.put("path", here);
                    } catch (Exception ignored) {
                    }
                    host.command("menuDo", args, text);
                    host.closeSheet();
                }
            });
            menu.addView(r, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.pz ? 0 : t.dp(8)));
        }
        host.openSheet(sheet);
    }
}
