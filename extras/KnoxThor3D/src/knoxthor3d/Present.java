package knoxthor3d;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.system.SharedLibrary;

import static org.lwjgl.system.MemoryUtil.memGetAddress;
import static org.lwjgl.system.MemoryUtil.memGetByte;
import static org.lwjgl.system.MemoryUtil.memGetLong;
import static org.lwjgl.system.MemoryUtil.memPutLong;

/**
 * Lets the CPU build the next frame while the GPU still draws this one (ZINK_ZFA).
 *
 * Zomdroid's GLFW shows a ZINK_ZFA frame with glFinish: the render thread waits until the GPU has
 * drawn all of it, and only then starts the next frame. The CPU and the GPU take turns, so the GPU
 * sits idle while the CPU builds each frame (about a quarter of the time in Viewpoint's 3D view on
 * the Thor). glFlush shows the frame the same way (both flush ZFA's front buffer to the window),
 * just without the wait. So the swap here marks the end of the frame with a fence, shows it with
 * glFlush and waits only for the frame before it: the GPU always has the next frame queued, and
 * the game never runs more than a frame ahead of the screen.
 *
 * While Zomdroid's window changes or is gone (the app goes to the background, the screen turns
 * off) Zomdroid's own swap runs: it waits for the GPU before it lets Android take the window away.
 * KNOXTHOR3D_FRAMES_AHEAD=0 in Zomdroid's environment variables turns this off; 2 allows two frames.
 * Zomboid/Lua/KnoxThor3D_present.txt holding 0, 1 or 2 changes it while the game runs.
 */
public final class Present {
    private static final int GL_SYNC_GPU_COMMANDS_COMPLETE = 0x9117;
    private static final int GL_SYNC_FLUSH_COMMANDS_BIT = 0x1;
    private static final long WAIT_NS = 250_000_000L;
    /** ZomdroidSurface { ANativeWindow* native_window; int width, height; bool is_dirty; ... } */
    private static final int IS_DIRTY = 16;

    private static boolean tried, on;
    private static int ahead = 1;
    private static long surface, presented;
    private static final ArrayDeque<Long> inFlight = new ArrayDeque<>();
    private static long frames, flushed, waited, handedBack;

    private static File control;
    private static long controlChecked, controlStamp;

    private Present() {}

    private static void init() {
        tried = true;
        try {
            String a = System.getenv("KNOXTHOR3D_FRAMES_AHEAD");
            if (a != null && !a.trim().isEmpty()) ahead = Math.max(0, Math.min(2, Integer.parseInt(a.trim())));
            // Zomdroid's own state, exported by libzomdroid.so, which Zomdroid's libglfw links to
            SharedLibrary glfw = GLFW.getLibrary();
            surface = glfw.getFunctionAddress("g_zomdroid_surface");
            presented = glfw.getFunctionAddress("g_zomdroid_presented_frames");
            if (surface == 0L) {
                Main.log("present: Zomdroid's window state not found, each frame waits for the GPU as before");
                return;
            }
            on = true;
            Main.log(ahead == 0 ? "present: off (KNOXTHOR3D_FRAMES_AHEAD=0), each frame waits for the GPU as before"
                    : "present: frames go to the screen without waiting for the GPU to finish them (at most "
                    + ahead + (ahead == 1 ? " frame" : " frames") + " ahead)");
        } catch (Throwable t) {
            Main.log("present: not available (" + t + "), each frame waits for the GPU as before");
        }
    }

    /** Picks up Zomboid/Lua/KnoxThor3D_present.txt (0, 1 or 2) when it changes; looked at every 2 s. */
    private static void readControl() {
        long now = System.nanoTime();
        if (now - controlChecked < 2_000_000_000L) return;
        controlChecked = now;
        Scale.watch();
        Hash.watch();
        try {
            if (control == null) control = new File(System.getProperty("user.home"), "Zomboid/Lua/KnoxThor3D_present.txt");
            long stamp = control.lastModified();   // 0 when there's no file
            if (stamp == controlStamp) return;
            controlStamp = stamp;
            if (stamp == 0L) return;
            int a = Math.max(0, Math.min(2, Integer.parseInt(new String(Files.readAllBytes(control.toPath()), StandardCharsets.UTF_8).trim())));
            if (a != ahead) {
                ahead = a;
                Main.log("present: " + (a == 0 ? "off, each frame waits for the GPU as before" : a + (a == 1 ? " frame" : " frames") + " ahead")
                        + " (KnoxThor3D_present.txt)");
            }
        } catch (Throwable ignored) {
        }
    }

    /** Fences of frames that are already shown: Zomdroid's swap finishes the GPU anyway. */
    private static void drop() {
        while (!inFlight.isEmpty()) GL32C.glDeleteSync(inFlight.pollFirst());
    }

    /** Stands in for glfwSwapBuffers. False: let Zomdroid's own swap run. */
    public static boolean swap() {
        if (!tried) init();
        if (!on) return false;
        readControl();
        try {
            if (ahead == 0) {
                drop();
                return false;
            }
            if (memGetAddress(surface) == 0L || memGetByte(surface + IS_DIRTY) != 0) {
                drop();
                handedBack++;
                return false;
            }
            long fence = GL32C.glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            long t0 = System.nanoTime();
            GL11C.glFlush();
            long t1 = System.nanoTime();
            flushed += t1 - t0;
            if (fence != 0L) inFlight.addLast(fence);
            while (inFlight.size() > ahead) {
                long f = inFlight.pollFirst();
                GL32C.glClientWaitSync(f, GL_SYNC_FLUSH_COMMANDS_BIT, WAIT_NS);
                GL32C.glDeleteSync(f);
            }
            waited += System.nanoTime() - t1;
            // Zomdroid's on-screen frame counter (its own swap counts there too)
            if (presented != 0L) memPutLong(presented, memGetLong(presented) + 1);
            if (++frames % 600 == 0) {
                Main.log(String.format(java.util.Locale.ROOT,
                        "present: last 600 frames: %.1f ms a frame in glFlush, %.1f ms waiting for the frame before (near 0: the CPU is the limit), %d handed to Zomdroid's swap",
                        flushed / 600 / 1e6, waited / 600 / 1e6, handedBack));
                flushed = waited = handedBack = 0;
            }
            return true;
        } catch (Throwable t) {
            on = false;
            Main.log("present: failed, back to Zomdroid's own swap: " + t);
            return false;
        }
    }
}
