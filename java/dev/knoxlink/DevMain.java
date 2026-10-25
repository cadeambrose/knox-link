package knoxlink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the server outside the game with made-up state, for working on the web app.
 * Not part of the mod jar. Usage: java -cp build/dev;build/classes -Dknoxlink.media=<game>/media knoxlink.DevMain [options]
 *
 * Options: car (driving), mp (multiplayer chat), imm (immersion, digital watch), analog (immersion, analog watch),
 * nowatch (immersion, no watch), dark (too dark to read the map), nomap (no world map in this world),
 * fog (the map shows only explored ground), bed (a bed close by), woman (a female character).
 */
public final class DevMain {
    static boolean mp, imm, analog, noWatch, dark, noMap, fog, bed, female;
    // the body part a screen has open (its treatments go out with "body"), the radar's range, Settings > Game
    static int healthPart = -1, radarRange = 20, fpsCap = 60;
    static String perfMode = "balanced";
    static boolean perfAuto = true, guideOn = false;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (System.getProperty("knoxlink.home") == null) System.setProperty("knoxlink.home", "build/dev-home");
        KnoxServer.get().start();
        Log.info("dev server up: " + KnoxServer.get().status().get("urls"));
        List<String> opts = Arrays.asList(args);
        boolean driving = opts.contains("car");
        mp = opts.contains("mp");
        analog = opts.contains("analog");
        noWatch = opts.contains("nowatch");
        imm = opts.contains("imm") || analog || noWatch;
        dark = opts.contains("dark");
        noMap = opts.contains("nomap");
        fog = opts.contains("fog");
        bed = opts.contains("bed");
        female = opts.contains("woman");
        double t = 0;
        Map<String, Object> wp = null;
        Map<String, Object> base = m("x", 10600.0, "y", 9420.0, "n", "Farmhouse");
        List<Object> marks = l(m("i", 0.0, "sym", "House", "x", 10600.0, "y", 9420.0, "c", "212121"),
                m("i", 1.0, "sym", "Skull", "x", 10690.0, "y", 9355.0, "c", "a60e0e"),
                m("i", 2.0, "text", "Lots of zeds", "x", 10700.0, "y", 9372.0, "c", "a60e0e"),
                m("i", 3.0, "sym", "MedCross", "x", 10580.0, "y", 9310.0, "c", "28307d"));
        List<Object> chat = new ArrayList<>();
        chat.add(m("k", "say", "a", "Ash", "s", "Anyone near the gas station?", "t", m("h", 14.0, "mi", 2.0, "w", true)));
        chat.add(m("k", "faction", "a", "Mira", "s", "Meet back at the farm before dark.", "t", m("h", 14.0, "mi", 5.0, "w", true)));
        int chatCount = 2;
        boolean radioCleared = false;
        Map<String, Object> journal = journal();
        List<Object> loot = loot();
        boolean marksDirty = true, chatDirty = true, baseDirty = true, journalDirty = true, lootDirty = true;
        while (true) {
            t += 0.25;
            KnoxServer.Command c;
            while ((c = KnoxServer.get().commands.poll()) != null) {
                Log.info("command " + c.name + " " + c.args);
                String msg = "Dev";
                boolean ok = true;
                switch (c.name) {
                    case "waypoint":
                        wp = new LinkedHashMap<>();
                        wp.put("x", c.args.get("x"));
                        wp.put("y", c.args.get("y"));
                        if (c.args.get("label") != null) wp.put("label", c.args.get("label"));
                        msg = null;
                        break;
                    case "waypointClear":
                        wp = null;
                        msg = null;
                        break;
                    case "markAdd": {
                        Map<String, Object> mk = m("i", (double) marks.size(), "x", c.args.get("x"), "y", c.args.get("y"), "c", ink(c.args.get("ink")));
                        if (c.args.get("sym") != null) mk.put("sym", c.args.get("sym"));
                        else mk.put("text", c.args.get("text"));
                        marks.add(mk);
                        marksDirty = true;
                        msg = null;
                        break;
                    }
                    case "markRemove": {
                        int i = ((Double) c.args.get("i")).intValue();
                        if (i >= 0 && i < marks.size()) marks.remove(i);
                        for (int k = 0; k < marks.size(); k++) ((Map<String, Object>) marks.get(k)).put("i", (double) k);
                        marksDirty = true;
                        msg = null;
                        break;
                    }
                    case "baseSet":
                        base = m("x", c.args.getOrDefault("x", 10650.0), "y", c.args.getOrDefault("y", 9400.0), "n", c.args.getOrDefault("n", "Base"));
                        baseDirty = true;
                        msg = null;
                        break;
                    case "baseClear":
                        base = null;
                        baseDirty = true;
                        msg = null;
                        break;
                    case "chat":
                        chat.add(m("k", c.args.getOrDefault("ch", "say"), "a", "Kate", "me", true, "s", c.args.get("s"), "t", m("h", 14.0, "mi", 9.0, "w", true)));
                        chatCount++;
                        chatDirty = true;
                        msg = null;
                        break;
                    case "note": {
                        List<Object> days = (List<Object>) journal.get("days");
                        ((List<Object>) ((Map<String, Object>) days.get(0)).get("notes")).add(m("s", c.args.get("s"), "h", 14.0, "mi", 20.0, "w", !noWatch));
                        journalDirty = true;
                        msg = null;
                        break;
                    }
                    case "noteDelete": {
                        ok = false;
                        msg = "That note is gone";
                        for (Object o : (List<Object>) journal.get("days")) {
                            Map<String, Object> d = (Map<String, Object>) o;
                            List<Object> notes = (List<Object>) d.get("notes");
                            int i = ((Double) c.args.get("i")).intValue();
                            if (((Double) d.get("d")).intValue() == ((Double) c.args.get("d")).intValue() && i >= 1 && i <= notes.size()) {
                                notes.remove(i - 1);
                                ok = true;
                                msg = null;
                            }
                        }
                        journalDirty = true;
                        break;
                    }
                    case "radioClear":
                        radioCleared = true;
                        msg = null;
                        break;
                    case "take":
                    case "takeAll":
                        msg = takeFrom(loot, c);
                        ok = msg != null;
                        if (!ok) msg = "It's not there anymore";
                        lootDirty = true;
                        break;
                    case "quick":
                    case "car":
                        msg = String.valueOf(c.args.get("a"));
                        break;
                    case "healthPart":
                        healthPart = c.args.get("part") instanceof Double ? ((Double) c.args.get("part")).intValue() : -1;
                        msg = null;
                        break;
                    case "treat":
                        msg = c.args.get("i") == null || String.valueOf(c.args.get("i")).isEmpty() ? null : String.valueOf(c.args.get("i"));
                        break;
                    case "perfMode":
                        if (c.args.get("mode") != null) {
                            perfMode = String.valueOf(c.args.get("mode"));
                            fpsCap = "cool".equals(perfMode) ? 30 : "smooth".equals(perfMode) ? 90 : 60;
                        }
                        if (c.args.get("auto") != null) perfAuto = Boolean.TRUE.equals(c.args.get("auto"));
                        msg = null;
                        break;
                    case "fps":
                        fpsCap = ((Double) c.args.get("cap")).intValue();
                        perfMode = "custom";
                        msg = fpsCap + " fps";
                        break;
                    case "guide":
                        guideOn = Boolean.TRUE.equals(c.args.get("show"));
                        msg = null;
                        break;
                    case "radar":
                        radarRange = ((Double) c.args.get("r")).intValue();
                        msg = null;
                        break;
                    case "menu":
                        // the game's own right-click menu, once on the "menu" channel with the screen's number
                        KnoxServer.get().publish("menu", Json.write(menu(c.args)));
                        msg = null;
                        break;
                    case "menuDo":
                    case "move":
                        msg = null;
                        break;
                    default:
                        break;
                }
                KnoxServer.get().reply(c.id, ok, msg);
            }
            Map<String, Object> core = core(t, driving, wp);
            if (base != null) core.put("base", base);
            core.put("cnt", m("radio", (double) (int) (t / 6), "chat", (double) chatCount));
            KnoxServer.get().publish("core", Json.write(core));
            if (KnoxServer.get().anyoneWants("body")) KnoxServer.get().publish("body", Json.write(body()));
            if (KnoxServer.get().anyoneWants("gear")) KnoxServer.get().publish("gear", Json.write(gear()));
            if (KnoxServer.get().anyoneWants("skills")) KnoxServer.get().publish("skills", Json.write(skills()));
            if (KnoxServer.get().anyoneWants("outfit") && ((int) (t * 4)) % 8 == 0) KnoxServer.get().publish("outfit", Json.write(outfit()));
            if (KnoxServer.get().anyoneWants("craft") && ((int) (t * 4)) % 12 == 0) KnoxServer.get().publish("craft", Json.write(craft()));
            if (lootDirty || ((int) (t * 4)) % 16 == 0) {
                KnoxServer.get().publish("loot", Json.write(m("list", loot, "objs", things())));
                lootDirty = false;
            }
            if (KnoxServer.get().anyoneWants("radar")) KnoxServer.get().publish("radar", Json.write(radar(t, (Double) ((Map<String, Object>) core.get("p")).get("dir"))));
            if (journalDirty) {
                KnoxServer.get().publish("journal", Json.write(journal));
                journalDirty = false;
            }
            if (marksDirty) {
                KnoxServer.get().publish("marks", Json.write(m("v", 1.0, "tools", m("inks", l("Pen", "RedPen", "BluePen"), "erase", true, "notes", true), "list", marks)));
                marksDirty = false;
            }
            if (((int) (t * 4)) % 4 == 0) {
                KnoxServer.get().publish("team", Json.write(m("list", l(
                        m("n", "Mira Kovacs", "u", "mira", "x", 10620 + Math.sin(t / 9) * 30, "y", 9440.0),
                        m("n", "Ash Brennan", "u", "ash", "x", 10710.0, "y", 9380 + Math.cos(t / 7) * 20)))));
            }
            if (((int) (t * 4)) % 24 == 0) KnoxServer.get().publish("radio", Json.write(radioCleared ? m("n", (double) (int) (t / 6), "list", l()) : radio((int) (t / 6))));
            // now and then someone else says something (for the chat alert and badge)
            if (mp && ((int) (t * 4)) % 180 == 120) {
                String[][] lines = {{"Mira", "faction", "Bring the generator fuel when you come."}, {"Ash", "say", "Heard a car alarm to the south."},
                        {"Mira", "general", "Anyone got spare bandages?"}};
                String[] said = lines[chatCount % lines.length];
                chat.add(m("k", said[1], "a", said[0], "s", said[2], "t", m("h", 15.0, "mi", (double) (chatCount % 60), "w", true)));
                chatCount++;
                chatDirty = true;
            }
            if (chatDirty) {
                List<Object> newest = new ArrayList<>(chat);
                java.util.Collections.reverse(newest);
                KnoxServer.get().publish("chat", Json.write(m("n", (double) chatCount, "mp", true, "list", newest,
                        "can", m("faction", true, "safehouse", false))));
                chatDirty = false;
            }
            if (baseDirty || ((int) (t * 4)) % 12 == 0) {
                KnoxServer.get().publish("base", Json.write(base == null ? m("set", false) : baseInfo(base)));
                baseDirty = false;
            }
            Thread.sleep(250);
        }
    }

    /** The colour of the chosen pen, like ISWorldMapSymbols. */
    static String ink(Object id) {
        String s = String.valueOf(id);
        switch (s) {
            case "Pencil": return "333333";
            case "RedPen": return "a60e0e";
            case "BluePen": return "28307d";
            case "GreenPen": return "0f632b";
            default: return "212121";
        }
    }

    @SuppressWarnings("unchecked")
    static String takeFrom(List<Object> loot, KnoxServer.Command c) {
        for (Object o : loot) {
            Map<String, Object> cont = (Map<String, Object>) o;
            List<Object> items = (List<Object>) cont.get("items");
            if (c.name.equals("takeAll")) {
                if (((Double) cont.get("k")).intValue() != ((Double) c.args.get("k")).intValue() || items.isEmpty()) continue;
                int n = items.size();
                items.clear();
                cont.put("w", 0.0);
                return n + " items";
            }
            for (int i = 0; i < items.size(); i++) {
                Map<String, Object> it = (Map<String, Object>) items.get(i);
                Object id = c.args.get("id");
                List<Object> ids = (List<Object>) c.args.get("ids");
                boolean hit = id != null ? id.equals(it.get("id")) : ids != null && ids.contains(it.get("id"));
                if (!hit) continue;
                items.remove(i);
                return ids != null && ids.size() > 1 ? it.get("n") + " ×" + ids.size() : String.valueOf(it.get("n"));
            }
        }
        return null;
    }

    static Map<String, Object> radio(int lines) {
        List<Object> news = new ArrayList<>();
        String[] said = {"...this is the Automated Emergency Broadcast System.", "Forecast for today: clear skies, light winds from the west.",
                "Temperatures reaching 26 degrees by the afternoon.", "Residents are advised to remain indoors.", "Further updates will follow.",
                "...repeat, this is the Automated Emergency Broadcast System."};
        for (int i = 0; i < Math.min(said.length, 2 + lines % 5); i++) news.add(said[i]);
        return m("n", (double) lines, "list", l(
                m("k", "radio", "f", 91200.0, "s", "Automated Emergency Broadcast", "t", m("h", 14.0, "mi", 0.0, "w", !noWatch), "l", news),
                m("k", "tv", "f", 200.0, "s", "Life and Living TV", "t", m("h", 12.0, "mi", 30.0, "w", !noWatch),
                        "l", l("And now, let's see what's cooking in the kitchen today.", "First, you'll want to pre-heat your oven...")),
                m("k", "walkie", "f", 101400.0, "t", m("h", 9.0, "mi", 10.0), "l", l("Is anyone out there? We're at the school.", "Over."))));
    }

    static Map<String, Object> baseInfo(Map<String, Object> base) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.put("set", true);
        out.put("ago", 3.5);
        out.put("seen", m("at", 100.0, "power", true,
                "gens", l(m("fuel", 62.0, "cond", 88.0, "on", true, "x", 10602.0, "y", 9418.0)),
                "barrels", m("n", 2.0, "pct", 0.45),
                "crops", l(m("n", "Tomato", "cnt", 8.0, "ready", 2.0, "dry", 3.0, "sick", 0.0, "dead", 0.0, "waterWord", "Thirsty", "healthWord", "Verdant"),
                        m("n", "Potato", "cnt", 6.0, "ready", 0.0, "dry", 0.0, "sick", 1.0, "dead", 1.0, "waterWord", "Well watered", "healthWord", "Healthy"))));
        return out;
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    private static List<Object> l(Object... v) {
        List<Object> out = new ArrayList<>();
        for (Object o : v) out.add(o);
        return out;
    }

    static Map<String, Object> core(double t, boolean driving, Map<String, Object> wp) {
        double x = 10650 + Math.cos(t / 20) * 40, y = 9400 + Math.sin(t / 20) * 40;
        double dir = (Math.toDegrees(t / 20) + 90) % 360;
        int minute = (int) (t * 4) % 60, hour = 14 + (int) (t * 4) / 60;
        boolean digital = !imm || !(analog || noWatch);
        Map<String, Object> core = m(
                "v", 1,
                "game", m("speed", 1.0, "mp", mp),
                "rules", m("imm", imm, "clock", !imm || !noWatch, "digital", digital,
                        "map", m("allowed", !noMap, "allKnown", !fog, "dark", dark)),
                "time", m("y", 1993.0, "mo", 7.0, "d", 9.0, "h", (double) (hour % 24), "mi", (double) minute, "ds", 2.4),
                "wx", m("t", 24.5, "rain", 0.0, "snow", 0.0, "fog", 0.0, "cloud", 0.3, "wind", 9.0, "storm", false, "light", 1.0),
                "p", m("name", female ? "Kate Morrow" : "Jack Morrow", "x", x, "y", y, "z", 0.0, "dir", dir, "out", true, "kills", 12.0, "female", female),
                "bars", m("hp", 0.82, "endu", 0.64, "hunger", 0.42, "thirst", 0.61, "fatigue", 0.2, "stress", 0.1,
                        "panic", 0.0, "boredom", 0.3, "unhappy", 0.05, "pain", 0.35, "sick", 0.0, "wet", 0.0, "temp", 37.1,
                        "weight", 11.4, "weightMax", 14.0),
                "hands", m("p", m("id", 101.0, "n", "Baseball Bat", "c", "Weapon", "ic", "Item_BaseballBat", "cond", 0.72),
                        "s", m("id", 109.0, "n", "Flashlight", "c", "LightSource", "ic", "Item_Flashlight", "use", 0.35)),
                "m", l(m("id", "BLEEDING", "l", 1.0, "g", 2.0, "n", "Minor Bleeding", "d", "Apply a bandage."),
                        m("id", "THIRST", "l", (double) (2 + ((int) (t / 20)) % 2), "g", 2.0, "n", "Parched", "d", "Very thirsty. Drink something."),
                        m("id", "PAIN", "l", 1.0, "g", 2.0, "n", "Minor Pain", "d", "Slightly reduced combat and movement."),
                        m("id", "HUNGRY", "l", 1.0, "g", 2.0, "n", "Peckish", "d", "Could do with a snack.")),
                // the last forecast heard on the Emergency Broadcast, this morning
                "fc", m("di", 2.0, "t", m("h", 7.0, "mi", 0.0, "w", !noWatch), "days", l(
                        m("tmin", 18.2, "tmax", 27.4, "cloud", 0.3, "cloudMax", 0.45, "wind", 0.2),
                        m("tmin", 14.6, "tmax", 21.0, "cloud", 0.82, "cloudMax", 0.95, "wind", 0.55, "wx", "from", "at", 14.0))),
                "di", 2.0);
        if (bed) ((Map<String, Object>) core.get("p")).put("bed", true);
        // what the item quick actions have to work with: no drink (the thirst alert offers nothing)
        core.put("can", m("bandage", true, "eat", true, "drink", false, "pills", true));
        // the frame-rate cap, the mode and heat control, the device's temperatures
        Map<String, Object> perf = m("cap", (double) fpsCap, "cpu", 71.5 + Math.sin(t / 8) * 4, "gpu", 62.0, "bat", 34.0,
                "guide", guideOn, "mode", perfMode, "auto", perfAuto);
        if (perfAuto && ((int) (t / 15)) % 2 == 1) perf.put("held", (double) Math.max(30, fpsCap - 15));
        core.put("perf", perf);
        if (imm) {
            // only what the character could know: see KnoxLink_State.lua
            Map<String, Object> time = (Map<String, Object>) core.get("time");
            if (noWatch) core.remove("time");
            else if (analog) core.put("time", m("h", time.get("h"), "mi", time.get("mi")));
            else time.remove("ds");
            Map<String, Object> wx = (Map<String, Object>) core.get("wx");
            if (!digital) wx.remove("t");
            wx.remove("wind");
            core.put("bars", m("weight", 11.4, "weightMax", 14.0));
        }
        if (wp != null) core.put("wp", wp);
        if (driving) {
            double spd = 40 + Math.sin(t / 3) * 25;
            core.put("veh", m("spd", (double) Math.round(spd), "max", 120.0, "fuel", 0.38, "run", true, "lights", true,
                    "hasLights", true, "gear", "3", "drv", true, "name", "Chevalier Nyala", "eng", 84.0, "batt", 0.9,
                    "heater", false, "hasHeater", true, "tires", l(92.0, 88.0, 41.0, 17.0),
                    "rpm", (double) Math.round(1400 + spd * 38), "keys", true, "hotwired", false, "locked", true,
                    "regulator", false, "mech", mech()));
        }
        return core;
    }

    /** What the mod sends for a Chevalier Nyala (prefix and images from ISCarMechanicsOverlay). */
    static Map<String, Object> mech() {
        Object[] parts = {"battery", 90.0, "engine", 84.0, "gastank", 100.0, "hood", 70.0, "trunk", 100.0, "muffler", 55.0,
                "headlight_left", 100.0, "headlight_right", -1.0, "door_front_left", 96.0, "door_front_right", 30.0,
                "door_rear_left", 100.0, "door_rear_right", 100.0, "window_windshield", 64.0, "window_rear_windshield", 100.0,
                "window_front_left", 100.0, "window_front_right", -1.0, "window_rear_left", 100.0, "window_rear_right", 100.0,
                "wheel_front_left", 92.0, "wheel_front_right", 88.0, "wheel_rear_left", 41.0, "wheel_rear_right", 17.0,
                "brake_front_left", 80.0, "brake_front_right", 80.0, "brake_rear_left", 60.0, "brake_rear_right", 60.0,
                "suspension_front_left", 75.0, "suspension_front_right", 75.0, "suspension_rear_left", 90.0, "suspension_rear_right", 90.0};
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < parts.length; i += 2) out.add(m("img", parts[i], "c", parts[i + 1]));
        return m("prefix", "4door_", "parts", out);
    }

    static Map<String, Object> body() {
        List<Object> parts = new ArrayList<>();
        String[] keys = {"Hand_L", "Hand_R", "ForeArm_L", "ForeArm_R", "UpperArm_L", "UpperArm_R", "Torso_Upper", "Torso_Lower",
                "Head", "Neck", "Groin", "UpperLeg_L", "UpperLeg_R", "LowerLeg_L", "LowerLeg_R", "Foot_L", "Foot_R"};
        String[] names = {"Left Hand", "Right Hand", "Left Forearm", "Right Forearm", "Left Upper Arm", "Right Upper Arm", "Upper Torso",
                "Lower Torso", "Head", "Neck", "Groin", "Left Thigh", "Right Thigh", "Left Shin", "Right Shin", "Left Foot", "Right Foot"};
        for (int i = 0; i < keys.length; i++) {
            Map<String, Object> p = m("i", (double) i, "k", keys[i], "n", names[i], "hp", 100.0);
            // with the health panel's own lines (ISHealthPanel's words and colours)
            if (i == 2) {
                p.put("hp", 62.0); p.put("scratch", true); p.put("bleed", true); p.put("pain", 12.0);
                p.put("lines", l(m("t", "Scratched", "k", "bad"), m("t", "Pain", "k", "bad"), m("t", "Bleeding", "k", "bad")));
            }
            if (i == 13) {
                p.put("hp", 81.0); p.put("cut", true); p.put("band", true);
                p.put("lines", l(m("t", "Laceration", "k", "bad"), m("t", "Dirty bandage", "k", "warn"), m("t", "Disinfected", "k", "good")));
            }
            if (i == 6) {
                p.put("hp", 90.0); p.put("glass", true);
                p.put("lines", l(m("t", "Glass shards lodged", "k", "bad")));
            }
            parts.add(p);
        }
        Map<String, Object> out = m("parts", parts, "hp", 82.0, "bandage", "Bandage");
        if (healthPart >= 0) out.put("sel", m("i", (double) healthPart, "t", treatments(healthPart)));
        return out;
    }

    /** What the game's health panel offers on a body part, in treating order: {a = what, i = with which item, ic}. */
    static List<Object> treatments(int part) {
        switch (part) {
            case 2:
                return l(m("a", "Disinfect", "i", "Bottle of Disinfectant", "ic", "Item_Disinfectant"),
                        m("a", "Bandage", "i", "Bandage", "ic", "Item_Bandage"),
                        m("a", "Bandage", "i", "Ripped Sheets", "ic", "Item_Rag"));
            case 13:
                return l(m("a", "Remove Bandage"),
                        m("a", "Stitch", "i", "Needle", "ic", "Item_Needle"),
                        m("a", "Bandage", "i", "Bandage", "ic", "Item_Bandage"));
            case 6:
                return l(m("a", "Remove Glass", "i", "Tweezers", "ic", "Item_Tweezers"));
            default:
                return l();
        }
    }

    static Map<String, Object> gear() {
        List<Object> main = l(
                m("id", 101.0, "n", "Baseball Bat", "t", "Base.BaseballBat", "c", "Weapon", "ic", "Item_BaseballBat", "w", 1.5, "eq", true, "cond", 0.72, "a", l("equip2", "unequip")),
                m("id", 109.0, "n", "Flashlight", "t", "Base.Torch", "c", "LightSource", "ic", "Item_Flashlight", "w", 0.6, "eq", true, "use", 0.35, "a", l("equip1", "unequip")),
                m("id", 102.0, "n", "Chips", "t", "Base.Crisps", "c", "Food", "ic", "Item_Crisps", "w", 0.2, "cnt", 2.0, "ids", l(102.0, 112.0), "food", m("hunger", -10.0, "thirst", 5.0), "a", l("eat")),
                m("id", 103.0, "n", "Water Bottle", "t", "Base.WaterBottle", "c", "Water", "ic", "Item_WaterBottle_Full", "w", 1.2, "fluid", m("amt", 0.7, "cap", 1.0, "n", "Water"), "a", l("drink")),
                m("id", 104.0, "n", "Bandage", "t", "Base.Bandage", "c", "FirstAid", "ic", "Item_Bandage", "w", 0.1, "cnt", 3.0, "ids", l(104.0, 114.0, 124.0), "a", l()),
                m("id", 105.0, "n", "Painkillers", "t", "Base.Pills", "c", "FirstAid", "ic", "Item_Pills", "w", 0.2, "use", 0.6, "a", l("pill")),
                m("id", 106.0, "n", "Hammer", "t", "Base.Hammer", "c", "Tool", "ic", "Item_Hammer", "w", 1.0, "cond", 0.9, "a", l("equip1", "equip2")),
                m("id", 107.0, "n", "Canned Beans (Opened)", "t", "Base.OpenBeans", "c", "Food", "ic", "Item_BeansOpen", "w", 0.6, "food", m("hunger", -25.0, "stale", true), "a", l("eat")),
                m("id", 108.0, "n", "Woodcraft Vol. 1", "t", "Base.BookCarpentry1", "c", "SkillBook", "ic", "Item_Book1", "w", 0.8, "a", l("read")),
                m("id", 110.0, "n", "Hoodie", "t", "Base.HoodieDOWN_WhiteTINT", "c", "Clothing", "ic", "Item_HoodieWhite", "w", 0.9, "worn", true, "loc", "Sweater", "a", l("takeoff")),
                m("id", 300.0, "n", "School Bag", "t", "Base.Bag_Schoolbag", "c", "Container", "ic", "Item_Bag_Schoolbag", "w", 0.4, "worn", true, "loc", "Back", "a", l("takeoff")));
        List<Object> nails = new ArrayList<>();
        for (int i = 0; i < 24; i++) nails.add(201.0 + i * 1000);
        List<Object> bag = l(
                m("id", 201.0, "n", "Nails", "t", "Base.Nails", "c", "Material", "ic", "Item_Nails", "w", 0.5, "cnt", 24.0, "ids", nails, "a", l()),
                m("id", 202.0, "n", "Vest", "t", "Base.Vest_Default", "c", "Clothing", "ic", "Item_VestGeneric", "w", 0.9, "a", l("wear")),
                m("id", 203.0, "n", "Rotten Apple", "t", "Base.Apple", "c", "Food", "ic", "Item_AppleRotten", "w", 0.2, "food", m("hunger", -5.0, "rotten", true), "a", l("eat")),
                m("id", 503.0, "n", "Shotgun", "t", "Base.Shotgun", "c", "Weapon", "ic", "Item_Firearm_PumpShotgun", "w", 4.0, "ammo", 2.0, "ammoMax", 6.0, "a", l("equip1")));
        return m("containers", l(
                m("id", 0.0, "n", "Inventory", "w", 7.4, "cap", 8.0, "items", main),
                m("id", 300.0, "n", "School Bag", "w", 4.0, "cap", 18.0, "items", bag)));
    }

    /** What the character wears (inner layers first), their hotbar and every weapon they carry. */
    static Map<String, Object> outfit() {
        List<Object> worn = l(
                m("id", 401.0, "n", "Socks", "ic", "Item_SocksWhite", "loc", "Socks", "parts", l("Foot_L", "Foot_R"), "bite", 0.0, "scratch", 5.0),
                m("id", 402.0, "n", "T-shirt", "ic", "Item_TShirt_Neon1", "c", "5b7f62", "loc", "Tshirt", "cond", 0.9,
                        "parts", l("Torso_Upper", "Torso_Lower", "UpperArm_L", "UpperArm_R"), "bite", 0.0, "scratch", 10.0),
                m("id", 403.0, "n", "Jeans", "ic", "Item_Trousers_Crafted_Denim", "c", "3d5a80", "loc", "Pants", "cond", 0.8, "holes", 1.0,
                        "parts", l("Groin", "UpperLeg_L", "UpperLeg_R", "LowerLeg_L", "LowerLeg_R"), "bite", 0.0, "scratch", 20.0),
                m("id", 110.0, "n", "Hoodie", "ic", "Item_HoodieWhite", "c", "7a3b3b", "loc", "Sweater", "cond", 0.65, "wet", 22.0, "blood", true,
                        "parts", l("Torso_Upper", "Torso_Lower", "UpperArm_L", "UpperArm_R", "ForeArm_L", "ForeArm_R", "Neck"), "bite", 10.0, "scratch", 25.0),
                m("id", 404.0, "n", "Leather Gloves", "ic", "Item_GlovesLeatherBrown", "loc", "Hands", "parts", l("Hand_L", "Hand_R"), "bite", 10.0, "scratch", 30.0),
                m("id", 405.0, "n", "Running Shoes", "ic", "Item_ShoesRunningBlue", "loc", "Shoes", "parts", l("Foot_L", "Foot_R"), "bite", 10.0, "scratch", 20.0),
                m("id", 406.0, "n", "Digital Watch", "ic", "Item_DigitalWatch_Black", "loc", "LeftWrist"),
                m("id", 300.0, "n", "School Bag", "ic", "Item_Backpack_Black", "loc", "Back", "bag", true));
        Map<String, Object> prot = new LinkedHashMap<>();
        String[] keys = {"Hand_L", "Hand_R", "ForeArm_L", "ForeArm_R", "UpperArm_L", "UpperArm_R", "Torso_Upper", "Torso_Lower",
                "Head", "Neck", "Groin", "UpperLeg_L", "UpperLeg_R", "LowerLeg_L", "LowerLeg_R", "Foot_L", "Foot_R"};
        double[][] values = {{10, 30}, {10, 30}, {10, 25}, {10, 25}, {10, 35}, {10, 35}, {10, 35}, {10, 35},
                {0, 0}, {10, 25}, {0, 20}, {0, 20}, {0, 20}, {0, 20}, {0, 20}, {10, 25}, {10, 25}};
        for (int i = 0; i < keys.length; i++) prot.put(keys[i], m("b", values[i][0], "s", values[i][1]));
        List<Object> hotbar = l(
                m("i", 1.0, "n", "Back", "id", 501.0, "item", "Crowbar", "ic", "Item_Crowbar", "cond", 0.9),
                m("i", 2.0, "n", "Belt Left", "id", 101.0, "item", "Baseball Bat", "ic", "Item_BaseballBat", "cond", 0.72, "eq", true),
                m("i", 3.0, "n", "Belt Right", "id", 502.0, "item", "Hunting Knife", "ic", "Item_HuntingKnife", "cond", 0.3),
                m("i", 4.0, "n", "Holster"));
        List<Object> weapons = l(
                m("id", 101.0, "n", "Baseball Bat", "ic", "Item_BaseballBat", "cond", 0.72, "eq", "p", "slot", "Belt Left"),
                m("id", 501.0, "n", "Crowbar", "ic", "Item_Crowbar", "cond", 0.9, "slot", "Back"),
                m("id", 502.0, "n", "Hunting Knife", "ic", "Item_HuntingKnife", "cond", 0.3, "slot", "Belt Right"),
                m("id", 503.0, "n", "Shotgun", "ic", "Item_Firearm_PumpShotgun", "cond", 0.85, "two", true, "ranged", true, "ammo", 2.0, "ammoMax", 6.0, "bag", "School Bag"),
                m("id", 504.0, "n", "Kitchen Knife", "ic", "Item_KitchenKnife_Forged", "cond", 0.0, "broken", true));
        return m("worn", worn, "attached", l(), "weapons", weapons, "prot", prot, "hotbar", hotbar,
                "skin", "cca673", "hair", "4a3424", "female", female);
    }

    /** The containers the loot window lists: the floor, the one open in the game, and an empty fridge. */
    static List<Object> loot() {
        return l(
                m("k", 1.0, "id", 1.0, "n", "Floor", "type", "floor", "floor", true, "w", 6.4, "cap", 50.0, "items", l(
                        m("id", 601.0, "n", "Plank", "t", "Base.Plank", "c", "Material", "ic", "Item_Plank", "w", 6.0, "cnt", 2.0, "ids", l(601.0, 602.0)),
                        m("id", 603.0, "n", "Ripped Sheet", "t", "Base.RippedSheets", "c", "FirstAid", "ic", "Item_Rag", "w", 0.4))),
                m("k", 2.0, "id", 2.0, "n", "Counter", "type", "counter", "sel", true, "w", 2.3, "cap", 50.0, "items", l(
                        m("id", 611.0, "n", "Canned Beans", "t", "Base.TinnedBeans", "c", "Food", "ic", "Item_Beans", "w", 1.8, "cnt", 3.0,
                                "ids", l(611.0, 612.0, 613.0), "food", m("hunger", -30.0)),
                        m("id", 614.0, "n", "Pop", "t", "Base.Pop", "c", "Food", "ic", "Item_PopCan_Generic", "w", 0.3,
                                "food", m("hunger", 0.0, "thirst", -40.0)),
                        m("id", 615.0, "n", "Screwdriver", "t", "Base.Screwdriver", "c", "Tool", "ic", "Item_Screwdriver", "w", 0.2, "cond", 0.8))),
                m("k", 3.0, "id", 3.0, "n", "Fridge", "type", "fridge", "w", 0.0, "cap", 40.0, "items", l()),
                // two of the same name: "Move to" numbers them
                m("k", 4.0, "id", 4.0, "n", "Drawer", "type", "drawer", "w", 0.2, "cap", 10.0, "items", l(
                        m("id", 631.0, "n", "Spoon", "t", "Base.Spoon", "c", "Cooking", "ic", "Item_Spoon", "w", 0.2))),
                m("k", 5.0, "id", 5.0, "n", "Drawer", "type", "drawer", "w", 0.0, "cap", 10.0, "items", l()),
                // the bodies a few steps away, nearest first (taking from one walks there first)
                m("k", 6.0, "id", 6.0, "n", "Dead zombie", "type", "inventorymale", "corpse", true, "d", 0.9, "w", 1.7, "cap", 0.0, "items", l(
                        m("id", 641.0, "n", "Wallet", "t", "Base.Wallet", "c", "Junk", "ic", "Item_Wallet", "w", 0.1),
                        m("id", 642.0, "n", "Hunting Knife", "t", "Base.HuntingKnife", "c", "Weapon", "ic", "Item_HuntingKnife", "w", 0.6, "cond", 0.4))),
                m("k", 7.0, "id", 7.0, "n", "Dead zombie", "type", "inventoryfemale", "corpse", true, "d", 3.2, "w", 0.6, "cap", 0.0, "items", l(
                        m("id", 651.0, "n", "Cigarettes", "t", "Base.Cigarettes", "c", "Junk", "ic", "Item_Cigarettes", "w", 0.1, "cnt", 2.0, "ids", l(651.0, 652.0)))));
    }

    /** The spots around you with a menu of their own (doors, windows, curtains, light switches, furniture), nearest first. */
    static List<Object> things() {
        return l(m("n", "Window, Curtains", "x", 10650.0, "y", 9401.0, "z", 0.0, "i", 2.0, "d", 1.1),
                m("n", "Door", "x", 10652.0, "y", 9400.0, "z", 0.0, "i", 1.0, "d", 2.3),
                m("n", "Light switch", "x", 10651.0, "y", 9398.0, "z", 0.0, "i", 3.0, "d", 2.9),
                m("n", "Counter, Oven", "x", 10648.0, "y", 9399.0, "z", 0.0, "i", 1.0, "d", 3.4));
    }

    /** The game's right-click menu for an item or a spot, as the add-on sends it: {t, d = disabled (reason), s = submenu, ic}. */
    static Map<String, Object> menu(Map<String, Object> a) {
        boolean obj = "obj".equals(a.get("kind"));
        List<Object> opts = obj
                ? l(m("t", "Turn On"), m("t", "Take down curtains"), m("t", "Close curtains"), m("t", "Open window"),
                        m("t", "Barricade", "d", "Needs a hammer, a plank and nails"), m("t", "Walk to"))
                : l(m("t", "Equip Primary"), m("t", "Equip Secondary"), m("t", "Drop"), m("t", "Favourite"),
                        m("t", "Repair", "s", l(m("t", "Duct Tape", "ic", "Item_DuctTape", "d", "You need 1 Duct Tape"),
                                m("t", "Glue", "ic", "Item_Glue", "d", true))),
                        m("t", "Put in", "s", l(m("t", "School Bag"), m("t", "Counter"))));
        return m("req", a.get("req"), "title", obj ? String.valueOf(a.getOrDefault("n", "Object")) : "Baseball Bat", "opts", opts);
    }

    /** The danger radar: what the character has seen or heard around them, turning with the view. */
    static Map<String, Object> radar(double t, double yaw) {
        List<Object> z = new ArrayList<>();
        z.add(m("i", 1.0, "x", 6 + Math.sin(t / 3) * 2, "y", -4 + Math.cos(t / 4), "s", "seen", "b", "idle"));
        z.add(m("i", 2.0, "x", 9 - (t % 12) * 0.6, "y", 3.0, "s", "seen", "b", "chase"));
        z.add(m("i", 3.0, "x", -10.5, "y", 5.5, "s", "heard", "b", "idle"));
        z.add(m("i", 4.0, "x", 12.0, "y", -9.0, "s", "lost", "b", "idle", "a", t % 6));
        z.add(m("i", 6.0, "x", -3.0, "y", -14.0, "s", "seen", "b", "idle", "f", 1.0));
        Map<String, Object> out = m("yaw", yaw, "c", 72.0, "r", (double) radarRange, "z", z);
        // now and then one creeps up behind you
        if (((int) (t / 6)) % 2 == 1) {
            double back = Math.toRadians(yaw + 180);
            z.add(0, m("i", 5.0, "x", Math.cos(back) * 2.2, "y", Math.sin(back) * 2.2, "s", "heard", "b", "attack"));
            out.put("near", m("a", 180.0, "d", 2.2, "i", 5.0, "b", "attack"));
        }
        return out;
    }

    /** What can be made right now, as the game's crafting window would list it. */
    static Map<String, Object> craft() {
        return m("list", l(
                m("id", "OpenCannedFood", "n", "Open Canned Beans", "ic", "BeansOpen", "cat", "Cooking", "t", 80.0),
                m("id", "SliceBread", "n", "Slice Bread", "ic", "BreadSlices", "cat", "CookingPrep", "t", 50.0),
                m("id", "RipClothing", "n", "Rip Clothing", "ic", "Rag", "cat", "FirstAid", "t", 100.0),
                m("id", "MakeTorch", "n", "Make Improvised Flashlight", "ic", "Flashlight_Improvised", "cat", "Survival", "t", 150.0),
                m("id", "CarveSpear", "n", "Carve Spear", "ic", "Spear_Crude01", "cat", "Survival", "t", 300.0),
                m("id", "MakePlankNail", "n", "Hammer Nails into Plank", "ic", "PlankNail", "cat", "Weaponry", "t", 120.0)));
    }

    static Map<String, Object> journal() {
        return m("today", 3.0, "write", true, "days", l(
                m("d", 3.0, "kills", 4.0,
                        "entry", "Woke early with the light coming in sideways through the boards. Found a pharmacy on Main Street and got scratched "
                                + "on the arm for my trouble. Four of them today. I keep telling myself it gets easier.",
                        "lines", l(
                        m("k", "place", "s", "Found a pharmacy.", "h", 9.0, "mi", 12.0, "w", !noWatch),
                        m("k", "hurt", "s", "Scratched on the left forearm.", "h", 11.0, "mi", 40.0, "w", !noWatch),
                        m("k", "skill", "s", "Sprinting is now level 1.", "h", 13.0, "mi", 5.0, "w", !noWatch)),
                        "notes", l(m("s", "The pharmacy on Main Street still has pills upstairs. The back door is boarded up.", "h", 13.0, "mi", 30.0, "w", !noWatch))),
                m("d", 2.0, "kills", 7.0,
                        "entry", "Seven of them. Bitten on the hand. I set up in the farmhouse and tried not to think about it.",
                        "ai", "Seven of them today, and the last one got my hand. I wrapped it before I even looked.\n\n"
                                + "The farmhouse will do. I pushed the dresser against the back door and sat with the lights off, listening.",
                        "lines", l(
                        m("k", "sleep", "s", "Slept 8 hours.", "h", 7.0, "mi", 0.0, "w", !noWatch),
                        m("k", "bite", "s", "Bitten on the right hand.", "h", 15.0, "mi", 10.0, "w", !noWatch),
                        m("k", "base", "s", "Made this place my base.", "h", 18.0, "mi", 20.0, "w", !noWatch)),
                        "notes", l()),
                m("d", 1.0, "kills", 0.0, "lines", l(), "notes", l())));
    }

    static Map<String, Object> skills() {
        return m("groups", l(
                m("g", "Passive", "s", l(m("n", "Fitness", "lvl", 5.0, "p", 0.4), m("n", "Strength", "lvl", 5.0, "p", 0.1))),
                m("g", "Agility", "s", l(m("n", "Sprinting", "lvl", 1.0, "p", 0.7), m("n", "Lightfooted", "lvl", 0.0, "p", 0.2),
                        m("n", "Nimble", "lvl", 0.0, "p", 0.0), m("n", "Sneaking", "lvl", 2.0, "p", 0.55, "mult", 2.5))),
                m("g", "Combat", "s", l(m("n", "Long Blunt", "lvl", 3.0, "p", 0.25, "boost", 1.0), m("n", "Short Blade", "lvl", 0.0, "p", 0.0),
                        m("n", "Maintenance", "lvl", 1.0, "p", 0.9))),
                m("g", "Crafting", "s", l(m("n", "Carpentry", "lvl", 2.0, "p", 0.33), m("n", "Cooking", "lvl", 0.0, "p", 0.1),
                        m("n", "First Aid", "lvl", 1.0, "p", 0.05)))));
    }
}
