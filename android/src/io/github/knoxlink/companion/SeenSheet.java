package io.github.knoxlink.companion;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * "Where did I see...?": a search through everything the character has looked in (the game's
 * loot window, as they went: Memory.java keeps it in the save). By name or by kind, nearest
 * first, with the container, the room and the town; a tap shows it on the map.
 */
final class SeenSheet {
    private SeenSheet() {}

    private static final String[][] KINDS = {
            {"Food", "cat:food"}, {"Water", "cat:water"}, {"Medicine", "cat:firstaid,bandage"}, {"Weapons", "cat:weapon"},
            {"Tools", "cat:tool"}, {"Ammo", "cat:ammo"}, {"Bags", "cat:bag"}, {"Books", "cat:skillbook,literature"},
            {"Seeds", "cat:gardening"}, {"Materials", "cat:material"}};

    private static int req;
    private static Results waiting;

    private interface Results {
        void show(JSONObject d);
    }

    /** The game answered a search ("seen"): to the open sheet, if it's the latest question. */
    static void onSeen(JSONObject d) {
        if (waiting != null && d != null && d.optInt("req") == req) waiting.show(d);
    }

    static void open(Context c, Host host, MapView map) {
        Theme t = host.theme();
        Handler main = new Handler(Looper.getMainLooper());
        LinearLayout sheet = Ui.col(c);
        sheet.addView(Ui.text(c, t, "Where did I see…?", 20, t.text, true));
        sheet.addView(Ui.text(c, t, "Everything your character has looked in, as they went.", 13.5f, t.text3, false),
                Ui.margins(Ui.lp(-1, -2), 0, t.dp(4), 0, t.dp(12)));
        EditText q = new EditText(c);
        q.setHint("A hammer, beans, a shotgun…");
        q.setTextColor(t.text);
        q.setHintTextColor(t.text3);
        q.setTextSize(t.sp(16));
        q.setSingleLine(true);
        q.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        q.setImeOptions(EditorInfo.IME_ACTION_GO);
        q.setBackground(t.round(t.surface2, 12));
        q.setPadding(t.dp(14), t.dp(11), t.dp(14), t.dp(11));
        sheet.addView(q, Ui.lp(-1, -2));

        Widgets.Flow chips = new Widgets.Flow(c, t.dp(8), t.dp(8));
        sheet.addView(chips, Ui.margins(Ui.lp(-1, -2), 0, t.dp(10), 0, t.dp(12)));
        LinearLayout results = Ui.col(c);
        sheet.addView(results, Ui.lp(-1, -2));
        TextView close = Ui.action(c, t, "x", "Close");
        close.setOnClickListener(v -> host.closeSheet());
        sheet.addView(close, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));

        final Runnable[] search = new Runnable[1];
        final String[] asked = {""};
        search[0] = () -> {
            String s = asked[0];
            if (s.trim().isEmpty()) {
                results.removeAllViews();
                return;
            }
            req++;
            host.command("seenFind", Ui.args("q", s, "req", req), null);
        };
        for (String[] k : KINDS) {
            TextView chip = Ui.text(c, t, k[0], 14, t.text, true);
            chip.setPadding(t.dp(14), t.dp(8), t.dp(14), t.dp(8));
            chip.setBackground(Ui.pressable(t, t.surface2, 16));
            chip.setOnClickListener(v -> {
                q.setText("");
                asked[0] = k[1];
                search[0].run();
            });
            chips.addView(chip);
        }
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}
            @Override
            public void afterTextChanged(Editable e) {
                asked[0] = e.toString();
                main.removeCallbacks(search[0]);
                main.postDelayed(search[0], 350);
            }
        });
        q.setOnEditorActionListener((v, a, e) -> {
            main.removeCallbacks(search[0]);
            search[0].run();
            return false;
        });

        waiting = d -> {
            results.removeAllViews();
            JSONArray r = d.optJSONArray("r");
            if (r == null || r.length() == 0) {
                TextView none = Ui.text(c, t, "Nothing like that in anything you've looked through.", 14, t.text3, false);
                results.addView(none, Ui.margins(Ui.lp(-1, -2), 0, t.dp(4), 0, t.dp(4)));
                map.setPins(null, -1);
                return;
            }
            int total = d.optInt("total", r.length());
            if (total > r.length()) {
                results.addView(Ui.text(c, t, "The nearest " + r.length() + " of " + total, 12.5f, t.text3, false),
                        Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(6)));
            }
            for (int i = 0; i < r.length(); i++) {
                JSONObject o = r.optJSONObject(i);
                if (o == null) continue;
                final int index = i;
                results.addView(row(c, host, o, v -> {
                    map.setPins(r, index);
                    host.showOnMap((float) o.optDouble("x") + 0.5f, (float) o.optDouble("y") + 0.5f, 3f);
                    host.closeSheet();
                }), Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(6)));
            }
            map.setPins(r, -1);
        };
        host.openSheet(sheet);
    }

    static String ago(double hours) {
        if (hours < 1) return "just now";
        if (hours < 24) {
            int h = (int) Math.round(hours);
            return h == 1 ? "an hour ago" : h + " hours ago";
        }
        int d = (int) Math.round(hours / 24);
        return d == 1 ? "yesterday" : d + " days ago";
    }

    private static View row(Context c, Host host, JSONObject o, View.OnClickListener tap) {
        Theme t = host.theme();
        LinearLayout row = Ui.row(c);
        row.setPadding(t.dp(10), t.dp(8), t.dp(12), t.dp(8));
        row.setBackground(Ui.pressable(t, t.surface2, 12));
        ImageView icon = new ImageView(c);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageDrawable(t.icon("box", t.text3));
        String ic = o.optString("ic", "");
        if (!ic.isEmpty()) host.art().icon(ic, b -> { if (b != null) icon.setImageBitmap(b); });
        row.addView(icon, Ui.lp(t.dp(34), t.dp(34)));
        LinearLayout col = Ui.col(c);
        int n = o.optInt("c", 1);
        col.addView(Ui.ellipsize(Ui.text(c, t, o.optString("n") + (n > 1 ? "  ×" + n : ""), 15, t.text, true)));
        StringBuilder where = new StringBuilder(o.optString("b", ""));
        if (o.has("rm")) where.append(" · ").append(o.optString("rm"));
        if (o.has("town")) where.append(" · ").append(o.optString("town"));
        if (o.optInt("z") != 0) where.append(" · ").append(o.optInt("z") > 0 ? "upstairs" : "downstairs");
        col.addView(Ui.ellipsize(Ui.text(c, t, where.toString(), 12.5f, t.text3, false)), Ui.margins(Ui.lp(-1, -2), 0, t.dp(3), 0, 0));
        row.addView(col, Ui.margins(Ui.weight(1), t.dp(10), 0, t.dp(8), 0));
        LinearLayout right = Ui.col(c);
        right.setGravity(android.view.Gravity.END);
        double dist = o.optDouble("d", 0);
        // a few steps away to the step, further in tens of metres
        String far = dist < 3 ? "Here" : (dist < 30 ? Fmt.near(dist) : Fmt.dist(dist)) + (o.has("dir") ? " " + o.optString("dir") : "");
        right.addView(Ui.text(c, t, far, 13.5f, t.text2, true));
        right.addView(Ui.text(c, t, ago(o.optDouble("h", 0)), 11.5f, t.text3, false), Ui.margins(Ui.lp(-2, -2), 0, t.dp(3), 0, 0));
        row.addView(right);
        row.setOnClickListener(tap);
        return row;
    }
}
