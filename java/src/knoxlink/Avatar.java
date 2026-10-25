package knoxlink;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.lwjgl.opengl.GL11;
import zombie.characters.IsoPlayer;
import zombie.core.SpriteRenderer;
import zombie.core.Styles.UIFBOStyle;
import zombie.core.skinnedmodel.advancedanimation.AnimatedModel;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureFBO;
import zombie.inventory.InventoryItem;
import zombie.iso.Vector2;

/**
 * The survivor's own 3D model, live, like the player model in an inventory screen. It is the
 * same thing the game's character screen shows (an AnimatedModel with the player's body,
 * clothes and hair, idling), drawn the same way, but into an off-screen buffer whose pixels
 * become a PNG the screens fetch from /api/avatar.png.
 *
 * Only runs while a screen follows the "avatar" channel, a few frames a second. Anything going
 * wrong turns it off for the session rather than risk the game's drawing.
 */
final class Avatar {
    static final int W = 280, H = 560;
    private static final long FRAME_MS = 140;

    private static AnimatedModel model;
    private static final Drawer[] drawers = {new Drawer(), new Drawer(), new Drawer()};
    private static TextureFBO fbo;
    private static ByteBuffer pixels;
    private static volatile byte[] png;
    private static volatile long pngAt;
    private static volatile boolean broken;
    private static long nextAt, nextExtAt;
    private static long lastPrimary = -2, lastSecondary = -2;
    private static int lastWorn = -1;
    private static boolean refresh = true;
    private static volatile float angle = 90f;   // degrees; 90 faces the viewer
    private static final Object encodeLock = new Object();

    private Avatar() {}

    /** Latest frame (PNG), or null. */
    static byte[] png() {
        return png;
    }

    static boolean available() {
        return !broken;
    }

    /** Turn the model (degrees, 90 = facing you). */
    static void setAngle(float degrees) {
        angle = ((degrees % 360) + 360) % 360;
    }

    /** Clothes changed: rebuild the model's outfit on the next frame. */
    static void refresh() {
        refresh = true;
    }

    /** Main thread, while the UI is being drawn (OnPostUIDraw): update the model and queue a draw. */
    static void frame() {
        if (broken || !KnoxServer.get().anyoneWants("avatar")) return;
        long now = System.currentTimeMillis();
        if (now < nextAt) return;
        nextAt = now + FRAME_MS;
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) return;
        try {
            if (model == null) {
                model = new AnimatedModel();
                model.setState("idle");
                model.setIsometric(false);
                model.setAnimate(true);
            }
            InventoryItem p = player.getPrimaryHandItem(), s = player.getSecondaryHandItem();
            long pid = p == null ? -1 : p.getID(), sid = s == null ? -1 : s.getID();
            int worn = player.getWornItems().size();
            if (refresh || pid != lastPrimary || sid != lastSecondary || worn != lastWorn) {
                refresh = false;
                lastPrimary = pid;
                lastSecondary = sid;
                lastWorn = worn;
                model.setCharacter(player);
                model.setState("idle");
            }
            double rad = Math.toRadians(angle);
            model.setAngle(new Vector2((float) Math.cos(rad), (float) Math.sin(rad)));
            // now and then one of the idle fidgets the character screen plays
            if (nextExtAt == 0) nextExtAt = now + 6000;
            if (now > nextExtAt) {
                nextExtAt = now + 5000 + (long) (Math.random() * 5000);
                model.getActionContext().reportEvent("EventDoExt");
                model.setVariable("Ext", (float) (1 + (int) (Math.random() * 6)));
            }
            model.update();
            Drawer d = drawers[SpriteRenderer.instance.getMainStateIndex()];
            d.init();
            SpriteRenderer.instance.drawGeneric(d);
        } catch (Throwable t) {
            fail("avatar (prepare)", t);
        }
    }

    private static void fail(String what, Throwable t) {
        if (!broken) {
            broken = true;
            Log.error(what + ": the live character picture is off for this session", t);
        }
    }

    /** UI3DModel's drawer, aimed at our own buffer instead of the screen. */
    private static final class Drawer extends TextureDraw.GenericDrawer {
        private float animAngle;
        private boolean rendered;

        void init() {
            animAngle = model.getAnimationPlayer().getRenderedAngle();
            rendered = false;
            model.setOffset(0f, -0.5f, 0f);
            model.renderMain();
        }

        @Override
        public void render() {
            if (broken) return;
            int[] viewport = new int[4];
            boolean bound = false;
            try {
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
                if (fbo == null) {
                    fbo = new TextureFBO(new Texture(W, H, 16), true);
                    pixels = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder());
                }
                fbo.startDrawing(true, true);
                bound = true;
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(true);
                GL11.glClearDepth(1.0);
                model.DoRender(0, 0, W, H, 24f, animAngle);   // a little closer than the character screen (28)
                pixels.clear();
                GL11.glReadPixels(0, 0, W, H, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                fbo.endDrawing();
                bound = false;
                GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
                UIFBOStyle.instance.setupState();
                rendered = true;
                byte[] raw = new byte[W * H * 4];
                pixels.get(raw);
                encodeLater(raw);
            } catch (Throwable t) {
                if (bound) {
                    try { fbo.endDrawing(); } catch (Throwable ignored) {}
                }
                try { GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]); } catch (Throwable ignored) {}
                fail("avatar (draw)", t);
            }
        }

        @Override
        public void postRender() {
            try {
                model.postRender(rendered);
            } catch (Throwable t) {
                fail("avatar (finish)", t);
            }
        }
    }

    // ------------------------------------------------------------------ PNG, off the render thread

    private static byte[] pendingRaw;
    private static Thread encoder;

    private static void encodeLater(byte[] raw) {
        synchronized (encodeLock) {
            pendingRaw = raw;
            if (encoder == null) {
                encoder = new Thread(Avatar::encodeLoop, "knoxlink-avatar");
                encoder.setDaemon(true);
                encoder.start();
            }
            encodeLock.notifyAll();
        }
    }

    private static void encodeLoop() {
        while (true) {
            byte[] raw;
            synchronized (encodeLock) {
                while (pendingRaw == null) {
                    try {
                        encodeLock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                raw = pendingRaw;
                pendingRaw = null;
            }
            try {
                png = encode(raw, W, H);
                pngAt = System.currentTimeMillis();
            } catch (Throwable t) {
                Log.error("avatar picture", t);
            }
        }
    }

    /** RGBA rows bottom-up (OpenGL) to a PNG, top-down. */
    static byte[] encode(byte[] rgba, int w, int h) {
        byte[] filtered = new byte[(w * 4 + 1) * h];
        int o = 0;
        for (int y = h - 1; y >= 0; y--) {
            filtered[o++] = 0;
            System.arraycopy(rgba, y * w * 4, filtered, o, w * 4);
            o += w * 4;
        }
        Deflater deflater = new Deflater(2);
        deflater.setInput(filtered);
        deflater.finish();
        ByteArrayOutputStream idat = new ByteArrayOutputStream(filtered.length / 6);
        byte[] buf = new byte[16384];
        while (!deflater.finished()) idat.write(buf, 0, deflater.deflate(buf));
        deflater.end();
        ByteArrayOutputStream out = new ByteArrayOutputStream(idat.size() + 64);
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}, 0, 8);
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(w).putInt(h).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IDAT", idat.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ByteBuffer len = ByteBuffer.allocate(4).putInt(data.length);
        out.write(len.array(), 0, 4);
        out.write(t, 0, 4);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        ByteBuffer c = ByteBuffer.allocate(4).putInt((int) crc.getValue());
        out.write(c.array(), 0, 4);
    }
}
