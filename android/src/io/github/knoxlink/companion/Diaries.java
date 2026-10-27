package io.github.knoxlink.companion;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * Diaries left behind: when a character dies, their journal stays on their body as a diary
 * (KnoxLink_Legacy.lua). The journal lists the ones the character has with them or within reach,
 * and opens one like a book: the dead character's days in their own words, with their photos.
 */
final class Diaries {
    private Diaries() {}

    private static final int PAPER = 0xFFE9E3CD, PAPER_EDGE = 0xFFCFC6A8, INK = 0xFF2B2620, INK_SOFT = 0xFF6B6050, PEN = 0xFF28307D,
            RED = 0xFFA60E0E;
    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August",
            "September", "October", "November", "December"};
    private static int req;

    /** The journal's "Diaries you found", or null when there are none here. */
    static View card(Context c, Host host, JSONObject d) {
        JSONArray list = d == null ? null : d.optJSONArray("list");
        if (list == null || list.length() == 0) return null;
        Theme t = host.theme();
        LinearLayout box = Ui.col(c);
        box.addView(Ui.label(c, t, list.length() == 1 ? "A diary you found" : "Diaries you found"), Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
        for (int i = 0; i < list.length(); i++) {
            JSONObject e = list.optJSONObject(i);
            if (e == null) continue;
            LinearLayout row = Ui.row(c);
            row.setBackground(paper(t, 10));
            row.setPadding(t.dp(14), t.dp(10), t.dp(12), t.dp(10));
            row.addView(Ui.icon(c, t, "book", RED, 24));
            LinearLayout col = Ui.col(c);
            TextView name = Ui.text(c, t, e.optString("name") + "'s diary", 16, INK, true);
            name.setTypeface(Typeface.create("serif", Typeface.BOLD));
            col.addView(name);
            int days = e.optInt("days");
            String where = e.optBoolean("on") ? "With you" : e.optBoolean("near") ? "Within reach" : "From this world";
            col.addView(Ui.text(c, t, where + " · " + (days == 1 ? "1 day" : days + " days"), 12.5f, INK_SOFT, false),
                    Ui.margins(Ui.lp(-2, -2), 0, t.dp(3), 0, 0));
            row.addView(col, Ui.margins(Ui.weight(1), t.dp(12), 0, 0, 0));
            row.addView(Ui.text(c, t, "Read", 14, INK, true));
            String id = e.optString("id");
            row.setOnClickListener(v -> read(host, id));
            box.addView(row, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
        }
        return box;
    }

    /** Asks the game for a diary; it comes back on "diary" (onDiary). */
    static void read(Host host, String id) {
        req++;
        host.command("diaryRead", Ui.args("id", id, "req", req), null);
    }

    /** The game sent a diary: open it if it's the one asked for last. */
    static void onDiary(Context c, Host host, JSONObject d) {
        if (d == null || d.optInt("req") != req) return;
        req++;   // shown once
        show(c, host, d);
    }

    private static GradientDrawable paper(Theme t, float radius) {
        GradientDrawable g = t.round(PAPER, radius);
        g.setStroke(t.dp(1), PAPER_EDGE);
        return g;
    }

    /** The calendar date of a diary's day n, from its day 1 ({y, mo, d}). */
    static String dateOf(JSONObject start, int n) {
        if (start == null || !start.has("y")) return null;
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(start.optInt("y"), start.optInt("mo", 1) - 1, start.optInt("d", 1));
        cal.add(Calendar.DAY_OF_MONTH, n - 1);
        return MONTHS[cal.get(Calendar.MONTH)] + " " + cal.get(Calendar.DAY_OF_MONTH) + ", " + cal.get(Calendar.YEAR);
    }

    static void show(Context c, Host host, JSONObject d) {
        Theme t = host.theme();
        LinearLayout sheet = Ui.col(c);
        LinearLayout book = Ui.col(c);
        book.setBackground(paper(t, 8));
        book.setPadding(t.dp(18), t.dp(16), t.dp(18), t.dp(16));
        // the cover: who they were
        TextView title = Ui.text(c, t, d.optString("name") + "'s diary", 22, INK, true);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        book.addView(title, Ui.lp(-1, -2));
        StringBuilder who = new StringBuilder();
        if (!d.optString("prof", "").isEmpty()) who.append(d.optString("prof"));
        JSONArray traits = d.optJSONArray("traits");
        for (int i = 0; traits != null && i < traits.length() && i < 4; i++) who.append(who.length() > 0 ? " · " : "").append(traits.optString(i));
        if (who.length() > 0) {
            TextView w = Ui.text(c, t, who.toString(), 13, INK_SOFT, false);
            w.setGravity(Gravity.CENTER);
            book.addView(w, Ui.margins(Ui.lp(-1, -2), 0, t.dp(6), 0, 0));
        }
        JSONArray days = d.optJSONArray("days");
        int last = days != null && days.length() > 0 ? days.optJSONObject(days.length() - 1).optInt("d") : 1;
        TextView span = Ui.text(c, t, last == 1 ? "One day." : "Day 1 to day " + last + ".", 13, INK_SOFT, false);
        span.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        span.setGravity(Gravity.CENTER);
        book.addView(span, Ui.margins(Ui.lp(-1, -2), 0, t.dp(4), 0, t.dp(6)));

        int written = 0;
        for (int i = 0; days != null && i < days.length(); i++) {
            JSONObject p = days.optJSONObject(i);
            if (p == null) continue;
            String text = p.optString("s", "");
            JSONArray notes = p.optJSONArray("notes");
            JSONArray ph = p.optJSONArray("ph");
            boolean any = !text.isEmpty() || (notes != null && notes.length() > 0) || (ph != null && ph.length() > 0);
            if (!any) continue;
            written++;
            View rule = new View(c);
            rule.setBackgroundColor(Theme.alpha(RED, 0.45f));
            book.addView(rule, Ui.margins(Ui.lp(-1, t.dp(1)), 0, t.dp(12), 0, t.dp(8)));
            LinearLayout head = Ui.row(c);
            TextView day = Ui.text(c, t, "Day " + p.optInt("d"), 18, INK, true);
            day.setTypeface(Typeface.create("serif", Typeface.BOLD));
            head.addView(day, Ui.weight(1));
            String date = dateOf(d.optJSONObject("start"), p.optInt("d"));
            if (date != null) head.addView(Ui.text(c, t, date, 12, INK_SOFT, false));
            book.addView(head);
            if (!text.isEmpty()) {
                TextView prose = Ui.text(c, t, text, 15.5f, INK, false);
                Fonts.handwrite(prose, false);
                prose.setLineSpacing(t.dp(3), 1f);
                book.addView(prose, Ui.margins(Ui.lp(-1, -2), 0, t.dp(8), 0, 0));
            }
            for (int k = 0; notes != null && k < notes.length(); k++) {
                TextView note = Ui.text(c, t, notes.optString(k), 15.5f, PEN, false);
                Fonts.handwrite(note, true);
                book.addView(note, Ui.margins(Ui.lp(-1, -2), 0, t.dp(8), 0, 0));
            }
            if (ph != null && ph.length() > 0) book.addView(Photos.strip(c, host, ph, false), Ui.margins(Ui.lp(-1, -2), 0, t.dp(10), 0, 0));
        }
        if (written == 0) {
            TextView blank = Ui.text(c, t, "The pages are blank.", 15, INK_SOFT, false);
            blank.setTypeface(Typeface.create("serif", Typeface.ITALIC));
            book.addView(blank, Ui.margins(Ui.lp(-2, -2), 0, t.dp(12), 0, 0));
        }
        sheet.addView(book, Ui.lp(-1, -2));
        TextView close = Ui.action(c, t, "x", "Close the diary");
        close.setOnClickListener(v -> host.closeSheet());
        sheet.addView(close, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, 0));
        host.openSheet(sheet);
    }
}
