package io.github.knoxlink.companion;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * What can I make? Only the recipes the game's crafting logic says you can do right now with
 * what you carry and what's around you, grouped like the game's crafting window. One tap starts it.
 */
final class CraftView extends LinearLayout {
    private final Host host;
    private final Theme t;
    private final LinearLayout list;
    private final EditText search;
    private JSONObject last;
    private String sig = "";

    CraftView(Context c, Host host) {
        super(c);
        this.host = host;
        t = host.theme();
        setOrientation(VERTICAL);
        setPadding(t.dp(12), t.dp(4), t.dp(12), 0);

        search = new EditText(c);
        search.setHint("Search what you can make");
        search.setHintTextColor(t.text3);
        search.setTextColor(t.text);
        search.setTextSize(t.sp(15));
        search.setSingleLine(true);
        search.setBackground(t.round(t.surface2, 12));
        search.setPadding(t.dp(14), t.dp(10), t.dp(14), t.dp(10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable s) { render(); }
        });
        addView(search, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(10)));

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        list = Ui.col(c);
        list.setPadding(0, 0, 0, t.dp(16));
        sv.addView(list);
        addView(sv, new LayoutParams(-1, 0, 1));
        list.addView(Ui.text(c, t, "Checking what you can make…", 14, t.text3, false));
    }

    void craft(JSONObject d) {
        String s = d.toString();
        if (s.equals(sig)) return;
        sig = s;
        last = d;
        render();
    }

    private void render() {
        if (last == null) return;
        Context c = getContext();
        list.removeAllViews();
        if (last.optBoolean("off")) {
            list.addView(Ui.text(c, t, "This version of the game doesn't have the new crafting.", 14, t.text3, false));
            return;
        }
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        JSONArray all = Screens.Json.arr(last.opt("list"));
        String cat = null;
        LinearLayout card = null;
        int shown = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject r = all.optJSONObject(i);
            if (r == null) continue;
            if (!q.isEmpty() && !r.optString("n").toLowerCase(Locale.ROOT).contains(q)) continue;
            String rc = r.optString("cat", "");
            if (card == null || !rc.equals(cat)) {
                cat = rc;
                card = Ui.col(c);
                card.setBackground(t.card());
                TextView h = Ui.label(c, t, rc.isEmpty() ? "Other" : prettify(rc));
                h.setPadding(t.dp(14), t.dp(12), t.dp(14), t.dp(6));
                card.addView(h);
                list.addView(card, Ui.margins(Ui.lp(-1, -2), 0, shown == 0 ? 0 : t.dp(12), 0, 0));
            }
            card.addView(row(c, r));
            shown++;
        }
        if (shown == 0) {
            LinearLayout box = Ui.col(c);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(t.dp(20), t.dp(30), t.dp(20), t.dp(20));
            box.addView(Ui.icon(c, t, "tool", t.text3, 36));
            box.addView(Ui.text(c, t, q.isEmpty() ? "Nothing to make right now" : "No match", 17, t.text, true), Ui.margins(Ui.lp(-2, -2), 0, t.dp(12), 0, t.dp(6)));
            TextView s = Ui.text(c, t, "Carry the tools and materials a recipe needs, or stand next to a table for the ones made on a surface.", 13.5f, t.text3, false);
            s.setGravity(Gravity.CENTER);
            box.addView(s);
            list.addView(box);
        }
    }

    /** "CookingPrep" -> "Cooking prep" */
    private static String prettify(String s) {
        String out = s.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
        return out.substring(0, 1).toUpperCase(Locale.ROOT) + out.substring(1).toLowerCase(Locale.ROOT);
    }

    private View row(Context c, JSONObject r) {
        LinearLayout row = Ui.row(c);
        row.setMinimumHeight(t.dp(54));
        row.setPadding(t.dp(12), t.dp(6), t.dp(14), t.dp(6));
        row.setBackground(Ui.pressable(t, 0x00000000, 0));
        String ic = r.optString("ic", "");
        if (!ic.isEmpty() && !ic.startsWith("Item_")) ic = "Item_" + ic;
        row.addView(Screens.itemIcon(c, host, ic, "tool", 38));
        row.addView(Ui.ellipsize(Ui.text(c, t, r.optString("n"), 15, t.text, true)), Ui.margins(Ui.weight(1), t.dp(12), 0, t.dp(8), 0));
        TextView make = Ui.text(c, t, "Make", 13.5f, t.accent, true);
        make.setPadding(t.dp(12), t.dp(7), t.dp(12), t.dp(7));
        make.setBackground(t.round(Theme.alpha(t.accent, 0.14f), 10));
        row.addView(make);
        final String id = r.optString("id");
        View.OnClickListener go = v -> host.command("craft", Ui.args("id", id), "Making");
        row.setOnClickListener(go);
        make.setOnClickListener(go);
        return row;
    }
}
