package knoxlink;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import zombie.GameTime;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.core.Translator;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.iso.BuildingDef;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.RoomDef;
import zombie.iso.areas.IsoBuilding;
import zombie.iso.areas.IsoRoom;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;

/**
 * What the character remembers of the world, for "Where did I see...?" and the map: every
 * container they looked in (what was in it and where), the buildings they went through, where
 * they left their car, and the way they walked.
 *
 * Only what this character saw: a container counts once the game's loot window has listed it
 * (it's within reach, a tap shows what's inside), and a new character starts with nothing. One
 * gzipped file per character in the save (knoxlink/memory-<journal id>.json.gz), so it goes
 * with the save and the dead keep theirs (the recap draws their path from it).
 *
 * Everything here runs on the game thread except the file write, which gets a snapshot.
 */
final class Memory {
    private static final int MAX_BOXES = 4000;
    private static final int MAX_KINDS = 60;          // different things kept per container
    private static final int MAX_TRAIL = 40000;       // points (x, y); about every 6 squares walked
    private static final int TRAIL_STEP = 6;
    private static final long SAVE_EVERY_MS = 90_000;

    /** One container the character looked in: a shelf, a fridge, a body, a car's trunk, a spot of floor. */
    private static final class Box {
        String key;
        int x, y, z;
        String type, name, room;
        int bx = Integer.MIN_VALUE, by;    // the building's corner, or MIN_VALUE outside
        double at;                         // world age (hours) when last seen
        boolean body;
        String[] types = new String[0], names = new String[0], cats = new String[0], icons = new String[0];
        int[] counts = new int[0];
    }

    /** A building the character went into. */
    private static final class Bldg {
        int x, y, w, h;
        int total = -1;                    // containers in it (-1: not counted yet)
        long countedAt;
        final HashSet<String> rooms = new HashSet<>();
        int roomCount;
        double at;
    }

    private static String charId;
    private static File file;
    private static final LinkedHashMap<String, Box> boxes = new LinkedHashMap<>();
    private static final HashMap<String, Bldg> buildings = new HashMap<>();
    private static int[] trail = new int[2048];
    private static int trailN;
    private static int carX, carY, carZ;
    private static String carName;
    private static double carAt = -1;
    private static boolean dirty;
    private static long savedAt;
    private static int version;
    private static String lastOverlay;
    private static final java.util.Set<String> failed = new HashSet<>();

    private Memory() {}

    private static void failedOnce(String what, Throwable t) {
        if (failed.add(what)) Log.error("memory: " + what, t);
    }

    // ------------------------------------------------------------------ whose memory

    /** The character whose memory this is (their journal's id); loads it from the save the first time. */
    static synchronized void select(String id) {
        if (id == null || id.isEmpty() || id.equals(charId)) return;
        if (charId != null) saveNow();
        charId = id;
        boxes.clear();
        buildings.clear();
        trailN = 0;
        carAt = -1;
        carName = null;
        dirty = false;
        version++;
        file = fileFor(id);
        if (file != null && file.isFile()) load(file);
    }

    static synchronized String current() {
        return charId;
    }

    /** knoxlink/<name> in the current save, or null when there's no save. */
    static File saveFile(String name) {
        try {
            File f = ZomboidFileSystem.instance.getFileInCurrentSave("knoxlink", name);
            return f;
        } catch (Throwable t) {
            failedOnce("save folder", t);
            return null;
        }
    }

    private static File fileFor(String id) {
        return saveFile("memory-" + id.replaceAll("[^A-Za-z0-9_-]", "_") + ".json.gz");
    }

    // ------------------------------------------------------------------ what the character saw

