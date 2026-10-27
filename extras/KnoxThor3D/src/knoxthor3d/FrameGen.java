package knoxthor3d;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import me.zed_0xff.zombie_buddy.Patch;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/**
 * Frame generation for Viewpoint where its own can't run.
 *
 * Viewpoint's frame generation (graphics.frameGeneration) hands each frame to AMD FidelityFX through
 * a Vulkan device of its own, which doesn't exist under Zomdroid. Its frame-generation pass
 * (FrameGenPass) is still all there: it keeps the frame without the HUD, the frame with it, the depth
 * and the motion of every pixel, and shows a made-up frame before each real one, the real one half a
 * frame later. Here the made-up frame comes from a shader instead: the frame halfway between the
 * last two, each pixel taken from where its surface was (following the motion of the nearest surface
 * around it, so the edges of moving things keep their own motion) and blended from both frames where
 * they agree; the HUD is put back where it is. At a 60 cap that's 120 frames a second on the Thor's
 * 120 Hz screen, half a frame later than without.
 * On and off with Viewpoint's own switch (graphics.frameGeneration, or Zomboid/Lua/KnoxThor3D_framegen.txt
 * holding 1 or 0, read every 2 s). KNOXTHOR3D_FRAMEGEN=0 in Zomdroid's environment variables leaves
 * Viewpoint's own frame generation alone.
 */
public final class FrameGen {
    private static final String VERT = "#version 330\n"
            + "out vec2 vUv;\n"
            + "void main() {\n"
            + "  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
            + "  vUv = p;\n"
            + "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
            + "}\n";
    private static final String FRAG = "#version 330\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uScreen, uHudless, uPrev, uMotion, uDepth;\n"
            + "uniform float uReset;\n"
            + "out vec4 frag;\n"
            + "void main() {\n"
            + "  vec3 now = texture(uHudless, vUv).rgb;\n"
            + "  vec3 hud = texture(uScreen, vUv).rgb - now;\n"
            + "  vec3 mid = now;\n"
            + "  if (uReset < 0.5) {\n"
            // the motion of the nearest and of the farthest surface around: along an edge, one is right
            + "    vec2 px = 1.5 / vec2(textureSize(uDepth, 0));\n"
            + "    vec2 nearAt = vUv, farAt = vUv;\n"
            + "    float d0 = texture(uDepth, vUv).r, dn = d0, df = d0;\n"
            + "    for (int i = 0; i < 4; i++) {\n"
            + "      vec2 o = vec2(i == 0 ? px.x : i == 1 ? -px.x : 0.0, i == 2 ? px.y : i == 3 ? -px.y : 0.0);\n"
            + "      float d = texture(uDepth, vUv + o).r;\n"
            + "      if (d < dn) { dn = d; nearAt = vUv + o; }\n"
            + "      if (d > df) { df = d; farAt = vUv + o; }\n"
            + "    }\n"
            + "    vec2 m1 = texture(uMotion, nearAt).xy, m2 = texture(uMotion, farAt).xy;\n" // to where it was a frame ago
            + "    m1 = texture(uMotion, clamp(vUv - 0.5 * m1, 0.0, 1.0)).xy;\n"              // its own motion, where it is now
            + "    vec3 a1 = texture(uHudless, clamp(vUv - 0.5 * m1, 0.0, 1.0)).rgb, b1 = texture(uPrev, clamp(vUv + 0.5 * m1, 0.0, 1.0)).rgb;\n"
            + "    vec3 a2 = texture(uHudless, clamp(vUv - 0.5 * m2, 0.0, 1.0)).rgb, b2 = texture(uPrev, clamp(vUv + 0.5 * m2, 0.0, 1.0)).rgb;\n"
            + "    float e1 = dot(abs(a1 - b1), vec3(0.3333)), e2 = dot(abs(a2 - b2), vec3(0.3333));\n"
            // keep the motion both frames agree on; where neither does, the newer frame (shown right after)
            + "    vec3 a = e1 <= e2 ? a1 : a2, b = e1 <= e2 ? b1 : b2;\n"
            + "    float apart = min(e1, e2);\n"
            + "    mid = mix(mix(a, b, 0.5), a, smoothstep(0.06, 0.20, apart));\n"
            + "  }\n"
            + "  frag = vec4(clamp(mid + hud, 0.0, 1.0), 1.0);\n"
            + "}\n";
    private static final int UNIT = 8;   // texture units 8..12, away from the ones the game keeps bound

    private static int enabled = -1;
    private static volatile boolean failed;
    private static boolean logged;
    private static Object pass;
    private static int screenTex, generatedTex, depthTex, motionTex, program, vao, current;
    private static final int[] hudlessTex = new int[2];
    private static int uReset;
    private static int width, height;
    private static boolean fresh;

    private static Field generationF, inputsF, screenF, hudlessF, generatedF, keptF;
    private static Method releaseM, colourFboM, framebufferM;
    private static Object unsafe;
    private static Method allocate;

    private static File control;
    private static long controlChecked, controlStamp = -1;

    private FrameGen() {}

    // (public: the patches below run inside Viewpoint's classes, which can only reach public members)
    public static boolean broken() {
        return failed;
    }

