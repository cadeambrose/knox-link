package io.github.knoxlink.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The journal's Writer sheet: who writes the diary pages (the offline writer, or an AI service
 * of the user's choice with their own key), the model, a test, and the character's story the AI
 * keeps to. Everything is saved as it's typed, on this device only.
 */
final class JournalWriterSheet {
    private JournalWriterSheet() {}

    interface Picked {
        void picked(int index);
    }

    interface Typed {
        void typed(String text);
    }

    static void open(Context c, Host host, AiWriter w) {
        Theme t = host.theme();
        SharedPreferences prefs = host.prefs();
        LinearLayout sheet = Ui.col(c);
        sheet.addView(Ui.text(c, t, "Diary writer", 20, t.text, true));
        sheet.addView(Ui.text(c, t, "Who writes the pages, in your character's voice.", 13.5f, t.text3, false),
                Ui.margins(Ui.lp(-1, -2), 0, t.dp(4), 0, t.dp(14)));

        boolean ai = w.on();
        sheet.addView(segment(c, t, new String[]{"Offline", "AI"}, ai ? 1 : 0, i -> {
            w.setOn(i == 1);
            open(c, host, w);
        }), Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(12)));
        if (!w.canWrite()) {
            sheet.addView(hint(c, t, "Your character can't read or write, so there's no diary for now."), Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(10)));
        }
        if (!ai) {
            sheet.addView(hint(c, t, "Knox Link writes each page in the game, from what happened that day, how your character felt "
                    + "and who they are. Free, private, and it works without internet."));
            host.openSheet(sheet);
            return;
        }
        sheet.addView(hint(c, t, "An AI service you pick writes the pages, with your own key. It gets only the diary: your "
                + "character's name, job, traits and story, and the day's events. Until it has written a page, you see the offline one."));

        AiClient.Config cfg = AiClient.config(prefs);
        String provider = cfg.provider;

        heading(c, t, sheet, "Service");
        Widgets.Flow services = new Widgets.Flow(c, t.dp(8), t.dp(8));
        for (String p : AiClient.PROVIDERS) {
            TextView chip = chip(c, t, AiClient.label(p), p.equals(provider));
            chip.setOnClickListener(v -> {
                if (p.equals(provider)) return;
                prefs.edit().putString(AiClient.PROVIDER, p).apply();
                w.settingsChanged();
                open(c, host, w);
            });
            services.addView(chip);
        }
        sheet.addView(services, Ui.lp(-1, -2));

        boolean custom = "custom".equals(provider);
        if (custom) {
            heading(c, t, sheet, "Server address");
            EditText base = field(c, t, prefs.getString(AiClient.BASE, ""), "http://192.168.1.20:11434/v1",
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            watch(base, s -> {
                prefs.edit().putString(AiClient.BASE, s.trim()).apply();
                w.settingsTyped();
            });
            sheet.addView(base, Ui.lp(-1, -2));
            sheet.addView(hint(c, t, "Any server that speaks OpenAI's API. For a model on your PC, Ollama is at "
                    + "http://PC-ADDRESS:11434/v1 and LM Studio at http://PC-ADDRESS:1234/v1. Let it accept connections "
                    + "from your network (Ollama: OLLAMA_HOST=0.0.0.0)."), Ui.margins(Ui.lp(-1, -2), 0, t.dp(6), 0, 0));
        }

        heading(c, t, sheet, "Model");
        EditText model = field(c, t, cfg.model, custom ? "llama3.2" : AiClient.defaultModel(provider),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        watch(model, s -> {
            prefs.edit().putString(AiClient.modelKey(provider), s.trim()).apply();
            w.settingsTyped();
        });
        sheet.addView(model, Ui.lp(-1, -2));
        String[] models = AiClient.models(provider);
        if (models.length > 0) {
            Widgets.Flow picks = new Widgets.Flow(c, t.dp(6), t.dp(6));
            for (String m : models) {
                TextView chip = chip(c, t, m, m.equals(cfg.model));
                chip.setTextSize(t.sp(12.5f));
                chip.setOnClickListener(v -> {
                    model.setText(m);
                    model.setSelection(m.length());
                    open(c, host, w);
                });
                picks.addView(chip);
            }
            sheet.addView(picks, Ui.margins(Ui.lp(-1, -2), 0, t.dp(8), 0, 0));
        }
        sheet.addView(hint(c, t, modelHint(provider)), Ui.margins(Ui.lp(-1, -2), 0, t.dp(6), 0, 0));

        heading(c, t, sheet, "API key");
        LinearLayout keyRow = Ui.row(c);
        int hidden = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        EditText key = field(c, t, cfg.key, custom ? "Not needed for most servers" : "Paste your key here", hidden);
        key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        watch(key, s -> {
            prefs.edit().putString(AiClient.keyKey(provider), s.trim()).apply();
            w.settingsTyped();
        });
        keyRow.addView(key, Ui.weight(1));
        TextView show = Ui.text(c, t, "Show", 14, t.text2, true);
        show.setGravity(Gravity.CENTER);
        show.setPadding(t.dp(14), 0, t.dp(14), 0);
        show.setBackground(Ui.pressable(t, t.surface2, 12));
        show.setOnClickListener(v -> {
            boolean shown = key.getInputType() != hidden;
            key.setInputType(shown ? hidden : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            key.setSelection(key.getText().length());
            show.setText(shown ? "Show" : "Hide");
        });
        keyRow.addView(show, Ui.margins(Ui.lp(-2, t.dp(44)), t.dp(8), 0, 0, 0));
        sheet.addView(keyRow, Ui.lp(-1, -2));
        sheet.addView(hint(c, t, (custom ? "Leave it empty if your server doesn't ask for one. " : "") + "Kept on this device only."),
                Ui.margins(Ui.lp(-1, -2), 0, t.dp(6), 0, 0));

        LinearLayout testRow = Ui.row(c);
        TextView test = Ui.text(c, t, "Test", 14.5f, t.accentInk, true);
        test.setGravity(Gravity.CENTER);
        test.setPadding(t.dp(18), 0, t.dp(18), 0);
        test.setBackground(Ui.pressable(t, t.accent, 12));
        testRow.addView(test, Ui.lp(-2, t.dp(42)));
        TextView result = Ui.text(c, t, "", 13.5f, t.text2, false);
        testRow.addView(result, Ui.margins(Ui.weight(1), t.dp(12), 0, 0, 0));
        test.setOnClickListener(v -> {
            result.setTextColor(t.text2);
            result.setText("Testing…");
            w.test((ok, text) -> {
                result.setTextColor(ok ? t.good : t.bad);
                result.setText(ok ? "It works. The model said “" + AiClient.shorten(text.replace('\n', ' '), 40) + "”" : text);
            });
        });
        sheet.addView(testRow, Ui.margins(Ui.lp(-1, -2), 0, t.dp(14), 0, 0));

        // the character's story, written once by the AI so every page sounds like the same person
        heading(c, t, sheet, "Character");
        JSONObject who = w.who();
        if (who == null) {
            sheet.addView(hint(c, t, "Load a save and open the journal, and your character shows up here."));
            host.openSheet(sheet);
            return;
        }
        sheet.addView(Ui.text(c, t, who.optString("name", "Your character"), 16, t.text, true));
        String about = aboutLine(who);
        if (!about.isEmpty()) sheet.addView(Ui.text(c, t, about, 13, t.text3, false), Ui.margins(Ui.lp(-1, -2), 0, t.dp(3), 0, 0));
        sheet.addView(hint(c, t, "Their story and how they write. The AI writes it the first time and keeps to it on every page. "
                + "Change anything you like."), Ui.margins(Ui.lp(-1, -2), 0, t.dp(8), 0, t.dp(8)));
        String story = w.profile();
        EditText storyField = field(c, t, story == null ? "" : story, "Written with the first page", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        storyField.setMinLines(4);
        storyField.setGravity(Gravity.TOP | Gravity.START);
        storyField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1500)});
        storyField.setLineSpacing(t.dp(2), 1f);
        watch(storyField, w::setProfile);
        sheet.addView(storyField, Ui.lp(-1, -2));
        TextView again = Ui.action(c, t, "pen", "Write a new one");
        again.setOnClickListener(v -> {
            again.setText("Writing…");
            again.setEnabled(false);
            w.newProfile((ok, text) -> {
                again.setText("Write a new one");
                again.setEnabled(true);
                if (ok) storyField.setText(text);
                else host.toast(text, true);
            });
        });
        sheet.addView(again, Ui.margins(Ui.lp(-1, -2), 0, t.dp(10), 0, 0));
        host.openSheet(sheet);
    }

    private static String modelHint(String provider) {
        switch (provider) {
            case "openai": return "The mini models cost the least and write well enough for a diary.";
            case "gemini": return "Flash is cheap and quick.";
            case "openrouter": return "Any model on OpenRouter, written as maker/model.";
            case "custom": return "The model's name on your server, as it lists it.";
            default: return "Haiku costs the least. Sonnet and Opus write better and cost more.";
        }
    }

    private static String aboutLine(JSONObject who) {
        StringBuilder sb = new StringBuilder(who.optString("prof", ""));
        JSONArray traits = Screens.Json.arr(who.opt("traits"));
        for (int i = 0; i < traits.length(); i++) sb.append(sb.length() > 0 ? " · " : "").append(traits.optString(i));
        return sb.toString();
    }

    private static void heading(Context c, Theme t, LinearLayout sheet, String s) {
        sheet.addView(Ui.label(c, t, s), Ui.margins(Ui.lp(-1, -2), 0, t.dp(18), 0, t.dp(8)));
    }

    private static TextView hint(Context c, Theme t, String s) {
        TextView v = Ui.text(c, t, s, 12.5f, t.text3, false);
        v.setLineSpacing(t.dp(1), 1f);
        return v;
    }

    private static EditText field(Context c, Theme t, String text, String hint, int type) {
        EditText e = new EditText(c);
        // single line first: setSingleLine after a password type would undo its dots
        e.setSingleLine((type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        e.setInputType(type);
        e.setText(text);
        e.setHint(hint);
        e.setHintTextColor(t.text3);
        e.setTextColor(t.text);
        e.setTextSize(t.sp(15));
        e.setBackground(t.round(t.surface2, 12));
        e.setPadding(t.dp(12), t.dp(10), t.dp(12), t.dp(10));
        return e;
    }

    private static void watch(EditText e, Typed typed) {
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) {}

            @Override public void onTextChanged(CharSequence s, int a, int b, int n) {}

            @Override public void afterTextChanged(Editable s) {
                typed.typed(s.toString());
            }
        });
    }

    private static TextView chip(Context c, Theme t, String s, boolean on) {
        TextView v = Ui.text(c, t, s, 14, on ? t.accentInk : t.text2, true);
        v.setPadding(t.dp(13), t.dp(8), t.dp(13), t.dp(8));
        if (on) {
            v.setBackground(t.round(t.accent, 99));
        } else {
            android.graphics.drawable.GradientDrawable g = t.round(t.surface2, 99);
            g.setStroke(t.dp(1), t.line);
            v.setBackground(g);
        }
        return v;
    }

    /** A row of choices like the Settings screen's (Offline | AI). */
    private static LinearLayout segment(Context c, Theme t, String[] options, int selected, Picked picked) {
        LinearLayout seg = Ui.row(c);
        seg.setBackground(t.round(t.surface2, 12));
        seg.setPadding(t.dp(4), t.dp(4), t.dp(4), t.dp(4));
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView b = Ui.text(c, t, options[i], 14.5f, i == selected ? t.text : t.text2, true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, t.dp(10), 0, t.dp(10));
            if (i == selected) b.setBackground(t.round(t.isLight() ? 0xFFFFFFFF : t.surface, 9));
            b.setOnClickListener(v -> {
                if (idx != selected) picked.picked(idx);
            });
            seg.addView(b, Ui.weight(1));
        }
        return seg;
    }
}
