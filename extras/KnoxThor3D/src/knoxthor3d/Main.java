package knoxthor3d;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.zed_0xff.zombie_buddy.Patch;
import org.joml.Matrix4f;
import zombie.gameStates.ChooseGameInfo;

/**
 * Project Viewpoint on Android handhelds (Zomdroid).
 *
 * Viewpoint's 3D renderer needs desktop OpenGL 4.5. ZINK_OSMESA gives it all of that; ZINK_ZFA
 * does once the functions its library doesn't export are filled in (ZfaFunctions); on the other
 * renderers the game aborts the moment the renderer starts. So on those, when a save loads,
 * Viewpoint (and the add-ons that need it) are left out of the mods the game loads, as if switched
 * off for that session; the save's own mod list is untouched. Switching Zomdroid's renderer is
 * then the only change between the 3D view and the everyday one.
 *
 * Also makes Viewpoint lighter there (the crosshair depth read, DepthRead).
 * Needs to be on in the main menu's mod list too, so it's already running when a save loads.
 * On a PC (no Zomdroid) nothing here does anything.
 */
public class Main {
    private static volatile Boolean supported;

    public static void main(String[] args) {
        log("ready (renderer " + renderer() + ", Viewpoint 3D "
                + (supported() ? "allowed" : "left out: for 3D pick ZINK_OSMESA or ZINK_ZFA in Zomdroid's instance settings") + ")");
    }

    static void log(String s) {
        System.out.println("[KnoxThor3D] " + s);
    }

    /** Zomdroid's renderer name, or "desktop" outside Zomdroid. */
    public static String renderer() {
        String r = System.getProperty("zomdroid.renderer");
        return r == null ? "desktop" : r;
    }

    public static boolean onZomdroid() {
        return System.getProperty("zomdroid.renderer") != null;
    }

    public static boolean zfa() {
        return renderer().startsWith("ZINK_ZFA");
    }

    /** Viewpoint's renderer can run here. */
    public static boolean supported() {
        Boolean s = supported;
        if (s == null) {
            String r = System.getProperty("zomdroid.renderer");
            s = r == null || r.startsWith("ZINK_OSMESA") || r.startsWith("ZINK_ZFA");
            supported = s;
        }
        return s;
    }

    /** "\Viewpoint" or "3809306528\Viewpoint" -> "Viewpoint". */
    private static String bare(String entry) {
        String s = entry == null ? "" : entry.trim();
        int i = s.lastIndexOf('\\');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    /** The mods to load without Viewpoint and everything that requires it (the game would pull it back in). */
    public static List<String> leaveOutViewpoint(List<String> mods) {
        if (mods == null || supported()) return mods;
        Set<String> drop = new HashSet<>();
        drop.add("Viewpoint");
        boolean grew = true;
        while (grew) {
            grew = false;
            for (String entry : mods) {
                String id = bare(entry);
                if (drop.contains(id)) continue;
                try {
                    ChooseGameInfo.Mod m = ChooseGameInfo.getModDetails(id);
                    if (m == null || m.getRequire() == null) continue;
                    for (String r : m.getRequire()) {
                        if (drop.contains(bare(r))) {
                            drop.add(id);
                            grew = true;
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        List<String> out = new ArrayList<>();
        List<String> left = new ArrayList<>();
        for (String entry : mods) {
            if (drop.contains(bare(entry))) left.add(bare(entry));
            else out.add(entry);
        }
        if (left.isEmpty()) return mods;
        log("Zomdroid's renderer " + renderer() + " can't run Viewpoint's 3D view; this session leaves out "
                + String.join(", ", left) + ". For 3D pick ZINK_OSMESA or ZINK_ZFA in Zomdroid's instance settings.");
        return out;
    }

    @Patch(className = "zombie.ZomboidFileSystem", methodName = "loadMods", strictMatch = true)
    public static final class LeaveOut {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(value = 0, readOnly = false) List<String> mods) {
            mods = Main.leaveOutViewpoint(mods);
        }
    }

    /**
     * On ZINK_ZFA with PZ Optimization the game's tile-depth shaders don't link, and the game's own
     * (top-down) renderer then throws for every floor tile it draws: a logged stack trace several
     * times a second, also while Viewpoint draws the 3D view. Without the shaders those tiles are
     * simply drawn without the depth step.
     */
    @Patch(className = "zombie.iso.sprite.IsoSprite", methodName = "startTileDepthShader")
    public static final class NoTileDepthShader {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() {
            return zombie.core.SceneShaderStore.tileDepthShader == null || zombie.core.SceneShaderStore.opaqueDepthShader == null;
        }
    }

    /** On ZINK_ZFA: fill in the GL functions its library lacks, on the render thread, before anything needs them. */
    @Patch(className = "zombie.core.opengl.RenderThread", methodName = "renderStep")
    public static final class ZfaHook {
        @Patch.OnEnter
        public static void enter() {
            if (!ZfaFunctions.tried && Main.zfa()) ZfaFunctions.install();
        }
    }

    // ------------------------------------------------------------------ what's under the crosshair

    /*
     * Viewpoint reads back the depth under the crosshair to know what you look at. That read is now
     * a GPU copy (DepthRead); it is still only asked when the camera has moved (at most every other
     * frame) and otherwise twice a second, for moving things. Finished reads are still collected
     * every frame, so answers arrive as soon as they can.
     */
    private static final float[] lastView = new float[16];
    private static final float[] view = new float[16];
    private static boolean haveView;
    private static int sinceRead = 1000;
    private static MethodHandle collect;
    private static boolean collectLooked;

    public static boolean skipPick(Matrix4f m) {
        if (!onZomdroid() || m == null) return false;
        sinceRead++;
        m.get(view);
        boolean moved = !haveView;
        for (int i = 0; i < 16 && !moved; i++) moved = Math.abs(view[i] - lastView[i]) > 1e-4f;
        if ((moved && sinceRead >= 2) || sinceRead >= 12) {
            System.arraycopy(view, 0, lastView, 0, 16);
            haveView = true;
            sinceRead = 0;
            return false;
        }
        collect();
        return true;
    }

    private static void collect() {
        try {
            if (!collectLooked) {
                collectLooked = true;
                Method c = Class.forName("viewpoint.render.MousePick").getDeclaredMethod("collect");
                c.setAccessible(true);
                collect = MethodHandles.lookup().unreflect(c);
            }
            if (collect != null) collect.invoke();
        } catch (Throwable t) {
            collect = null;
        }
    }

    @Patch(className = "viewpoint.render.MousePick", methodName = "read")
    public static final class Pick {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(2) Matrix4f viewProjection) {
            boolean skip = Main.skipPick(viewProjection);
            DepthRead.inPick = !skip && Main.onZomdroid();
            return skip;   // true = skip this frame's read
        }

        @Patch.OnExit
        public static void exit() {
            DepthRead.inPick = false;
        }
    }

    @Patch(className = "org.lwjgl.opengl.GL11", methodName = "glReadPixels", strictMatch = true)
    public static final class ReadPixels {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) int x, @Patch.Argument(1) int y, @Patch.Argument(2) int w,
                                    @Patch.Argument(3) int h, @Patch.Argument(4) int format, @Patch.Argument(5) int type,
                                    @Patch.Argument(6) long pixels) {
            // true = done here (the original is skipped)
            return DepthRead.inPick && w == 1 && h == 1 && format == 0x1902 && type == 0x1406 && pixels == 0L && DepthRead.read(x, y);
        }
    }
}