    public static boolean on() {
        if (enabled < 0) {
            int v = Main.onZomdroid() ? 1 : 0;
            try {
                String e = System.getenv("KNOXTHOR3D_FRAMEGEN");
                if (e != null && e.trim().equals("0")) v = 0;
            } catch (Throwable ignored) {
            }
            enabled = v;
        }
        return enabled == 1 && !failed;
    }

    /** Zomboid/Lua/KnoxThor3D_framegen.txt (1/0) flips Viewpoint's switch; looked at every 2 s (Present). */
    static void watch() {
        if (!on()) return;
        long now = System.nanoTime();
        if (now - controlChecked < 2_000_000_000L) return;
        controlChecked = now;
        try {
            if (control == null) control = new File(System.getProperty("user.home"), "Zomboid/Lua/KnoxThor3D_framegen.txt");
            long stamp = control.lastModified();
            if (stamp == controlStamp) return;
            boolean first = controlStamp == -1;
            controlStamp = stamp;
            if (stamp == 0L || first) return;   // only a change made while the game runs
            boolean want = new String(Files.readAllBytes(control.toPath()), StandardCharsets.UTF_8).trim().equals("1");
            Object toggle = Class.forName("viewpoint.platform.Graphics").getField("FRAME_GENERATION").get(null);
            for (Method m : toggle.getClass().getMethods()) {
                if (m.getName().equals("set") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == boolean.class) {
                    m.invoke(toggle, want);
                    Main.log("frame generation " + (want ? "on" : "off") + " (KnoxThor3D_framegen.txt)");
                    return;
                }
            }
        } catch (Throwable t) {
            Main.log("frame generation switch: " + t);
        }
    }

    // ------------------------------------------------------------------ in place of Viewpoint's FrameGeneration

    /** FrameGenPass.fit: the textures and framebuffers for this screen and frame size. False: Viewpoint's own. */
    public static boolean fit(Object framePass, int sw, int sh, int fw, int fh) {
        if (!on()) return false;
        try {
            if (generationF == null) look(framePass.getClass());
            pass = framePass;
            Object old = generationF.get(framePass);
            if (old != null && width == sw && height == sh && sizeOf(old, "frameWidth") == fw && sizeOf(old, "frameHeight") == fh) return true;
            releaseM.invoke(framePass);   // closes the old one (close below) and deletes the pass's framebuffers
            if (program == 0) build();
            screenTex = texture(GL11.GL_RGBA8, sw, sh, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
            hudlessTex[0] = texture(GL11.GL_RGBA8, sw, sh, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
            hudlessTex[1] = texture(GL11.GL_RGBA8, sw, sh, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
            generatedTex = texture(GL11.GL_RGBA8, sw, sh, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
            depthTex = texture(GL30.GL_R16F, fw, fh, GL11.GL_RED, GL11.GL_FLOAT);
            motionTex = texture(GL30.GL_RG16F, fw, fh, GL30.GL_RG, GL11.GL_FLOAT);
            current = 0;
            width = sw;
            height = sh;
            fresh = true;
            Class<?> fg = Class.forName("viewpoint.upscale.FrameGeneration");
            Object gen = allocate.invoke(unsafe, fg);
            setSize(gen, "screenWidth", sw);
            setSize(gen, "screenHeight", sh);
            setSize(gen, "frameWidth", fw);
            setSize(gen, "frameHeight", fh);
            generationF.set(framePass, gen);
            inputsF.setInt(framePass, (Integer) framebufferM.invoke(null, depthTex, motionTex, "frame generation's inputs"));
            screenF.setInt(framePass, (Integer) colourFboM.invoke(null, screenTex));
            hudlessF.setInt(framePass, (Integer) colourFboM.invoke(null, hudlessTex[0]));
            generatedF.setInt(framePass, (Integer) colourFboM.invoke(null, generatedTex));
            keptF.setBoolean(framePass, false);
            if (!logged) {
                logged = true;
                Main.log("frame generation: KnoxThor3D's own, " + sw + "x" + sh + " from a " + fw + "x" + fh + " frame");
            }
            return true;
        } catch (Throwable t) {
            fail("setting up", t);
            return true;   // (capture below stops at once now)
        }
    }

    /** FrameGeneration.generate: the frame halfway between the last real one and this one, into the generated texture. */
    public static void generate(boolean reset) {
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] tex = new int[5], samplers = new int[5];
        int[] inputs = {screenTex, hudlessTex[current], hudlessTex[1 - current], motionTex, depthTex};
        try {
            for (int i = 0; i < 5; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT + i);
                tex[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                samplers[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
                GL33.glBindSampler(UNIT + i, 0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, inputs[i]);
            }
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, generatedF.getInt(pass));
            GL11.glViewport(0, 0, width, height);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL20.glUseProgram(program);
            GL20.glUniform1f(uReset, reset || fresh ? 1f : 0f);
            GL30.glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            fresh = false;
            // the next frame's picture without the HUD goes into the other texture; this one is "before" then
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, hudlessF.getInt(pass));
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, hudlessTex[1 - current], 0);
            current = 1 - current;
        } catch (Throwable t) {
            throw new IllegalStateException("KnoxThor3D frame generation: " + t, t);
        } finally {
            for (int i = 0; i < 5; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT + i);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex[i]);
                GL33.glBindSampler(UNIT + i, samplers[i]);
            }
            GL13.glActiveTexture(prevActive);
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (blend) GL11.glEnable(GL11.GL_BLEND);
            if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
            if (cull) GL11.glEnable(GL11.GL_CULL_FACE);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
    }

