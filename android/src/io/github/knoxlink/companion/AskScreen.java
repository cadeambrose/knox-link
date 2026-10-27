package io.github.knoxlink.companion;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * "Ask": questions about the game, answered by the AI the player set up for the journal (any of
 * its services), with the character's situation (KnoxLink_Ask.lua, on "ask") so the answer fits
 * what they have and where they are. Nothing goes out until a question is asked; one request each.
 */
final class AskScreen extends LinearLayout {
    private static final String[] STARTERS = {
            "What should I do next?", "How do I treat my wounds?", "What can I make with what I have?",
            "Where should I look for food and water?", "How do I get a generator running?"};

    private static final String SYSTEM = "You help someone playing Project Zomboid, Build 42, on a second screen next to the game. "
            + "Answer their question about the game: its mechanics, crafting, building, farming, cooking, medicine, fighting, "
            + "vehicles and how to survive. Use the character's situation given below: say which of the things they have, or "
            + "that are within reach, they can use, and what they still need. Keep it short and practical: a few sentences or a "
            + "short list, about 150 words at most unless the question needs steps. Use the game's own names for items, skills "
            + "and menus. Build 42 changed a lot from Build 41 (crafting, farming, animals, building); when you aren't sure "
            + "something works the same way in Build 42, say so rather than guess, and never make up items or recipes. "
            + "Don't mention the situation summary itself; just use it. Plain text, no markdown headings.";

    private final Host host;
    private final Theme t;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout list;
    private final ScrollView scroll;
    private final EditText input;
    private final TextView status;
    private final List<String[]> history = new ArrayList<>();   // {"q" | "a", text}
    private String situation = "", name = "";
    private boolean busy;

    AskScreen(Context c, Host host) {
        super(c);
        this.host = host;
        t = host.theme();
        setOrientation(VERTICAL);
        setPadding(t.dp(12), t.dp(10), t.dp(12), t.dp(10));

        LinearLayout head = Ui.row(c);
        head.addView(Ui.icon(c, t, "ask", t.accent, 22));
        LinearLayout titles = Ui.col(c);
        titles.addView(Ui.text(c, t, "Ask about the game", 17, t.text, true));
        status = Ui.ellipsize(Ui.text(c, t, "", 12.5f, t.text3, false));
        titles.addView(status, Ui.margins(Ui.lp(-1, -2), 0, t.dp(3), 0, 0));
        head.addView(titles, Ui.margins(Ui.weight(1), t.dp(10), 0, t.dp(8), 0));
        TextView clear = Ui.text(c, t, "New", 13.5f, t.text2, true);
        clear.setPadding(t.dp(12), t.dp(8), t.dp(12), t.dp(8));
        clear.setBackground(Ui.pressable(t, t.surface2, 10));
        clear.setOnClickListener(v -> { history.clear(); render(); });
        head.addView(clear);
        TextView setup = Ui.text(c, t, "AI", 13.5f, t.text2, true);
        setup.setPadding(t.dp(12), t.dp(8), t.dp(12), t.dp(8));
        setup.setBackground(Ui.pressable(t, t.surface2, 10));
        setup.setOnClickListener(v -> openSettings());
        head.addView(setup, Ui.margins(Ui.lp(-2, -2), t.dp(8), 0, 0, 0));
        addView(head, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(10)));

        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        list = Ui.col(c);
        scroll.addView(list);
        addView(scroll, new LayoutParams(-1, 0, 1));

