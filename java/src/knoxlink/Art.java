package knoxlink;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * The game's own artwork for the second screen, read straight from the player's install:
 * loose UI images under media/ui, and item icons from the texture packs.
 *
 * Pack format (little-endian): "PZPK", int version, int pageCount, then per page:
 * string name, int entryCount, int mask, entryCount x (string name, int x, y, w, h, offsetX,
 * offsetY, fullW, fullH), int pngLength, png bytes. Strings are int length + bytes.
 */
final class Art {
    private static final Art INSTANCE = new Art();
    private static final String[] PACKS = {"UI2.pack", "UI.pack"};

    static Art get() {
        return INSTANCE;
    }

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

    private final List<Page> pages = new ArrayList<>();
    /** texture name -> {page, x, y, w, h, offsetX, offsetY, fullW, fullH} */
    private final Map<String, int[]> textures = new LinkedHashMap<>();
    private volatile byte[] indexGz;
    private volatile boolean indexed;

    private synchronized void index() {
        if (indexed) return;
        indexed = true;
        long t0 = System.currentTimeMillis();
        for (String name : PACKS) {
            File f = Paths.media("texturepacks/" + name);
            if (!f.isFile()) continue;
            try (RandomAccessFile raf = new RandomAccessFile(f, "r"); FileChannel ch = raf.getChannel()) {
                ByteBuffer b = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN);
                byte[] magic = new byte[4];
                b.get(magic);
                // Build 42 packs start with "PZPK" and store each PNG's length; older packs (all of
                // Build 41, and a few in 42) store the PNG raw, followed by a 0xDEADBEEF marker.
                boolean modern = "PZPK".equals(new String(magic, StandardCharsets.US_ASCII));
                int count;
                if (modern) {
                    b.getInt();   // version
                    count = b.getInt();
                } else {
                    b.position(0);
                    count = b.getInt();
                }
                for (int p = 0; p < count; p++) {
                    str(b);   // page name
                    int entries = b.getInt();
                    b.getInt();   // mask flag
                    int pageId = pages.size();
                    for (int e = 0; e < entries; e++) {
                        String tex = str(b);
                        int[] r = new int[9];
                        r[0] = pageId;
                        for (int k = 1; k < 9; k++) r[k] = b.getInt();
                        textures.putIfAbsent(tex, r);
                    }
                    int len;
                    if (modern) {
                        len = b.getInt();
                    } else {
                        len = pngLength(b, b.position());
                        if (len <= 0) break;
                    }
                    pages.add(new Page(f, b.position(), len));
                    b.position(b.position() + len);
                    if (!modern && b.remaining() >= 4 && b.getInt(b.position()) == 0xDEADBEEF) b.position(b.position() + 4);
                }
            } catch (Exception e) {
                Log.error("could not read " + f, e);
            }
        }
        Log.info("art index: " + textures.size() + " icons on " + pages.size() + " pages, "
                + (System.currentTimeMillis() - t0) + " ms");
    }

    /** Length of the PNG starting at {@code start}: up to and including the IEND chunk's CRC. */
    private static int pngLength(ByteBuffer b, int start) {
        int limit = b.limit() - 8;
        for (int i = start + 8; i < limit; i++) {
            if (b.get(i) == 'I' && b.get(i + 1) == 'E' && b.get(i + 2) == 'N' && b.get(i + 3) == 'D') {
                return i + 8 - start;   // "IEND" + 4-byte CRC
            }
        }
        return -1;
    }

    private static String str(ByteBuffer b) {
        int n = b.getInt();
        byte[] s = new byte[n];
        b.get(s);
        return new String(s, StandardCharsets.UTF_8);
    }

    /**
     * Which copy of the game's art this is (the packs' sizes and dates): clients keep pages under
     * this name, so pages cut from one game version are never used with another one's index.
     */
    String version() {
        long h = 1125899906842597L;
        for (String name : PACKS) {
            File f = Paths.media("texturepacks/" + name);
            h = 31 * h + (f.isFile() ? f.length() : 0);
            h = 31 * h + (f.isFile() ? f.lastModified() / 1000 : 0);
        }
        return Long.toHexString(h);
    }

    /** JSON {"v": art version, "pages": n, "tex": {"Item_Bat": [page,x,y,w,h,ox,oy,fw,fh], ...}}, gzipped, built once. */
    byte[] indexJsonGz() throws IOException {
        if (indexGz != null) return indexGz;
        index();
        StringBuilder sb = new StringBuilder(textures.size() * 40);
        sb.append("{\"v\":\"").append(version()).append("\",\"pages\":").append(pages.size()).append(",\"tex\":{");
        boolean first = true;
        for (Map.Entry<String, int[]> e : textures.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            Json.string(sb, e.getKey());
            sb.append(":[");
            int[] r = e.getValue();
            for (int i = 0; i < r.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(r[i]);
            }
            sb.append(']');
        }
        sb.append("}}");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(bo)) {
            g.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        indexGz = bo.toByteArray();
        return indexGz;
    }

    /** The PNG of one pack page, or null. */
    byte[] page(int id) throws IOException {
        index();
        if (id < 0 || id >= pages.size()) return null;
        Page p = pages.get(id);
        byte[] out = new byte[p.length];
        try (RandomAccessFile raf = new RandomAccessFile(p.file, "r")) {
            raf.seek(p.offset);
            raf.readFully(out);
        }
        return out;
    }

    /**
     * An item icon that isn't in the texture packs: a loose "&lt;name&gt;.png" in media/textures (newer
     * and modded items keep theirs there; the game's file system also finds them in active mods).
     */
    static byte[] looseIcon(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9_\\-]{1,96}")) return null;
        File f = Paths.media("textures/" + name + ".png");
        if (!f.isFile()) f = Paths.media("textures/inventory/" + name + ".png");
        return f.isFile() ? Files.readAllBytes(f.toPath()) : null;
    }

    /** A loose PNG under media/ui or media/textures/worldMap (moodles, body outlines, sidebar, vehicle and map art), or null. */
    static byte[] media(String path) throws IOException {
        if (path == null || path.contains("..") || path.contains("\\") || !path.toLowerCase().endsWith(".png")
                || !(path.startsWith("ui/") || path.startsWith("textures/worldMap/"))) {
            return null;
        }
        File f = Paths.media(path);
        return f.isFile() ? Files.readAllBytes(f.toPath()) : null;
    }
}
