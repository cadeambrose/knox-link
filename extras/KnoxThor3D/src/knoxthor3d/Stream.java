package knoxthor3d;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import me.zed_0xff.zombie_buddy.Patch;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL44;

import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memCopy;

/**
 * Keeps Viewpoint's per-frame data stream from cutting the GPU's work into hundreds of passes.
 *
 * Viewpoint streams small per-draw data (indirect draw commands, instance data, bone palettes) into
 * one ring buffer with glBufferSubData, right before each draw. Zink turns every such update of a
 * buffer the GPU may still be using into a copy on the GPU, and a copy can't happen inside a render
 * pass: the pass ends, the copy runs, a new pass starts. On the Thor that made ~240 render passes a
 * frame, ~230 of them in the translucent pass with one draw each. A pass that small is drawn
 * straight to memory instead of on-chip (tiled), so its blending is slow: about 40 % of the GPU's
 * time went there (Turnip's GPU trace).
 *
 * Here the ring is created persistently mapped and each update is a plain memory copy into it: no GL
 * call, nothing for Zink to copy, and the passes stay whole. Viewpoint's own ring bookkeeping (where
 * each piece goes, when it wraps) is kept. The ring holds at least four frames of data; it's split
 * into four parts, each closed with a fence when writing moves on, and a part is only written again
 * once its fence from the lap before has passed, so nothing the GPU may still read is overwritten.
 * KNOXTHOR3D_MAPPED_STREAM=0 in Zomdroid's environment variables, or Zomboid/Lua/KnoxThor3D_stream.txt
 * holding 0, goes back to Viewpoint's own uploads (1 turns this on again, without a restart).
 */
public final class Stream {
    private static final int COPY_WRITE = 0x8F37;
    private static final int MAP_WRITE = 0x2, MAP_PERSISTENT = 0x40, MAP_COHERENT = 0x80, DYNAMIC_STORAGE = 0x100;
    private static final int SYNC_GPU_COMMANDS_COMPLETE = 0x9117, SYNC_FLUSH_COMMANDS_BIT = 0x1;

    private static boolean looked, ready, on = true, logged;
    private static MethodHandle reserve;
    private static Field bufferF, sizeF;
    private static int mapped;          // the GL buffer this class created and mapped
    private static long ptr;            // where it's mapped
    private static long ringSize;
    private static final int PARTS = 4;
    private static final long[] partFence = new long[PARTS];
    private static int part = -1;       // the part being written
    private static long result;
    private static boolean took;
    private static File control;
    private static long controlChecked, controlStamp;

    private Stream() {}

    private static boolean ready() {
        if (looked) return ready;
        looked = true;
        if (!Main.onZomdroid()) return false;
        try {
            String e = System.getenv("KNOXTHOR3D_MAPPED_STREAM");
            if (e != null && e.trim().equals("0")) on = false;
            Class<?> c = Class.forName("viewpoint.render.FrameStream");
            Method r = c.getDeclaredMethod("reserve", long.class, int.class);
            r.setAccessible(true);
            reserve = MethodHandles.lookup().unreflect(r).asType(MethodType.methodType(long.class, long.class, int.class));
            bufferF = c.getDeclaredField("buffer");
            bufferF.setAccessible(true);
            sizeF = c.getDeclaredField("size");
            sizeF.setAccessible(true);
            ready = true;
        } catch (Throwable t) {
            Main.log("mapped stream: not available (" + t + "), Viewpoint uploads its own way");
        }
        return ready;
    }

