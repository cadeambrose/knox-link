package knoxlink;

import java.util.List;
import java.util.Map;

import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;

/**
 * The global Lua functions this mod adds. ZombieBuddy registers every static method marked
 * with {@code @LuaMethod(global = true)}. All of them are called from the game thread.
 * The Lua side checks {@code KnoxLink_isServer} to know the server is present.
 */
public final class LuaBridge {
    // Public on purpose: ZombieBuddy instantiates the class to register its global functions.
    public LuaBridge() {}

    private static volatile boolean firstPublish = true;

    @LuaMethod(name = "KnoxLink_isServer", global = true)
    public static boolean isServer() {
        return true;
    }

    /** Sends a state table to every screen that follows the channel. */
    @LuaMethod(name = "KnoxLink_publish", global = true)
    public static void publish(String channel, Object table) {
        if (channel == null) return;
        if (firstPublish) {
            firstPublish = false;
            Log.info("receiving game state from Lua");
        }
        try {
            KnoxServer.get().publish(channel, Json.write(table));
        } catch (Throwable t) {
            Log.error("publish " + channel, t);
        }
    }

    @LuaMethod(name = "KnoxLink_event", global = true)
    public static void event(Object table) {
        try {
            KnoxServer.get().event(Json.write(table));
        } catch (Throwable t) {
            Log.error("event", t);
        }
    }

    /** True if at least one connected screen is showing this channel, so Lua can skip the work otherwise. */
    @LuaMethod(name = "KnoxLink_wants", global = true)
    public static boolean wants(String channel) {
        return KnoxServer.get().anyoneWants(channel);
    }

    @LuaMethod(name = "KnoxLink_screens", global = true)
    public static double screens() {
        return KnoxServer.get().screenCount();
    }

    /** Next button press from a screen as {id=, cmd=, args={}} or nil. */
    @LuaMethod(name = "KnoxLink_pollCommand", global = true)
    public static KahluaTable pollCommand() {
        KnoxServer.Command c = KnoxServer.get().commands.poll();
        if (c == null) return null;
        KahluaTable t = LuaManager.platform.newTable();
        t.rawset("id", (double) c.id);
        t.rawset("cmd", c.name);
        t.rawset("args", toLua(c.args));
        return t;
    }

    @LuaMethod(name = "KnoxLink_reply", global = true)
    public static void reply(double id, boolean ok, String message) {
        KnoxServer.get().reply((int) id, ok, message);
    }

    @LuaMethod(name = "KnoxLink_status", global = true)
    public static KahluaTable status() {
        return (KahluaTable) toLua(KnoxServer.get().status());
    }

    @LuaMethod(name = "KnoxLink_setLan", global = true)
    public static void setLan(boolean lan) {
        KnoxServer.get().setLan(lan);
    }

    @LuaMethod(name = "KnoxLink_newPin", global = true)
    public static void newPin() {
        KnoxServer.get().newPin();
    }

    /** QR code for a URL as rows of "0"/"1" characters, or nil if the game has no QR library. */
    @LuaMethod(name = "KnoxLink_qr", global = true)
    public static KahluaTable qr(String text) {
        String[] rows = Qr.rows(text);
        if (rows == null) return null;
        KahluaTable t = LuaManager.platform.newTable();
        for (int i = 0; i < rows.length; i++) t.rawset(i + 1, rows[i]);
        return t;
    }

    // ------------------------------------------------------------------ map marks and other players

    private static final java.util.Set<String> failed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void failedOnce(String what, Throwable t) {
        if (failed.add(what)) Log.error(what, t);
    }

    /** Publishes the player's map marks (and which pens and eraser they carry) when they change. */
    @LuaMethod(name = "KnoxLink_marks", global = true)
    public static void marks(Object tools) {
        try {
            Marks.sync(tools);
        } catch (Throwable t) {
            failedOnce("map marks", t);
        }
    }

    /** Adds a symbol (sym) or a note (text) to the in-game world map; nil, or why it could not. */
    @LuaMethod(name = "KnoxLink_markAdd", global = true)
    public static String markAdd(String sym, String text, double x, double y, double r, double g, double b) {
        try {
            return Marks.add(sym, text, (float) x, (float) y, (float) r, (float) g, (float) b);
        } catch (Throwable t) {
            failedOnce("add mark", t);
            return "Could not add it";
        }
    }

