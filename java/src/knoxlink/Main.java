package knoxlink;

import zombie.Lua.LuaManager;

/**
 * ZombieBuddy entry point. Runs once when the game loads the "Knox Link Server" mod's jar.
 * Starts the local web server; everything game-related happens in Lua, which talks to us
 * through the global functions in {@link LuaBridge}.
 */
public class Main {
    public static void main(String[] args) {
        try {
            KnoxServer.get().start();
        } catch (Throwable t) {
            Log.error("could not start the server", t);
        }
        exposeLuaFunctions();
    }

    /**
     * Mod jars load after the game has built its Lua environment, so the KnoxLink_* globals are
     * added right away, and handed to ZombieBuddy so they come back whenever the game rebuilds
     * the environment (e.g. loading another save).
     */
    static void exposeLuaFunctions() {
        try {
            if (LuaManager.exposer != null) {
                LuaManager.exposer.exposeGlobalFunctions(new LuaBridge());
                Log.info("Lua functions ready");
            }
        } catch (Throwable t) {
            Log.error("could not add the Lua functions", t);
        }
        try {
            Class.forName("me.zed_0xff.zombie_buddy.Exposer")
                    .getMethod("addClassWithGlobalLuaMethod", Class.class)
                    .invoke(null, LuaBridge.class);
        } catch (Throwable ignored) {
            // older ZombieBuddy: fine for this session; a restart picks the functions up again
        }
    }
}