    /** A container the game's loot window lists; label is the loot window's name for it. */
    static synchronized void saw(Object container, String label) {
        if (charId == null || !(container instanceof ItemContainer)) return;
        try {
            ItemContainer c = (ItemContainer) container;
            String type = c.getType();
            if ("floor".equals(type)) {
                floor(c);
                return;
            }
            IsoGridSquare sq = null;
            String key;
            boolean body = false;
            Object parent = c.getParent();
            VehiclePart part = c.getVehiclePart();
            InventoryItem holder = c.getContainingItem();
            if (part != null && part.getVehicle() != null) {
                BaseVehicle v = part.getVehicle();
                sq = v.getSquare();
                key = "car:" + v.getSqlId() + ":" + part.getId();
            } else if (parent instanceof IsoDeadBody) {
                sq = ((IsoDeadBody) parent).getSquare();
                body = true;
                key = sq == null ? null : sq.getX() + "," + sq.getY() + "," + sq.getZ() + ":body";
            } else if (parent instanceof IsoObject) {
                sq = ((IsoObject) parent).getSquare();
                int idx = 0;
                IsoObject o = (IsoObject) parent;
                for (int i = 0; i < o.getContainerCount(); i++) if (o.getContainerByIndex(i) == c) idx = i;
                key = sq == null ? null : sq.getX() + "," + sq.getY() + "," + sq.getZ() + ":" + type + (idx > 0 ? "#" + idx : "");
            } else if (holder != null && holder.getWorldItem() != null) {
                sq = holder.getWorldItem().getSquare();   // a bag lying on the floor
                key = sq == null ? null : sq.getX() + "," + sq.getY() + "," + sq.getZ() + ":bag:" + holder.getID();
            } else {
                return;   // something carried
            }
            if (sq == null || key == null) return;
            Box b = box(key, sq, type, label);
            b.body = body;
            fill(b, c.getItems());
        } catch (Throwable t) {
            failedOnce("container", t);
        }
    }