        LinearLayout bar = Ui.row(c);
        input = new EditText(c);
        input.setHint("Ask anything about the game…");
        input.setTextColor(t.text);
        input.setHintTextColor(t.text3);
        input.setTextSize(t.sp(15));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setBackground(t.round(t.surface2, 12));
        input.setPadding(t.dp(14), t.dp(11), t.dp(14), t.dp(11));
        input.setOnEditorActionListener((v, a, e) -> {
            send();
            return true;
        });
        bar.addView(input, Ui.weight(1));
        ImageView go = new ImageView(c);
        go.setImageDrawable(t.icon("send", t.accentInk));
        go.setScaleType(ImageView.ScaleType.CENTER);
        go.setBackground(Ui.pressable(t, t.accent, 12));
        go.setOnClickListener(v -> send());
        bar.addView(go, Ui.margins(Ui.lp(t.dp(46), t.dp(44)), t.dp(8), 0, 0, 0));
        addView(bar, Ui.margins(Ui.lp(-1, -2), 0, t.dp(8), 0, 0));
        render();
    }

    /** The character's situation, as the game tells it while this tab is open. */
    void situation(JSONObject d) {
        if (d == null) return;
        situation = d.optString("text", "");
        name = d.optString("name", "");
    }

    /** The tab came into view: the AI's settings may have changed meanwhile. */
    void shown() {
        render();
    }

    private void openSettings() {
        AiWriter w = AiWriter.existing();
        if (w != null) JournalWriterSheet.open(getContext(), host, w);
    }

    private void send() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) return;
        input.setText("");
        ask(q);
    }

    private void ask(String q) {
        if (busy) return;
        AiClient.Config cfg = AiClient.config(host.prefs());
        String missing = cfg.missing();
        if (missing != null) {
            host.toast(missing + " (AI settings)", true);
            openSettings();
            return;
        }
        busy = true;
        history.add(new String[]{"q", q});
        render();
        StringBuilder user = new StringBuilder();
        user.append("The character's situation:\n").append(situation.isEmpty() ? "(not known yet)" : situation).append("\n\n");
        if (history.size() > 1) {
            user.append("Earlier in this conversation:\n");
            // the last few turns are enough to follow up on
            for (int i = Math.max(0, history.size() - 9); i < history.size() - 1; i++) {
                String[] h = history.get(i);
                user.append("q".equals(h[0]) ? "Player: " : "You: ").append(h[1]).append('\n');
            }
            user.append('\n');
        }
        user.append("The player asks: ").append(q);
        new Thread(() -> {
            String answer;
            boolean ok;
            try {
                answer = AiClient.complete(cfg, SYSTEM, user.toString(), 700);
                ok = true;
            } catch (AiClient.Failure f) {
                answer = f.getMessage();
                ok = false;
            }
            final String a = answer;
            final boolean good = ok;
            main.post(() -> {
                busy = false;
                if (good) history.add(new String[]{"a", a});
                else history.add(new String[]{"e", a});
                render();
            });
        }, "knoxlink-ask").start();
    }

    private void render() {
        Context c = getContext();
        AiClient.Config cfg = AiClient.config(host.prefs());
        String missing = cfg.missing();
        status.setText(missing != null ? "Set up an AI first: tap AI" : "Answered by " + cfg.describe() + (name.isEmpty() ? "" : " · knows how " + name + " is doing"));
        status.setTextColor(missing != null ? t.warn : t.text3);
        list.removeAllViews();
        if (history.isEmpty()) {
            TextView hello = Ui.text(c, t, "Questions about the game, answered with your character's situation in mind: what you carry, "
                    + "your wounds, your skills, where you are. It uses the AI you set up for the journal; each question is one request.",
                    14, t.text2, false);
            hello.setLineSpacing(t.dp(2), 1f);
            list.addView(hello, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(12)));
            for (String s : STARTERS) {
                TextView chip = Ui.text(c, t, s, 14.5f, t.text, true);
                chip.setPadding(t.dp(14), t.dp(11), t.dp(14), t.dp(11));
                chip.setBackground(Ui.pressable(t, t.surface2, 12));
                chip.setOnClickListener(v -> ask(s));
                list.addView(chip, Ui.margins(Ui.lp(-2, -2), 0, 0, 0, t.dp(8)));
            }
            return;
        }
        for (String[] h : history) {
            boolean q = "q".equals(h[0]), err = "e".equals(h[0]);
            TextView b = Ui.text(c, t, h[1], 15, q ? t.accentInk : err ? t.bad : t.text, q);
            b.setLineSpacing(t.dp(2), 1f);
            b.setPadding(t.dp(14), t.dp(10), t.dp(14), t.dp(10));
            b.setBackground(t.round(q ? t.accent : t.surface2, 14));
            LinearLayout.LayoutParams lp = Ui.margins(Ui.lp(-2, -2), q ? t.dp(48) : 0, 0, q ? 0 : t.dp(24), t.dp(8));
            lp.gravity = q ? Gravity.END : Gravity.START;
            list.addView(b, lp);
        }
        if (busy) {
            TextView wait = Ui.text(c, t, "Thinking…", 14, t.text3, false);
            wait.setPadding(t.dp(14), t.dp(6), t.dp(14), t.dp(6));
            list.addView(wait);
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
}
