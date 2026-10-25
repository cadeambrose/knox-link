package knoxthor3d;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL43C;

/**
 * Reads the depth under Viewpoint's crosshair without stopping the GPU.
 *
 * Viewpoint asks for one depth value a frame: glReadPixels(DEPTH_COMPONENT, FLOAT) from its
 * 24-bit depth buffer into a pixel buffer, collected a frame later. Mesa has no GPU path for that
 * conversion, so it maps the depth texture on the CPU and waits for the GPU to finish everything
 * queued (tens of milliseconds a read on the Thor). Here a one-pixel draw copies the value into a
 * 1x1 float colour target instead, and the colour read into the same pixel buffer runs on the GPU
 * like any other command. Viewpoint reads the result exactly as before.
 */
public final class DepthRead {
    private static final int UNIT = 15;
    private static final int GL_DEPTH_STENCIL_TEXTURE_MODE = 0x90EA;
    public static boolean inPick;
    private static int program, fbo, colour, vao, uPx, uDepth;
    private static boolean broken;
    static int done, fallbacks;

    private DepthRead() {}

    private static int shader(int type, String src) {
        int s = GL20C.glCreateShader(type);
        GL20C.glShaderSource(s, src);
        GL20C.glCompileShader(s);
        if (GL20C.glGetShaderi(s, GL20C.GL_COMPILE_STATUS) == 0) throw new IllegalStateException(GL20C.glGetShaderInfoLog(s));
        return s;
    }

    private static boolean init() {
        int vs = shader(GL20C.GL_VERTEX_SHADER, "#version 330 core\n"
                + "void main() { vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2)); gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }\n");
        int fs = shader(GL20C.GL_FRAGMENT_SHADER, "#version 330 core\n"
                + "uniform sampler2D depthTex; uniform ivec2 px; out vec4 colour;\n"
                + "void main() { colour = vec4(texelFetch(depthTex, px, 0).r, 0.0, 0.0, 1.0); }\n");
        program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vs);
        GL20C.glAttachShader(program, fs);
        GL20C.glLinkProgram(program);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) throw new IllegalStateException(GL20C.glGetProgramInfoLog(program));
        uPx = GL20C.glGetUniformLocation(program, "px");
        uDepth = GL20C.glGetUniformLocation(program, "depthTex");

        int prevTex = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        colour = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, colour);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_R32F, 1, 1, 0, GL11C.GL_RED, GL11C.GL_FLOAT, (java.nio.ByteBuffer) null);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTex);

        int prevDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        fbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, fbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colour, 0);
        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, prevDraw);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("framebuffer " + Integer.toHexString(status));

        int prevVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        vao = GL30C.glGenVertexArrays();
        GL30C.glBindVertexArray(prevVao);
        Main.log("crosshair depth read: GPU copy ready");
        return true;
    }

    /** The depth texture attached to the bound read framebuffer, or 0. */
    private static int depthTexture() {
        for (int attachment : new int[] {GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_DEPTH_STENCIL_ATTACHMENT}) {
            int type = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_READ_FRAMEBUFFER, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            if (type == GL11C.GL_TEXTURE) {
                int level = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_READ_FRAMEBUFFER, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
                int layer = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_READ_FRAMEBUFFER, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER);
                if (level != 0 || layer != 0) return 0;
                return GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_READ_FRAMEBUFFER, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            }
        }
        return 0;
    }

    /** Stands in for glReadPixels(x, y, 1, 1, DEPTH_COMPONENT, FLOAT, 0) into the bound pack buffer. False: let the original run. */
    public static boolean read(int x, int y) {
        if (broken) return false;
        try {
            if (program == 0) init();
            int depthTex = depthTexture();
            if (depthTex == 0) {
                fallbacks++;
                return false;
            }
            int readFb = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
            int drawFb = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            int prevProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            int prevVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
            int prevActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            int[] viewport = new int[4];
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
            boolean depthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST), blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
            boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST), cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
            boolean stencil = GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST), discard = GL11C.glIsEnabled(GL30C.GL_RASTERIZER_DISCARD);
            int[] mask = new int[4];
            GL11C.glGetIntegerv(GL11C.GL_COLOR_WRITEMASK, mask);

            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + UNIT);
            int prevTex = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            int prevSampler = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
            GL33C.glBindSampler(UNIT, 0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTex);
            int compare = GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE);
            int dsMode = GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D, GL_DEPTH_STENCIL_TEXTURE_MODE);
            if (compare != GL11C.GL_NONE) GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
            if (dsMode != GL11C.GL_DEPTH_COMPONENT) GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL_DEPTH_STENCIL_TEXTURE_MODE, GL11C.GL_DEPTH_COMPONENT);

            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, fbo);
            GL11C.glViewport(0, 0, 1, 1);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL11C.GL_STENCIL_TEST);
            GL11C.glDisable(GL30C.GL_RASTERIZER_DISCARD);
            GL11C.glColorMask(true, true, true, true);
            GL20C.glUseProgram(program);
            GL20C.glUniform1i(uDepth, UNIT);
            GL20C.glUniform2i(uPx, x, y);
            GL30C.glBindVertexArray(vao);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, fbo);
            GL11C.glReadPixels(0, 0, 1, 1, GL11C.GL_RED, GL11C.GL_FLOAT, 0L);

            // everything back as it was
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFb);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFb);
            if (compare != GL11C.GL_NONE) GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE, compare);
            if (dsMode != GL11C.GL_DEPTH_COMPONENT) GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL_DEPTH_STENCIL_TEXTURE_MODE, dsMode);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTex);
            GL33C.glBindSampler(UNIT, prevSampler);
            GL13C.glActiveTexture(prevActive);
            GL30C.glBindVertexArray(prevVao);
            GL20C.glUseProgram(prevProgram);
            GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL11C.glColorMask(mask[0] != 0, mask[1] != 0, mask[2] != 0, mask[3] != 0);
            if (depthTest) GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            if (blend) GL11C.glEnable(GL11C.GL_BLEND);
            if (scissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            if (cull) GL11C.glEnable(GL11C.GL_CULL_FACE);
            if (stencil) GL11C.glEnable(GL11C.GL_STENCIL_TEST);
            if (discard) GL11C.glEnable(GL30C.GL_RASTERIZER_DISCARD);
            if (done++ == 0) Main.log("crosshair depth read: first GPU copy done (texture " + depthTex + ")");
            return true;
        } catch (Throwable t) {
            broken = true;
            Main.log("crosshair depth read: GPU copy failed, back to Viewpoint's own read: " + t);
            return false;
        }
    }
}
