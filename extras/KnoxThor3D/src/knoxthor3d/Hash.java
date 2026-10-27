package knoxthor3d;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Takes the world's chunks out of Viewpoint's translucent pass.
 *
 * Viewpoint draws the near world twice: once into its G-buffer (texels at least 85 % opaque) and
 * once more in a forward "translucent" pass that keeps only the texels between 4 and 85 % (window
 * panes, sprite edges) and blends them. That second pass sends every chunk with any such texel
 * through the GPU again, and the cost is in fetching the vertices, not in drawing them: on the Thor
 * about 4 ms of GPU time a frame, and dropping the triangles in the vertex shader saves nothing.
 * So the chunks' second draw is skipped. Window panes then go into the G-buffer as alpha-hashed
 * coverage (a dither TAA averages out, as Viewpoint already does for plants), so a pane, a broken
 * one and an empty frame still look different; other soft texels (a texel-wide fringe on sprite
 * edges) are simply left out. Glass models and flames keep their own translucent drawing.
 * KNOXTHOR3D_HASH in Zomdroid's environment variables or Zomboid/Lua/KnoxThor3D_hash.txt (the file
 * wins): 0 = Viewpoint's own (default), 1 = skip the chunks' translucent draw (panes vanish too),
 * 2 = skip it and dither the panes into the G-buffer, 3 = draw it only for the chunks near the camera
 * (within KnoxThor3D_near.txt tiles, 24 by default): panes close by stay exactly as Viewpoint draws
 * them, farther ones lose their tint. Only while TAA is on (Viewpoint hashes plants then); 2 changes a
 * shader, so going to or from it applies from the next start.
 */
public final class Hash {
    private static volatile float near = 24f;
    private static boolean nearOnly;   // inside the chunks' translucent draw, mode 3
    private static final String PLANTS_ONLY = "if (uAlphaHash != 0 && plant) {";
    private static final String WITH_GLASS =
            "if (uAlphaHash != 0 && (plant || (flags & FLAG_GLASS) != 0 && c.a >= 0.04 && c.a < uAlphaRange.x)) {";

    private static volatile int mode = -1;
    private static volatile boolean shaderPatched;   // the sprite shader last read dithers panes
    private static File file;
    private static long fileStamp = -1;
    private static boolean loggedMissing, loggedPatched;

    private Hash() {}

    static int mode() {
        int m = mode;
        if (m < 0) {
            m = Main.onZomdroid() ? read() : 0;
            mode = m;
            if (m != 0) Main.log("translucent chunks: mode " + m);
        }
        return m;
    }

    private static int read() {
        int m = 0;
        try {
            String e = System.getenv("KNOXTHOR3D_HASH");
            if (e != null && !e.trim().isEmpty()) m = Integer.parseInt(e.trim());
        } catch (Throwable ignored) {
        }
        try {
            File f = new File(System.getProperty("user.home"), "Zomboid/Lua/KnoxThor3D_hash.txt");
            if (f.isFile()) m = Integer.parseInt(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim());
        } catch (Throwable ignored) {
        }
        try {
            File f = new File(System.getProperty("user.home"), "Zomboid/Lua/KnoxThor3D_near.txt");
            if (f.isFile()) near = Math.max(4f, Float.parseFloat(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim()));
        } catch (Throwable ignored) {
        }
        return m >= 0 && m <= 3 ? m : 0;
    }

    /** Called every 2 s on the render thread (Present): a changed KnoxThor3D_hash.txt takes effect. */
    static void watch() {
        try {
            if (file == null) file = new File(System.getProperty("user.home"), "Zomboid/Lua/KnoxThor3D_hash.txt");
            long stamp = file.lastModified();
            if (fileStamp == -1) {
                fileStamp = stamp;
                return;
            }
            if (stamp == fileStamp || !Main.onZomdroid()) return;
            fileStamp = stamp;
            mode = read();
            Main.log("translucent chunks: mode " + mode + (mode == 3 ? ", within " + near + " tiles" : "") + " (KnoxThor3D_hash.txt)");
        } catch (Throwable ignored) {
        }
    }

    /** Shaders.read: in mode 2 the sprite sampler dithers window panes wherever it dithers plants. */
    public static String shader(String name, String text) {
        if (text == null || name == null || !name.endsWith("sample_sprite.glsl")) return text;
        if (text.contains(WITH_GLASS)) {   // both read overloads pass it on
            shaderPatched = true;
            return text;
        }
        if (mode() != 2) {
            shaderPatched = false;
            return text;
        }
        if (!text.contains(PLANTS_ONLY)) {
            if (!loggedMissing) {
                loggedMissing = true;
                Main.log("translucent chunks: this Viewpoint's sprite shader differs, left as it is");
            }
            shaderPatched = false;
            return text;
        }
        shaderPatched = true;
        if (!loggedPatched) {
            loggedPatched = true;
            Main.log("translucent chunks: skipped, window panes dithered into the G-buffer");
        }
        return text.replace(PLANTS_ONLY, WITH_GLASS);
    }

    /** Meshes.drawPlanned: true skips the chunks' translucent draw (pass 4 with plants hashed, i.e. TAA on). */
    public static boolean skip(byte pass, boolean foliageToo) {
        nearOnly = false;
        if (pass != 4 || foliageToo) return false;
        int m = mode();
        nearOnly = m == 3;
        return m == 1 || m == 2 && shaderPatched;
    }

    public static void drawn() {
        nearOnly = false;
    }

    /** Meshes.drawn, during the chunks' translucent draw in mode 3: only meshes near the camera. */
    public static boolean near(Object sceneData, int i, boolean drawn) {
        if (!drawn || !nearOnly) return drawn;
        try {
            if (offsetsF == null) offsetsF = sceneData.getClass().getField("meshOffsets");
            float[] o = (float[]) offsetsF.get(sceneData);
            float x = o[i * 3] - 4f, z = o[i * 3 + 2] - 4f;   // to the chunk's middle, as Viewpoint sorts them
            return x * x + z * z <= near * near;
        } catch (Throwable t) {
            nearOnly = false;
            mode = 0;
            Main.log("translucent chunks: can't tell how far the chunks are (" + t + "), Viewpoint's own drawing");
            return drawn;
        }
    }

    private static java.lang.reflect.Field offsetsF;

    // ------------------------------------------------------------------ the patches

    @Patch(className = "viewpoint.render.Meshes", methodName = "drawPlanned")
    public static final class Translucent {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(2) byte pass, @Patch.Argument(3) boolean foliageToo) {
            return Hash.skip(pass, foliageToo);   // true = not drawn
        }

        @Patch.OnExit
        public static void exit() {
            Hash.drawn();
        }
    }

    @Patch(className = "viewpoint.render.Meshes", methodName = "drawn")
    public static final class Near {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) Object scene, @Patch.Argument(1) int i,
                                @Patch.Return(readOnly = false) boolean drawn) {
            drawn = Hash.near(scene, i, drawn);
        }
    }

    @Patch(className = "viewpoint.platform.Shaders", methodName = "read")
    public static final class Source {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) String name, @Patch.Return(readOnly = false) String text) {
            text = Hash.shader(name, text);
        }
    }
}