    /** The loot window's "floor": the items lying around, kept per square they lie on. */
    private static void floor(ItemContainer c) {
        HashMap<IsoGridSquare, ArrayList<InventoryItem>> by = new HashMap<>();
        for (InventoryItem it : c.getItems()) {
            IsoWorldInventoryObject w = it.getWorldItem();
            IsoGridSquare sq = w == null ? null : w.getSquare();
            if (sq != null) by.computeIfAbsent(sq, k -> new ArrayList<>()).add(it);
        }
        // squares in reach that had something and are bare now
        IsoPlayer p = IsoPlayer.getInstance();
        IsoGridSquare at = p == null ? null : p.getCurrentSquare();
        if (at != null) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    String k = (at.getX() + dx) + "," + (at.getY() + dy) + "," + at.getZ() + ":floor";
                    Box old = boxes.get(k);
                    if (old != null && old.types.length > 0) {
                        boolean still = false;
                        for (IsoGridSquare s : by.keySet()) still |= s.getX() == old.x && s.getY() == old.y;
                        if (!still) {
                            boxes.remove(k);
                            dirty = true;
                        }
                    }
                }
            }
        }
        for (Map.Entry<IsoGridSquare, ArrayList<InventoryItem>> e : by.entrySet()) {
            IsoGridSquare sq = e.getKey();
            Box b = box(sq.getX() + "," + sq.getY() + "," + sq.getZ() + ":floor", sq, "floor", "Floor");
            fill(b, e.getValue());
        }
    }

    private static Box box(String key, IsoGridSquare sq, String type, String label) {
        Box b = boxes.remove(key);   // re-inserted: the map stays in the order things were last seen
        if (b == null) {
            b = new Box();
            b.key = key;
            version++;
            dirty = true;
        }
        boxes.put(key, b);
        b.x = sq.getX();
        b.y = sq.getY();
        b.z = sq.getZ();
        b.type = type;
        if (label != null && !label.isEmpty()) b.name = label;
        if (b.name == null) b.name = type;
        IsoRoom room = sq.getRoom();
        b.room = room == null ? null : room.getName();
        IsoBuilding building = sq.getBuilding();
        BuildingDef def = building == null ? null : building.getDef();
        if (def != null) {
            b.bx = def.getX();
            b.by = def.getY();
        } else {
            b.bx = Integer.MIN_VALUE;
        }
        b.at = GameTime.getInstance().getWorldAgeHours();
        while (boxes.size() > MAX_BOXES) {
            Iterator<String> it = boxes.keySet().iterator();
            it.next();
            it.remove();
        }
        return b;
    }

    private static void fill(Box b, List<InventoryItem> items) {
        LinkedHashMap<String, int[]> counts = new LinkedHashMap<>();
        HashMap<String, String[]> about = new HashMap<>();
        for (InventoryItem it : items) {
            if (it == null) continue;
            String t = it.getFullType();
            int[] n = counts.get(t);
            if (n == null) {
                if (counts.size() >= MAX_KINDS) continue;
                counts.put(t, n = new int[1]);
                about.put(t, new String[]{it.getDisplayName(), it.getDisplayCategory(), iconOf(it)});
            }
            n[0] += Math.max(1, it.getCount());
        }
        int k = counts.size();
        String[] types = new String[k], names = new String[k], cats = new String[k], icons = new String[k];
        int[] cs = new int[k];
        int i = 0;
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            types[i] = e.getKey();
            String[] a = about.get(e.getKey());
            names[i] = a[0] == null ? e.getKey() : a[0];
            cats[i] = a[1] == null ? "" : a[1];
            icons[i] = a[2] == null ? "" : a[2];
            cs[i] = e.getValue()[0];
            i++;
        }
        if (!java.util.Arrays.equals(types, b.types) || !java.util.Arrays.equals(cs, b.counts)) {
            version++;
            dirty = true;
        }
        b.types = types;
        b.names = names;
        b.cats = cats;
        b.icons = icons;
        b.counts = cs;
    }

    /** The item's icon in the game's texture packs ("Item_Bat"), as the screens ask for it. */
    private static String iconOf(InventoryItem it) {
        try {
            zombie.core.textures.Texture tex = it.getTex();
            String n = tex == null ? null : tex.getName();
            if (n == null) return null;
            n = n.replaceAll("^.*[/\\\\]", "");
            return n.endsWith(".png") ? n.substring(0, n.length() - 4) : n;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Once a second or so: the way walked, and the building the character is in. */
    static synchronized void tick() {
        if (charId == null) return;
        IsoPlayer p = IsoPlayer.getInstance();
        if (p == null || p.isDead()) return;
        try {
            int x = (int) Math.floor(p.getX()), y = (int) Math.floor(p.getY());
            if (trailN == 0 || Math.abs(x - trail[trailN - 2]) + Math.abs(y - trail[trailN - 1]) >= TRAIL_STEP) {
                if (trailN + 2 > trail.length) {
                    if (trail.length < MAX_TRAIL * 2) {
                        trail = java.util.Arrays.copyOf(trail, Math.min(MAX_TRAIL * 2, trail.length * 2));
                    } else {
                        // full: the older half keeps every other point
                        int half = (trailN / 4) * 2, w = 0;
                        for (int r = 0; r < half; r += 4) { trail[w++] = trail[r]; trail[w++] = trail[r + 1]; }
                        for (int r = half; r < trailN; r++) trail[w++] = trail[r];
                        trailN = w;
                    }
                }
                trail[trailN++] = x;
                trail[trailN++] = y;
                dirty = true;
            }
            IsoGridSquare sq = p.getCurrentSquare();
            IsoRoom room = sq == null ? null : sq.getRoom();
            BuildingDef def = room == null || room.getBuilding() == null ? null : room.getBuilding().getDef();
            if (def != null) {
                String k = def.getX() + "," + def.getY();
                Bldg b = buildings.get(k);
                if (b == null) {
                    b = new Bldg();
                    b.x = def.getX();
                    b.y = def.getY();
                    b.w = def.getW();
                    b.h = def.getH();
                    buildings.put(k, b);
                    version++;
                }
                b.roomCount = def.getRooms().size();
                RoomDef rd = room.getRoomDef();
                if (rd != null && b.rooms.add(Long.toString(rd.getID()))) {
                    version++;
                    dirty = true;
                }
                b.at = GameTime.getInstance().getWorldAgeHours();
                long now = System.currentTimeMillis();
                if (b.total < 0 || now - b.countedAt > 60_000) {
                    b.countedAt = now;
                    int n = countContainers(def);
                    if (n >= 0 && n != b.total) {
                        b.total = n;
                        version++;
                        dirty = true;
                    }
                }
            }
            if (dirty && System.currentTimeMillis() - savedAt > SAVE_EVERY_MS) saveNow();
        } catch (Throwable t) {
            failedOnce("walk", t);
        }
    }

    /** The containers in a building (furniture, fridges, crates...), or -1 while part of it isn't loaded. */
    private static int countContainers(BuildingDef def) {
        IsoCell cell = IsoWorld.instance.getCell();
        if (cell == null) return -1;
        int n = 0;
        for (RoomDef r : def.getRooms()) {
            for (RoomDef.RoomRect rr : r.getRects()) {
                for (int y = rr.getY(); y < rr.getY2(); y++) {
                    for (int x = rr.getX(); x < rr.getX2(); x++) {
                        IsoGridSquare sq = cell.getGridSquare(x, y, r.getZ());
                        if (sq == null) return -1;
                        for (int i = 0; i < sq.getObjects().size(); i++) {
                            IsoObject o = sq.getObjects().get(i);
                            if (o != null && !(o instanceof IsoWorldInventoryObject)) n += o.getContainerCount();
                        }
                    }
                }
            }
        }
        return n;
    }

    /** The car the character just got out of: where it was left. */
    static synchronized void car(Object vehicle) {
        if (charId == null || !(vehicle instanceof BaseVehicle)) return;
        try {
            BaseVehicle v = (BaseVehicle) vehicle;
            carX = (int) Math.floor(v.getX());
            carY = (int) Math.floor(v.getY());
            carZ = (int) Math.floor(v.getZ());
            String script = v.getScriptName();
            if (script != null) script = script.replaceFirst("^.*\\.", "");
            String name = script == null ? null : Translator.getTextOrNull("IGUI_VehicleName" + script);
            carName = name != null ? name : script != null ? script : "Car";
            carAt = GameTime.getInstance().getWorldAgeHours();
            version++;
            dirty = true;
        } catch (Throwable t) {
            failedOnce("car", t);
        }
    }

    // ------------------------------------------------------------------ the screens

    // the towns on the game's world map (as in the journal): name, x, y, how far out it counts
    private static final Object[][] TOWNS = {
            {"Muldraugh", 10754, 9926, 1000}, {"West Point", 11654, 6864, 1000}, {"Riverside", 6450, 5430, 900},
            {"Rosewood", 8159, 11661, 900}, {"March Ridge", 10130, 12801, 800}, {"Louisville", 13077, 2238, 2100},
            {"Valley Station", 13447, 5278, 900}, {"Brandenburg", 2056, 6070, 1000}, {"Ekron", 634, 9746, 900},
            {"Irvington", 2427, 14185, 1000}, {"Echo Creek", 3589, 10952, 900}, {"Fallas Lake", 7253, 8279, 900},
    };

    static String townAt(int x, int y) {
        String best = null;
        double bestD = Double.MAX_VALUE;
        for (Object[] t : TOWNS) {
            double d = Math.hypot(x - (Integer) t[1], y - (Integer) t[2]);
            if (d <= (Integer) t[3] && d < bestD) {
                bestD = d;
                best = (String) t[0];
            }
        }
        return best;
    }

    private static final Map<String, String> ROOMS = new HashMap<>();

    static {
        String[] r = {
                "kitchen", "Kitchen", "bedroom", "Bedroom", "bathroom", "Bathroom", "livingroom", "Living room",
                "diningroom", "Dining room", "garage", "Garage", "garagestorage", "Garage", "storage", "Storage",
                "storageunit", "Storage unit", "laundry", "Laundry", "hall", "Hall", "hallway", "Hall", "office", "Office",
                "closet", "Closet", "basement", "Basement", "shed", "Shed", "kidsbedroom", "Kids' bedroom",
                "grocery", "Grocery store", "grocerystorage", "Grocery store", "pharmacy", "Pharmacy",
                "pharmacystorage", "Pharmacy", "gunstore", "Gun store", "gunstorestorage", "Gun store",
                "policestorage", "Police station", "toolstore", "Hardware store", "hardwarestore", "Hardware store",
                "warehouse", "Warehouse", "mechanic", "Mechanic's", "gasstore", "Gas station", "gasstation", "Gas station",
                "restaurant", "Restaurant", "restaurantkitchen", "Restaurant kitchen", "bar", "Bar", "motelroom", "Motel room",
                "classroom", "Classroom", "library", "Library", "church", "Church", "medical", "Doctor's office",
                "hospitalroom", "Hospital", "farmstorage", "Farm", "barn", "Barn", "camping", "Camping store",
                "spiffoskitchen", "Spiffo's kitchen", "bakery", "Bakery", "liquorstore", "Liquor store", "shoestore", "Shoe store",
                "clothingstore", "Clothing store", "zippeestore", "Zippee Market", "fossoil", "Fossoil",
        };
        for (int i = 0; i + 1 < r.length; i += 2) ROOMS.put(r[i], r[i + 1]);
    }

    static String roomLabel(String room) {
        if (room == null || room.isEmpty()) return null;
        String s = ROOMS.get(room);
        if (s != null) return s;
        return Character.toUpperCase(room.charAt(0)) + room.substring(1);
    }

    private static String dir(int dx, int dy) {
        if (dx * dx + dy * dy < 9) return "";
        // +x east, +y south (the world map's right and down)
        String[] d = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
        double a = Math.toDegrees(Math.atan2(dy, dx));
        return d[(int) Math.floorMod(Math.round(a / 45), 8)];
    }

    /**
     * Where the character saw things matching q (words in the item's name, its type, or its
     * category: "cat:Food" for a category alone), nearest first, published on "seen".
     */
    static synchronized void find(String q, int req) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("req", req);
        out.put("q", q == null ? "" : q);
        List<Object> rows = new ArrayList<>();
        IsoPlayer p = IsoPlayer.getInstance();
        int px = p == null ? 0 : (int) Math.floor(p.getX()), py = p == null ? 0 : (int) Math.floor(p.getY());
        int pz = p == null ? 0 : (int) Math.floor(p.getZ());
        double now = GameTime.getInstance().getWorldAgeHours();
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String cat = null;
        if (query.startsWith("cat:")) {
            cat = query.substring(4).trim();
            query = "";
        }
        String[] words = query.isEmpty() ? new String[0] : query.split("\\s+");
        List<Object[]> hits = new ArrayList<>();   // score, distance, box, index
        int total = 0;
        if (cat != null || words.length > 0) {
            for (Box b : boxes.values()) {
                for (int i = 0; i < b.types.length; i++) {
                    String name = b.names[i].toLowerCase(Locale.ROOT);
                    String hay = name + " " + b.types[i].toLowerCase(Locale.ROOT) + " " + b.cats[i].toLowerCase(Locale.ROOT);
                    int score;
                    if (cat != null) {
                        // "cat:firstaid,bandage": any of the game's display categories that has one of the words
                        String c = b.cats[i].toLowerCase(Locale.ROOT);
                        boolean any = false;
                        for (String w : cat.split(",")) any |= !w.trim().isEmpty() && c.contains(w.trim());
                        if (!any) continue;
                        score = 1;
                    } else {
                        boolean all = true;
                        for (String w : words) all &= hay.contains(w);
                        if (!all) continue;
                        score = name.equals(query) ? 3 : name.startsWith(query) ? 2 : 1;
                    }
                    total++;
                    double d = Math.hypot(b.x + 0.5 - px, b.y + 0.5 - py) + Math.abs(b.z - pz) * 3;
                    hits.add(new Object[]{score, d, b, i});
                }
            }
            hits.sort((a, c) -> {
                int s = Integer.compare((Integer) c[0], (Integer) a[0]);
                return s != 0 ? s : Double.compare((Double) a[1], (Double) c[1]);
            });
        }
        for (int k = 0; k < hits.size() && k < 40; k++) {
            Object[] h = hits.get(k);
            Box b = (Box) h[2];
            int i = (Integer) h[3];
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("n", b.names[i]);
            r.put("c", b.counts[i]);
            r.put("t", b.types[i]);
            if (i < b.icons.length && !b.icons[i].isEmpty()) r.put("ic", b.icons[i]);
            r.put("b", b.name);
            String room = roomLabel(b.room);
            if (room != null) r.put("rm", room);
            String town = townAt(b.x, b.y);
            if (town != null) r.put("town", town);
            r.put("x", b.x);
            r.put("y", b.y);
            r.put("z", b.z);
            r.put("d", (int) Math.round((Double) h[1]));
            String dd = dir(b.x - px, b.y - py);
            if (!dd.isEmpty()) r.put("dir", dd);
            r.put("h", Math.max(0, Math.round((now - b.at) * 10) / 10.0));
            if (b.body) r.put("body", true);
            rows.add(r);
        }
        out.put("total", total);
        out.put("r", rows);
        KnoxServer.get().publish("seen", Json.write(out));
    }

    /** One place a thing was seen, for the screens: {n, c, ic, cat, b, rm, town, x, y, z, d, dir, h, body}. */
    private static Map<String, Object> where(Box b, int i, int px, int py, int pz, double now) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("t", b.types[i]);
        r.put("n", b.names[i]);
        r.put("c", b.counts[i]);
        if (i < b.icons.length && !b.icons[i].isEmpty()) r.put("ic", b.icons[i]);
        r.put("cat", b.cats[i]);
        r.put("b", b.name);
        String room = roomLabel(b.room);
        if (room != null) r.put("rm", room);
        String town = townAt(b.x, b.y);
        if (town != null) r.put("town", town);
        r.put("x", b.x);
        r.put("y", b.y);
        r.put("z", b.z);
        r.put("d", (int) Math.round(Math.hypot(b.x + 0.5 - px, b.y + 0.5 - py) + Math.abs(b.z - pz) * 3));
        String dd = dir(b.x - px, b.y - py);
        if (!dd.isEmpty()) r.put("dir", dd);
        r.put("h", Math.max(0, Math.round((now - b.at) * 10) / 10.0));
        if (b.body) r.put("body", true);
        return r;
    }

    /** The nearest place each of these item types was seen: {type: where}, for the gunsmith's missing parts. */
    static synchronized Map<String, Object> nearest(List<String> types) {
        Map<String, Object> out = new LinkedHashMap<>();
        IsoPlayer p = IsoPlayer.getInstance();
        int px = p == null ? 0 : (int) Math.floor(p.getX()), py = p == null ? 0 : (int) Math.floor(p.getY());
        int pz = p == null ? 0 : (int) Math.floor(p.getZ());
        double now = GameTime.getInstance().getWorldAgeHours();
        HashMap<String, Object[]> best = new HashMap<>();
        HashSet<String> wanted = new HashSet<>(types);
        for (Box b : boxes.values()) {
            for (int i = 0; i < b.types.length; i++) {
                if (!wanted.contains(b.types[i])) continue;
                double d = Math.hypot(b.x + 0.5 - px, b.y + 0.5 - py) + Math.abs(b.z - pz) * 3;
                Object[] cur = best.get(b.types[i]);
                if (cur == null || d < (Double) cur[0]) best.put(b.types[i], new Object[]{d, b, i});
            }
        }
        for (Map.Entry<String, Object[]> e : best.entrySet()) out.put(e.getKey(), where((Box) e.getValue()[1], (Integer) e.getValue()[2], px, py, pz, now));
        return out;
    }

    /** Things seen whose category has a word ("weapon"): one row a type, the nearest place, nearest first. */
    static synchronized List<Object> kinds(String word, int max) {
        IsoPlayer p = IsoPlayer.getInstance();
        int px = p == null ? 0 : (int) Math.floor(p.getX()), py = p == null ? 0 : (int) Math.floor(p.getY());
        int pz = p == null ? 0 : (int) Math.floor(p.getZ());
        double now = GameTime.getInstance().getWorldAgeHours();
        String w = word == null ? "" : word.toLowerCase(Locale.ROOT);
        HashMap<String, Object[]> best = new HashMap<>();
        for (Box b : boxes.values()) {
            for (int i = 0; i < b.types.length; i++) {
                if (!b.cats[i].toLowerCase(Locale.ROOT).contains(w)) continue;
                double d = Math.hypot(b.x + 0.5 - px, b.y + 0.5 - py) + Math.abs(b.z - pz) * 3;
                Object[] cur = best.get(b.types[i]);
                if (cur == null || d < (Double) cur[0]) best.put(b.types[i], new Object[]{d, b, i});
            }
        }
        List<Object[]> sorted = new ArrayList<>(best.values());
        sorted.sort((a, c) -> Double.compare((Double) a[0], (Double) c[0]));
        List<Object> out = new ArrayList<>();
        for (int k = 0; k < sorted.size() && k < max; k++) out.add(where((Box) sorted.get(k)[1], (Integer) sorted.get(k)[2], px, py, pz, now));
        return out;
    }

    /** For the map, when it changed: the buildings searched, where the car was left, and the recent way walked. */
    static synchronized void overlay() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (charId != null) {
            HashMap<String, int[]> seenIn = new HashMap<>();
            for (Box b : boxes.values()) {
                if (b.bx == Integer.MIN_VALUE || b.body || "floor".equals(b.type) || b.key.startsWith("car:")) continue;
                seenIn.computeIfAbsent(b.bx + "," + b.by, k -> new int[1])[0]++;
            }
            List<Object> bl = new ArrayList<>();
            for (Map.Entry<String, Bldg> e : buildings.entrySet()) {
                Bldg b = e.getValue();
                int[] s = seenIn.get(e.getKey());
                int seen = s == null ? 0 : s[0];
                double frac;
                if (b.total > 0) frac = Math.min(1, seen / (double) b.total);
                else frac = b.total == 0 && b.rooms.size() >= Math.max(1, b.roomCount) ? 1 : 0;
                List<Object> row = new ArrayList<>();
                row.add(b.x);
                row.add(b.y);
                row.add(b.w);
                row.add(b.h);
                row.add(Math.round(frac * 100) / 100.0);
                bl.add(row);
            }
            out.put("b", bl);
            if (carAt >= 0) {
                Map<String, Object> car = new LinkedHashMap<>();
                car.put("x", carX);
                car.put("y", carY);
                car.put("z", carZ);
                car.put("n", carName);
                out.put("car", car);
            }
            // the last stretch walked (the whole way goes to the recap, from the file)
            List<Object> tr = new ArrayList<>();
            int from = Math.max(0, trailN - 1200);
            for (int i = from; i < trailN; i++) tr.add(trail[i]);
            out.put("tr", tr);
        }
        String json = Json.write(out);
        if (json.equals(lastOverlay)) return;
        lastOverlay = json;
        KnoxServer.get().publish("memory", json);
    }

    // ------------------------------------------------------------------ the file

    /** The dev server's walks (java/dev: no save to read them from); null in the game. */
    static volatile java.util.function.Function<String, int[]> devTrail;

    /** The way a character walked, from their file (or from memory for the current one): [x, y, x, y, ...]. */
    static synchronized int[] trailOf(String id) {
        if (devTrail != null) return devTrail.apply(id);
        if (id != null && id.equals(charId)) return java.util.Arrays.copyOf(trail, trailN);
        File f = id == null ? null : fileFor(id);
        if (f == null || !f.isFile()) return null;
        try {
            Object o = Json.parse(read(f));
            if (!(o instanceof Map)) return null;
            Object t = ((Map<?, ?>) o).get("trail");
            if (!(t instanceof List)) return null;
            List<?> l = (List<?>) t;
            int[] out = new int[l.size() - l.size() % 2];
            for (int i = 0; i < out.length; i++) out[i] = ((Number) l.get(i)).intValue();
            return out;
        } catch (Throwable e) {
            failedOnce("read trail", e);
            return null;
        }
    }

    /**
     * Writes the memory now (every minute and a half when it changed, when the game saves, when the
     * character dies). The game thread only copies the lists; the file is made on a thread of its own
     * (a container's contents are replaced, never changed in place, so the copies stay whole).
     */
    static synchronized void saveNow() {
        savedAt = System.currentTimeMillis();
        if (charId == null || file == null || !dirty) return;
        dirty = false;
        final String id = charId;
        final File f = file;
        final List<Box> bs = new ArrayList<>(boxes.values());
        final List<Object> gs = new ArrayList<>();
        for (Bldg b : buildings.values()) {
            List<Object> row = new ArrayList<>();
            row.add(b.x); row.add(b.y); row.add(b.w); row.add(b.h); row.add(b.total); row.add(b.roomCount);
            row.add(new ArrayList<Object>(b.rooms)); row.add(Math.round(b.at * 100) / 100.0);
            gs.add(row);
        }
        final int[] tr = java.util.Arrays.copyOf(trail, trailN);
        final List<Object> car = new ArrayList<>();
        if (carAt >= 0) {
            car.add(carX); car.add(carY); car.add(carZ); car.add(carName); car.add(carAt);
        }
        Thread t = new Thread(() -> write(f, Json.write(snapshot(id, bs, gs, tr, car))), "knoxlink-memory");
        t.setDaemon(true);
        t.start();
    }

    private static Map<String, Object> snapshot(String id, List<Box> bs, List<Object> gl, int[] trail, List<Object> car) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", 1);
        m.put("id", id);
        List<Object> bl = new ArrayList<>();
        for (Box b : bs) {
            List<Object> row = new ArrayList<>();
            row.add(b.key); row.add(b.x); row.add(b.y); row.add(b.z); row.add(b.type); row.add(b.name);
            row.add(b.room == null ? "" : b.room);
            row.add(b.bx == Integer.MIN_VALUE ? null : b.bx); row.add(b.by);
            row.add(Math.round(b.at * 100) / 100.0); row.add(b.body ? 1 : 0);
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < b.types.length; i++) {
                List<Object> it = new ArrayList<>();
                it.add(b.types[i]); it.add(b.names[i]); it.add(b.cats[i]); it.add(b.counts[i]); it.add(b.icons[i]);
                items.add(it);
            }
            row.add(items);
            bl.add(row);
        }
        m.put("boxes", bl);
        m.put("bld", gl);
        List<Object> tr = new ArrayList<>(trail.length);
        for (int p : trail) tr.add(p);
        m.put("trail", tr);
        if (!car.isEmpty()) m.put("car", car);
        return m;
    }

    private static void write(File f, String json) {
        try {
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (GZIPOutputStream out = new GZIPOutputStream(new FileOutputStream(tmp))) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (f.exists() && !f.delete()) Log.info("memory: could not replace " + f);
            if (!tmp.renameTo(f)) Log.info("memory: could not write " + f);
        } catch (Throwable t) {
            failedOnce("write", t);
        }
    }

    static String read(File f) throws java.io.IOException {
        try (InputStream in = new GZIPInputStream(new FileInputStream(f))) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    private static int num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    private static void load(File f) {
        try {
            Object o = Json.parse(read(f));
            if (!(o instanceof Map)) return;
            Map<?, ?> m = (Map<?, ?>) o;
            if (m.get("boxes") instanceof List) {
                for (Object r : (List<?>) m.get("boxes")) {
                    List<?> row = (List<?>) r;
                    Box b = new Box();
                    b.key = (String) row.get(0);
                    b.x = num(row.get(1)); b.y = num(row.get(2)); b.z = num(row.get(3));
                    b.type = (String) row.get(4); b.name = (String) row.get(5);
                    String room = (String) row.get(6);
                    b.room = room == null || room.isEmpty() ? null : room;
                    b.bx = row.get(7) == null ? Integer.MIN_VALUE : num(row.get(7));
                    b.by = num(row.get(8));
                    b.at = ((Number) row.get(9)).doubleValue();
                    b.body = num(row.get(10)) == 1;
                    List<?> items = (List<?>) row.get(11);
                    int k = items.size();
                    b.types = new String[k]; b.names = new String[k]; b.cats = new String[k]; b.counts = new int[k]; b.icons = new String[k];
                    for (int i = 0; i < k; i++) {
                        List<?> it = (List<?>) items.get(i);
                        b.types[i] = (String) it.get(0); b.names[i] = (String) it.get(1);
                        b.cats[i] = (String) it.get(2); b.counts[i] = num(it.get(3));
                        b.icons[i] = it.size() > 4 && it.get(4) != null ? (String) it.get(4) : "";
                    }
                    boxes.put(b.key, b);
                }
            }
            if (m.get("bld") instanceof List) {
                for (Object r : (List<?>) m.get("bld")) {
                    List<?> row = (List<?>) r;
                    Bldg b = new Bldg();
                    b.x = num(row.get(0)); b.y = num(row.get(1)); b.w = num(row.get(2)); b.h = num(row.get(3));
                    b.total = num(row.get(4)); b.roomCount = num(row.get(5));
                    for (Object id : (List<?>) row.get(6)) b.rooms.add(String.valueOf(id));
                    b.at = ((Number) row.get(7)).doubleValue();
                    buildings.put(b.x + "," + b.y, b);
                }
            }
            if (m.get("trail") instanceof List) {
                List<?> l = (List<?>) m.get("trail");
                int n = l.size() - l.size() % 2;
                trail = new int[Math.max(2048, n + 256)];
                for (int i = 0; i < n; i++) trail[i] = num(l.get(i));
                trailN = n;
            }
            if (m.get("car") instanceof List) {
                List<?> c = (List<?>) m.get("car");
                carX = num(c.get(0)); carY = num(c.get(1)); carZ = num(c.get(2));
                carName = (String) c.get(3);
                carAt = ((Number) c.get(4)).doubleValue();
            }
            Log.info("memory: " + boxes.size() + " containers, " + buildings.size() + " buildings, "
                    + trailN / 2 + " steps for this character");
        } catch (Throwable t) {
            failedOnce("read " + f.getName(), t);
        }
    }
}
