package knoxlink;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Turns the game's own vector world map (media/maps/Muldraugh, KY/worldmap.xml and
 * worldmap-forest.xml) into a compact binary the web app can draw. Nothing from the game is
 * shipped with the mod: it is converted on the player's machine, once, and cached in
 * Zomboid/KnoxLink.
 *
 * Format (little-endian): "KLM1", u32 featureCount, then per feature
 *   u8 kind, u8 geometry (1 polygon, 2 line), u16 ringCount,
 *   per ring: u32 pointCount, i32 x0, i32 y0, then (pointCount-1) x (i16 dx, i16 dy)
 * followed by u32 length + UTF-8 JSON array of labels [{t,x,y,s,r,k}].
 * Coordinates are world squares (the same numbers the game shows as x/y).
 */
final class MapData {
    private static final MapData INSTANCE = new MapData();
    private static final int CELL = 300;
    private static final int FORMAT = 1;

    static MapData get() {
        return INSTANCE;
    }

    // Feature kinds; the web app has the matching table.
    static final int WATER = 1, FOREST = 2, ROAD_PRIMARY = 3, ROAD_SECONDARY = 4, ROAD_TERTIARY = 5,
            TRAIL = 6, RAIL = 7, B_RESIDENTIAL = 10, B_RETAIL = 11, B_FOOD = 12, B_INDUSTRIAL = 13,
            B_COMMUNITY = 14, B_MEDICAL = 15, B_HOSPITALITY = 16, B_OTHER = 17;

    private volatile String state = "idle";
    private volatile byte[] gzipped;
    private volatile int maxX = 66 * CELL, maxY = 53 * CELL;   // world extent in squares (updated by the scan)

    int maxX() {
        return maxX;
    }

    int maxY() {
        return maxY;
    }

    String state() {
        return state;
    }

    byte[] gzipped() {
        return gzipped;
    }

