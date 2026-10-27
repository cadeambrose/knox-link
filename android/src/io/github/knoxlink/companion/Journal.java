package io.github.knoxlink.companion;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * The survivor's journal: a page a day, written as a diary in the character's own voice (by the
 * game's offline writer, or the AI writer when it's on), with what happened folded underneath
 * (kills, skills, wounds, places, sleep) and your own notes, on paper like the game's notebooks.
 */
final class Journal extends ScrollView implements AiWriter.Listener {
    // paper and ink, the same paper as the game's map
    private static final int PAPER = 0xFFE9E3CD, PAPER_EDGE = 0xFFCFC6A8, INK = 0xFF2B2620, INK_SOFT = 0xFF6B6050,
            PEN = 0xFF28307D, RED = 0xFFA60E0E;

    private final Host host;
    private final Theme t;
    private final AiWriter writer;
    private final LinearLayout col, pages, writeBox, found;
    private final TextView status;
    private final EditText input;
    private final Set<Integer> unfolded = new HashSet<>();   // pages whose "what happened" is open
    private JSONObject data;
    private String sig = "";

    Journal(Context c, Host host) {
        super(c);
        this.host = host;
        t = host.theme();
        writer = AiWriter.get(host, this);
        setVerticalScrollBarEnabled(false);
        col = Ui.col(c);
        col.setPadding(t.dp(12), t.dp(12), t.dp(12), t.dp(20));
        addView(col);

        // who writes the pages, and the button to choose
        LinearLayout head = Ui.row(c);
        status = Ui.text(c, t, "", 12.5f, t.text3, false);
        status.setOnClickListener(v -> openWriter());
        head.addView(status, Ui.weight(1));
        TextView pick = Ui.text(c, t, "Writer", 13.5f, t.text2, true);
        Drawable pen = t.icon("pen", t.text2);
        pen.setBounds(0, 0, t.dp(16), t.dp(16));
        pick.setCompoundDrawables(pen, null, null, null);
        pick.setCompoundDrawablePadding(t.dp(6));
        pick.setGravity(Gravity.CENTER_VERTICAL);
        pick.setPadding(t.dp(12), t.dp(8), t.dp(14), t.dp(8));
        pick.setBackground(Ui.pressable(t, t.surface2, 10));
        pick.setOnClickListener(v -> openWriter());
        head.addView(pick, Ui.margins(Ui.lp(-2, -2), t.dp(10), 0, 0, 0));
        // the scrapbook: a photo of the game for today's page (a camera in the game, in immersion)
        TextView snap = Ui.text(c, t, "Photo", 13.5f, t.text2, true);
        Drawable cam = t.icon("camera", t.text2);
        cam.setBounds(0, 0, t.dp(16), t.dp(16));
        snap.setCompoundDrawables(cam, null, null, null);
        snap.setCompoundDrawablePadding(t.dp(6));
        snap.setGravity(Gravity.CENTER_VERTICAL);
        snap.setPadding(t.dp(12), t.dp(8), t.dp(14), t.dp(8));
        snap.setBackground(Ui.pressable(t, t.surface2, 10));
        snap.setOnClickListener(v -> host.command("photo", new JSONObject(), "Photo taken"));
        head.addView(snap, Ui.margins(Ui.lp(-2, -2), t.dp(8), 0, 0, 0));
        col.addView(head, Ui.margins(Ui.lp(-1, -2), t.dp(2), 0, 0, t.dp(10)));

        // diaries left behind by the dead, that this character has found
        found = Ui.col(c);
        found.setVisibility(GONE);
        col.addView(found, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(14)));

