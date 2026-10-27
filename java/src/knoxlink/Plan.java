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
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    int k = j * n + i;
                    IsoGridSquare sq = sqs[k];
                    if (sq == null) continue;
                    boolean north = seen[k] || (j > 0 && seen[k - n]);
                    boolean west = seen[k] || (i > 0 && seen[k - 1]);
                    if (!seen[k] && !north && !west) continue;
                    PropertyContainer pc = sq.getProperties();
                    if (seen[k]) out[k * 3] = (byte) square(sq, pc, pn, everything);
                    if (north) out[k * 3 + 1] = (byte) edge(sq, pc, true);
                    if (west) out[k * 3 + 2] = (byte) edge(sq, pc, false);
                }
            }
            String c = Base64.getEncoder().encodeToString(out);
            String json = "{\"x\":" + x0 + ",\"y\":" + y0 + ",\"z\":" + pz + ",\"w\":" + n + ",\"h\":" + n + ",\"c\":\"" + c + "\"}";
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