    void buildInBackground() {
        if (!"idle".equals(state)) return;
        state = "building";
        Thread t = new Thread(() -> {
            try {
                build();
                state = "ready";
            } catch (Throwable e) {
                state = "error";
                Log.error("map conversion failed", e);
            }
        }, "KnoxLink-map");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);   // never compete with the game for the CPU
        t.start();
    }

    private void build() throws IOException {
        File dir = Paths.media("maps/Muldraugh, KY");
        File main = new File(dir, "worldmap.xml");
        File forest = new File(dir, "worldmap-forest.xml");
        File labels = new File(dir, "worldmap-annotations.lua");
        if (!main.isFile()) throw new IOException("no " + main);

        long stamp = main.length() ^ main.lastModified() ^ (forest.length() * 31) ^ (forest.lastModified() * 17) ^ labels.length();
        File cache = new File(new File(Paths.zomboidDir(), "KnoxLink"), "map-v" + FORMAT + "-" + Long.toHexString(stamp) + ".bin.gz");
        if (cache.isFile() && cache.length() > 0) {
            gzipped = Files.readAllBytes(cache.toPath());
            Log.info("map loaded from cache (" + gzipped.length / 1024 + " KB)");
            return;
        }

        long t0 = System.currentTimeMillis();
        ByteArrayOutputStream raw = new ByteArrayOutputStream(8 << 20);
        DataOutputStream out = new DataOutputStream(raw);
        out.writeBytes("KLM1");
        out.writeInt(0);   // patched below (big-endian placeholder; rewritten little-endian)
        int[] count = {0};
        scan(main, out, count);
        if (forest.isFile()) scan(forest, out, count);
        byte[] labelJson = labels(labels).getBytes(StandardCharsets.UTF_8);
        writeIntLE(out, labelJson.length);
        out.write(labelJson);
        out.flush();

        byte[] bytes = raw.toByteArray();
        bytes[4] = (byte) count[0];
        bytes[5] = (byte) (count[0] >>> 8);
        bytes[6] = (byte) (count[0] >>> 16);
        bytes[7] = (byte) (count[0] >>> 24);

        ByteArrayOutputStream gz = new ByteArrayOutputStream(bytes.length / 3);
        try (GZIPOutputStream g = new GZIPOutputStream(gz, 1 << 16)) {
            g.write(bytes);
        }
        gzipped = gz.toByteArray();
        try {
            cache.getParentFile().mkdirs();
            File[] old = cache.getParentFile().listFiles((d, n) -> n.startsWith("map-v") && n.endsWith(".bin.gz"));
            if (old != null) for (File f : old) f.delete();
            try (OutputStream o = new FileOutputStream(cache)) {
                o.write(gzipped);
            }
        } catch (IOException e) {
            Log.error("could not cache the map", e);
        }
        Log.info("map converted: " + count[0] + " shapes, " + gzipped.length / 1024 + " KB, "
                + (System.currentTimeMillis() - t0) + " ms");
    }

    // ------------------------------------------------------------------ XML scanning

    /**
     * A purpose-built scanner for the game's map XML. The files are machine-written and very
     * regular, so this is far quicker and lighter than a general XML parser (the forest file
     * alone is ~65 MB).
     */
    private static void scan(File file, DataOutputStream out, int[] count) throws IOException {
        byte[] b;
        try (InputStream in = new FileInputStream(file)) {
            b = KnoxServer.readAll(in);
        }
        int n = b.length;
        int cellX = 0, cellY = 0;
        int geom = 0;
        List<int[]> rings = new ArrayList<>();
        IntList ring = null;
        String propName = null;
        int kind = 0;
        int i = 0;
        while (i < n) {
            if (b[i] != '<') { i++; continue; }
            int j = i + 1;
            if (match(b, j, "point ")) {
                int x = attrInt(b, j, "x=\"");
                int y = attrInt(b, j, "y=\"");
                if (ring != null) ring.add(cellX * CELL + x, cellY * CELL + y);
            } else if (match(b, j, "cell ")) {
                cellX = attrInt(b, j, "x=\"");
                cellY = attrInt(b, j, "y=\"");
                INSTANCE.maxX = Math.max(INSTANCE.maxX, (cellX + 1) * CELL);
                INSTANCE.maxY = Math.max(INSTANCE.maxY, (cellY + 1) * CELL);
            } else if (match(b, j, "feature")) {
                rings.clear();
                kind = 0;
                geom = 0;
            } else if (match(b, j, "geometry ")) {
                String type = attrStr(b, j, "type=\"");
                geom = "Polygon".equals(type) ? 1 : "LineString".equals(type) ? 2 : 0;
            } else if (match(b, j, "coordinates>")) {
                ring = new IntList();
            } else if (match(b, j, "/coordinates>")) {
                if (ring != null && ring.size >= 2) rings.add(ring.toArray());
                ring = null;
            } else if (match(b, j, "property ")) {
                propName = attrStr(b, j, "name=\"");
                String value = attrStr(b, j, "value=\"");
                int k = kindOf(propName, value);
                if (k != 0 && (kind == 0 || priority(k) > priority(kind))) kind = k;
            } else if (match(b, j, "/feature>")) {
                if (kind != 0 && geom != 0 && !rings.isEmpty()) {
                    writeFeature(out, kind, geom, rings);
                    count[0]++;
                }
            }
            i = j;
        }
    }

    private static int priority(int kind) {
        return kind >= B_RESIDENTIAL ? 3 : kind >= ROAD_PRIMARY ? 2 : 1;
    }

    private static int kindOf(String name, String value) {
        if (name == null || value == null) return 0;
        switch (name) {
            case "water":
                return WATER;
            case "natural":
                return ("forest".equals(value) || "wood".equals(value)) ? FOREST : ("water".equals(value) ? WATER : 0);
            case "highway":
                switch (value) {
                    case "primary": return ROAD_PRIMARY;
                    case "secondary": return ROAD_SECONDARY;
                    case "trail": return TRAIL;
                    default: return ROAD_TERTIARY;
                }
            case "railway":
                return RAIL;
            case "building":
                switch (value) {
                    case "Residential": return B_RESIDENTIAL;
                    case "RetailAndCommercial": return B_RETAIL;
                    case "RestaurantsAndEntertainment": return B_FOOD;
                    case "Industrial": return B_INDUSTRIAL;
                    case "CommunityServices": return B_COMMUNITY;
                    case "Medical": return B_MEDICAL;
                    case "Hospitality": return B_HOSPITALITY;
                    default: return B_OTHER;
                }
            default:
                return 0;
        }
    }

    private static void writeFeature(DataOutputStream out, int kind, int geom, List<int[]> rings) throws IOException {
        out.writeByte(kind);
        out.writeByte(geom);
        writeShortLE(out, Math.min(rings.size(), 65535));
        for (int r = 0; r < rings.size() && r < 65535; r++) {
            int[] p = rings.get(r);
            int pts = p.length / 2;
            writeIntLE(out, pts);
            writeIntLE(out, p[0]);
            writeIntLE(out, p[1]);
            for (int k = 1; k < pts; k++) {
                int dx = p[k * 2] - p[k * 2 - 2];
                int dy = p[k * 2 + 1] - p[k * 2 - 1];
                writeShortLE(out, clampShort(dx));
                writeShortLE(out, clampShort(dy));
            }
        }
    }

    private static int clampShort(int v) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }

    private static boolean match(byte[] b, int at, String s) {
        if (at + s.length() > b.length) return false;
        for (int k = 0; k < s.length(); k++) if (b[at + k] != s.charAt(k)) return false;
        return true;
    }

    /** Value of attr="..." inside the tag starting at {@code at}; stops at the end of the tag. */
    private static int attrInt(byte[] b, int at, String attr) {
        int p = find(b, at, attr);
        if (p < 0) return 0;
        boolean neg = false;
        if (b[p] == '-') { neg = true; p++; }
        int v = 0;
        while (p < b.length && b[p] >= '0' && b[p] <= '9') v = v * 10 + (b[p++] - '0');
        return neg ? -v : v;
    }

    private static String attrStr(byte[] b, int at, String attr) {
        int p = find(b, at, attr);
        if (p < 0) return null;
        int e = p;
        while (e < b.length && b[e] != '"') e++;
        return new String(b, p, e - p, StandardCharsets.UTF_8);
    }

    private static int find(byte[] b, int at, String attr) {
        for (int p = at; p < b.length && b[p] != '>'; p++) {
            if (match(b, p, attr)) return p + attr.length();
        }
        return -1;
    }

    private static void writeIntLE(DataOutputStream out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 24) & 0xff);
    }

    private static void writeShortLE(DataOutputStream out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
    }

    private static final class IntList {
        int[] a = new int[64];
        int size;   // number of points

        void add(int x, int y) {
            if (size * 2 + 2 > a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[size * 2] = x;
            a[size * 2 + 1] = y;
            size++;
        }

        int[] toArray() {
            return java.util.Arrays.copyOf(a, size * 2);
        }
    }

    // ------------------------------------------------------------------ labels

    private static final Pattern LABEL = Pattern.compile(
            "addUntranslatedText\\(\"([^\"]+)\",\\s*\"([^\"]*)\",\\s*(-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)\\)");
    private static final Pattern SCALE = Pattern.compile("setScale\\((-?\\d+(?:\\.\\d+)?)\\)");
    private static final Pattern ROT = Pattern.compile("setRotation\\((-?\\d+(?:\\.\\d+)?)\\)");

    /** Place names from worldmap-annotations.lua: [{t: text, x, y, s: scale, r: rotation, k: style}]. */
    private static String labels(File f) {
        List<Object> list = new ArrayList<>();
        if (f.isFile()) {
            try {
                String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                String[] blocks = src.split("symbol = ");
                for (String block : blocks) {
                    Matcher m = LABEL.matcher(block);
                    if (!m.find()) continue;
                    Map<String, Object> l = new LinkedHashMap<>();
                    l.put("t", translate(m.group(1)));
                    l.put("k", m.group(2));
                    l.put("x", Double.parseDouble(m.group(3)));
                    l.put("y", Double.parseDouble(m.group(4)));
                    Matcher s = SCALE.matcher(block);
                    l.put("s", s.find() ? Double.parseDouble(s.group(1)) : 1.0);
                    Matcher r = ROT.matcher(block);
                    l.put("r", r.find() ? Double.parseDouble(r.group(1)) : 0.0);
                    list.add(l);
                }
            } catch (Exception e) {
                Log.error("could not read map labels", e);
            }
        }
        return Json.write(list);
    }

    private static String translate(String key) {
        try {
            String t = zombie.core.Translator.getText(key);
            if (t != null && !t.equals(key) && !t.isEmpty()) return t;
        } catch (Throwable ignored) {
        }
        // MapLabel_SaltRiver -> Salt River
        String k = key.startsWith("MapLabel_") ? key.substring(9) : key;
        return k.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
    }
}
