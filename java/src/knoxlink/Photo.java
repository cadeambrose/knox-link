package knoxlink;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;

/**
 * The scrapbook's photos: a picture of the game as it's on the screen, without the game's own
 * windows and bars, taken when a screen asks (with a camera, in immersion: KnoxLink_Photo.lua),
 * kept as a JPEG in the save (knoxlink/photos/<id>.jpg) and shown on that day's journal page.
 *
 * Main thread: take() books the next frame; frame(), called before the game draws its UI
 * (OnPreUIDraw, when the world is on the screen and the UI not yet), queues the read on the
 * render thread; the JPEG is made and written on a thread of its own.
 */
final class Photo {
    private static final int MAX_W = 1600;     // wider screens are halved
    private static final int QUALITY = 88;

    private static volatile String pending;
    private static volatile boolean broken;
    private static final Drawer[] drawers = {new Drawer(), new Drawer(), new Drawer()};
    private static final Map<String, byte[]> cache = new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) {
            return size() > 6;
        }
    };

    private Photo() {}

    static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,80}");
    }

    /** Books a photo for the next frame; null, or why it can't be taken. */
    static String take(String id) {
        if (broken) return "Photos don't work with this game's graphics";
        if (!validId(id)) return "Bad photo id";
        if (pending != null) return "Still taking the last one";
        pending = id;
        return null;
    }

    /** Main thread, before the game's UI is drawn: queues the read of the screen. */
    static void frame() {
        String id = pending;
        if (id == null || broken) return;
        pending = null;
        try {
            Drawer d = drawers[SpriteRenderer.instance.getMainStateIndex()];
            d.id = id;
            SpriteRenderer.instance.drawGeneric(d);
        } catch (Throwable t) {
            fail("photo (queue)", t);
        }
    }

    private static void fail(String what, Throwable t) {
        if (!broken) {
            broken = true;
            Log.error(what + ": photos are off for this session", t);
        }
    }

    private static final class Drawer extends TextureDraw.GenericDrawer {
        volatile String id;

        @Override
        public void render() {
            String photo = id;
            if (photo == null || broken) return;
            id = null;
            int prev = 0;
            boolean bound = false;
            try {
                int w = Core.getInstance().getScreenWidth(), h = Core.getInstance().getScreenHeight();
                if (w <= 0 || h <= 0) return;
                prev = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);   // the screen itself
                bound = true;
                ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
                GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prev);
                bound = false;
                byte[] raw = new byte[w * h * 4];
                buf.get(raw);
                Thread t = new Thread(() -> save(photo, raw, w, h), "knoxlink-photo");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                if (bound) {
                    try { GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prev); } catch (Throwable ignored) {}
                }
                fail("photo (read)", t);
            }
        }
    }

    /** OpenGL's bottom-up RGBA to top-down RGB (halved when wide), a JPEG, and into the save. */
    private static void save(String id, byte[] rgba, int w, int h) {
        try {
            int f = w > MAX_W ? 2 : 1;
            int ow = w / f, oh = h / f;
            byte[] rgb = new byte[ow * oh * 3];
            for (int y = 0; y < oh; y++) {
                for (int x = 0; x < ow; x++) {
                    int r = 0, g = 0, b = 0;
                    for (int dy = 0; dy < f; dy++) {
                        int sy = h - 1 - (y * f + dy);
                        for (int dx = 0; dx < f; dx++) {
                            int o = (sy * w + x * f + dx) * 4;
                            r += rgba[o] & 0xff;
                            g += rgba[o + 1] & 0xff;
                            b += rgba[o + 2] & 0xff;
                        }
                    }
                    int n = f * f, o = (y * ow + x) * 3;
                    rgb[o] = (byte) (r / n);
                    rgb[o + 1] = (byte) (g / n);
                    rgb[o + 2] = (byte) (b / n);
                }
            }
            byte[] jpg = Jpeg.encode(rgb, ow, oh, QUALITY);
            File file = fileOf(id);
            if (file == null) return;
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(jpg);
            }
            synchronized (cache) {
                cache.put(id, jpg);
            }
            Log.info("photo " + id + ": " + ow + "x" + oh + ", " + jpg.length / 1024 + " KB");
        } catch (Throwable t) {
            Log.error("photo " + id, t);
        }
    }

    static File fileOf(String id) {
        if (!validId(id)) return null;
        return Memory.saveFile("photos" + File.separator + id + ".jpg");
    }

    /** The dev server's photos (java/dev: no game to take them); null in the game. */
    static volatile java.util.function.Function<String, byte[]> dev;

    /** A photo's JPEG, or null. */
    static byte[] get(String id) {
        if (!validId(id)) return null;
        if (dev != null) return dev.apply(id);
        synchronized (cache) {
            byte[] b = cache.get(id);
            if (b != null) return b;
        }
        try {
            File f = fileOf(id);
            if (f == null || !f.isFile()) return null;
            byte[] b = Files.readAllBytes(f.toPath());
            synchronized (cache) {
                cache.put(id, b);
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean delete(String id) {
        if (!validId(id)) return false;
        synchronized (cache) {
            cache.remove(id);
        }
        File f = fileOf(id);
        return f != null && f.delete();
    }
}
