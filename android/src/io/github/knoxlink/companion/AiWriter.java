package io.github.knoxlink.companion;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The optional AI diary writer. When it's on, every journal page the game marks as due (it has
 * something to tell and no AI entry for what it says now) gets one from the service the user
 * picked, in the character's voice and true to their story: a short profile written once per
 * character, which the user can edit. Entries go back to the game ("journalEntry") so they stay
 * in the save, and a copy stays on this device. A page it hasn't written shows the offline one.
 *
 * Cost stays low on purpose: one request at a time, today's page at most every ten minutes
 * (the game adds a few in-game hours on top), every other page once, never twice for the same
 * facts, and a ceiling per hour whatever happens.
 */
final class AiWriter {
    interface Listener {
        void onWriterChanged();
    }

    interface Done {
        void done(boolean ok, String text);
    }

    private static final long TODAY_EVERY_MS = 10 * 60_000L, RESEND_EVERY_MS = 2 * 60_000L;
    // the journal only arrives when something changed; past this, the game may well be gone
    private static final long STALE_MS = 20 * 60_000L;
    private static final int MAX_PER_HOUR = 30, MAX_CACHED = 60;

    private static AiWriter instance;

    /** One writer for the app: the journal screen is rebuilt (a theme change) while a page may be on its way. */
    static AiWriter get(Host host, Listener listener) {
        if (instance == null) instance = new AiWriter();
        instance.host = host;
        instance.listener = listener;
        return instance;
    }

