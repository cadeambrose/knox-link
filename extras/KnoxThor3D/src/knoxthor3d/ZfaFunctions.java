package knoxthor3d;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.zed_0xff.zombie_buddy.Patch;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.libffi.FFIType;
import org.lwjgl.system.libffi.LibFFI;

import static org.lwjgl.system.MemoryUtil.memGetAddress;
import static org.lwjgl.system.MemoryUtil.memGetInt;
import static org.lwjgl.system.MemoryUtil.memGetLong;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.system.MemoryUtil.memSet;
import static org.lwjgl.system.Pointer.POINTER_SIZE;

/**
 * Zomdroid's ZINK_ZFA library (libzfa.so) exports Mesa's GL 4.6 functions only by symbol and has
 * no GetProcAddress, so the OpenGL 4.5 direct-state-access functions (and the timer query) that
 * Mesa has but does not export are missing: LWJGL points them at its "function missing" abort and
 * the game dies the first time a mod (Project Viewpoint) calls one.
 *
 * This fills those holes in LWJGL's function table with small stand-ins written against the older
 * bind-to-edit functions libzfa does export. Only entries that are missing are touched; on any
 * other renderer (or a libzfa that gains the exports) nothing changes. Installed once, on the
 * render thread, where the GL context and LWJGL's table live.
 */
public class ZfaFunctions {
    public static volatile boolean tried;
    private static final List<Callback> KEEP = new ArrayList<>();
    private static final Map<Integer, Integer> TEXTURE_TARGET = new HashMap<>();
    private static final Map<Integer, Long> STAMP = new HashMap<>();
    private static int errors, unknownTextures;

public static void log(String s) {
        System.out.println("[KnoxThor3D] ZINK_ZFA: " + s);
    }

