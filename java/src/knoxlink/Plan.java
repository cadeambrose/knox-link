package knoxlink;

import java.util.Base64;

import zombie.characters.IsoPlayer;
import zombie.core.properties.PropertyContainer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.IsoCurtain;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWindow;

/**
 * The floor plan around the character, for the map zoomed all the way in: the walls, doors,
 * windows, stairs and furniture of the squares the character has seen on their floor, and
 * which of them are in view right now. Made for Project Viewpoint's first person, where the
 * game's own overview is gone.
 *
 * "plan" (twice a second while a screen shows it, sent when it changed):
 *   x, y, z, w, h  the area (world squares; x, y its north-west corner), the character's floor
 *   c              base64, 3 bytes per square, row by row from the north-west:
 *     byte 0  1 seen, 2 in view now, 4 floor, 8 indoors, 16 stairs, 32 furniture (can't walk through),
 *             64 storage, 128 tree
 *     byte 1  the square's north edge, byte 2 its west edge:
 *             low 4 bits: 0 nothing, 1 wall, 2 door, 3 open door, 4 window, 5 open window,
 *                         6 broken window, 7 fence or railing, 8 doorway, 9 empty window frame
 *             +16 barricaded, +32 curtains drawn
 *             An edge is only there when the character has seen a square on either side of it.
 *   fl, f          the floors, to draw with the game's own (/api/art/floor?n=): fl = their sprite names,
 *                  f = base64, a byte per square: 0 none, k = fl[k - 1] (a rug counts as the floor)
 *   o              base64, a byte per square: what stands there, for a plan to draw: low 5 bits the kind
 *                  (FURNITURE below, 31 something else in the way), bits 5-6 the way it faces (N, E, S, W)
 *   v              the cars in sight: [[x, y (centre), heading (degrees, 0 east, 90 south), length,
 *                  width, "rrggbb"], ...]
 *
 * "Seen" is the game's own (the squares its top-down view keeps drawing once you've looked at
 * them), "in view" its line of sight; with immersion off everything loaded around you counts.
 */
final class Plan {
    static final int R = 24;               // squares from the character to the edge of the plan
    private static final int BLOCK = 8;    // the area moves in steps of this, not with every step
    static final int SIZE = 2 * R + BLOCK;

    static final int NONE = 0, WALL = 1, DOOR = 2, DOOR_OPEN = 3, WINDOW = 4, WINDOW_OPEN = 5, WINDOW_BROKEN = 6,
            FENCE = 7, DOORWAY = 8, FRAME = 9, BARRICADED = 16, CURTAINS = 32;

    // what stands on a square ("o"), from its name in the game (the moveables' "Blue_Bed", "Fancy_Toilet",
    // "Bedroom_Drawers"... and the sprite's): the first word found wins, so "drawers" is never a bed
    static final int BED = 1, SOFA = 2, CHAIR = 3, TABLE = 4, COUNTER = 5, SINK = 6, TOILET = 7, BATH = 8, STOVE = 9,
            FRIDGE = 10, SHELF = 11, WARDROBE = 12, WASHER = 13, TV = 14, PLANT = 15, BOX = 16, OTHER = 31;
    private static final Object[][] FURNITURE = {
            {"drawer", WARDROBE}, {"wardrobe", WARDROBE}, {"dresser", WARDROBE}, {"locker", WARDROBE}, {"closet", WARDROBE},
            {"cabinet", WARDROBE}, {"dishwasher", WASHER}, {"washer", WASHER}, {"dryer", WASHER}, {"laundry", WASHER},
            {"sink", SINK}, {"toilet", TOILET}, {"urinal", TOILET}, {"bath", BATH}, {"shower", BATH}, {"tub", BATH},
            {"fridge", FRIDGE}, {"freezer", FRIDGE}, {"refrigerat", FRIDGE}, {"cooled", FRIDGE},
            {"oven", STOVE}, {"stove", STOVE}, {"microwave", STOVE}, {"barbecue", STOVE}, {"fryer", STOVE}, {"grill", STOVE},
            {"plant", PLANT}, {"fern", PLANT}, {"flower", PLANT}, {"bonsai", PLANT}, {"cactus", PLANT},
            {"couch", SOFA}, {"sofa", SOFA}, {"bed", BED}, {"chair", CHAIR}, {"stool", CHAIR}, {"seat", CHAIR},
            {"bench", CHAIR}, {"pew", CHAIR}, {"shel", SHELF}, {"bookcase", SHELF}, {"rack", SHELF}, {"display", SHELF},
            {"counter", COUNTER}, {"register", COUNTER}, {"table", TABLE}, {"desk", TABLE}, {"television", TV},
            {"computer", TV}, {"_tv", TV}, {"crate", BOX}, {"box", BOX}, {"bin", BOX}, {"garbage", BOX}, {"dumpster", BOX},
            {"trash", BOX},
    };