    /** The journal's writer, as it is (for its settings sheet elsewhere), or null before the journal made it. */
    static AiWriter existing() {
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor(r -> {
        Thread th = new Thread(r, "knoxlink-ai");
        th.setDaemon(true);
        return th;
    });
    private Host host;
    private Listener listener;
    private JSONObject data;            // the last journal message
    private long dataAt;
    private boolean busy;
    private int busyDay = -1;           // the page being written (0: the character's story)
    private String error;               // the last failure, while waiting after it
    private long pauseUntil, toastedAt;
    private int failures;
    private final ArrayDeque<Long> calls = new ArrayDeque<>();
    // this session: pages asked for (character|day|rev), and when a device copy was last handed to the game
    private final Set<String> asked = new HashSet<>();
    private final Map<String, Long> resentAt = new HashMap<>(), todayAt = new HashMap<>();
    private final Map<String, JSONObject> copies = new HashMap<>();
    private final Runnable pumpLater = this::pump;
    private final Runnable notify = () -> {
        if (listener != null) listener.onWriterChanged();
    };

    private AiWriter() {}

    private SharedPreferences prefs() {
        return host.prefs();
    }

    // ------------------------------------------------------------------ what the screens ask

    boolean on() {
        return host != null && prefs().getBoolean(AiClient.ON, false);
    }

    void setOn(boolean on) {
        prefs().edit().putBoolean(AiClient.ON, on).apply();
        settingsChanged();
    }

    /** The service, model or key changed: try again (after a failure too). */
    void settingsChanged() {
        pauseUntil = 0;
        failures = 0;
        error = null;
        later(400);
        changed();
    }

    /** A setting is being typed: try once the typing stops. */
    void settingsTyped() {
        pauseUntil = 0;
        failures = 0;
        error = null;
        later(3000);
        changed();
    }

    void journal(JSONObject d) {
        data = d;
        dataAt = System.currentTimeMillis();
        pump();
    }

    JSONObject who() {
        return data == null ? null : data.optJSONObject("who");
    }

    /** False for a character who can't read or write: no diary then. */
    boolean canWrite() {
        return data == null || data.optBoolean("write", true);
    }

    /** One character's journal: the save's world and the journal's own id. */
    String charKey() {
        JSONObject w = who();
        if (w == null) return null;
        return w.optString("world", "") + "/" + w.optString("id", w.optString("name", ""));
    }

    /** The character's story the AI keeps to (null: not written yet; empty: the user wants none). */
    String profile() {
        String k = charKey();
        return k == null || host == null ? null : prefs().getString("aiProfile_" + k, null);
    }

    void setProfile(String text) {
        String k = charKey();
        if (k != null) prefs().edit().putString("aiProfile_" + k, text).apply();
    }

    /** The AI entry to show for a page: the game's copy, or this device's while the game catches up. */
    String textFor(JSONObject page) {
        String s = page.optString("ai", "");
        if (!s.isEmpty()) return s;
        String key = charKey();
        if (key == null) return null;
        JSONObject mine = copies(key).optJSONObject(String.valueOf(page.optInt("d")));
        String t = mine == null ? "" : mine.optString("s", "");
        return t.isEmpty() ? null : t;
    }

    boolean writing(int day) {
        return busy && busyDay == day;
    }

    /** One line for the journal's header: {text, kind}, kind "", "busy" or "warn". */
    String[] status() {
        if (!canWrite()) return new String[]{"", ""};
        if (!on()) return new String[]{"Written offline, in your character's voice", ""};
        AiClient.Config c = AiClient.config(prefs());
        String miss = c.missing();
        if (miss != null) return new String[]{"AI writer: " + lowerFirst(miss) + " (tap Writer)", "warn"};
        if (busy) return new String[]{busyDay > 0 ? "Writing day " + busyDay + "…" : "Writing your character's story…", "busy"};
        long now = System.currentTimeMillis();
        if (error != null && now < pauseUntil) return new String[]{"AI writer paused: " + error, "warn"};
        if (!budget(now)) return new String[]{"AI writer resting: enough pages for this hour", "warn"};
        return new String[]{"Written by " + c.describe(), ""};
    }

    /** Writes a page again (press and hold it): the game drops its AI entry, so the page is due again. */
    void rewrite(int day) {
        String key = charKey();
        if (key == null) return;
        JSONObject mine = copies(key);
        mine.remove(String.valueOf(day));
        prefs().edit().putString("aiPages_" + key, mine.toString()).apply();
        String prefix = key + "|" + day + "|";
        asked.removeIf(s -> s.startsWith(prefix));
        resentAt.keySet().removeIf(s -> s.startsWith(prefix));
        todayAt.remove(key);
        host.command("journalEntry", Ui.args("d", day, "s", "", "r", 0), null);
        changed();
    }

    /** Writes the character's story again (the Writer sheet). */
    void newProfile(Done done) {
        JSONObject who = who();
        String key = charKey();
        AiClient.Config cfg = AiClient.config(prefs());
        String miss = cfg.missing();
        if (who == null || key == null) done.done(false, "Open the journal in a game first");
        else if (miss != null) done.done(false, miss);
        else if (busy) done.done(false, "Busy with a page, try again in a moment");
        else writeProfile(cfg, key, who, done);
    }

    /** A tiny request to see that the service, model and key work. */
    void test(Done done) {
        AiClient.Config cfg = AiClient.config(prefs());
        String miss = cfg.missing();
        if (miss != null) {
            done.done(false, miss);
            return;
        }
        Thread th = new Thread(() -> {
            String text = null, err = null;
            try {
                text = AiClient.complete(cfg, "You check that a connection works.", "Reply with just the word OK.", 20);
            } catch (AiClient.Failure f) {
                err = f.getMessage();
            }
            String t = text, e = err;
            main.post(() -> {
                if (t != null) settingsChanged();
                done.done(t != null, t != null ? t : e);
            });
        }, "knoxlink-ai-test");
        th.setDaemon(true);
        th.start();
    }

    // ------------------------------------------------------------------ writing

    private void changed() {
        main.removeCallbacks(notify);
        main.post(notify);
    }

    private void later(long ms) {
        main.removeCallbacks(pumpLater);
        main.postDelayed(pumpLater, Math.max(400, ms));
    }

    private boolean budget(long now) {
        while (!calls.isEmpty() && now - calls.peekFirst() > 3_600_000L) calls.pollFirst();
        return calls.size() < MAX_PER_HOUR;
    }

    /** Looks for the next page to write and starts on it (one at a time). */
    private void pump() {
        main.removeCallbacks(pumpLater);
        if (host == null || busy || data == null || !on() || !canWrite()) return;
        JSONObject who = who();
        String key = charKey();
        if (who == null || key == null) return;
        AiClient.Config cfg = AiClient.config(prefs());
        if (cfg.missing() != null) return;
        long now = System.currentTimeMillis();
        if (now - dataAt > STALE_MS) return;   // the next journal message starts it again
        if (now < pauseUntil) {
            later(pauseUntil - now);
            return;
        }
        if (!budget(now)) {
            later(3_600_000L - (now - calls.peekFirst()) + 1000);
            changed();
            return;
        }
        if (profile() == null) {
            writeProfile(cfg, key, who, null);
            return;
        }
        JSONArray days = Screens.Json.arr(data.opt("days"));
        int today = data.optInt("today");
        long wait = -1;
        for (int i = 0; i < days.length(); i++) {   // newest first
            JSONObject p = days.optJSONObject(i);
            if (p == null || !p.optBoolean("due")) continue;
            int d = p.optInt("d"), rev = p.optInt("rev");
            String id = key + "|" + d + "|" + rev;
            JSONObject mine = copies(key).optJSONObject(String.valueOf(d));
            if (mine != null && mine.optInt("r", -1) == rev) {
                // written already, the game just doesn't have it (yet): hand it over instead of paying twice
                Long at = resentAt.get(id);
                if (at == null || now - at > RESEND_EVERY_MS) {
                    resentAt.put(id, now);
                    send(d, mine.optString("s"), rev);
                }
                continue;
            }
            if (asked.contains(id)) continue;
            if (d == today && p.has("ai")) {
                Long at = todayAt.get(key);
                if (at != null && now - at < TODAY_EVERY_MS) {
                    long w = TODAY_EVERY_MS - (now - at);
                    wait = wait < 0 ? w : Math.min(wait, w);
                    continue;
                }
            }
            writePage(cfg, key, who, days, i, today);
            return;
        }
        if (wait > 0) later(wait + 500);
    }

    private void writePage(AiClient.Config cfg, String key, JSONObject who, JSONArray days, int i, int today) {
        JSONObject p = days.optJSONObject(i);
        int d = p.optInt("d"), rev = p.optInt("rev");
        String id = key + "|" + d + "|" + rev;
        asked.add(id);
        String prompt = pagePrompt(who, profile(), p, d == today && !p.optBoolean("fin"), previous(days, i), bites(days, i));
        start(d);
        net.execute(() -> {
            String text = null, err = null;
            try {
                text = AiClient.complete(cfg, PAGE_SYSTEM, prompt, 700);
            } catch (AiClient.Failure f) {
                err = f.getMessage();
            }
            String t = text, e = err;
            main.post(() -> {
                stop();
                if (t == null) {
                    asked.remove(id);
                    failed(e);
                    return;
                }
                remember(key, d, t, rev);
                long done = System.currentTimeMillis();
                if (d == today) todayAt.put(key, done);
                resentAt.put(id, done);
                if (key.equals(charKey())) send(d, t, rev);
                succeeded();
            });
        });
    }

    private void writeProfile(AiClient.Config cfg, String key, JSONObject who, Done done) {
        String prompt = profilePrompt(who, firstTown());
        start(0);
        net.execute(() -> {
            String text = null, err = null;
            try {
                text = unlabel(AiClient.complete(cfg, PROFILE_SYSTEM, prompt, 600));
            } catch (AiClient.Failure f) {
                err = f.getMessage();
            }
            String t = text, e = err;
            main.post(() -> {
                stop();
                if (t != null) {
                    prefs().edit().putString("aiProfile_" + key, t).apply();
                    succeeded();
                } else {
                    failed(e);
                }
                if (done != null) done.done(t != null, t != null ? t : e);
            });
        });
    }

    private void start(int day) {
        busy = true;
        busyDay = day;
        calls.addLast(System.currentTimeMillis());
        changed();
    }

    private void stop() {
        busy = false;
        busyDay = -1;
    }

    private void succeeded() {
        failures = 0;
        error = null;
        pauseUntil = 0;
        changed();
        later(1500);   // the next page, if there is one
    }

    /** Waits a while (longer each time it fails again), says why once, and shows the offline page meanwhile. */
    private void failed(String why) {
        failures++;
        error = why;
        long now = System.currentTimeMillis();
        long pause = failures == 1 ? 60_000L : failures == 2 ? 5 * 60_000L : 15 * 60_000L;
        pauseUntil = now + pause;
        if (failures == 1 || now - toastedAt > 10 * 60_000L) {
            toastedAt = now;
            if (host != null) host.toast("AI writer: " + why, true);
        }
        changed();
        later(pause);
    }

    private void send(int day, String text, int rev) {
        if (host != null) host.command("journalEntry", Ui.args("d", day, "s", text, "r", rev), null);
    }

    /** This device's copies of a character's AI pages: {"12": {"s": text, "r": rev}}. */
    private JSONObject copies(String key) {
        JSONObject c = copies.get(key);
        if (c == null) {
            try {
                c = new JSONObject(prefs().getString("aiPages_" + key, "{}"));
            } catch (Exception e) {
                c = new JSONObject();
            }
            copies.put(key, c);
        }
        return c;
    }

    private void remember(String key, int day, String text, int rev) {
        JSONObject c = copies(key);
        try {
            c.put(String.valueOf(day), new JSONObject().put("s", text).put("r", rev));
            if (c.length() > MAX_CACHED) {
                List<Integer> ds = new ArrayList<>();
                for (Iterator<String> it = c.keys(); it.hasNext(); ) {
                    try {
                        ds.add(Integer.parseInt(it.next()));
                    } catch (NumberFormatException ignored) {
                    }
                }
                Collections.sort(ds);
                for (int i = 0; i < ds.size() - MAX_CACHED; i++) c.remove(String.valueOf(ds.get(i)));
            }
        } catch (Exception ignored) {
        }
        prefs().edit().putString("aiPages_" + key, c.toString()).apply();
    }

    // ------------------------------------------------------------------ prompts

    static final String PAGE_SYSTEM = "You write one page of a survivor's diary in Project Zomboid: Knox County, Kentucky, 1993, "
            + "after the dead started walking and the county was sealed off. No rescue is coming. Write as the survivor, in the first person, like a real "
            + "handwritten diary: personal, specific, plain words, sometimes raw. 3 to 8 sentences, under 150 words. "
            + "Use only what the survivor knows: the facts given, how they felt, the weather and the places. Don't invent big events "
            + "(no new people, injuries, fights, rescues or finds); small sensory details, thoughts and memories that fit their story "
            + "are welcome. Never use game terms: no levels, skill numbers, XP, moodles or stats, and no exact clock times. "
            + "Don't list the facts back. No title, date line, signature, quotes around the text or markdown.";

    static final String PROFILE_SYSTEM = "You create a believable, ordinary person for a survivor's diary in Project Zomboid "
            + "(Knox County, Kentucky, 1993, a zombie outbreak has just begun). Plain text, no markdown or headings.";

    private static final String[][] MOODS = {
            {"PANIC", "panicked"}, {"PAIN", "in pain"}, {"SICK", "nauseous"}, {"STRESS", "stressed"}, {"UNHAPPY", "unhappy"},
            {"HYPOTHERMIA", "freezing"}, {"HYPERTHERMIA", "overheated"}, {"HAS_A_COLD", "sick with a cold"}, {"DRUNK", "drunk"},
            {"HUNGRY", "hungry"}, {"THIRST", "thirsty"}, {"ENDURANCE", "exhausted"}, {"TIRED", "tired"}, {"WET", "soaked"},
            {"BORED", "bored"}, {"ANGRY", "angry"}};
    private static final String[][] WEATHER = {
            {"storm", "a thunderstorm"}, {"rain", "rain"}, {"snow", "snow"}, {"fog", "thick fog"}, {"wind", "strong wind"},
            {"hot", "great heat"}, {"cold", "freezing cold"}};

    private static String who(JSONObject who) {
        StringBuilder sb = new StringBuilder(who.optString("name", "A survivor"));
        sb.append(who.optBoolean("female") ? " (a woman)" : " (a man)");
        String job = who.optString("prof", "");
        if (!job.isEmpty()) sb.append(". Job before the outbreak: ").append(job);
        JSONArray traits = Screens.Json.arr(who.opt("traits"));
        if (traits.length() > 0) {
            sb.append(". Traits: ");
            for (int i = 0; i < traits.length(); i++) sb.append(i > 0 ? ", " : "").append(traits.optString(i));
        }
        return sb.append('.').toString();
    }

    static String pagePrompt(JSONObject who, String profile, JSONObject p, boolean going, String previous, List<String> bites) {
        int d = p.optInt("d");
        StringBuilder sb = new StringBuilder("Who is writing: ").append(who(who)).append('\n');
        if (profile != null && !profile.trim().isEmpty()) sb.append("Their story and voice (stay true to it):\n").append(profile.trim()).append('\n');
        sb.append("\nDay ").append(d).append(" since it started for them");
        String date = dateOf(who, d);
        if (date != null) sb.append(", around ").append(date);
        sb.append(going ? ". The day isn't over yet: write the page so far.\n" : ". The day is over.\n");
        JSONArray at = Screens.Json.arr(p.opt("at"));
        if (at.length() == 1) sb.append("Where: around ").append(at.optString(0)).append(".\n");
        else if (at.length() > 1) sb.append("Where: went from ").append(at.optString(0)).append(" to ").append(at.optString(at.length() - 1)).append(".\n");
        if (p.optBoolean("woods")) sb.append("Spent time in the woods.\n");
        JSONObject wx = p.optJSONObject("wx");
        if (wx != null) {
            List<String> w = new ArrayList<>();
            for (String[] x : WEATHER) if (wx.optBoolean(x[0])) w.add(x[1]);
            if (!w.isEmpty()) sb.append("Weather: ").append(String.join(", ", w)).append(".\n");
        }
        JSONArray lines = Screens.Json.arr(p.opt("lines"));
        int kills = p.optInt("kills");
        if (lines.length() > 0 || kills > 0) {
            sb.append("What happened, in order:\n");
            for (int i = 0; i < lines.length(); i++) {
                JSONObject l = lines.optJSONObject(i);
                if (l == null) continue;
                String s = l.optString("s");
                // "Sprinting is now level 2." -> a feeling of getting better, not a number
                if ("skill".equals(l.optString("k"))) s = "Got better at " + s.replaceAll(" is now level \\d+\\.?$", "") + ".";
                sb.append("- ").append(period(l.optInt("h", -1))).append(s).append('\n');
            }
            if (kills > 0) sb.append("- Over the day: killed ").append(kills == 1 ? "one zombie" : kills + " zombies").append(".\n");
        }
        JSONArray notes = Screens.Json.arr(p.opt("notes"));
        if (notes.length() > 0) {
            sb.append("Their own notes from that day (their words; you may echo them):\n");
            for (int i = 0; i < notes.length(); i++) {
                JSONObject n = notes.optJSONObject(i);
                if (n != null) sb.append("- \"").append(n.optString("s")).append("\"\n");
            }
        }
        JSONObject peak = p.optJSONObject("peak");
        if (peak != null) {
            List<String> m = new ArrayList<>();
            for (String[] x : MOODS) {
                int lvl = peak.optInt(x[0]);
                if (lvl >= 2) m.add(lvl >= 3 && !x[1].contains(" ") ? "very " + x[1] : x[1]);
            }
            if (!m.isEmpty()) sb.append("How they felt at times that day: ").append(String.join(", ", m)).append(".\n");
        }
        if (!bites.isEmpty()) sb.append("Before that day: ").append(String.join(" ", bites)).append('\n');
        if (previous != null) sb.append("\nThe page before, for continuity (don't repeat it):\n").append(previous).append('\n');
        return sb.append("\nWrite the diary page for day ").append(d).append('.').toString();
    }

    static String profilePrompt(JSONObject who, String town) {
        StringBuilder sb = new StringBuilder("Name: ").append(who(who)).append('\n');
        if (town != null) sb.append("Their first days were around ").append(town).append(".\n");
        String date = dateOf(who, 1);
        if (date != null) sb.append("It began for them around ").append(date).append(".\n");
        return sb.append("Write two short paragraphs, with no numbers, labels or headings:\n")
                .append("First their story, 3 to 5 sentences: where in Knox County they lived, family, what life was like before, ")
                .append("and one detail that makes them human. Fit the job and traits.\n")
                .append("Then their voice, 2 to 4 short sentences: how they write in a diary (tone, sentence length, habits, words they use or avoid).\n")
                .append("Under 170 words in total.").toString();
    }

    /** Without a "1. Their story" or "Voice:" line a model may still put above a part. */
    static String unlabel(String profile) {
        return profile.replaceAll("(?im)^[ \\t]*(\\d[.)][ \\t]*)?(their[ \\t]+)?(story|voice)[ \\t]*:?[ \\t]*(\\r?\\n|$)", "").trim();
    }

    private static String period(int h) {
        if (h < 0) return "";
        if (h >= 5 && h < 12) return "morning: ";
        if (h >= 12 && h < 17) return "afternoon: ";
        if (h >= 17 && h < 21) return "evening: ";
        return "night: ";
    }

    /** "Tuesday, July 13, 1993": day 1 is the calendar day the character started on. */
    private static String dateOf(JSONObject who, int day) {
        JSONObject s = who.optJSONObject("start");
        if (s == null || s.optInt("y") <= 0) return null;
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(s.optInt("y"), s.optInt("mo", 1) - 1, s.optInt("d", 1));
        c.add(Calendar.DAY_OF_MONTH, day - 1);
        return new java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(c.getTime());
    }

    /** The previous page's words (AI or offline), for continuity. */
    private String previous(JSONArray days, int i) {
        JSONObject prev = days.optJSONObject(i + 1);
        if (prev == null) return null;
        String t = textFor(prev);
        if (t == null) t = prev.optString("entry", "");
        if (t.isEmpty()) return null;
        return AiClient.shorten(t, 700);
    }

    /** Bites on earlier pages: the dread carries over. */
    private static List<String> bites(JSONArray days, int i) {
        List<String> out = new ArrayList<>();
        for (int j = i + 1; j < days.length() && out.size() < 3; j++) {
            JSONObject p = days.optJSONObject(j);
            JSONArray lines = p == null ? new JSONArray() : Screens.Json.arr(p.opt("lines"));
            for (int k = 0; k < lines.length(); k++) {
                JSONObject l = lines.optJSONObject(k);
                if (l != null && "bite".equals(l.optString("k"))) out.add("Day " + p.optInt("d") + ": " + l.optString("s"));
            }
        }
        return out;
    }

    /** The first town on the oldest page that has one. */
    private String firstTown() {
        JSONArray days = data == null ? new JSONArray() : Screens.Json.arr(data.opt("days"));
        for (int i = days.length() - 1; i >= 0; i--) {
            JSONObject p = days.optJSONObject(i);
            JSONArray at = p == null ? new JSONArray() : Screens.Json.arr(p.opt("at"));
            if (at.length() > 0) return at.optString(0);
        }
        return null;
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