    public static void install() {
        tried = true;
        try {
            GLCapabilities caps = GL.getCapabilities();
            Field af = GLCapabilities.class.getDeclaredField("addresses");
            af.setAccessible(true);
            PointerBuffer table = (PointerBuffer) af.get(caps);
            List<Field> fns = new ArrayList<>();
            for (Field f : GLCapabilities.class.getDeclaredFields()) {
                int m = f.getModifiers();
                if (f.getType() == long.class && Modifier.isPublic(m) && !Modifier.isStatic(m)) fns.add(f);
            }
            // the table holds the function fields in declaration order; check that before writing to it
            int same = 0, differ = 0;
            for (int i = 0; i < fns.size() && i < table.capacity(); i++) {
                long v = fns.get(i).getLong(caps);
                if (v == 0) continue;
                if (table.get(i) == v) same++; else differ++;
            }
            log("table " + table.capacity() + " entries, " + fns.size() + " functions, " + same + " match, " + differ + " differ");
            if (differ > 0 || same == 0) {
                log("table layout not as expected: nothing changed");
                return;
            }
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < fns.size(); i++) index.put(fns.get(i).getName(), i);
            List<String> filled = new ArrayList<>();
            fill(caps, fns, table, index, filled);
            log(filled.isEmpty() ? "nothing missing" : "filled " + filled.size() + ": " + String.join(", ", filled));
            // what Project Viewpoint calls that is still missing (a call to one of these aborts the game)
            List<String> still = new ArrayList<>();
            for (String n : VIEWPOINT_NEEDS) {
                Integer at = index.get(n);
                if (at != null && table.get(at) == fns.get(at).getLong(caps) && fns.get(at).getLong(caps) == 0) still.add(n.substring(2));
            }
            log(still.isEmpty() ? "every function Viewpoint calls is there" : "still missing for Viewpoint " + still.size() + ": " + String.join(", ", still));
        } catch (Throwable t) {
            log("failed: " + t);
        }
    }

    private static final String[] VIEWPOINT_NEEDS = {"glActiveTexture","glAttachShader","glBeginQuery","glBindAttribLocation","glBindBuffer","glBindBufferBase","glBindFragDataLocation","glBindFramebuffer","glBindImageTexture","glBindRenderbuffer","glBindSampler","glBindTexture","glBindVertexArray","glBindVertexBuffer","glBlendEquation","glBlendFunc","glBlendFunci","glBlendFuncSeparate","glBlendFuncSeparatei","glBlitFramebuffer","glBufferData","glBufferStorage","glBufferSubData","glCheckFramebufferStatus","glClear","glClearBufferData","glClearBufferfv","glClearBufferuiv","glClearColor","glClearDepth","glClearStencil","glClearTexImage","glClientWaitSync","glColorMask","glColorMaski","glCompileShader","glCopyBufferSubData","glCopyImageSubData","glCopyNamedBufferSubData","glCreateBuffers","glCreateFramebuffers","glCreateMemoryObjectsEXT","glCreateProgram","glCreateShader","glCreateTextures","glCullFace","glDebugMessageCallback","glDeleteBuffers","glDeleteFramebuffers","glDeleteMemoryObjectsEXT","glDeleteProgram","glDeleteSamplers","glDeleteSemaphoresEXT","glDeleteShader","glDeleteSync","glDeleteTextures","glDeleteVertexArrays","glDepthFunc","glDepthMask","glDepthRange","glDetachShader","glDisable","glDisablei","glDisableVertexAttribArray","glDispatchCompute","glDrawArrays","glDrawArraysInstanced","glDrawArraysInstancedBaseInstance","glDrawBuffer","glDrawBuffers","glEnable","glEnablei","glEnableVertexAttribArray","glEndQuery","glFenceSync","glFinish","glFlush","glFramebufferRenderbuffer","glFramebufferTexture2D","glFramebufferTextureLayer","glFrontFace","glGenBuffers","glGenerateMipmap","glGenFramebuffers","glGenQueries","glGenRenderbuffers","glGenSamplers","glGenSemaphoresEXT","glGenTextures","glGenVertexArrays","glGetActiveUniform","glGetBufferParameteriv","glGetBufferSubData","glGetFloatv","glGetIntegerv","glGetNamedBufferSubData","glGetProgramInfoLog","glGetProgramInterfaceiv","glGetProgramiv","glGetProgramResourceiv","glGetQueryObjecti64v","glGetQueryObjectiv","glGetQueryObjectui64v","glGetShaderInfoLog","glGetShaderiv","glGetString","glGetTexImage","glGetTexLevelParameteriv","glGetTexParameteriv","glGetTextureImage","glGetTextureLevelParameteriv","glGetUniformiv","glGetUniformLocation","glGetUnsignedBytei_vEXT","glImportMemoryWin32HandleEXT","glImportSemaphoreWin32HandleEXT","glIsEnabled","glLinkProgram","glMemoryBarrier","glMultiDrawArraysIndirect","glMultiDrawElementsIndirect","glNamedBufferStorage","glNamedFramebufferDrawBuffer","glNamedFramebufferReadBuffer","glNamedFramebufferTextureLayer","glPatchParameteri","glPixelStorei","glPolygonMode","glPolygonOffset","glPopAttrib","glPopClientAttrib","glPopDebugGroup","glPushAttrib","glPushClientAttrib","glPushDebugGroup","glQueryCounter","glReadBuffer","glReadPixels","glRenderbufferStorage","glSamplerParameterf","glSamplerParameteri","glScissor","glShaderSource","glSignalSemaphoreEXT","glStencilFunc","glStencilMask","glStencilOp","glTexBuffer","glTexBufferRange","glTexImage1D","glTexImage2D","glTexImage3D","glTexParameterf","glTexParameteri","glTexStorage1D","glTexStorage2D","glTexStorage3D","glTexStorageMem2DEXT","glTexSubImage2D","glTexSubImage3D","glTextureParameteri","glTextureStorage2D","glTextureStorage3D","glTextureSubImage2D","glTextureSubImage3D","glTextureView","glUniform1f","glUniform1fv","glUniform1i","glUniform1iv","glUniform1ui","glUniform2f","glUniform2i","glUniform3f","glUniform3fv","glUniform3i","glUniform4f","glUniform4fv","glUniform4i","glUniformMatrix3fv","glUniformMatrix4fv","glUseProgram","glVertexAttrib4f","glVertexAttribBinding","glVertexAttribDivisor","glVertexAttribFormat","glVertexAttribIFormat","glVertexAttribIPointer","glVertexAttribPointer","glVertexBindingDivisor","glViewport","glWaitSemaphoreEXT"};

    // ------------------------------------------------------------------ the stand-ins

    private static final int TEXTURE_2D = 0x0DE1, TEXTURE_CUBE_MAP = 0x8513, CUBE_POSITIVE_X = 0x8515;
    private static final int DRAW_FB = 0x8CA9, READ_FB = 0x8CA8, DRAW_FB_BINDING = 0x8CA6, READ_FB_BINDING = 0x8CAA;
    private static final int COPY_READ = 0x8F36, COPY_WRITE = 0x8F37;
    private static final int QUERY_RESULT = 0x8866, QUERY_RESULT_AVAILABLE = 0x8867, QUERY_RESULT_NO_WAIT = 0x9194;

    private static int bindingOf(int target) {
        switch (target) {
            case 0x0DE0: return 0x8068;   // 1D
            case 0x0DE1: return 0x8069;   // 2D
            case 0x806F: return 0x806A;   // 3D
            case 0x8C18: return 0x8C1C;   // 1D array
            case 0x8C1A: return 0x8C1D;   // 2D array
            case 0x84F5: return 0x84F6;   // rectangle
            case 0x8513: return 0x8514;   // cube map
            case 0x9009: return 0x900A;   // cube map array
            case 0x8C2A: return 0x8C2C;   // buffer
            case 0x9100: return 0x9104;   // 2D multisample
            case 0x9102: return 0x9105;   // 2D multisample array
            default: return 0;
        }
    }

    private interface IntBody { void run(int target); }

    private static final int[] TARGETS = {TEXTURE_2D, 0x8C1A, 0x806F, TEXTURE_CUBE_MAP, 0x9009, 0x0DE0, 0x8C18, 0x84F5, 0x9100, 0x9102};

    /**
     * The target of a texture made elsewhere (glGenTextures + a first bind): binding a texture to any
     * other target is an error, so the first target that binds cleanly is its own. Remembered.
     */
    private static int targetOf(int texture) {
        Integer known = TEXTURE_TARGET.get(texture);
        if (known != null) return known;
        for (int i = 0; i < 16 && GL11C.glGetError() != 0; i++) { }
        for (int target : TARGETS) {
            int binding = bindingOf(target);
            int prev = GL11C.glGetInteger(binding);
            GL11C.glBindTexture(target, texture);
            int err = GL11C.glGetError();
            GL11C.glBindTexture(target, prev);
            if (err == 0) {
                TEXTURE_TARGET.put(texture, target);
                return target;
            }
        }
        if (unknownTextures++ < 5) log("texture " + texture + ": no target binds it; using 2D");
        return TEXTURE_2D;
    }

    /** Binds the texture to its target for the call, then puts the old binding back. */
    private static void withTexture(int texture, IntBody body) {
        int target = targetOf(texture);
        int binding = bindingOf(target);
        int prev = binding != 0 ? GL11C.glGetInteger(binding) : 0;
        GL11C.glBindTexture(target, texture);
        try {
            body.run(target);
        } finally {
            GL11C.glBindTexture(target, prev);
        }
    }

    private static void withFramebuffer(int which, int bindingQuery, int fb, Runnable body) {
        int prev = GL11C.glGetInteger(bindingQuery);
        GL30C.glBindFramebuffer(which, fb);
        try {
            body.run();
        } finally {
            GL30C.glBindFramebuffer(which, prev);
        }
    }

    private static void withBuffer(int target, int buffer, Runnable body) {
        int prev = GL11C.glGetInteger(target == COPY_READ ? 0x8F36 : 0x8F37);   // COPY_*_BUFFER_BINDING has the same value
        GL15C.glBindBuffer(target, buffer);
        try {
            body.run();
        } finally {
            GL15C.glBindBuffer(target, prev);
        }
    }

    private static void fill(GLCapabilities caps, List<Field> fns, PointerBuffer table, Map<String, Integer> index, List<String> filled) throws Exception {
        FFIType u = LibFFI.ffi_type_uint32, s = LibFFI.ffi_type_sint32, p = LibFFI.ffi_type_pointer, l = LibFFI.ffi_type_sint64;

        put(caps, fns, table, index, filled, "glCreateTextures", sig(u, s, p), a -> {
            int target = i(a, 0), n = i(a, 1);
            long ids = ptr(a, 2);
            GL11C.nglGenTextures(n, ids);
            int binding = bindingOf(target);
            int prev = binding != 0 ? GL11C.glGetInteger(binding) : 0;
            for (int k = 0; k < n; k++) {
                int id = memGetInt(ids + 4L * k);
                TEXTURE_TARGET.put(id, target);
                GL11C.glBindTexture(target, id);   // the first bind makes the object
            }
            GL11C.glBindTexture(target, prev);
        });
        put(caps, fns, table, index, filled, "glTextureParameteri", sig(u, u, s), a ->
                withTexture(i(a, 0), t -> GL11C.glTexParameteri(t, i(a, 1), i(a, 2))));
        put(caps, fns, table, index, filled, "glTextureStorage2D", sig(u, s, u, s, s), a ->
                withTexture(i(a, 0), t -> GL42C.glTexStorage2D(t, i(a, 1), i(a, 2), i(a, 3), i(a, 4))));
        put(caps, fns, table, index, filled, "glTextureStorage3D", sig(u, s, u, s, s, s), a ->
                withTexture(i(a, 0), t -> GL42C.glTexStorage3D(t, i(a, 1), i(a, 2), i(a, 3), i(a, 4), i(a, 5))));
        put(caps, fns, table, index, filled, "glTextureSubImage2D", sig(u, s, s, s, s, s, u, u, p), a ->
                withTexture(i(a, 0), t -> GL11C.nglTexSubImage2D(t, i(a, 1), i(a, 2), i(a, 3), i(a, 4), i(a, 5), i(a, 6), i(a, 7), ptr(a, 8))));
        put(caps, fns, table, index, filled, "glTextureSubImage3D", sig(u, s, s, s, s, s, s, s, u, u, p), a ->
                withTexture(i(a, 0), t -> {
                    if (t == TEXTURE_CUBE_MAP && i(a, 7) == 1) {
                        // a cube map's faces are its layers in the DSA call
                        GL11C.nglTexSubImage2D(CUBE_POSITIVE_X + i(a, 4), i(a, 1), i(a, 2), i(a, 3), i(a, 5), i(a, 6), i(a, 8), i(a, 9), ptr(a, 10));
                    } else {
                        GL12C.nglTexSubImage3D(t, i(a, 1), i(a, 2), i(a, 3), i(a, 4), i(a, 5), i(a, 6), i(a, 7), i(a, 8), i(a, 9), ptr(a, 10));
                    }
                }));
        put(caps, fns, table, index, filled, "glGetTextureImage", sig(u, s, u, u, s, p), a ->
                withTexture(i(a, 0), t -> GL11C.nglGetTexImage(t, i(a, 1), i(a, 2), i(a, 3), ptr(a, 5))));
        put(caps, fns, table, index, filled, "glGetTextureLevelParameteriv", sig(u, s, u, p), a ->
                withTexture(i(a, 0), t -> GL11C.nglGetTexLevelParameteriv(t, i(a, 1), i(a, 2), ptr(a, 3))));

        put(caps, fns, table, index, filled, "glCreateFramebuffers", sig(s, p), a -> {
            int n = i(a, 0);
            long ids = ptr(a, 1);
            GL30C.nglGenFramebuffers(n, ids);
            int prev = GL11C.glGetInteger(DRAW_FB_BINDING);
            for (int k = 0; k < n; k++) GL30C.glBindFramebuffer(DRAW_FB, memGetInt(ids + 4L * k));
            GL30C.glBindFramebuffer(DRAW_FB, prev);
        });
        put(caps, fns, table, index, filled, "glNamedFramebufferTextureLayer", sig(u, u, u, s, s), a ->
                withFramebuffer(DRAW_FB, DRAW_FB_BINDING, i(a, 0), () -> GL30C.glFramebufferTextureLayer(DRAW_FB, i(a, 1), i(a, 2), i(a, 3), i(a, 4))));
        put(caps, fns, table, index, filled, "glNamedFramebufferDrawBuffer", sig(u, u), a ->
                withFramebuffer(DRAW_FB, DRAW_FB_BINDING, i(a, 0), () -> GL11C.glDrawBuffer(i(a, 1))));
        put(caps, fns, table, index, filled, "glNamedFramebufferReadBuffer", sig(u, u), a ->
                withFramebuffer(READ_FB, READ_FB_BINDING, i(a, 0), () -> GL11C.glReadBuffer(i(a, 1))));

        put(caps, fns, table, index, filled, "glCreateBuffers", sig(s, p), a -> {
            int n = i(a, 0);
            long ids = ptr(a, 1);
            GL15C.nglGenBuffers(n, ids);
            int prev = GL11C.glGetInteger(COPY_WRITE);
            for (int k = 0; k < n; k++) GL15C.glBindBuffer(COPY_WRITE, memGetInt(ids + 4L * k));
            GL15C.glBindBuffer(COPY_WRITE, prev);
        });
        put(caps, fns, table, index, filled, "glNamedBufferStorage", sig(u, l, p, u), a ->
                withBuffer(COPY_WRITE, i(a, 0), () -> GL44C.nglBufferStorage(COPY_WRITE, j(a, 1), ptr(a, 2), i(a, 3))));
        put(caps, fns, table, index, filled, "glCopyNamedBufferSubData", sig(u, u, l, l, l), a ->
                withBuffer(COPY_READ, i(a, 0), () -> withBuffer(COPY_WRITE, i(a, 1), () ->
                        GL31C.glCopyBufferSubData(COPY_READ, COPY_WRITE, j(a, 2), j(a, 3), j(a, 4)))));
        put(caps, fns, table, index, filled, "glGetNamedBufferSubData", sig(u, l, l, p), a ->
                withBuffer(COPY_READ, i(a, 0), () -> GL15C.nglGetBufferSubData(COPY_READ, j(a, 1), j(a, 2), ptr(a, 3))));

        // timestamps: the CPU time the frame's commands were given (libzfa lacks the GPU counter)
        put(caps, fns, table, index, filled, "glQueryCounter", sig(u, u), a -> STAMP.put(i(a, 0), System.nanoTime()));
        CallbackBody result64 = a -> {
            int id = i(a, 0), pname = i(a, 1);
            long out = ptr(a, 2);
            Long t = STAMP.get(id);
            if (t != null) {
                memPutLong(out, pname == QUERY_RESULT_AVAILABLE ? 1 : t);
            } else {
                // an elapsed-time query: the 32-bit result is enough below four seconds
                long tmp = org.lwjgl.system.MemoryUtil.nmemAlloc(8);
                try {
                    GL15C.nglGetQueryObjectuiv(id, pname == QUERY_RESULT_NO_WAIT ? QUERY_RESULT : pname, tmp);
                    memPutLong(out, memGetInt(tmp) & 0xffffffffL);
                } finally {
                    org.lwjgl.system.MemoryUtil.nmemFree(tmp);
                }
            }
        };
        put(caps, fns, table, index, filled, "glGetQueryObjectui64v", sig(u, u, p), result64);
        put(caps, fns, table, index, filled, "glGetQueryObjecti64v", sig(u, u, p), result64);
        // device UUIDs for Vulkan sharing (upscalers, off here): answer "unknown"
        put(caps, fns, table, index, filled, "glGetUnsignedBytei_vEXT", sig(u, u, p), a -> memSet(ptr(a, 2), 0, 16));
    }

    // ------------------------------------------------------------------ plumbing

    private interface CallbackBody { void run(long args); }

    private static int i(long args, int n) { return memGetInt(memGetAddress(args + (long) n * POINTER_SIZE)); }
    private static long j(long args, int n) { return memGetLong(memGetAddress(args + (long) n * POINTER_SIZE)); }
    private static long ptr(long args, int n) { return memGetAddress(memGetAddress(args + (long) n * POINTER_SIZE)); }

    private static Callback.Descriptor sig(FFIType... args) {
        return new Callback.Descriptor(MethodHandles.lookup(), APIUtil.apiCreateCIF(LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_void, args));
    }

    private static void put(GLCapabilities caps, List<Field> fns, PointerBuffer table, Map<String, Integer> index, List<String> filled,
                            String name, Callback.Descriptor d, CallbackBody body) throws Exception {
        Integer at = index.get(name);
        if (at == null || fns.get(at).getLong(caps) != 0) return;   // unknown to LWJGL, or the driver has it
        Fn fn = new Fn(d, name, body);
        KEEP.add(fn);
        table.put(at, fn.address());
        filled.add(name.substring(2));
    }

    private static final class Fn extends Callback implements CallbackI {
        private final Callback.Descriptor descriptor;
        private final String name;
        private final CallbackBody body;

        Fn(Callback.Descriptor d, String name, CallbackBody body) {
            super(d);
            this.descriptor = d;
            this.name = name;
            this.body = body;
        }

        @Override
        public Callback.Descriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public void callback(long ret, long args) {
            try {
                body.run(args);
            } catch (Throwable t) {
                if (errors++ < 10) log(name + " failed: " + t);
            }
        }
    }
}