        writeBox = Ui.col(c);
        writeBox.setBackground(paper(14));
        writeBox.setPadding(t.dp(14), t.dp(12), t.dp(14), t.dp(12));
        input = new EditText(c);
        input.setHint("Write in your journal…");
        input.setHintTextColor(INK_SOFT);
        input.setTextColor(PEN);
        input.setTypeface(Typeface.create("casual", Typeface.BOLD));
        input.setTextSize(t.sp(16));
        input.setBackground(null);
        input.setMinLines(2);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        writeBox.addView(input, Ui.lp(-1, -2));
        LinearLayout row = Ui.row(c);
        row.addView(new View(c), Ui.weight(1));
        TextView save = Ui.text(c, t, "Write it down", 14, 0xFFFFFFFF, true);
        save.setPadding(t.dp(16), t.dp(9), t.dp(16), t.dp(9));
        save.setBackground(Ui.pressable(t, INK, 10));
        save.setOnClickListener(v -> {
            String s = input.getText().toString().trim();
            if (s.isEmpty()) return;
            host.command("note", Ui.args("s", s), null);
            input.setText("");
        });
        row.addView(save);
        writeBox.addView(row, Ui.margins(Ui.lp(-1, -2), 0, t.dp(4), 0, 0));
        col.addView(writeBox, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(14)));

        pages = Ui.col(c);
        col.addView(pages, Ui.lp(-1, -2));
        pages.addView(Ui.text(c, t, "Opening your journal…", 14, t.text3, false));
        showStatus();
    }

    private android.graphics.drawable.GradientDrawable paper(float radius) {
        android.graphics.drawable.GradientDrawable g = t.round(PAPER, radius);
        g.setStroke(t.dp(1), PAPER_EDGE);
        return g;
    }

    private void openWriter() {
        JournalWriterSheet.open(getContext(), host, writer);
    }

    void journal(JSONObject d) {
        String s = d.toString();
        if (s.equals(sig)) return;
        sig = s;
        data = d;
        writer.journal(d);
        render();
    }

    /** The left-behind diaries the character has with them or within reach ("diaries"). */
    void diaries(JSONObject d) {
        found.removeAllViews();
        View card = Diaries.card(getContext(), host, d);
        if (card != null) found.addView(card, Ui.lp(-1, -2));
        found.setVisibility(card != null ? VISIBLE : GONE);
    }

    /** The writer started or finished a page, or its settings changed. */
    @Override
    public void onWriterChanged() {
        render();
    }

    private void showStatus() {
        String[] st = writer.status();
        status.setText(st[0]);
        status.setTextColor("warn".equals(st[1]) ? t.warn : "busy".equals(st[1]) ? t.text2 : t.text3);
        status.setVisibility(st[0].isEmpty() ? INVISIBLE : VISIBLE);
    }

    private void render() {
        showStatus();
        JSONObject d = data;
        if (d == null) return;
        Context c = getContext();
        writeBox.setVisibility(d.optBoolean("write", true) ? VISIBLE : GONE);
        pages.removeAllViews();
        if (!d.optBoolean("write", true)) {
            pages.addView(Ui.text(c, t, "Your character can't read or write, but the days still go by.", 13.5f, t.text3, false),
                    Ui.margins(Ui.lp(-1, -2), t.dp(4), 0, 0, t.dp(12)));
        }
        JSONArray days = Screens.Json.arr(d.opt("days"));
        for (int i = 0; i < days.length(); i++) {
            JSONObject p = days.optJSONObject(i);
            if (p != null) pages.addView(page(c, p, d.optInt("today"), d.optBoolean("write", true)), Ui.margins(Ui.lp(-1, -2), 0, i == 0 ? 0 : t.dp(12), 0, 0));
        }
    }

    private View page(Context c, JSONObject p, int today, boolean canWrite) {
        LinearLayout page = Ui.col(c);
        page.setBackground(paper(6));
        page.setPadding(t.dp(16), t.dp(12), t.dp(16), t.dp(14));
        LinearLayout head = Ui.row(c);
        int d = p.optInt("d");
        TextView title = Ui.text(c, t, "Day " + d, 19, INK, true);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        head.addView(title, Ui.weight(1));
        if (d == today) head.addView(Ui.text(c, t, "Today", 12.5f, INK_SOFT, true));
        page.addView(head);
        View rule = new View(c);
        rule.setBackgroundColor(Theme.alpha(RED, 0.45f));
        page.addView(rule, Ui.margins(Ui.lp(-1, t.dp(1)), 0, t.dp(6), 0, t.dp(8)));

        // the diary entry, in the character's hand: the AI's when it's on and has written one
        boolean ai = writer.on() && canWrite;
        String aiText = ai ? writer.textFor(p) : null;
        String entry = aiText != null ? aiText : (canWrite ? p.optString("entry", "") : "");
        boolean hasEntry = !entry.isEmpty();
        if (hasEntry) {
            TextView prose = Ui.text(c, t, entry, 15.5f, INK, false);
            prose.setTypeface(Typeface.create("casual", Typeface.NORMAL));
            prose.setLineSpacing(t.dp(3), 1f);
            if (ai && hasFacts(p)) prose.setOnLongClickListener(v -> { rewriteSheet(d); return true; });
            page.addView(prose, Ui.lp(-1, -2));
        }
        // the day's photos, like prints tucked in the page
        JSONArray photos = Screens.Json.arr(p.opt("ph"));
        if (photos.length() > 0) page.addView(Photos.strip(c, host, photos, true), Ui.margins(Ui.lp(-1, -2), 0, t.dp(10), 0, t.dp(2)));
        boolean writing = ai && writer.writing(d);
        if (ai && (hasEntry || writing)) {
            TextView hint = Ui.text(c, t, writing ? "writing…" : aiText != null ? "written by AI" : "offline", 11.5f, INK_SOFT, false);
            hint.setTypeface(Typeface.create("serif", Typeface.ITALIC));
            hint.setGravity(Gravity.END);
            page.addView(hint, Ui.margins(Ui.lp(-1, -2), 0, t.dp(4), 0, 0));
        }

        // what happened: folded under the entry, or the page itself when there's no entry
        LinearLayout facts = Ui.col(c);
        int lines = 0;
        int kills = p.optInt("kills");
        if (kills > 0) {
            facts.addView(line(c, kills == 1 ? "Killed a zombie." : "Killed " + kills + " zombies.", null, "kill", hasEntry));
            lines++;
        }
        JSONArray ls = Screens.Json.arr(p.opt("lines"));
        for (int i = 0; i < ls.length(); i++) {
            JSONObject l = ls.optJSONObject(i);
            if (l == null) continue;
            facts.addView(line(c, l.optString("s"), l, l.optString("k"), hasEntry));
            lines++;
        }
        if (lines > 0 && hasEntry) {
            LinearLayout fold = Ui.row(c);
            fold.setPadding(0, t.dp(8), 0, t.dp(2));
            fold.addView(Ui.text(c, t, "What happened (" + lines + ")", 12.5f, INK_SOFT, true));
            ImageView chev = Ui.icon(c, t, "chevron", INK_SOFT, 14);
            fold.addView(chev, Ui.margins(Ui.lp(t.dp(14), t.dp(14)), t.dp(4), 0, 0, 0));
            boolean open = unfolded.contains(d);
            facts.setVisibility(open ? VISIBLE : GONE);
            chev.setRotation(open ? 90 : 0);
            fold.setOnClickListener(v -> {
                boolean show = facts.getVisibility() != VISIBLE;
                facts.setVisibility(show ? VISIBLE : GONE);
                chev.setRotation(show ? 90 : 0);
                if (show) unfolded.add(d);
                else unfolded.remove(d);
            });
            page.addView(fold, Ui.margins(Ui.lp(-1, -2), 0, t.dp(4), 0, 0));
        }
        if (lines > 0) page.addView(facts, Ui.lp(-1, -2));

        JSONArray notes = Screens.Json.arr(p.opt("notes"));
        for (int i = 0; i < notes.length(); i++) {
            JSONObject n = notes.optJSONObject(i);
            if (n == null) continue;
            TextView note = Ui.text(c, t, n.optString("s"), 16, PEN, false);
            note.setTypeface(Typeface.create("casual", Typeface.BOLD));
            note.setLineSpacing(t.dp(2), 1f);
            final int idx = i + 1;
            note.setOnLongClickListener(v -> { confirmDelete(d, idx); return true; });
            page.addView(note, Ui.margins(Ui.lp(-1, -2), 0, t.dp(8), 0, 0));
            if (n.optBoolean("w")) {
                TextView when = Ui.text(c, t, Fmt.clock(n.optInt("h"), n.optInt("mi")), 11.5f, INK_SOFT, false);
                when.setGravity(Gravity.END);
                page.addView(when, Ui.lp(-1, -2));
            }
            lines++;
        }
        if (lines == 0 && !hasEntry && photos.length() == 0) {
            TextView quiet = Ui.text(c, t, d == today ? "Nothing written yet today." : "A quiet day.", 14, INK_SOFT, false);
            quiet.setTypeface(Typeface.create("serif", Typeface.ITALIC));
            page.addView(quiet);
        }
        return page;
    }

    private static boolean hasFacts(JSONObject p) {
        return p.optInt("kills") > 0 || Screens.Json.arr(p.opt("lines")).length() > 0 || Screens.Json.arr(p.opt("notes")).length() > 0;
    }

    /** One thing that happened; small and soft when it sits under the diary entry. */
    private View line(Context c, String text, JSONObject l, String kind, boolean small) {
        boolean bite = "bite".equals(kind);
        LinearLayout r = Ui.row(c);
        r.setGravity(Gravity.TOP);
        TextView dot = Ui.text(c, t, "•", small ? 13.5f : 15, bite ? RED : INK_SOFT, true);
        r.addView(dot, Ui.margins(Ui.lp(t.dp(14), -2), 0, 0, 0, 0));
        TextView tv = Ui.text(c, t, text, small ? 13.5f : 14.5f, bite ? RED : small ? INK_SOFT : INK, bite);
        tv.setTypeface(Typeface.create("serif", bite ? Typeface.BOLD : Typeface.NORMAL));
        tv.setLineSpacing(t.dp(2), 1f);
        r.addView(tv, Ui.weight(1));
        if (l != null && l.optBoolean("w")) {
            r.addView(Ui.text(c, t, Fmt.clock(l.optInt("h"), l.optInt("mi")), 11.5f, INK_SOFT, false), Ui.margins(Ui.lp(-2, -2), t.dp(8), t.dp(2), 0, 0));
        }
        LinearLayout.LayoutParams lp = Ui.lp(-1, -2);
        lp.topMargin = t.dp(small ? 3 : 4);
        r.setLayoutParams(lp);
        return r;
    }

    private void rewriteSheet(int day) {
        Context c = getContext();
        LinearLayout sheet = Ui.col(c);
        sheet.addView(Ui.text(c, t, "Day " + day, 20, t.text, true));
        sheet.addView(Ui.text(c, t, "The AI writer can write this page again from what happened that day.", 13.5f, t.text3, false),
                Ui.margins(Ui.lp(-1, -2), 0, t.dp(4), 0, t.dp(14)));
        TextView again = Ui.action(c, t, "pen", "Write this page again");
        again.setOnClickListener(v -> { writer.rewrite(day); host.closeSheet(); });
        sheet.addView(again, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
        TextView keep = Ui.action(c, t, "x", "Keep it");
        keep.setOnClickListener(v -> host.closeSheet());
        sheet.addView(keep);
        host.openSheet(sheet);
    }

    private void confirmDelete(int day, int index) {
        Context c = getContext();
        LinearLayout sheet = Ui.col(c);
        sheet.addView(Ui.text(c, t, "Tear out this note?", 20, t.text, true), Ui.margins(Ui.lp(-2, -2), 0, 0, 0, t.dp(14)));
        TextView yes = Ui.action(c, t, "trash", "Tear it out");
        yes.setTextColor(t.bad);
        yes.setOnClickListener(v -> { host.command("noteDelete", Ui.args("d", day, "i", index), null); host.closeSheet(); });
        sheet.addView(yes, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
        TextView no = Ui.action(c, t, "x", "Keep it");
        no.setOnClickListener(v -> host.closeSheet());
        sheet.addView(no);
        host.openSheet(sheet);
    }
}