    @LuaMethod(name = "KnoxLink_markRemove", global = true)
    public static String markRemove(double index, double x, double y) {
        try {
            return Marks.remove((int) index, (float) x, (float) y);
        } catch (Throwable t) {
            failedOnce("erase mark", t);
            return "Could not erase it";
        }
    }

    /** Publishes split-screen and (allowed) multiplayer survivors for the map. */
    @LuaMethod(name = "KnoxLink_team", global = true)
    public static void team() {
        try {
            Team.sync();
        } catch (Throwable t) {
            failedOnce("other players", t);
        }
    }

    /** The device's temperatures {cpu=, gpu=, bat=} in °C, where readable (handhelds), else an empty table. */
    @LuaMethod(name = "KnoxLink_device", global = true)
    public static KahluaTable device() {
        try {
            return (KahluaTable) toLua(Device.temps());
        } catch (Throwable t) {
            failedOnce("device temperatures", t);
            return LuaManager.platform.newTable();
        }
    }

    /** Zomdroid's renderer (ZINK_ZFA, ZINK_OSMESA, MOBILEGLUES_...), or "" outside Zomdroid. */
    @LuaMethod(name = "KnoxLink_renderer", global = true)
    public static String renderer() {
        String r = System.getProperty("zomdroid.renderer");
        return r == null ? "" : r;
    }

    private static java.lang.reflect.Field viewpointFailed;
    private static java.lang.reflect.Method viewpointSupported;
    private static boolean viewpointLooked;

