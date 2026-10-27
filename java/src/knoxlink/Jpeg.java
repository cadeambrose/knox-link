package knoxlink;

import java.io.ByteArrayOutputStream;

/**
 * A small baseline JPEG encoder (4:2:0, the standard tables of the JPEG spec's annex K), for the
 * scrapbook's photos: the game's Java runtime on a handheld may come without javax.imageio, and
 * a PNG of a screen is ten times the size.
 */
final class Jpeg {
    private Jpeg() {}

    private static final int[] ZIGZAG = {
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63};

    private static final int[] LUM_Q = {
            16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56,
            14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99};
    private static final int[] CHR_Q = {
            17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99, 99, 99,
            47, 66, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99};

    private static final int[] DC_LUM_BITS = {0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
    private static final int[] DC_CHR_BITS = {0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
    private static final int[] DC_VALS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    private static final int[] AC_LUM_BITS = {0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d};
    private static final int[] AC_LUM_VALS = {
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
            0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
            0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
            0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
            0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
            0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
            0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa};
    private static final int[] AC_CHR_BITS = {0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77};
    private static final int[] AC_CHR_VALS = {
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
            0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
            0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
            0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
            0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
            0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
            0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
            0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
            0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
            0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa};

    private static final float[][] COS = new float[8][8];   // [k][n]: s(k) cos((2n+1)k pi / 16)

    static {
        for (int k = 0; k < 8; k++) {
            double s = k == 0 ? Math.sqrt(1.0 / 8) : Math.sqrt(2.0 / 8);
            for (int n = 0; n < 8; n++) COS[k][n] = (float) (s * Math.cos((2 * n + 1) * k * Math.PI / 16));
        }
    }

    /** One Huffman table: code and length per symbol. */
    private static final class Huff {
        final int[] code = new int[256], len = new int[256];

        Huff(int[] bits, int[] vals) {
            int c = 0, k = 0;
            for (int l = 1; l <= 16; l++) {
                for (int i = 0; i < bits[l - 1]; i++) {
                    code[vals[k]] = c++;
                    len[vals[k]] = l;
                    k++;
                }
                c <<= 1;
            }
        }
    }

    private static final Huff DC_LUM = new Huff(DC_LUM_BITS, DC_VALS), DC_CHR = new Huff(DC_CHR_BITS, DC_VALS);
    private static final Huff AC_LUM = new Huff(AC_LUM_BITS, AC_LUM_VALS), AC_CHR = new Huff(AC_CHR_BITS, AC_CHR_VALS);

    /** Bits out, with the 0xFF byte stuffing the format needs. */
    private static final class Bits {
        final ByteArrayOutputStream out;
        int acc, n;

        Bits(ByteArrayOutputStream out) {
            this.out = out;
        }

        void put(int code, int len) {
            for (int i = len - 1; i >= 0; i--) {
                acc = (acc << 1) | ((code >>> i) & 1);
                if (++n == 8) {
                    out.write(acc);
                    if (acc == 0xFF) out.write(0);
                    acc = 0;
                    n = 0;
                }
            }
        }

        void flush() {
            while (n != 0) put(1, 1);
        }
    }

    private static int[] table(int[] base, int quality) {
        int q = Math.max(1, Math.min(100, quality));
        int scale = q < 50 ? 5000 / q : 200 - 2 * q;
        int[] t = new int[64];
        for (int i = 0; i < 64; i++) t[i] = Math.max(1, Math.min(255, (base[i] * scale + 50) / 100));
        return t;
    }

    /** rgb: top-down rows of width * 3 bytes. */
    static byte[] encode(byte[] rgb, int w, int h, int quality) {
        int[] lq = table(LUM_Q, quality), cq = table(CHR_Q, quality);
        ByteArrayOutputStream out = new ByteArrayOutputStream(w * h / 4);
        marker(out, 0xD8);
        // JFIF header
        segment(out, 0xE0, new int[]{'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0});
        // quantisation tables, in zigzag order
        int[] dqt = new int[130];
        dqt[0] = 0;
        for (int i = 0; i < 64; i++) dqt[1 + i] = lq[ZIGZAG[i]];
        dqt[65] = 1;
        for (int i = 0; i < 64; i++) dqt[66 + i] = cq[ZIGZAG[i]];
        segment(out, 0xDB, dqt);
        segment(out, 0xC0, new int[]{8, h >> 8, h & 0xff, w >> 8, w & 0xff, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1});
        ByteArrayOutputStream dht = new ByteArrayOutputStream();
        huffTable(dht, 0x00, DC_LUM_BITS, DC_VALS);
        huffTable(dht, 0x10, AC_LUM_BITS, AC_LUM_VALS);
        huffTable(dht, 0x01, DC_CHR_BITS, DC_VALS);
        huffTable(dht, 0x11, AC_CHR_BITS, AC_CHR_VALS);
        byte[] d = dht.toByteArray();
        out.write(0xFF);
        out.write(0xC4);
        out.write((d.length + 2) >> 8);
        out.write((d.length + 2) & 0xff);
        out.write(d, 0, d.length);
        segment(out, 0xDA, new int[]{3, 1, 0x00, 2, 0x11, 3, 0x11, 0, 63, 0});

        Bits bits = new Bits(out);
        float[] y = new float[256], cb = new float[64], cr = new float[64], block = new float[64];
        int[] prev = new int[3];
        for (int my = 0; my < h; my += 16) {
            for (int mx = 0; mx < w; mx += 16) {
                java.util.Arrays.fill(cb, 0);
                java.util.Arrays.fill(cr, 0);
                for (int j = 0; j < 16; j++) {
                    int sy = Math.min(h - 1, my + j);
                    for (int i = 0; i < 16; i++) {
                        int sx = Math.min(w - 1, mx + i);
                        int o = (sy * w + sx) * 3;
                        float r = rgb[o] & 0xff, g = rgb[o + 1] & 0xff, b = rgb[o + 2] & 0xff;
                        y[j * 16 + i] = 0.299f * r + 0.587f * g + 0.114f * b - 128;
                        int c = (j >> 1) * 8 + (i >> 1);
                        cb[c] += (-0.168736f * r - 0.331264f * g + 0.5f * b) * 0.25f;
                        cr[c] += (0.5f * r - 0.418688f * g - 0.081312f * b) * 0.25f;
                    }
                }
                for (int by = 0; by < 16; by += 8) {
                    for (int bx = 0; bx < 16; bx += 8) {
                        for (int j = 0; j < 8; j++) System.arraycopy(y, (by + j) * 16 + bx, block, j * 8, 8);
                        prev[0] = block(bits, block, lq, prev[0], DC_LUM, AC_LUM);
                    }
                }
                prev[1] = block(bits, cb, cq, prev[1], DC_CHR, AC_CHR);
                prev[2] = block(bits, cr, cq, prev[2], DC_CHR, AC_CHR);
            }
        }
        bits.flush();
        marker(out, 0xD9);
        return out.toByteArray();
    }

    /** DCT, quantise and write one 8x8 block; returns its DC value for the next block's difference. */
    private static int block(Bits bits, float[] px, int[] q, int prevDc, Huff dc, Huff ac) {
        float[] t = new float[64];
        for (int r = 0; r < 8; r++) {
            for (int k = 0; k < 8; k++) {
                float s = 0;
                for (int n = 0; n < 8; n++) s += px[r * 8 + n] * COS[k][n];
                t[r * 8 + k] = s;
            }
        }
        int[] z = new int[64];
        for (int c = 0; c < 8; c++) {
            for (int k = 0; k < 8; k++) {
                float s = 0;
                for (int n = 0; n < 8; n++) s += t[n * 8 + c] * COS[k][n];
                int idx = k * 8 + c;
                z[idx] = Math.round(s / q[idx]);
            }
        }
        int dcv = z[0];
        int diff = dcv - prevDc;
        int size = size(diff);
        bits.put(dc.code[size], dc.len[size]);
        if (size > 0) bits.put(diff < 0 ? diff + (1 << size) - 1 : diff, size);
        int run = 0;
        for (int i = 1; i < 64; i++) {
            int v = z[ZIGZAG[i]];
            if (v == 0) {
                run++;
                continue;
            }
            while (run > 15) {
                bits.put(ac.code[0xF0], ac.len[0xF0]);
                run -= 16;
            }
            int s = size(v);
            int sym = (run << 4) | s;
            bits.put(ac.code[sym], ac.len[sym]);
            bits.put(v < 0 ? v + (1 << s) - 1 : v, s);
            run = 0;
        }
        if (run > 0) bits.put(ac.code[0], ac.len[0]);
        return dcv;
    }

    private static int size(int v) {
        int a = Math.abs(v), s = 0;
        while (a > 0) {
            s++;
            a >>= 1;
        }
        return s;
    }

    private static void marker(ByteArrayOutputStream out, int m) {
        out.write(0xFF);
        out.write(m);
    }

    private static void segment(ByteArrayOutputStream out, int m, int[] data) {
        marker(out, m);
        out.write((data.length + 2) >> 8);
        out.write((data.length + 2) & 0xff);
        for (int b : data) out.write(b);
    }

    private static void huffTable(ByteArrayOutputStream out, int classId, int[] bits, int[] vals) {
        out.write(classId);
        for (int b : bits) out.write(b);
        for (int v : vals) out.write(v);
    }
}
