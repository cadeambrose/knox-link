package knoxthor3d;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.Map;

/**
 * Lets Viewpoint's Vanilla mode draw its 3D view at a lower resolution than the screen.
 *
 * Viewpoint's renderer can draw at a fraction of the screen and rebuild the full-size picture with
 * its own temporal anti-aliasing (TAA+ samples a slightly different spot every frame and keeps the
 * history at screen size), but only its Modern packs set a render scale: the built-in Vanilla pack
 * leaves it at 100 %. A scale given here goes into every pack that doesn't set one itself, from
 * KNOXTHOR3D_RENDER_SCALE in Zomdroid's environment variables or Zomboid/Lua/KnoxThor3D_scale.txt
 * (25..100; the file wins, and changing it while the game runs makes Viewpoint rebuild its pipeline).
 */
public final class Scale {
    private static Object knob;
    private static boolean looked;
    private static int logged = -1;
    private static File file;
    private static long fileStamp = -1;

    private Scale() {}

    /**
     * Called now and then on the render thread: when KnoxThor3D_scale.txt changes, marks Viewpoint's
     * settings as edited, so it rebuilds its pipeline (through Pipeline.of, where the scale goes in).
     */
    static void watch() {
        try {
            if (file == null) file = new File(System.getProperty("user.home"), "Zomboid/Lua/KnoxThor3D_scale.txt");
            long stamp = file.lastModified();
            if (fileStamp == -1) {
                fileStamp = stamp;
                return;
            }
            if (stamp == fileStamp) return;
            fileStamp = stamp;
            Class.forName("viewpoint.platform.SettingsWindow").getMethod("edited").invoke(null);
        } catch (Throwable ignored) {
        }
    }

    /** The render scale to use, or 0 to leave Viewpoint's own. */
    static int scale() {
        int s = 0;
        try {
            String e = System.getenv("KNOXTHOR3D_RENDER_SCALE");
            if (e != null && !e.trim().isEmpty()) s = Integer.parseInt(e.trim());
        } catch (Throwable ignored) {
        }
        try {
            File f = new File(System.getProperty("user.home"), "Zomboid/Lua/KnoxThor3D_scale.txt");
            if (f.isFile()) s = Integer.parseInt(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim());
        } catch (Throwable ignored) {
        }
        return s >= 25 && s < 100 ? s : 0;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Map<Object, String> apply(Map<Object, String> knobs) {
        if (knobs == null || knobs.isEmpty() || !Main.onZomdroid()) return knobs;   // empty: Viewpoint's own LIBRARY defaults
        try {
            if (!looked) {
                looked = true;
                Class<? extends Enum> k = (Class<? extends Enum>) Class.forName("viewpoint.platform.Pipeline$Knob");
                knob = Enum.valueOf(k, "RENDER_SCALE");
            }
            if (knob == null || knobs.containsKey(knob)) return knobs;   // the pack sets its own scale
            int s = scale();
            if (s != logged) {
                logged = s;
                Main.log(s == 0 ? "3D view at full resolution" : "3D view drawn at " + s + " % of the screen, rebuilt to full size by Viewpoint's TAA");
            }
            if (s == 0) return knobs;
            Map copy = new EnumMap((Map) knobs);
            copy.put(knob, Integer.toString(s));
            return copy;
        } catch (Throwable t) {
            Main.log("render scale: not available (" + t + ")");
            knob = null;
            return knobs;
        }
    }
}