    /**
     * Project Viewpoint's 3D view is off for this session: it refused this setup (BuildPin, also how
     * it stays off on renderers that can't run it) or turned itself off after an error
     * (SceneDrawer.failed). False when Viewpoint isn't here.
     */
    @LuaMethod(name = "KnoxLink_viewpointOff", global = true)
    public static boolean viewpointOff() {
        try {
            if (!viewpointLooked) {
                viewpointLooked = true;
                java.lang.reflect.Field f = Class.forName("viewpoint.SceneDrawer").getDeclaredField("failed");
                f.setAccessible(true);
                viewpointFailed = f;
                viewpointSupported = Class.forName("viewpoint.platform.BuildPin").getMethod("supported");
            }
            if (viewpointSupported != null && !(Boolean) viewpointSupported.invoke(null)) return true;
            return viewpointFailed != null && viewpointFailed.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    private static java.lang.reflect.Field viewpointYaw, viewpointEnabled;
    private static boolean yawLooked;

    /** Where Project Viewpoint's 3D camera looks, in degrees (its Look.yaw), or nil when its 3D view is off. */
    @LuaMethod(name = "KnoxLink_viewYaw", global = true)
    public static Double viewYaw() {
        try {
            if (!yawLooked) {
                yawLooked = true;
                viewpointYaw = Class.forName("viewpoint.input.Look").getField("yaw");
                viewpointEnabled = Class.forName("viewpoint.core.View").getField("enabled");
            }
            if (viewpointYaw == null || viewpointEnabled == null || !viewpointEnabled.getBoolean(null)) return null;
            return Math.toDegrees(viewpointYaw.getFloat(null));
        } catch (Throwable t) {
            return null;
        }
    }

    private static java.lang.reflect.Field viewpointFovY;

    /** How tall Project Viewpoint's 3D view is, in degrees (its frame's vertical field of view), or nil when it's off. */
    @LuaMethod(name = "KnoxLink_viewFov", global = true)
    public static Double viewFov() {
        try {
            if (viewpointFovY == null) {
                viewpointFovY = Class.forName("viewpoint.core.View").getField("frameFovY");
                if (viewpointEnabled == null) viewpointEnabled = Class.forName("viewpoint.core.View").getField("enabled");
            }
            return viewpointEnabled.getBoolean(null) ? Math.toDegrees(viewpointFovY.getFloat(null)) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static java.lang.reflect.Method viewpointPut;

    /**
     * Project Viewpoint's camera in its 3D view follows its "third person on foot" (or "seated", in a
     * vehicle) setting, which its own key flips; this sets it. False when Viewpoint isn't here.
     */
    @LuaMethod(name = "KnoxLink_viewpointThird", global = true)
    public static boolean viewpointThird(boolean third, boolean seated) {
        return viewpointPut(seated ? "camera.thirdPersonSeated" : "camera.thirdPersonOnFoot", third ? "true" : "false");
    }

    /**
     * Changes one of Project Viewpoint's settings live (what its own settings window does), for the
     * one-tap performance modes, 3D bodies and frame generation. Only its level-of-detail,
     * model-pack, corpse and frame-generation settings; false otherwise.
     */
    @LuaMethod(name = "KnoxLink_viewpointSetting", global = true)
    public static boolean viewpointSetting(String key, String value) {
        if (key == null || value == null) return false;
        if (!(key.startsWith("lod.") || key.startsWith("memory.pack") || key.startsWith("corpses.") || key.equals("memory.corpseMiB")
                || key.equals("graphics.frameGeneration"))) return false;
        return viewpointPut(key, value);
    }

    private static Object frameGenToggle;
    private static java.lang.reflect.Method frameGenGet, frameGenWhyNot;
    private static boolean frameGenLooked;

    /**
     * Whether Project Viewpoint's frame generation is on (a made-up frame between every two), or nil
     * when it can't run here: no Viewpoint, or its own frame generation needs what this device lacks
     * (Viewpoint on Android brings one that runs under Zomdroid).
     */
    @LuaMethod(name = "KnoxLink_frameGen", global = true)
    public static Boolean frameGen() {
        try {
            if (!frameGenLooked) {
                frameGenLooked = true;
                frameGenToggle = Class.forName("viewpoint.platform.Graphics").getField("FRAME_GENERATION").get(null);
                frameGenGet = frameGenToggle.getClass().getMethod("get");
                frameGenWhyNot = Class.forName("viewpoint.upscale.FrameGeneration").getMethod("unavailable");
            }
            if (frameGenToggle == null || frameGenWhyNot.invoke(null) != null) return null;
            return (Boolean) frameGenGet.invoke(frameGenToggle);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean viewpointPut(String key, String value) {
        try {
            if (viewpointPut == null) {
                for (java.lang.reflect.Method m : Class.forName("viewpoint.platform.LiveSettings").getDeclaredMethods()) {
                    if (m.getName().equals("put") && m.getParameterCount() == 2 && m.getParameterTypes()[0] == String.class
                            && m.getParameterTypes()[1] == String.class) {
                        m.setAccessible(true);
                        viewpointPut = m;
                    }
                }
            }
            if (viewpointPut == null) return false;
            viewpointPut.invoke(null, key, value);
            return true;
        } catch (Throwable t) {
            failedOnce("Viewpoint setting", t);
            return false;
        }
    }

    // ------------------------------------------------------------------ floor plan and the character's memory

    /** Publishes the floor plan around the character ("plan") if it changed; everything = immersion off. */
    @LuaMethod(name = "KnoxLink_plan", global = true)
    public static void plan(boolean everything) {
        Plan.publish(everything);
    }

    /** Whose memory "Where did I see...?" keeps: the character's journal id. */
    @LuaMethod(name = "KnoxLink_memoryFor", global = true)
    public static void memoryFor(String id) {
        try {
            Memory.select(id);
        } catch (Throwable t) {
            failedOnce("memory (load)", t);
        }
    }

    /** A container the game's loot window lists (label = its name there). */
    @LuaMethod(name = "KnoxLink_memorySaw", global = true)
    public static void memorySaw(Object container, String label) {
        Memory.saw(container, label);
    }

    /** About once a second: the way walked and the building the character is in. */
    @LuaMethod(name = "KnoxLink_memoryTick", global = true)
    public static void memoryTick() {
        Memory.tick();
    }

    /** The car the character just got out of. */
    @LuaMethod(name = "KnoxLink_memoryCar", global = true)
    public static void memoryCar(Object vehicle) {
        Memory.car(vehicle);
    }

    /** Looks for things the character saw; the answer goes out once on "seen" with the screen's request number. */
    @LuaMethod(name = "KnoxLink_memoryFind", global = true)
    public static void memoryFind(String query, double req) {
        try {
            Memory.find(query, (int) req);
        } catch (Throwable t) {
            failedOnce("memory (find)", t);
        }
    }

    /** Publishes the map's memory layer ("memory": searched buildings, the car, the way walked) if it changed. */
    @LuaMethod(name = "KnoxLink_memoryOverlay", global = true)
    public static void memoryOverlay() {
        try {
            Memory.overlay();
        } catch (Throwable t) {
            failedOnce("memory (map)", t);
        }
    }

    /** The nearest place each of these item types was seen (a list of full types): {type = where, ...}. */
    @LuaMethod(name = "KnoxLink_memoryNearest", global = true)
    public static KahluaTable memoryNearest(Object types) {
        try {
            List<String> list = new java.util.ArrayList<>();
            if (types instanceof KahluaTable) {
                KahluaTable t = (KahluaTable) types;
                for (int i = 1; i <= t.len(); i++) {
                    Object v = t.rawget(i);
                    if (v != null) list.add(String.valueOf(v));
                }
            }
            return (KahluaTable) toLua(Memory.nearest(list));
        } catch (Throwable t) {
            failedOnce("memory (nearest)", t);
            return LuaManager.platform.newTable();
        }
    }

    /** Things seen whose category has a word ("weapon"), nearest first: {{t, n, c, ic, cat, b, rm, town, x, y, z, d, dir, h}, ...}. */
    @LuaMethod(name = "KnoxLink_memoryKinds", global = true)
    public static KahluaTable memoryKinds(String word, double max) {
        try {
            return (KahluaTable) toLua(Memory.kinds(word, (int) max));
        } catch (Throwable t) {
            failedOnce("memory (kinds)", t);
            return LuaManager.platform.newTable();
        }
    }

    /** Writes the memory to the save now (the game saves, or the character died). */
    @LuaMethod(name = "KnoxLink_memorySave", global = true)
    public static void memorySave() {
        try {
            Memory.saveNow();
        } catch (Throwable t) {
            failedOnce("memory (save)", t);
        }
    }

    // ------------------------------------------------------------------ the scrapbook's photos

    /** Books a photo of the game's screen for the next frame (saved as knoxlink/photos/<id>.jpg); nil, or why not. */
    @LuaMethod(name = "KnoxLink_photo", global = true)
    public static String photo(String id) {
        try {
            return Photo.take(id);
        } catch (Throwable t) {
            failedOnce("photo", t);
            return "Could not take it";
        }
    }

    /** Called before the game draws its UI (OnPreUIDraw): takes a booked photo once it's time; true when it did. */
    @LuaMethod(name = "KnoxLink_photoFrame", global = true)
    public static boolean photoFrame() {
        return Photo.frame();
    }

    @LuaMethod(name = "KnoxLink_photoDelete", global = true)
    public static boolean photoDelete(String id) {
        try {
            return Photo.delete(id);
        } catch (Throwable t) {
            failedOnce("delete photo", t);
            return false;
        }
    }

    // ------------------------------------------------------------------ live character picture

    private static boolean tidsQueued;

    /** Called every UI frame (OnPostUIDraw); draws the character for the screens when one is watching. */
    @LuaMethod(name = "KnoxLink_avatar", global = true)
    public static void avatar() {
        if (!tidsQueued) {
            // for the profiler: which Linux threads are the game's main and render threads
            tidsQueued = true;
            Profiler.noteThread();
            try {
                zombie.core.SpriteRenderer.instance.drawGeneric(new zombie.core.textures.TextureDraw.GenericDrawer() {
                    @Override
                    public void render() {
                        Profiler.noteThread();
                    }
                });
            } catch (Throwable ignored) {
            }
        }
        Avatar.frame();
    }

    @LuaMethod(name = "KnoxLink_avatarRefresh", global = true)
    public static void avatarRefresh() {
        Avatar.refresh();
    }

    @LuaMethod(name = "KnoxLink_avatarAngle", global = true)
    public static void avatarAngle(double degrees) {
        Avatar.setAngle((float) degrees);
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    static Object toLua(Object v) {
        if (v instanceof Map) {
            KahluaTable t = LuaManager.platform.newTable();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                Object x = toLua(e.getValue());
                if (x != null) t.rawset(e.getKey(), x);
            }
            return t;
        }
        if (v instanceof List) {
            KahluaTable t = LuaManager.platform.newTable();
            int i = 1;
            for (Object o : (List<Object>) v) {
                Object x = toLua(o);
                if (x != null) t.rawset(i++, x);
            }
            return t;
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Float) return ((Number) v).doubleValue();
        return v;   // String, Double, Boolean, null
    }
}