    /** FrameGeneration.close: frees the textures (the pass deletes its framebuffers itself). */
    public static void close() {
        int[] all = {screenTex, hudlessTex[0], hudlessTex[1], generatedTex, depthTex, motionTex};
        for (int t : all) if (t != 0) GL11.glDeleteTextures(t);
        screenTex = hudlessTex[0] = hudlessTex[1] = generatedTex = depthTex = motionTex = 0;
        width = height = 0;
    }

    public static int generated() {
        return generatedTex;
    }

    // ------------------------------------------------------------------ helpers

    private static void look(Class<?> passClass) throws Exception {
        generationF = field(passClass, "generation");
        inputsF = field(passClass, "inputsFbo");
        screenF = field(passClass, "screenFbo");
        hudlessF = field(passClass, "hudlessFbo");
        generatedF = field(passClass, "generatedFbo");
        keptF = field(passClass, "kept");
        releaseM = passClass.getDeclaredMethod("release");
        releaseM.setAccessible(true);
        colourFboM = passClass.getDeclaredMethod("colourFbo", int.class);
        colourFboM.setAccessible(true);
        framebufferM = Class.forName("viewpoint.render.VendorInputs").getDeclaredMethod("framebuffer", int.class, int.class, String.class);
        framebufferM.setAccessible(true);
        Class<?> u = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = u.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        unsafe = theUnsafe.get(null);
        allocate = u.getMethod("allocateInstance", Class.class);
    }

    private static Field field(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static int sizeOf(Object gen, String name) throws Exception {
        return gen.getClass().getField(name).getInt(gen);
    }

    private static void setSize(Object gen, String name, int v) throws Exception {
        Field f = gen.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(gen, v);
    }

    private static int texture(int internal, int w, int h, int format, int type) {
        int t = GL11.glGenTextures();
        int was = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F);   // CLAMP_TO_EDGE
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, was);
        return t;
    }

    private static void build() {
        int vs = shader(GL20.GL_VERTEX_SHADER, VERT), fs = shader(GL20.GL_FRAGMENT_SHADER, FRAG);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException("link: " + GL20.glGetProgramInfoLog(p));
        int was = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(p);
        String[] names = {"uScreen", "uHudless", "uPrev", "uMotion", "uDepth"};
        for (int i = 0; i < names.length; i++) GL20.glUniform1i(GL20.glGetUniformLocation(p, names[i]), UNIT + i);
        uReset = GL20.glGetUniformLocation(p, "uReset");
        GL20.glUseProgram(was);
        program = p;
        vao = GL30.glGenVertexArrays();
    }

    private static int shader(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) throw new IllegalStateException("compile: " + GL20.glGetShaderInfoLog(s));
        return s;
    }

    private static void fail(String what, Throwable t) {
        failed = true;
        Main.log("frame generation: " + what + " failed (" + t + "), off until the game restarts");
    }

    // ------------------------------------------------------------------ the patches

    @Patch(className = "viewpoint.upscale.FrameGeneration", methodName = "unavailable")
    public static final class Available {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() {
            return FrameGen.on();
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) String why) {
            if (FrameGen.on()) why = null;
        }
    }

    @Patch(className = "viewpoint.upscale.FrameGeneration", methodName = "unsupported")
    public static final class Supported {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() {
            return FrameGen.on();
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) String why) {
            if (FrameGen.on()) why = null;
        }
    }

    @Patch(className = "viewpoint.render.FrameGenPass", methodName = "fit")
    public static final class Fit {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) int sw, @Patch.Argument(1) int sh,
                                    @Patch.Argument(2) int fw, @Patch.Argument(3) int fh) {
            return FrameGen.fit(self, sw, sh, fw, fh);   // true = done here
        }
    }

    @Patch(className = "viewpoint.render.FrameGenPass", methodName = "capture")
    public static final class Capture {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() {
            return FrameGen.broken();   // true = nothing this frame
        }
    }

    @Patch(className = "viewpoint.upscale.FrameGeneration", methodName = "generate")
    public static final class Generate {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(1) boolean reset) {
            if (!FrameGen.on()) return false;
            FrameGen.generate(reset);
            return true;
        }
    }

    @Patch(className = "viewpoint.upscale.FrameGeneration", methodName = "close")
    public static final class Close {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() {
            if (!FrameGen.on()) return false;
            FrameGen.close();
            return true;
        }
    }

    @Patch(className = "viewpoint.upscale.FrameGeneration", methodName = "generated")
    public static final class Generated {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() {
            return FrameGen.on();
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) int texture) {
            if (FrameGen.on()) texture = FrameGen.generated();
        }
    }
}