    /** Zomboid/Lua/KnoxThor3D_stream.txt: 0 = Viewpoint's own uploads, 1 = mapped; a change makes the ring start over. */
    private static void readControl() {
        long now = System.nanoTime();
        if (now - controlChecked < 1_000_000_000L) return;
        controlChecked = now;
        try {
            if (control == null) control = new File(System.getProperty("user.home"), "Zomboid/Lua/KnoxThor3D_stream.txt");
            long stamp = control.lastModified();
            if (stamp == controlStamp) return;
            controlStamp = stamp;
            if (stamp == 0L) return;
            boolean want = !new String(java.nio.file.Files.readAllBytes(control.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim().equals("0");
            if (want != on) {
                on = want;
                sizeF.setLong(null, 0L);   // the next put grows a new ring, mapped or not
                Main.log("mapped stream: " + (on ? "on" : "off, Viewpoint uploads its own way") + " (KnoxThor3D_stream.txt)");
            }
        } catch (Throwable ignored) {
        }
    }

    /** Stands in for FrameStream.grow: a persistently mapped ring. False: Viewpoint's own grow runs. */
    public static boolean grow(long size) {
        if (!ready() || !on) return false;
        try {
            int old = bufferF.getInt(null);
            int buf = GL15.glGenBuffers();
            GL15.glBindBuffer(COPY_WRITE, buf);
            GL44.glBufferStorage(COPY_WRITE, size, MAP_WRITE | MAP_PERSISTENT | MAP_COHERENT | DYNAMIC_STORAGE);
            ByteBuffer m = GL30.glMapBufferRange(COPY_WRITE, 0L, size, MAP_WRITE | MAP_PERSISTENT | MAP_COHERENT);
            GL15.glBindBuffer(COPY_WRITE, 0);
            if (m == null) {
                GL15.glDeleteBuffers(buf);
                on = false;
                Main.log("mapped stream: the driver wouldn't map the ring, Viewpoint uploads its own way");
                return false;
            }
            if (old != 0) GL15.glDeleteBuffers(old);   // GL keeps it until the GPU is done with it
            for (int i = 0; i < PARTS; i++) {
                if (partFence[i] != 0L) GL32C.glDeleteSync(partFence[i]);
                partFence[i] = 0L;
            }
            part = -1;
            ptr = memAddress(m);
            mapped = buf;
            ringSize = size;
            bufferF.setInt(null, buf);
            sizeF.setLong(null, size);
            // FrameStream.head starts again at 0 like in its own grow
            Field head = bufferF.getDeclaringClass().getDeclaredField("head");
            head.setAccessible(true);
            head.setLong(null, 0L);
            if (!logged) {
                logged = true;
                Main.log("mapped stream: Viewpoint's per-draw data goes into a mapped " + (size >> 20) + " MB ring (no GL uploads mid-pass)");
            }
            return true;
        } catch (Throwable t) {
            on = false;
            Main.log("mapped stream: failed (" + t + "), Viewpoint uploads its own way");
            return false;
        }
    }

    /** Stands in for FrameStream.put. False: Viewpoint's own put runs (glBufferSubData). */
    public static boolean put(long src, long bytes, int align) {
        took = false;
        if (!ready()) return false;
        readControl();
        if (!on) return false;
        try {
            long off = (long) reserve.invokeExact(bytes, align);   // may grow the ring (through grow above)
            if (ptr == 0L || bufferF.getInt(null) != mapped) {
                // a ring from before (not mapped): write it the way Viewpoint does, the next grow maps
                GL15.glBindBuffer(COPY_WRITE, bufferF.getInt(null));
                GL15.nglBufferSubData(COPY_WRITE, off, bytes, src);
                GL15.glBindBuffer(COPY_WRITE, 0);
                sizeF.setLong(null, 0L);
            } else {
                int p = (int) Math.min(PARTS - 1, (off + Math.max(0L, bytes - 1)) * PARTS / ringSize);
                if (p != part) {
                    // close the part written so far, then make sure the GPU is done with this part's last lap
                    if (part >= 0) partFence[part] = GL32C.glFenceSync(SYNC_GPU_COMMANDS_COMPLETE, 0);
                    if (partFence[p] != 0L) {
                        GL32C.glClientWaitSync(partFence[p], SYNC_FLUSH_COMMANDS_BIT, 100_000_000L);
                        GL32C.glDeleteSync(partFence[p]);
                        partFence[p] = 0L;
                    }
                    part = p;
                }
                memCopy(src, ptr + off, bytes);
            }
            result = off;
            took = true;
            return true;
        } catch (Throwable t) {
            on = false;
            Main.log("mapped stream: failed (" + t + "), Viewpoint uploads its own way");
            return false;
        }
    }

    public static long result(long original) {
        if (!took) return original;
        took = false;
        return result;
    }

    // ------------------------------------------------------------------ the patches

    @Patch(className = "viewpoint.render.FrameStream", methodName = "grow")
    public static final class Grow {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) long size) {
            return Stream.grow(size);   // true = done here
        }
    }

    @Patch(className = "viewpoint.render.FrameStream", methodName = "put", strictMatch = true)
    public static final class PutFloats {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) FloatBuffer data, @Patch.Argument(1) int align) {
            return Stream.put(memAddress(data), (long) data.remaining() * 4L, align);
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) long offset) {
            offset = Stream.result(offset);
        }
    }

    @Patch(className = "viewpoint.render.FrameStream", methodName = "put", strictMatch = true)
    public static final class PutInts {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) IntBuffer data, @Patch.Argument(1) int align) {
            return Stream.put(memAddress(data), (long) data.remaining() * 4L, align);
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) long offset) {
            offset = Stream.result(offset);
        }
    }

    @Patch(className = "viewpoint.render.FrameStream", methodName = "put", strictMatch = true)
    public static final class PutBytes {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) ByteBuffer data, @Patch.Argument(1) int align) {
            return Stream.put(memAddress(data), data.remaining(), align);
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) long offset) {
            offset = Stream.result(offset);
        }
    }
}
