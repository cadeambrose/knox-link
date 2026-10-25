package knoxlink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

import zombie.worldMap.WorldMapVisited;

/**
 * Which parts of the map the character has explored (or learned from a paper map), from the
 * game's own WorldMapVisited, so the second screen reveals exactly what the in-game map does.
 *
 * Format (little-endian, gzipped): "KLV1", int unit (squares per cell of the mask), int width,
 * int height (in units), then width*height bits, row by row, 1 = visible.
 */
final class Fog {
    private static final int UNIT = 32;   // the game tracks exploration in 32x32 squares
    private static byte[] cached;
    private static long cachedAt;

    private Fog() {}

    static synchronized byte[] maskGz() throws IOException {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < 4000) return cached;
        WorldMapVisited v;
        try {
            v = WorldMapVisited.getInstance();
        } catch (Throwable t) {
            return null;
        }
        if (v == null) return null;
        int maxX = Math.max(MapData.get().maxX(), 300), maxY = Math.max(MapData.get().maxY(), 300);
        int w = (maxX + UNIT - 1) / UNIT, h = (maxY + UNIT - 1) / UNIT;
        byte[] bits = new byte[(w * h + 7) / 8];
        for (int uy = 0; uy < h; uy++) {
            int y = uy * UNIT + UNIT / 2;
            for (int ux = 0; ux < w; ux++) {
                int x = ux * UNIT + UNIT / 2;
                boolean seen;
                try {
                    seen = v.isVisited(x, y) || v.isKnown(x, y);
                } catch (Throwable t) {
                    seen = true;
                }
                if (seen) {
                    int i = uy * w + ux;
                    bits[i >> 3] |= (byte) (1 << (i & 7));
                }
            }
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream(bits.length + 16);
        raw.write('K'); raw.write('L'); raw.write('V'); raw.write('1');
        le(raw, UNIT);
        le(raw, w);
        le(raw, h);
        raw.write(bits);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(gz)) {
            g.write(raw.toByteArray());
        }
        cached = gz.toByteArray();
        cachedAt = now;
        return cached;
    }

    private static void le(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >>> 8) & 0xff);
        o.write((v >>> 16) & 0xff);
        o.write((v >>> 24) & 0xff);
    }
}