    private static String last;
    private static boolean broken;

    private Plan() {}

    /** Publishes "plan" if it changed. everything: immersion is off. */
    static void publish(boolean everything) {
        if (broken) return;
        try {
            IsoPlayer p = IsoPlayer.getInstance();
            IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.getCell();
            if (p == null || cell == null) return;
            int pn = p.getPlayerNum();
            int px = (int) Math.floor(p.getX()), py = (int) Math.floor(p.getY()), pz = (int) Math.floor(p.getZ());
            int x0 = Math.floorDiv(px - R, BLOCK) * BLOCK, y0 = Math.floorDiv(py - R, BLOCK) * BLOCK;
            int n = SIZE;
            IsoGridSquare[] sqs = new IsoGridSquare[n * n];
            boolean[] seen = new boolean[n * n];
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    IsoGridSquare sq = cell.getGridSquare(x0 + i, y0 + j, pz);
                    sqs[j * n + i] = sq;
                    seen[j * n + i] = sq != null && (everything || sq.isSeen(pn));
                }
            }
            byte[] out = new byte[n * n * 3];
            byte[] floors = new byte[n * n], things = new byte[n * n];
            java.util.LinkedHashMap<String, Integer> names = new java.util.LinkedHashMap<>();
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    int k = j * n + i;
                    IsoGridSquare sq = sqs[k];
                    if (sq == null) continue;
                    boolean north = seen[k] || (j > 0 && seen[k - n]);
                    boolean west = seen[k] || (i > 0 && seen[k - 1]);
                    if (!seen[k] && !north && !west) continue;
                    PropertyContainer pc = sq.getProperties();
                    if (seen[k]) {
                        out[k * 3] = (byte) square(sq, pc, pn, everything);
                        String fl = floorOf(sq);
                        if (fl != null) {
                            Integer idx = names.get(fl);
                            if (idx == null && names.size() < 255) names.put(fl, idx = names.size() + 1);
                            if (idx != null) floors[k] = (byte) (int) idx;
                        }
                        things[k] = (byte) thing(sq, pc);
                    }
                    if (north) out[k * 3 + 1] = (byte) edge(sq, pc, true);
                    if (west) out[k * 3 + 2] = (byte) edge(sq, pc, false);
                }
            }
            String c = Base64.getEncoder().encodeToString(out);
            StringBuilder sb = new StringBuilder(c.length() * 2 + 512);
            sb.append("{\"x\":").append(x0).append(",\"y\":").append(y0).append(",\"z\":").append(pz)
                    .append(",\"w\":").append(n).append(",\"h\":").append(n).append(",\"c\":\"").append(c).append('"');
            sb.append(",\"fl\":[");
            boolean first = true;
            for (String name : names.keySet()) {
                if (!first) sb.append(',');
                first = false;
                Json.string(sb, name);
            }
            sb.append("],\"f\":\"").append(Base64.getEncoder().encodeToString(floors)).append('"');
            sb.append(",\"o\":\"").append(Base64.getEncoder().encodeToString(things)).append('"');
            sb.append(",\"v\":").append(cars(cell, x0, y0, n, pz, pn, everything)).append('}');
            String json = sb.toString();
            if (json.equals(last)) return;
            last = json;
            KnoxServer.get().publish("plan", json);
        } catch (Throwable t) {
            broken = true;
            Log.error("floor plan: off for this session", t);
        }
    }

    private static int square(IsoGridSquare sq, PropertyContainer pc, int pn, boolean everything) {
        int b = 1;
        if (everything || sq.isCanSee(pn)) b |= 2;
        if (pc != null && pc.has(IsoFlagType.solidfloor)) b |= 4;
        if (sq.getRoom() != null) b |= 8;
        if (sq.HasStairs()) b |= 16;
        if (sq.getTree() != null) {
            b |= 128;
        } else if (pc != null && (pc.has(IsoFlagType.solid) || pc.has(IsoFlagType.solidtrans))) {
            b |= 32;
        }
        for (int i = 0; i < sq.getObjects().size(); i++) {
            IsoObject o = sq.getObjects().get(i);
            if (o != null && o.getContainerCount() > 0) {
                b |= 64;
                break;
            }
        }
        return b;
    }

    private static String spriteName(IsoObject o) {
        zombie.iso.sprite.IsoSprite s = o == null ? null : o.getSprite();
        return s == null ? null : s.getName();
    }

    /** The square's floor as the game draws it: a rug on it, else the floor itself (when the packs have it). */
    private static String floorOf(IsoGridSquare sq) {
        for (int i = 0; i < sq.getObjects().size(); i++) {
            String n = spriteName(sq.getObjects().get(i));
            if (n != null && n.startsWith("floors_rugs") && FloorArt.has(n)) return n;
        }
        String n = spriteName(sq.getFloor());
        return n != null && FloorArt.has(n) ? n : null;
    }

    /** What stands on the square, for the plan: kind | facing << 5, or 0. */
    private static int thing(IsoGridSquare sq, PropertyContainer square) {
        IsoObject floor = sq.getFloor();
        for (int i = 0; i < sq.getObjects().size(); i++) {
            IsoObject o = sq.getObjects().get(i);
            if (o == null || o == floor || o instanceof IsoDoor || o instanceof IsoWindow || o instanceof IsoCurtain
                    || o instanceof zombie.iso.objects.IsoWorldInventoryObject || o instanceof zombie.iso.objects.IsoTree) continue;
            String sprite = spriteName(o);
            if (sprite == null || sprite.startsWith("walls_") || sprite.startsWith("fixtures_doors") || sprite.startsWith("fixtures_windows")
                    || sprite.startsWith("floors_") || sprite.startsWith("blends_") || sprite.startsWith("lighting_")) continue;
            PropertyContainer p = o.getProperties();
            String custom = p == null ? null : p.get("CustomName");
            String group = p == null ? null : p.get("GroupName");
            String words = ((group == null ? "" : group + "_") + (custom == null ? "" : custom) + " " + sprite).toLowerCase(java.util.Locale.ROOT);
            for (Object[] f : FURNITURE) {
                if (words.contains((String) f[0])) {
                    String facing = p == null ? null : p.get("Facing");
                    int face = "E".equals(facing) ? 1 : "S".equals(facing) ? 2 : "W".equals(facing) ? 3 : 0;
                    return (Integer) f[1] | face << 5;
                }
            }
        }
        return square != null && (square.has(IsoFlagType.solid) || square.has(IsoFlagType.solidtrans)) && sq.getTree() == null ? OTHER : 0;
    }

    /** The cars on the character's floor in the area that the character has seen: [[x, y, heading, length, width, "rrggbb"], ...]. */
    private static String cars(IsoCell cell, int x0, int y0, int n, int z, int pn, boolean everything) {
        StringBuilder sb = new StringBuilder("[");
        java.util.Set<zombie.vehicles.BaseVehicle> all = cell.getVehicles();
        if (all == null) return "[]";
        org.joml.Vector3f fwd = new org.joml.Vector3f();
        for (zombie.vehicles.BaseVehicle v : all) {
            if (v == null) continue;
            float x = v.getX(), y = v.getY();
            if (x < x0 || y < y0 || x >= x0 + n || y >= y0 + n || (int) Math.floor(v.getZ()) != z) continue;
            IsoGridSquare sq = v.getSquare();
            if (!everything && (sq == null || !sq.isSeen(pn))) continue;
            v.getForwardVector(fwd);
            double heading = Math.toDegrees(Math.atan2(fwd.z, fwd.x));
            org.joml.Vector3f ext = v.getScript() == null ? null : v.getScript().getExtents();
            float len = ext == null ? 4.5f : ext.z, wid = ext == null ? 1.9f : ext.x;
            int rgb = hsv(v.getColorHue(), v.getColorSaturation(), v.getColorValue());
            if (sb.length() > 1) sb.append(',');
            sb.append('[').append(Math.round(x * 10) / 10.0).append(',').append(Math.round(y * 10) / 10.0).append(',')
                    .append(Math.round(heading)).append(',').append(Math.round(len * 10) / 10.0).append(',')
                    .append(Math.round(wid * 10) / 10.0).append(",\"").append(String.format("%06x", rgb)).append("\"]");
        }
        return sb.append(']').toString();
    }

    private static int hsv(float h, float s, float v) {
        h = ((h % 1) + 1) % 1;
        float c = v * s, x = c * (1 - Math.abs((h * 6) % 2 - 1)), m = v - c;
        float r, g, b;
        int sector = (int) (h * 6);
        switch (sector) {
            case 0: r = c; g = x; b = 0; break;
            case 1: r = x; g = c; b = 0; break;
            case 2: r = 0; g = c; b = x; break;
            case 3: r = 0; g = x; b = c; break;
            case 4: r = x; g = 0; b = c; break;
            default: r = c; g = 0; b = x; break;
        }
        return Math.round((r + m) * 255) << 16 | Math.round((g + m) * 255) << 8 | Math.round((b + m) * 255);
    }

    /** What's on the square's north (or west) edge. */
    private static int edge(IsoGridSquare sq, PropertyContainer pc, boolean north) {
        int kind = NONE, extra = 0;
        if (pc != null) {
            if (north ? pc.has(IsoFlagType.WallN) || pc.has(IsoFlagType.WallNW) || pc.has(IsoFlagType.WallNTrans)
                    : pc.has(IsoFlagType.WallW) || pc.has(IsoFlagType.WallNW) || pc.has(IsoFlagType.WallWTrans)) kind = WALL;
            if (north ? pc.has(IsoFlagType.DoorWallN) : pc.has(IsoFlagType.DoorWallW)) kind = DOORWAY;
            if (north ? pc.has(IsoFlagType.WindowN) || pc.has(IsoFlagType.windowN) : pc.has(IsoFlagType.WindowW) || pc.has(IsoFlagType.windowW)) kind = FRAME;
            if (kind == NONE && (north ? pc.has(IsoFlagType.HoppableN) || pc.has(IsoFlagType.TallHoppableN) || pc.has(IsoFlagType.collideN)
                    : pc.has(IsoFlagType.HoppableW) || pc.has(IsoFlagType.TallHoppableW) || pc.has(IsoFlagType.collideW))) kind = FENCE;
        }
        for (int i = 0; i < sq.getObjects().size(); i++) {
            IsoObject o = sq.getObjects().get(i);
            if (o instanceof IsoDoor) {
                IsoDoor d = (IsoDoor) o;
                if (d.getNorth() != north) continue;
                kind = d.IsOpen() ? DOOR_OPEN : DOOR;
                if (d.isBarricaded()) extra |= BARRICADED;
            } else if (o instanceof IsoWindow) {
                IsoWindow w = (IsoWindow) o;
                if (w.getNorth() != north) continue;
                kind = w.isSmashed() || w.isDestroyed() || w.isGlassRemoved() ? WINDOW_BROKEN : w.IsOpen() ? WINDOW_OPEN : WINDOW;
                if (w.isBarricaded()) extra |= BARRICADED;
                IsoCurtain c = w.HasCurtains();
                if (c != null && !c.IsOpen()) extra |= CURTAINS;
            } else if (o instanceof IsoThumpable) {
                IsoThumpable t = (IsoThumpable) o;
                if (t.getNorth() != north) continue;
                if (t.isDoor()) kind = t.IsOpen() ? DOOR_OPEN : DOOR;
                else if (t.isDoorFrame()) kind = DOORWAY;
                else if (t.isHoppable()) kind = FENCE;
                if (t.isBarricaded()) extra |= BARRICADED;
            }
        }
        return kind == NONE ? 0 : kind | extra;
    }
}
