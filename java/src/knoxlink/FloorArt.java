package knoxlink;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.Inflater;

/**
 * The game's own floors for the floor plan: wood, tiles, carpet, rugs, grass, dirt, asphalt...
 * The game keeps them as isometric diamonds in its floor texture packs (Tiles2x.floor.pack); this
 * turns one back into the square it is, seen from above, as a PNG (/api/art/floor?n=<sprite>).
 *
 * Read straight from the player's install, like the item icons (Art.java); nothing is shipped.
 */
final class FloorArt {
    private static final String[] PACKS = {"Tiles2x.floor.pack", "Tiles1x.floor.pack"};
    static final int SIZE = 96;   // pixels a side

    private static final class Page {
        final File file;
        final long offset;
        final int length;

        Page(File file, long offset, int length) {
            this.file = file;
            this.offset = offset;
            this.length = length;
        }
    }

    private static final java.util.List<Page> pages = new java.util.ArrayList<>();
    /** sprite name -> {page, x, y, w, h, offsetX, offsetY, fullW, fullH} */
    private static final Map<String, int[]> tiles = new HashMap<>();
    private static boolean indexed;
    /** decoded pages: id -> {width, height, argb...} (the last few) */
    private static final Map<Integer, int[]> decoded = new LinkedHashMap<Integer, int[]>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, int[]> e) {
            return size() > 3;
        }
    };
    private static final Map<String, byte[]> made = new LinkedHashMap<String, byte[]>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) {
            return size() > 400;
        }
    };

    private FloorArt() {}

    private static synchronized void index() {
        if (indexed) return;
        indexed = true;
        for (String name : PACKS) {
            File f = Paths.media("texturepacks/" + name);
            if (!f.isFile()) continue;
            try (RandomAccessFile raf = new RandomAccessFile(f, "r"); FileChannel ch = raf.getChannel()) {
                ByteBuffer b = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN);
                byte[] magic = new byte[4];
                b.get(magic);
                if (!"PZPK".equals(new String(magic, StandardCharsets.US_ASCII))) continue;
                b.getInt();   // version
                int count = b.getInt();
                for (int p = 0; p < count; p++) {
                    str(b);
                    int entries = b.getInt();
                    b.getInt();
                    int pageId = pages.size();
                    for (int e = 0; e < entries; e++) {
                        String tex = str(b);
                        int[] r = new int[9];
                        r[0] = pageId;
                        for (int k = 1; k < 9; k++) r[k] = b.getInt();
                        tiles.putIfAbsent(tex, r);   // the 2x pack first: the sharper one wins
                    }
                    int len = b.getInt();
                    pages.add(new Page(f, b.position(), len));
                    b.position(b.position() + len);
                }
            } catch (Exception e) {
                Log.error("could not read " + f, e);
            }
        }
        Log.info("floor art: " + tiles.size() + " floors on " + pages.size() + " pages");
    }

    private static String str(ByteBuffer b) {
        int n = b.getInt();
        byte[] s = new byte[n];
        b.get(s);
        return new String(s, StandardCharsets.UTF_8);
    }

    static boolean has(String sprite) {
        index();
        return sprite != null && tiles.containsKey(sprite);
    }

    /** The floor as a square PNG seen from above, or null when the packs don't have it. */
    static synchronized byte[] png(String sprite) {
        if (sprite == null || !sprite.matches("[A-Za-z0-9_\\-]{1,96}")) return null;
        byte[] hit = made.get(sprite);
        if (hit != null) return hit;
        index();
        int[] r = tiles.get(sprite);
        if (r == null) return null;
        try {
            int[] page = decoded.get(r[0]);
            if (page == null) {
                Page p = pages.get(r[0]);
                byte[] png = new byte[p.length];
                try (RandomAccessFile raf = new RandomAccessFile(p.file, "r")) {
                    raf.seek(p.offset);
                    raf.readFully(png);
                }
                page = decode(png);
                decoded.put(r[0], page);
            }
            byte[] out = Avatar.encode(square(page, r), SIZE, SIZE);
            made.put(sprite, out);
            return out;
        } catch (Exception e) {
            Log.error("floor art " + sprite, e);
            return null;
        }
    }

    /**
     * The diamond back to its square. A floor sits at the bottom of its sprite's frame (fullW wide,
     * fullW / 2 high); world (u, v) in the square (u east, v south) is at frame
     * x = (u - v) * fullW / 2 + fullW / 2, y = fullH - fullW / 2 + (u + v) * fullW / 4.
     * RGBA rows bottom-up (what Avatar.encode takes).
     */
    private static byte[] square(int[] page, int[] r) {
        int pw = page[0], ph = page[1];
        int x0 = r[1], y0 = r[2], w = r[3], h = r[4], ox = r[5], oy = r[6], fw = r[7], fh = r[8];
        float dh = fw / 2f, top = fh - dh;
        byte[] out = new byte[SIZE * SIZE * 4];
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                // a little in from the edges: the diamond's own edge pixels are half transparent or darker
                float u = 0.03f + 0.94f * (i + 0.5f) / SIZE, v = 0.03f + 0.94f * (j + 0.5f) / SIZE;
                float fx = (u - v) * fw / 2f + fw / 2f, fy = top + (u + v) * dh / 2f;
                float px = x0 + fx - ox - 0.5f, py = y0 + fy - oy - 0.5f;
                // stay inside the packed picture
                px = Math.max(x0, Math.min(x0 + w - 1.001f, px));
                py = Math.max(y0, Math.min(y0 + h - 1.001f, py));
                int ix = (int) px, iy = (int) py;
                float ax = px - ix, ay = py - iy;
                int c00 = pixel(page, pw, ph, ix, iy), c10 = pixel(page, pw, ph, ix + 1, iy);
                int c01 = pixel(page, pw, ph, ix, iy + 1), c11 = pixel(page, pw, ph, ix + 1, iy + 1);
                int o = ((SIZE - 1 - j) * SIZE + i) * 4;
                for (int s = 0; s < 4; s++) {
                    int sh = s == 3 ? 24 : 16 - 8 * s;   // r, g, b, a out of ARGB
                    float top0 = ((c00 >>> sh) & 255) * (1 - ax) + ((c10 >>> sh) & 255) * ax;
                    float bot0 = ((c01 >>> sh) & 255) * (1 - ax) + ((c11 >>> sh) & 255) * ax;
                    out[o + s] = (byte) Math.round(top0 * (1 - ay) + bot0 * ay);
                }
                out[o + 3] = (byte) 255;   // a floor is solid
            }
        }
        return out;
    }

    private static int pixel(int[] page, int pw, int ph, int x, int y) {
        x = Math.max(0, Math.min(pw - 1, x));
        y = Math.max(0, Math.min(ph - 1, y));
        return page[2 + y * pw + x];
    }

    // ------------------------------------------------------------------ PNG (8-bit RGBA or RGB, not interlaced: the packs')

    /** {width, height, argb...} */
    static int[] decode(byte[] png) throws java.util.zip.DataFormatException {
        ByteBuffer b = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN);
        b.position(8);
        int w = 0, h = 0, type = 6;
        ByteArrayOutputStream idat = new ByteArrayOutputStream(png.length);
        while (b.remaining() >= 12) {
            int len = b.getInt();
            byte[] t = new byte[4];
            b.get(t);
            String kind = new String(t, StandardCharsets.US_ASCII);
            if ("IHDR".equals(kind)) {
                w = b.getInt(b.position());
                h = b.getInt(b.position() + 4);
                type = png[b.position() + 9];
            } else if ("IDAT".equals(kind)) {
                idat.write(png, b.position(), len);
            } else if ("IEND".equals(kind)) {
                break;
            }
            b.position(b.position() + len + 4);   // data, CRC
        }
        int bpp = type == 2 ? 3 : 4;
        int stride = w * bpp;
        byte[] raw = new byte[(stride + 1) * h];
        Inflater inf = new Inflater();
        inf.setInput(idat.toByteArray());
        int got = 0;
        while (got < raw.length && !inf.finished()) {
            int n = inf.inflate(raw, got, raw.length - got);
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
            got += n;
        }
        inf.end();
        int[] out = new int[2 + w * h];
        out[0] = w;
        out[1] = h;
        byte[] prev = new byte[stride], cur = new byte[stride];
        for (int y = 0; y < h; y++) {
            int f = raw[y * (stride + 1)] & 255;
            System.arraycopy(raw, y * (stride + 1) + 1, cur, 0, stride);
            for (int i = 0; i < stride; i++) {
                int a = i >= bpp ? cur[i - bpp] & 255 : 0, up = prev[i] & 255, c = i >= bpp ? prev[i - bpp] & 255 : 0;
                int x = cur[i] & 255;
                switch (f) {
                    case 1: x += a; break;
                    case 2: x += up; break;
                    case 3: x += (a + up) >> 1; break;
                    case 4: {
                        int p = a + up - c, pa = Math.abs(p - a), pb = Math.abs(p - up), pc = Math.abs(p - c);
                        x += pa <= pb && pa <= pc ? a : pb <= pc ? up : c;
                        break;
                    }
                    default: break;
                }
                cur[i] = (byte) x;
            }
            for (int i = 0; i < w; i++) {
                int o = i * bpp;
                int alpha = bpp == 4 ? cur[o + 3] & 255 : 255;
                out[2 + y * w + i] = alpha << 24 | (cur[o] & 255) << 16 | (cur[o + 1] & 255) << 8 | (cur[o + 2] & 255);
            }
            byte[] s = prev;
            prev = cur;
            cur = s;
        }
        return out;
    }
}
