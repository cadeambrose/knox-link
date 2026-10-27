package io.github.knoxlink.companion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The world map: the game's own vector map (converted by the mod), drawn in the style of the
 * in-game paper map, with a tile cache filled on a background thread so panning stays smooth.
 */
final class MapView extends View {
    interface Listener {
        void onLongPress(int x, int y);

        void onTapBuilding(String name);

        void onFollowChanged(boolean follow);

        /**
         * A tap on one of the player's map marks ("mark"), another survivor ("team"), the base ("base"),
         * the car left behind ("car") or a "Where did I see...?" find ("seen").
         */
        void onTapThing(String kind, JSONObject thing);

        /** Zoomed in far enough for the floor plan (or out of it again): the screen asks for it, or stops. */
        void onPlanWanted(boolean on);
    }

    // feature kinds (must match MapData.java on the server)
    static final int WATER = 1, FOREST = 2, PRIMARY = 3, SECONDARY = 4, TERTIARY = 5, TRAIL = 6, RAIL = 7,
            RES = 10, RETAIL = 11, FOOD = 12, INDUSTRIAL = 13, COMMUNITY = 14, MEDICAL = 15, HOSPITALITY = 16, OTHER = 17;
    private static final int[] ORDER = {FOREST, WATER, TRAIL, TERTIARY, SECONDARY, PRIMARY, RAIL,
            RES, OTHER, INDUSTRIAL, RETAIL, FOOD, HOSPITALITY, COMMUNITY, MEDICAL};
    static final String[][] KIND_NAMES = {
            {"10", "Residential"}, {"11", "Shops & offices"}, {"12", "Restaurants & bars"}, {"13", "Industrial"},
            {"14", "Police, fire, schools"}, {"15", "Medical"}, {"16", "Hotels & motels"}, {"17", "Building"}};

    /** Map colours: "paper" is the game's own map style (ISMapDefinitions.lua), "dark" suits night use. */
    static final class Palette {
        final int land, outline, label, labelWater, halo, player, wp;
        final int[] kind = new int[32];
        final boolean paper;

        Palette(boolean paper) {
            this.paper = paper;
            if (paper) {
                land = 0xFFDBD7C0;
                kind[FOREST] = 0xFFBDC5A3; kind[WATER] = 0xFF3B8D95; kind[TRAIL] = 0xFFB97A57; kind[TERTIARY] = 0xFFAB9E8F;
                kind[SECONDARY] = 0xFF867D71; kind[PRIMARY] = 0xFF867D71; kind[RAIL] = 0xFFC8BFE7;
                kind[RES] = 0xFFD29E69; kind[OTHER] = 0xFFD29E69; kind[COMMUNITY] = 0xFF8B75EB; kind[HOSPITALITY] = 0xFF7FCEE1;
                kind[INDUSTRIAL] = 0xFF383635; kind[MEDICAL] = 0xFFE58097; kind[FOOD] = 0xFFF5E13C; kind[RETAIL] = 0xFFB8CD54;
                outline = 0x55463C32; label = 0xFF2B2620; labelWater = 0xFF1F4F55; halo = 0xCCE6E2CC;
                player = 0xFFD0461E; wp = 0xFFB0281E;
            } else {
                land = 0xFF191C21;
                kind[FOREST] = 0xFF1C2A21; kind[WATER] = 0xFF1F3A53; kind[TRAIL] = 0xFF41464E; kind[TERTIARY] = 0xFF565B64;
                kind[SECONDARY] = 0xFF6B717A; kind[PRIMARY] = 0xFFA8844F; kind[RAIL] = 0xFF6F7480;
                kind[RES] = 0xFF3A4049; kind[OTHER] = 0xFF464C55; kind[COMMUNITY] = 0xFF31837A; kind[HOSPITALITY] = 0xFFA3863A;
                kind[INDUSTRIAL] = 0xFF62558A; kind[MEDICAL] = 0xFFC2504A; kind[FOOD] = 0xFFAD6A34; kind[RETAIL] = 0xFF3E68A0;
                outline = 0x73000000; label = 0xFFD9DDE3; labelWater = 0xFF7FB2DC; halo = 0xD90D0F12;
                player = 0xFFF2A93B; wp = 0xFFF2A93B;
            }
        }
    }

    private static final class Data {
        int count;
        byte[] kind, geom;
        int[] fRing, rPt;
        float[] pts, bbox;
        HashMap<Integer, int[]> cells;
        int[] rank = new int[32];
        List<float[]> labelPos = new ArrayList<>();   // x, y, scale, rotation, water(0/1)
        List<String> labelText = new ArrayList<>();
    }

    private static final int TILE = 256;   // device pixels per cached tile
    private static final int MAX_TILES = 140;
    private static final int CELL = 300;

    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paperPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path markPath = new Path();
    private final RectF rect = new RectF();
    private final Rect src = new Rect();

    private final LinkedHashMap<String, Tile> tiles = new LinkedHashMap<>(64, 0.75f, true);
    private final HandlerThread worker = new HandlerThread("knoxlink-map");
    private final Handler workerHandler;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile Data data;
    private volatile Palette pal = new Palette(true);
    private int generation;   // bumps when the palette changes: tiles from older generations are redrawn

    private float cx = 10650, cy = 9400, scale;   // view centre (world) and device pixels per world square
    private boolean follow = true;
    private float[] player, shown;   // x, y, dir
    private int[] waypoint;
    private boolean labels = true;
    private Listener listener;
    private final float density;
    private Bitmap paperTexture;

    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;

    // fog of war: what the character has not explored (the game's WorldMapVisited, via the mod)
    private Bitmap fog;
    private int fogUnit, fogW, fogH;
    private byte[] fogBits;
    private final Paint fogPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private boolean dark;

    /** Sets the explored-area mask (null = everything visible). */
    void setFog(byte[] raw) {
        if (raw == null) {
            fog = null;
            fogBits = null;
            invalidate();
            return;
        }
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (b.get() != 'K' || b.get() != 'L' || b.get() != 'V' || b.get() != '1') return;
        int unit = b.getInt(), w = b.getInt(), h = b.getInt();
        byte[] bits = new byte[(w * h + 7) / 8];
        b.get(bits);
        fogUnit = unit;
        fogW = w;
        fogH = h;
        fogBits = bits;
        buildFog();
    }

    /** The fog image in the current palette's "unvisited" colour (the game's paper x 0.915). */
    private void buildFog() {
        if (fogBits == null) return;
        int color = pal.paper ? 0xFFC8C5B0 : 0xFF0F1114;
        int[] px = new int[fogW * fogH];
        for (int i = 0; i < px.length; i++) {
            boolean seen = (fogBits[i >> 3] & (1 << (i & 7))) != 0;
            px[i] = seen ? 0 : color;
        }
        fog = Bitmap.createBitmap(px, fogW, fogH, Bitmap.Config.ARGB_8888);
        invalidate();
    }

    /** The map can't be read here (dark, and the world needs light for maps). */
    void setDark(boolean on) {
        if (dark == on) return;
        dark = on;
        invalidate();
    }

    private boolean fogged(float wx, float wy) {
        if (fogBits == null || fogUnit <= 0) return false;
        int ux = (int) (wx / fogUnit), uy = (int) (wy / fogUnit);
        if (ux < 0 || uy < 0 || ux >= fogW || uy >= fogH) return true;
        int i = uy * fogW + ux;
        return (fogBits[i >> 3] & (1 << (i & 7))) == 0;
    }

    private static final class Tile {
        final int level, tx, ty;
        Bitmap bitmap;
        int gen = -1;
        boolean queued;

        Tile(int level, int tx, int ty) {
            this.level = level;
            this.tx = tx;
            this.ty = ty;
        }
    }

    MapView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        scale = 0.7f * density;
        worker.start();
        workerHandler = new Handler(worker.getLooper());
        labelPaint.setTextAlign(Paint.Align.CENTER);
        haloPaint.setTextAlign(Paint.Align.CENTER);
        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStyle(Paint.Style.STROKE);

        scaler = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomAt(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaler.isInProgress()) return true;
                if (follow) setFollow(false);
                cx += dx / scale;
                cy += dy / scale;
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                zoomAt(2f, e.getX(), e.getY());
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (scaler.isInProgress()) return;
                float[] w = toWorld(e.getX(), e.getY());
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (listener != null) listener.onLongPress((int) Math.floor(w[0]), (int) Math.floor(w[1]));
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener == null) return true;
                Object[] thing = dark ? null : thingAt(e.getX(), e.getY());
                if (thing != null) {
                    listener.onTapThing((String) thing[0], (JSONObject) thing[1]);
                    return true;
                }
                float[] w = toWorld(e.getX(), e.getY());
                String b = fogged(w[0], w[1]) || dark ? null : buildingAt(w[0], w[1]);
                if (b != null) {
                    // what the character remembers of it
                    int s = searchedAt(w[0], w[1]);
                    if (s >= 80) b += " · searched";
                    else if (s > 0) b += " · " + s + "% searched";
                    else if (s == 0) b += " · been inside";
                }
                listener.onTapBuilding(b);
                return true;
            }
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setPaper(Bitmap texture) {
        paperTexture = texture;
        if (texture != null) {
            paperPaint.setShader(new BitmapShader(texture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                // a true multiply blend; PorterDuff MULTIPLY ignores alpha and would blacken the map
                paperPaint.setBlendMode(android.graphics.BlendMode.MULTIPLY);
                paperPaint.setAlpha(150);
            } else {
                paperPaint.setAlpha(45);   // older Android: a light grain on top instead
            }
        }
        invalidate();
    }

    void setStyle(boolean paper) {
        if (pal.paper == paper) return;
        pal = new Palette(paper);
        generation++;
        buildFog();
        invalidate();
    }

    boolean isPaper() {
        return pal.paper;
    }

    int colorOf(int kind) {
        return pal.kind[kind];
    }

    void setLabels(boolean on) {
        labels = on;
        invalidate();
    }

    void setFollow(boolean on) {
        follow = on;
        if (listener != null) listener.onFollowChanged(on);
        invalidate();
    }

    boolean isFollowing() {
        return follow;
    }

    void zoom(float f) {
        zoomAt(f, getWidth() / 2f, getHeight() / 2f);
    }

    void setPlayer(double x, double y, double dir) {
        if (player == null) {
            player = new float[3];
            cx = (float) x;
            cy = (float) y;
        }
        player[0] = (float) x;
        player[1] = (float) y;
        player[2] = (float) dir;
        if (shown == null) shown = player.clone();
        postInvalidateOnAnimation();
    }

    void setWaypoint(JSONObject wp) {
        waypoint = wp == null ? null : new int[]{wp.optInt("x"), wp.optInt("y")};
        invalidate();
    }

    // ------------------------------------------------------------------ marks, other survivors, base

    private JSONArray marks = new JSONArray(), team = new JSONArray();
    private JSONObject base;
    private final Map<String, Bitmap> symbolArt = new HashMap<>();
    private final Map<Integer, android.graphics.ColorFilter> tints = new HashMap<>();
    private final Paint symbolPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint notePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final int TEAM = 0xFF2A9D8F;

    /** The player's own marks on the game's world map: [{sym|text, x, y, c, rot}]. */
    void setMarks(JSONArray list) {
        marks = list == null ? new JSONArray() : list;
        invalidate();
    }

    /** The picture of a map symbol (media/ui/LootableMaps), drawn in the mark's ink colour. */
    void setSymbolArt(String id, Bitmap b) {
        if (b == null) return;
        symbolArt.put(id, b);
        invalidate();
    }

    void setTeam(JSONArray list) {
        team = list == null ? new JSONArray() : list;
        invalidate();
    }

    void setBase(JSONObject b) {
        base = b;
        invalidate();
    }

    private float markSize() {
        return 22 * density * Math.max(0.75f, Math.min(1.45f, (float) Math.sqrt(scale / density)));
    }

    private android.graphics.ColorFilter tint(int color) {
        android.graphics.ColorFilter f = tints.get(color);
        if (f == null) {
            f = new android.graphics.PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
            tints.put(color, f);
        }
        return f;
    }

    private static int inkColor(JSONObject m) {
        try {
            return 0xFF000000 | Integer.parseInt(m.optString("c", "212121"), 16);
        } catch (NumberFormatException e) {
            return 0xFF212121;
        }
    }

    private void drawMarks(Canvas canvas, Palette p) {
        float size = markSize();
        float zoomFit = Math.max(0.75f, Math.min(1.3f, (float) Math.sqrt(scale / density)));
        for (int i = 0; i < marks.length(); i++) {
            JSONObject m = marks.optJSONObject(i);
            if (m == null) continue;
            float x = sx((float) m.optDouble("x")), y = sy((float) m.optDouble("y"));
            if (x < -60 || y < -60 || x > getWidth() + 60 || y > getHeight() + 60) continue;
            int color = inkColor(m);
            // on the dark map, ink would vanish: draw it light
            if (!p.paper) color = Theme.mix(color, 0xFFF2EEE4, 0.75f);
            float rot = (float) m.optDouble("rot", 0);
            canvas.save();
            canvas.translate(x, y);
            if (rot != 0) canvas.rotate(rot);
            if (m.has("sym")) {
                Bitmap b = symbolArt.get(m.optString("sym"));
                if (b != null) {
                    symbolPaint.setColorFilter(tint(color));
                    rect.set(-size / 2, -size / 2, size / 2, size / 2);
                    canvas.drawBitmap(b, null, rect, symbolPaint);
                } else {
                    markPaint.setStyle(Paint.Style.STROKE);
                    markPaint.setStrokeWidth(2.5f * density);
                    markPaint.setColor(color);
                    canvas.drawCircle(0, 0, size * 0.35f, markPaint);
                }
            } else {
                // a handwritten note, like the game's own map notes
                notePaint.setTextAlign(Paint.Align.CENTER);
                notePaint.setTypeface(Typeface.create("casual", Typeface.BOLD));
                notePaint.setTextSize(14 * density * zoomFit);
                String text = m.optString("text");
                notePaint.setStyle(Paint.Style.STROKE);
                notePaint.setStrokeWidth(3 * density);
                notePaint.setColor(p.halo);
                canvas.drawText(text, 0, notePaint.getTextSize() * 0.35f, notePaint);
                notePaint.setStyle(Paint.Style.FILL);
                notePaint.setColor(color);
                canvas.drawText(text, 0, notePaint.getTextSize() * 0.35f, notePaint);
            }
            canvas.restore();
        }
    }

    private void drawBase(Canvas canvas, Palette p) {
        if (base == null || !base.has("x")) return;
        float x = sx((float) base.optDouble("x") + 0.5f), y = sy((float) base.optDouble("y") + 0.5f);
        float r = 10 * density;
        markPath.rewind();
        markPath.moveTo(x, y - r * 1.15f);
        markPath.lineTo(x + r, y - r * 0.2f);
        markPath.lineTo(x + r * 0.72f, y - r * 0.2f);
        markPath.lineTo(x + r * 0.72f, y + r * 0.85f);
        markPath.lineTo(x - r * 0.72f, y + r * 0.85f);
        markPath.lineTo(x - r * 0.72f, y - r * 0.2f);
        markPath.lineTo(x - r, y - r * 0.2f);
        markPath.close();
        markPaint.setStyle(Paint.Style.STROKE);
        markPaint.setStrokeJoin(Paint.Join.ROUND);
        markPaint.setStrokeWidth(4 * density);
        markPaint.setColor(p.paper ? 0xFFFFFFFF : 0xFF0D0F12);
        canvas.drawPath(markPath, markPaint);
        markPaint.setStyle(Paint.Style.FILL);
        markPaint.setColor(0xFF3E7C47);
        canvas.drawPath(markPath, markPaint);
        markPaint.setColor(0xFFFFFFFF);
        rect.set(x - r * 0.22f, y + r * 0.2f, x + r * 0.22f, y + r * 0.85f);
        canvas.drawRect(rect, markPaint);
        String name = base.optString("n", "");
        if (scale / density > 0.45f) drawTag(canvas, p, name.isEmpty() ? "Base" : name, x, y + r * 2.1f, 0xFF3E7C47);
    }

    private void drawTeam(Canvas canvas, Palette p) {
        float r = 7 * density;
        for (int i = 0; i < team.length(); i++) {
            JSONObject m = team.optJSONObject(i);
            if (m == null) continue;
            float x = sx((float) m.optDouble("x")), y = sy((float) m.optDouble("y"));
            if (x < -80 || y < -40 || x > getWidth() + 80 || y > getHeight() + 40) continue;
            markPaint.setStyle(Paint.Style.FILL);
            markPaint.setColor(0xFFFFFFFF);
            canvas.drawCircle(x, y, r + 2.5f * density, markPaint);
            markPaint.setColor(TEAM);
            canvas.drawCircle(x, y, r, markPaint);
            String n = m.optString("n");
            int sp = n.indexOf(' ');
            drawTag(canvas, p, sp > 0 ? n.substring(0, sp) : n, x, y + r + 13 * density, TEAM);
        }
    }

    /** A small name under a marker, with a halo so it reads on any ground. */
    private void drawTag(Canvas canvas, Palette p, String s, float x, float y, int color) {
        if (s.isEmpty()) return;
        notePaint.setTextAlign(Paint.Align.CENTER);
        notePaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        notePaint.setTextSize(11.5f * density);
        notePaint.setStyle(Paint.Style.STROKE);
        notePaint.setStrokeWidth(3.5f * density);
        notePaint.setColor(p.halo);
        canvas.drawText(s, x, y, notePaint);
        notePaint.setStyle(Paint.Style.FILL);
        notePaint.setColor(p.paper ? Theme.mix(color, 0xFF000000, 0.25f) : Theme.mix(color, 0xFFFFFFFF, 0.35f));
        canvas.drawText(s, x, y, notePaint);
    }

    /** The mark, survivor or base under a tap, or null. */
    private Object[] thingAt(float px, float py) {
        float best = 22 * density;
        Object[] hit = null;
        for (int i = 0; i < team.length(); i++) {
            JSONObject m = team.optJSONObject(i);
            if (m == null) continue;
            float d = (float) Math.hypot(sx((float) m.optDouble("x")) - px, sy((float) m.optDouble("y")) - py);
            if (d < best) { best = d; hit = new Object[]{"team", m}; }
        }
        if (base != null && base.has("x")) {
            float d = (float) Math.hypot(sx((float) base.optDouble("x") + 0.5f) - px, sy((float) base.optDouble("y") + 0.5f) - py);
            if (d < best) { best = d; hit = new Object[]{"base", base}; }
        }
        if (car != null && scale / density >= 0.3f) {
            float d = (float) Math.hypot(sx((float) car.optDouble("x") + 0.5f) - px, sy((float) car.optDouble("y") + 0.5f) - py);
            if (d < best) { best = d; hit = new Object[]{"car", car}; }
        }
        for (int i = 0; i < pins.length(); i++) {
            JSONObject o = pins.optJSONObject(i);
            if (o == null) continue;
            float d = (float) Math.hypot(sx((float) o.optDouble("x") + 0.5f) - px, sy((float) o.optDouble("y") + 0.5f) - py);
            if (d < best) { best = d; hit = new Object[]{"seen", o}; }
        }
        float reach = Math.max(best, markSize() * 0.7f);
        float zoomFit = Math.max(0.75f, Math.min(1.3f, (float) Math.sqrt(scale / density)));
        for (int i = 0; i < marks.length(); i++) {
            JSONObject m = marks.optJSONObject(i);
            if (m == null) continue;
            float mx = sx((float) m.optDouble("x")), my = sy((float) m.optDouble("y"));
            float d;
            if (m.has("text")) {
                // a note: inside its own text box (as drawn), with a little room around it
                notePaint.setTypeface(Typeface.create("casual", Typeface.BOLD));
                notePaint.setTextSize(14 * density * zoomFit);
                float hw = notePaint.measureText(m.optString("text")) / 2 + 6 * density;
                float hh = notePaint.getTextSize() / 2 + 6 * density;
                float dx = Math.max(0, Math.abs(px - mx) - hw), dy = Math.max(0, Math.abs(py - my) - hh);
                if (dx > 0 || dy > 0) continue;
                d = 18 * density;   // inside the note; a survivor, the base or a symbol right under the finger still wins
            } else {
                d = (float) Math.hypot(mx - px, my - py);
                if (d >= reach) continue;
            }
            if (d < best) { best = d; hit = new Object[]{"mark", m}; }
        }
        return hit;
    }

    // ------------------------------------------------------------------ the floor plan

    /** Device pixels per square (over the density) from which the floor plan shows, and the most zoom there is. */
    static final float PLAN_ZOOM = 5f, MAX_ZOOM = 44f;
    // edges, as Plan.java on the server sends them
    private static final int E_WALL = 1, E_DOOR = 2, E_DOOR_OPEN = 3, E_WINDOW = 4, E_WINDOW_OPEN = 5, E_WINDOW_BROKEN = 6,
            E_FENCE = 7, E_DOORWAY = 8, E_FRAME = 9;
    // what stands on a square ("o": low 5 bits; bits 5-6 the way it faces, N E S W)
    private static final int BED = 1, SOFA = 2, CHAIR = 3, TABLE = 4, COUNTER = 5, SINK = 6, TOILET = 7, BATH = 8, STOVE = 9,
            FRIDGE = 10, SHELF = 11, WARDROBE = 12, WASHER = 13, TV = 14, PLANT = 15, BOX = 16;
    private static final int WALL_INK = 0xFF2A2622, DOOR_WOOD = 0xFF8A5A2B, GLASS = 0xF2B2D4E8;
    private byte[] plan, planFloors, planThings;
    private String[] planFloorNames = new String[0];
    private int[] pieces = new int[0];   // per piece: i, j, w, h, kind, face
    private JSONArray planCars = new JSONArray();
    private int planX, planY, planW, planH;
    private boolean planWanted;
    private ArtCache art;
    private final Paint planPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint floorPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path planPath = new Path();
    private final RectF box = new RectF();
    private float[] zeds = new float[0];   // per zombie: x, y (world), kind (0 seen, 1 heard, 2 lost), attacking (1)

    /** Where the game's own floors come from (the server turns them from diamonds back into squares). */
    void setArt(ArtCache a) {
        art = a;
    }

    /** The floor plan around the character ("plan": see Plan.java): squares, floors, furniture and cars. */
    void setPlan(JSONObject d) {
        plan = null;
        if (d != null && d.has("c")) {
            try {
                byte[] c = android.util.Base64.decode(d.optString("c"), android.util.Base64.DEFAULT);
                int w = d.optInt("w"), h = d.optInt("h");
                if (w > 0 && h > 0 && c.length >= w * h * 3) {
                    planX = d.optInt("x");
                    planY = d.optInt("y");
                    planW = w;
                    planH = h;
                    // the game's floors, what stands where, the cars (older server add-ons send none of them)
                    byte[] f = d.has("f") ? android.util.Base64.decode(d.optString("f"), android.util.Base64.DEFAULT) : null;
                    byte[] o = d.has("o") ? android.util.Base64.decode(d.optString("o"), android.util.Base64.DEFAULT) : null;
                    planFloors = f != null && f.length >= w * h ? f : null;
                    planThings = o != null && o.length >= w * h ? o : null;
                    JSONArray fl = d.optJSONArray("fl");
                    planFloorNames = new String[fl == null ? 0 : fl.length()];
                    for (int i = 0; i < planFloorNames.length; i++) planFloorNames[i] = fl.optString(i);
                    JSONArray v = d.optJSONArray("v");
                    planCars = v == null ? new JSONArray() : v;
                    pieces = makePieces(planThings, w, h);
                    plan = c;
                }
            } catch (IllegalArgumentException e) {
                plan = null;
            }
        }
        invalidate();
    }

    boolean planWanted() {
        return planWanted;
    }

    private void checkPlanWanted() {
        boolean want = scale / density >= PLAN_ZOOM * 0.7f;
        if (want == planWanted) return;
        planWanted = want;
        if (listener != null) listener.onPlanWanted(want);
    }

    /** The zombies the danger radar knows of, put on the map around the character. */
    void setZombies(JSONObject radar) {
        JSONArray z = radar == null ? null : radar.optJSONArray("z");
        if (z == null || player == null) {
            zeds = new float[0];
        } else {
            float[] out = new float[z.length() * 4];
            int n = 0;
            for (int i = 0; i < z.length(); i++) {
                JSONObject o = z.optJSONObject(i);
                if (o == null || o.optInt("f") != 0) continue;   // another floor
                String s = o.optString("s");
                out[n++] = player[0] + (float) o.optDouble("x");
                out[n++] = player[1] + (float) o.optDouble("y");
                out[n++] = "seen".equals(s) ? 0 : "heard".equals(s) ? 1 : 2;
                out[n++] = "attack".equals(o.optString("b")) || "chase".equals(o.optString("b")) ? 1 : 0;
            }
            zeds = java.util.Arrays.copyOf(out, n);
        }
        if (scale / density >= 2) invalidate();
    }

    /** How big one piece of a kind can be: squares along its front, and from front to back. */
    private static int[] span(int kind) {
        switch (kind) {
            case BED: return new int[]{2, 2};
            case SOFA: return new int[]{3, 1};
            case TABLE: return new int[]{3, 2};
            case COUNTER:
            case SHELF: return new int[]{8, 1};
            case WARDROBE:
            case BATH: return new int[]{2, 1};
            default: return new int[]{1, 1};
        }
    }

    /** The furniture as pieces: neighbouring squares of the same kind facing the same way are one bed, couch or counter. */
    private static int[] makePieces(byte[] o, int n, int rows) {
        if (o == null) return new int[0];
        int[] out = new int[64];
        int count = 0;
        boolean[] used = new boolean[o.length];
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < n; i++) {
                int k = j * n + i, v = o[k] & 0xff;
                if (v == 0 || used[k]) continue;
                int kind = v & 31, face = (v >> 5) & 3;
                int[] s = span(kind);
                // along its front, and back from it (the front of a north- or south-facing piece runs east-west)
                int mw = face == 0 || face == 2 ? s[0] : s[1], mh = face == 0 || face == 2 ? s[1] : s[0];
                int w = 1;
                while (i + w < n && w < mw && (o[k + w] & 0xff) == v && !used[k + w]) w++;
                int h = 1;
                grow:
                while (j + h < rows && h < mh) {
                    for (int a = 0; a < w; a++) if ((o[k + h * n + a] & 0xff) != v || used[k + h * n + a]) break grow;
                    h++;
                }
                for (int b = 0; b < h; b++) for (int a = 0; a < w; a++) used[k + b * n + a] = true;
                if (count + 6 > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                out[count++] = i;
                out[count++] = j;
                out[count++] = w;
                out[count++] = h;
                out[count++] = kind;
                out[count++] = face;
            }
        }
        return java.util.Arrays.copyOf(out, count);
    }

    /**
     * What the character has seen of their floor around them, drawn like the place itself: the game's own
     * floors, the furniture from above, the walls with their doors and windows, trees and cars. What's out of
     * view now is drawn darker: remembered, not seen.
     */
    private void drawPlan(Canvas canvas, Palette p) {
        float zoom = scale / density;
        byte[] pl = plan;
        if (pl == null || zoom < PLAN_ZOOM * 0.7f) return;
        float fade = Math.min(1f, (zoom - PLAN_ZOOM * 0.7f) / (PLAN_ZOOM * 0.3f));
        float[] a = toWorld(0, 0), b = toWorld(getWidth(), getHeight());
        int i0 = Math.max(0, (int) Math.floor(a[0]) - planX - 1), i1 = Math.min(planW - 1, (int) Math.floor(b[0]) - planX + 1);
        int j0 = Math.max(0, (int) Math.floor(a[1]) - planY - 1), j1 = Math.min(planH - 1, (int) Math.floor(b[1]) - planY + 1);
        if (i0 > i1 || j0 > j1) return;
        int layer = fade < 0.99f ? canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), (int) (255 * fade)) : -1;
        float u = scale;   // pixels per square
        byte[] floors = planFloors;
        String[] names = planFloorNames;
        planPaint.setStyle(Paint.Style.FILL);
        // the ground
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                int k = j * planW + i, b0 = pl[k * 3] & 0xff;
                if ((b0 & 1) == 0) continue;
                float x = sx(planX + i), y = sy(planY + j);
                int fi = floors == null ? 0 : floors[k] & 0xff;
                Bitmap img = fi > 0 && fi <= names.length ? floorBitmap(names[fi - 1]) : null;
                rect.set(x, y, x + u + 0.6f, y + u + 0.6f);
                if (img != null) {
                    canvas.drawBitmap(img, null, rect, floorPaint);
                } else {
                    planPaint.setColor((b0 & 8) != 0 ? 0xFFBDB29A : 0xFF8C9670);
                    canvas.drawRect(rect, planPaint);
                }
                if ((b0 & 16) != 0) stairs(canvas, x, y, u);
            }
        }
        // the furniture, with a little shadow
        int[] ps = pieces;
        for (int q = 0; q + 5 < ps.length; q += 6) {
            if (ps[q] + ps[q + 2] < i0 || ps[q] > i1 || ps[q + 1] + ps[q + 3] < j0 || ps[q + 1] > j1) continue;
            piece(canvas, sx(planX + ps[q]), sy(planY + ps[q + 1]), ps[q + 2] * u, ps[q + 3] * u, ps[q + 4], ps[q + 5], u);
        }
        // what isn't in view now: remembered, darker (one shape, so where squares meet isn't darker twice)
        planPath.rewind();
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                int b0 = pl[(j * planW + i) * 3] & 0xff;
                if ((b0 & 1) != 0 && (b0 & 2) == 0) {
                    float x = sx(planX + i), y = sy(planY + j);
                    planPath.addRect(x - 0.3f, y - 0.3f, x + u + 0.3f, y + u + 0.3f, Path.Direction.CW);
                }
            }
        }
        planPath.setFillType(Path.FillType.WINDING);
        planPaint.setColor(0x6B0C0E12);
        canvas.drawPath(planPath, planPaint);
        // walls, doors and windows on the squares' north and west edges
        float wall = Math.max(2.5f * density, u * 0.17f);
        edgePaint.setStyle(Paint.Style.STROKE);
        for (int j = j0; j <= Math.min(planH - 1, j1 + 1); j++) {
            for (int i = i0; i <= Math.min(planW - 1, i1 + 1); i++) {
                int k = (j * planW + i) * 3;
                int n = pl[k + 1] & 0xff, w = pl[k + 2] & 0xff;
                if (n == 0 && w == 0) continue;
                float x = sx(planX + i), y = sy(planY + j);
                if (n != 0) edge(canvas, n, x, y, x + u, y, true, u, wall);
                if (w != 0) edge(canvas, w, x, y, x, y + u, false, u, wall);
            }
        }
        edgePaint.setPathEffect(null);
        // trees from above, then the cars
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                if ((pl[(j * planW + i) * 3] & 128) != 0) tree(canvas, sx(planX + i + 0.5f), sy(planY + j + 0.5f), u);
            }
        }
        for (int i = 0; i < planCars.length(); i++) vehicle(canvas, planCars.optJSONArray(i), u);
        if (layer >= 0) canvas.restoreToCount(layer);
    }

    /** A floor's picture, or null while it's on its way (the map is drawn again when it arrives). */
    private Bitmap floorBitmap(String name) {
        if (art == null || name == null || name.isEmpty()) return null;
        Bitmap b = art.peek("f", name);
        if (b == null) art.floor(name, bm -> { if (bm != null) invalidate(); });
        return b;
    }

    private void stairs(Canvas c, float x, float y, float u) {
        for (int s = 0; s < 4; s++) {
            planPaint.setColor(s % 2 == 1 ? 0x2E000000 : 0x1FFFFFFF);
            c.drawRect(x + u * 0.08f, y + u * s / 4f, x + u * 0.92f, y + u * (s + 1) / 4f, planPaint);
        }
        line(c, 0x73000000, Math.max(1, u * 0.03f), x + u * 0.08f, y, x + u * 0.08f, y + u);
        line(c, 0x73000000, Math.max(1, u * 0.03f), x + u * 0.92f, y, x + u * 0.92f, y + u);
    }

    private void line(Canvas c, int color, float width, float x0, float y0, float x1, float y1) {
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setColor(color);
        edgePaint.setStrokeWidth(width);
        c.drawLine(x0, y0, x1, y1, edgePaint);
    }

    /** A rounded box: fill (0 for none), outline (0 for none). */
    private void box(Canvas c, float x, float y, float w, float h, float r, int fill, int stroke, float sw) {
        box.set(x, y, x + w, y + h);
        float rr = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (fill != 0) {
            planPaint.setStyle(Paint.Style.FILL);
            planPaint.setColor(fill);
            c.drawRoundRect(box, rr, rr, planPaint);
        }
        if (stroke != 0) {
            planPaint.setStyle(Paint.Style.STROKE);
            planPaint.setColor(stroke);
            planPaint.setStrokeWidth(sw);
            c.drawRoundRect(box, rr, rr, planPaint);
            planPaint.setStyle(Paint.Style.FILL);
        }
    }

    private void dot(Canvas c, float x, float y, float r, int color) {
        planPaint.setStyle(Paint.Style.FILL);
        planPaint.setColor(color);
        c.drawCircle(x, y, Math.max(0, r), planPaint);
    }

    /** One piece of furniture from above, in its box; face: the way its front points (0 N, 1 E, 2 S, 3 W). */
    private void piece(Canvas c, float x, float y, float w, float h, int kind, int face, float u) {
        c.save();
        // drawn facing south (front down), then turned into place
        boolean turned = face == 1 || face == 3;
        float lw = turned ? h : w, ld = turned ? w : h;
        c.translate(x + w / 2, y + h / 2);
        c.rotate(new float[]{180, -90, 0, 90}[face]);
        c.translate(-lw / 2, -ld / 2);
        float k = u * 0.06f, l1 = Math.max(1, u * 0.025f);
        box(c, k + u * 0.05f, k + u * 0.07f, lw - 2 * k, ld - 2 * k, u * 0.1f, 0x47000000, 0, 0);   // its shadow
        switch (kind) {
            case BED: {
                box(c, k, k, lw - 2 * k, ld - 2 * k, u * 0.08f, 0xFF6E4D2E, 0, 0);
                box(c, k * 2, k * 2, lw - 4 * k, ld - 4 * k, u * 0.08f, 0xFFEFE9DC, 0, 0);
                box(c, k * 2, ld * 0.38f, lw - 4 * k, ld * 0.62f - 2 * k, u * 0.08f, 0xFF56789C, 0, 0);   // the blanket
                line(c, 0x59FFFFFF, l1, k * 2, ld * 0.38f + u * 0.06f, lw - k * 2, ld * 0.38f + u * 0.06f);
                int pillows = Math.max(1, Math.round(lw / u));
                float pw = (lw - 4 * k) / pillows;
                for (int a = 0; a < pillows; a++) box(c, k * 2 + pw * a + u * 0.06f, k * 2 + u * 0.05f, pw - u * 0.12f, u * 0.24f, u * 0.08f, 0xFFFBF8F2, 0x26000000, l1);
                break;
            }
            case SOFA: {
                box(c, k, u * 0.1f, lw - 2 * k, ld - u * 0.16f, u * 0.1f, 0xFF8F7A63, 0, 0);
                box(c, k, u * 0.06f, lw - 2 * k, u * 0.26f, u * 0.08f, 0xFF76624D, 0, 0);                 // the back
                box(c, k, u * 0.1f, u * 0.18f, ld - u * 0.16f, u * 0.08f, 0xFF76624D, 0, 0);                // the arms
                box(c, lw - k - u * 0.18f, u * 0.1f, u * 0.18f, ld - u * 0.16f, u * 0.08f, 0xFF76624D, 0, 0);
                int seats = Math.max(1, Math.round(lw / u));
                for (int a = 1; a < seats; a++) line(c, 0x4D000000, l1, k + (lw - 2 * k) * a / seats, u * 0.34f, k + (lw - 2 * k) * a / seats, ld - u * 0.1f);
                break;
            }
            case CHAIR:
                box(c, lw * 0.22f, ld * 0.32f, lw * 0.56f, ld * 0.5f, u * 0.08f, 0xFF8A6440, 0, 0);
                box(c, lw * 0.2f, ld * 0.18f, lw * 0.6f, ld * 0.14f, u * 0.05f, 0xFF6E4D2E, 0, 0);
                break;
            case TABLE:
                box(c, k, k, lw - 2 * k, ld - 2 * k, u * 0.08f, 0xFF9B7653, 0xFF7A5A3C, l1);
                box(c, k * 2.2f, k * 2.2f, lw - 4.4f * k, ld - 4.4f * k, u * 0.06f, 0, 0x2EFFFFFF, l1);
                break;
            case COUNTER:
            case SINK:
                box(c, 0, 0, lw, ld * 0.74f, u * 0.03f, 0xFFD6CFBF, 0x40000000, l1);
                line(c, 0x59000000, Math.max(1, u * 0.04f), 0, ld * 0.74f, lw, ld * 0.74f);
                if (kind == SINK) {
                    box(c, lw / 2 - u * 0.26f, ld * 0.16f, u * 0.52f, ld * 0.42f, u * 0.12f, 0xFFA9B6BF, 0x4D000000, l1);
                    dot(c, lw / 2, ld * 0.1f, u * 0.04f, 0xFF7B858C);
                }
                break;
            case TOILET:
                box(c, lw * 0.24f, ld * 0.04f, lw * 0.52f, ld * 0.22f, u * 0.05f, 0xFFF4F3EF, 0x40000000, l1);
                box.set(lw / 2 - lw * 0.22f, ld * 0.56f - ld * 0.3f, lw / 2 + lw * 0.22f, ld * 0.56f + ld * 0.3f);
                planPaint.setStyle(Paint.Style.FILL);
                planPaint.setColor(0xFFF4F3EF);
                c.drawOval(box, planPaint);
                planPaint.setStyle(Paint.Style.STROKE);
                planPaint.setStrokeWidth(l1);
                planPaint.setColor(0x40000000);
                c.drawOval(box, planPaint);
                planPaint.setStyle(Paint.Style.FILL);
                planPaint.setColor(0xFFC9D6DD);
                box.set(lw / 2 - lw * 0.13f, ld * 0.58f - ld * 0.19f, lw / 2 + lw * 0.13f, ld * 0.58f + ld * 0.19f);
                c.drawOval(box, planPaint);
                break;
            case BATH:
                box(c, k, k, lw - 2 * k, ld - 2 * k, u * 0.16f, 0xFFF4F3EF, 0x40000000, l1);
                box(c, k * 2.6f, k * 2.6f, lw - 5.2f * k, ld - 5.2f * k, u * 0.14f, 0xFFCDDCE4, 0, 0);
                dot(c, lw / 2, ld - k * 4, u * 0.04f, 0xFF7B858C);
                break;
            case STOVE: {
                box(c, k, 0, lw - 2 * k, ld * 0.8f, u * 0.05f, 0xFF2F3236, 0, 0);
                planPaint.setStyle(Paint.Style.STROKE);
                planPaint.setStrokeWidth(Math.max(1, u * 0.04f));
                planPaint.setColor(0xFF6D7278);
                float[][] burners = {{0.3f, 0.25f}, {0.7f, 0.25f}, {0.3f, 0.58f}, {0.7f, 0.58f}};
                for (float[] bn : burners) c.drawCircle(lw * bn[0], ld * bn[1], u * 0.12f, planPaint);
                planPaint.setStyle(Paint.Style.FILL);
                break;
            }
            case FRIDGE:
                box(c, k, 0, lw - 2 * k, ld * 0.86f, u * 0.06f, 0xFFE3E6E8, 0x4D000000, l1);
                line(c, 0x40000000, l1, k, ld * 0.3f, lw - k, ld * 0.3f);
                line(c, 0xFF9AA1A7, Math.max(1, u * 0.05f), lw * 0.3f, ld * 0.8f, lw * 0.7f, ld * 0.8f);
                break;
            case SHELF: {
                box(c, 0, 0, lw, ld * 0.5f, u * 0.03f, 0xFF7A6A58, 0, 0);
                int[] colors = {0xFFB5523B, 0xFF3F6E9C, 0xFFC9A23A, 0xFF4F7D4A, 0xFF8C5AA0};
                int books = (int) Math.floor(lw / (u * 0.18f));
                planPaint.setStyle(Paint.Style.FILL);
                for (int a = 0; a < books; a++) {
                    planPaint.setColor(colors[a % colors.length]);
                    c.drawRect(u * 0.06f + a * u * 0.18f, ld * 0.08f, u * 0.18f + a * u * 0.18f, ld * 0.4f, planPaint);
                }
                break;
            }
            case WARDROBE:
                box(c, 0, 0, lw, ld * 0.72f, u * 0.04f, 0xFF7C5A39, 0x4D000000, l1);
                line(c, 0x59000000, l1, lw / 2, 0, lw / 2, ld * 0.72f);
                dot(c, lw / 2 - u * 0.08f, ld * 0.62f, u * 0.035f, 0xFFD9C08A);
                dot(c, lw / 2 + u * 0.08f, ld * 0.62f, u * 0.035f, 0xFFD9C08A);
                break;
            case WASHER:
                box(c, k, k, lw - 2 * k, ld * 0.84f, u * 0.06f, 0xFFECEEEF, 0x4D000000, l1);
                dot(c, lw / 2, ld * 0.48f, Math.min(lw, ld) * 0.26f, 0xFF93A7B6);
                break;
            case TV:
                box(c, lw * 0.14f, ld * 0.3f, lw * 0.72f, ld * 0.24f, u * 0.04f, 0xFF5A4632, 0, 0);
                box(c, lw * 0.08f, ld * 0.18f, lw * 0.84f, ld * 0.1f, u * 0.03f, 0xFF1B1D20, 0, 0);
                break;
            case PLANT:
                dot(c, lw / 2, ld / 2, u * 0.2f, 0xFF8A5A3A);
                dot(c, lw * 0.5f, ld * 0.32f, u * 0.17f, 0xFF4F7D3A);
                dot(c, lw * 0.32f, ld * 0.55f, u * 0.17f, 0xFF5E9446);
                dot(c, lw * 0.68f, ld * 0.55f, u * 0.17f, 0xFF467233);
                dot(c, lw * 0.5f, ld * 0.62f, u * 0.17f, 0xFF6AA250);
                break;
            case BOX:
                box(c, k * 1.5f, k * 1.5f, lw - 3 * k, ld - 3 * k, u * 0.03f, 0xFFA07C4F, 0xFF7A5A35, l1);
                line(c, 0xFF7A5A35, l1, k * 1.5f, k * 1.5f, lw - k * 1.5f, ld - k * 1.5f);
                line(c, 0xFF7A5A35, l1, lw - k * 1.5f, k * 1.5f, k * 1.5f, ld - k * 1.5f);
                break;
            default:
                box(c, k * 1.8f, k * 1.8f, lw - 3.6f * k, ld - 3.6f * k, u * 0.08f, 0xFF8F8577, 0x40000000, l1);
                break;
        }
        c.restore();
    }

    /** A tree from above: its crown, with the light from the south-west. */
    private void tree(Canvas c, float x, float y, float u) {
        float r = u * 0.72f;
        dot(c, x + u * 0.12f, y + u * 0.16f, r, 0x4D000000);
        dot(c, x, y, r, 0xFF3D5C2C);
        dot(c, x - r * 0.18f, y - r * 0.2f, r * 0.68f, 0xFF4F7537);
        dot(c, x - r * 0.3f, y - r * 0.32f, r * 0.32f, 0xFF62893F);
    }

    /** A car from above: [x, y (centre), heading (degrees, 0 east), length, width, "rrggbb"]. */
    private void vehicle(Canvas c, JSONArray v, float u) {
        if (v == null || v.length() < 6) return;
        float len = (float) v.optDouble(3, 4.5) * u, wid = (float) v.optDouble(4, 1.9) * u;
        int color;
        try {
            color = 0xFF000000 | Integer.parseInt(v.optString(5, "888888"), 16);
        } catch (NumberFormatException e) {
            color = 0xFF888888;
        }
        c.save();
        c.translate(sx((float) v.optDouble(0)), sy((float) v.optDouble(1)));
        c.rotate((float) v.optDouble(2, 0));
        box(c, -len / 2 + u * 0.12f, -wid / 2 + u * 0.16f, len, wid, u * 0.35f, 0x4D000000, 0, 0);
        box(c, -len / 2, -wid / 2, len, wid, u * 0.35f, color, 0x73000000, Math.max(1, u * 0.03f));
        // the windscreen (front: +x), the roof, the back window, the lights
        planPath.rewind();
        planPath.moveTo(len * 0.1f, -wid * 0.42f);
        planPath.lineTo(len * 0.27f, -wid * 0.36f);
        planPath.lineTo(len * 0.27f, wid * 0.36f);
        planPath.lineTo(len * 0.1f, wid * 0.42f);
        planPath.close();
        planPaint.setStyle(Paint.Style.FILL);
        planPaint.setColor(0xD9192028);
        c.drawPath(planPath, planPaint);
        box(c, -len * 0.24f, -wid * 0.38f, len * 0.34f, wid * 0.76f, u * 0.1f, 0x24FFFFFF, 0, 0);
        planPaint.setColor(0xD9192028);
        c.drawRect(-len * 0.36f, -wid * 0.36f, -len * 0.26f, wid * 0.36f, planPaint);
        planPaint.setColor(0xFFF3E6A8);
        c.drawRect(len / 2 - u * 0.12f, -wid * 0.42f, len / 2 - u * 0.02f, -wid * 0.22f, planPaint);
        c.drawRect(len / 2 - u * 0.12f, wid * 0.22f, len / 2 - u * 0.02f, wid * 0.42f, planPaint);
        planPaint.setColor(0xFFB23A2E);
        c.drawRect(-len / 2 + u * 0.02f, -wid * 0.42f, -len / 2 + u * 0.1f, -wid * 0.24f, planPaint);
        c.drawRect(-len / 2 + u * 0.02f, wid * 0.24f, -len / 2 + u * 0.1f, wid * 0.42f, planPaint);
        c.restore();
    }

    private void edge(Canvas c, int code, float x0, float y0, float x1, float y1, boolean north, float u, float wall) {
        int kind = code & 15;
        float jam = u * 0.12f;   // the wall either side of an opening
        float gx0 = north ? x0 + jam : x0, gy0 = north ? y0 : y0 + jam, gx1 = north ? x1 - jam : x1, gy1 = north ? y1 : y1 - jam;
        edgePaint.setPathEffect(null);
        edgePaint.setStrokeCap(Paint.Cap.SQUARE);
        switch (kind) {
            case E_WALL:
                line(c, WALL_INK, wall, x0, y0, x1, y1);
                line(c, 0x1AFFFFFF, Math.max(1, wall * 0.18f), x0, y0, x1, y1);
                break;
            case E_DOOR:
            case E_DOOR_OPEN:
            case E_DOORWAY:
                line(c, WALL_INK, wall, x0, y0, gx0, gy0);
                line(c, WALL_INK, wall, gx1, gy1, x1, y1);
                edgePaint.setStrokeCap(Paint.Cap.BUTT);
                if (kind == E_DOOR) {
                    line(c, DOOR_WOOD, wall * 0.5f, gx0, gy0, gx1, gy1);
                } else if (kind == E_DOOR_OPEN) {
                    // the door swung open into the square, and the way it swings
                    float len = u - 2 * jam;
                    line(c, DOOR_WOOD, wall * 0.4f, gx0, gy0, north ? gx0 : gx0 + len, north ? gy0 + len : gy0);
                    edgePaint.setPathEffect(new DashPathEffect(new float[]{u * 0.06f, u * 0.06f}, 0));
                    edgePaint.setColor(0x8C281E14);
                    edgePaint.setStrokeWidth(Math.max(1, u * 0.025f));
                    box.set(gx0 - len, gy0 - len, gx0 + len, gy0 + len);
                    c.drawArc(box, 0, 90, false, edgePaint);
                    edgePaint.setPathEffect(null);
                }
                break;
            case E_WINDOW:
            case E_WINDOW_OPEN:
            case E_WINDOW_BROKEN:
            case E_FRAME: {
                line(c, WALL_INK, wall, x0, y0, gx0, gy0);
                line(c, WALL_INK, wall, gx1, gy1, x1, y1);
                edgePaint.setStrokeCap(Paint.Cap.BUTT);
                // the frame either side of the glass
                float off = wall * 0.42f, ox = north ? 0 : off, oy = north ? off : 0, thin = Math.max(1, wall * 0.16f);
                line(c, WALL_INK, thin, gx0 - ox, gy0 - oy, gx1 - ox, gy1 - oy);
                line(c, WALL_INK, thin, gx0 + ox, gy0 + oy, gx1 + ox, gy1 + oy);
                if (kind == E_WINDOW) {
                    line(c, GLASS, wall * 0.62f, gx0, gy0, gx1, gy1);
                } else if (kind == E_WINDOW_OPEN) {
                    line(c, GLASS, wall * 0.62f, gx0, gy0, (gx0 + gx1) / 2, (gy0 + gy1) / 2);   // the sash pushed up: half the pane
                } else if (kind == E_WINDOW_BROKEN) {
                    edgePaint.setPathEffect(new DashPathEffect(new float[]{u * 0.05f, u * 0.09f}, 0));
                    line(c, GLASS, wall * 0.62f, gx0, gy0, gx1, gy1);
                    edgePaint.setPathEffect(null);
                }
                if ((code & 32) != 0) {   // curtains drawn, on the inside
                    float cx = north ? 0 : wall * 0.7f, cy = north ? wall * 0.7f : 0;
                    edgePaint.setPathEffect(new DashPathEffect(new float[]{u * 0.05f, u * 0.03f}, 0));
                    line(c, 0xFFA5473F, Math.max(1.5f, wall * 0.3f), gx0 + cx, gy0 + cy, gx1 + cx, gy1 + cy);
                    edgePaint.setPathEffect(null);
                }
                break;
            }
            case E_FENCE: {
                line(c, 0xFF7A5C3A, Math.max(1.5f * density, u * 0.06f), x0, y0, x1, y1);
                float p = Math.max(2 * density, u * 0.12f);
                planPaint.setStyle(Paint.Style.FILL);
                planPaint.setColor(0xFF5E4429);
                c.drawRect(x0 - p / 2, y0 - p / 2, x0 + p / 2, y0 + p / 2, planPaint);
                c.drawRect((x0 + x1) / 2 - p / 2, (y0 + y1) / 2 - p / 2, (x0 + x1) / 2 + p / 2, (y0 + y1) / 2 + p / 2, planPaint);
                break;
            }
            default:
                break;
        }
        if ((code & 16) != 0) {   // barricaded: planks nailed across
            edgePaint.setStrokeCap(Paint.Cap.BUTT);
            float px = north ? u * 0.2f : 0, py = north ? 0 : u * 0.2f;
            for (int s = 1; s <= 3; s++) {
                float mx = x0 + (x1 - x0) * s / 4f, my = y0 + (y1 - y0) * s / 4f;
                line(c, 0xFF6B4A2B, wall * 0.55f, mx - py * 0.6f - px, my - px * 0.6f - py, mx + py * 0.6f + px, my + px * 0.6f + py);
            }
        }
    }

    private void drawZombies(Canvas canvas, Palette p) {
        float zoom = scale / density;
        if (zeds.length == 0 || zoom < 2) return;
        float r = Math.max(4.5f * density, Math.min(scale * 0.32f, 11 * density));
        for (int i = 0; i + 3 < zeds.length; i += 4) {
            float x = sx(zeds[i]), y = sy(zeds[i + 1]);   // where they stand, not a square's corner
            if (x < -20 || y < -20 || x > getWidth() + 20 || y > getHeight() + 20) continue;
            int kind = (int) zeds[i + 2];
            int color = zeds[i + 3] > 0 ? 0xFFE0352B : kind == 0 ? 0xFFC0392B : kind == 1 ? 0xFFE08A2B : 0xFF8A8F96;
            markPaint.setStyle(Paint.Style.FILL);
            markPaint.setColor(p.paper ? 0xFFFFFFFF : 0xFF0D0F12);
            canvas.drawCircle(x, y, r + 2 * density, markPaint);
            if (kind == 0) {
                markPaint.setColor(color);
                canvas.drawCircle(x, y, r, markPaint);
            } else {
                markPaint.setStyle(Paint.Style.STROKE);
                markPaint.setStrokeWidth(2.2f * density);
                markPaint.setColor(color);
                canvas.drawCircle(x, y, r - density, markPaint);
            }
        }
    }

    // ------------------------------------------------------------------ what the character remembers

    private int[] stamps = new int[0];   // x, y, w, h, searched (0..100) per building
    private JSONObject car;
    private int[] trail = new int[0];
    private boolean showTrail;
    private JSONArray pins = new JSONArray();
    private int focusPin = -1;

    /** The map's memory layer ("memory"): searched buildings, the car left behind, the way walked. */
    void setMemory(JSONObject m) {
        JSONArray b = m == null ? null : m.optJSONArray("b");
        int[] s = new int[b == null ? 0 : b.length() * 5];
        for (int i = 0; b != null && i < b.length(); i++) {
            JSONArray r = b.optJSONArray(i);
            if (r == null) continue;
            for (int k = 0; k < 4; k++) s[i * 5 + k] = r.optInt(k);
            s[i * 5 + 4] = (int) Math.round(r.optDouble(4) * 100);
        }
        stamps = s;
        car = m == null ? null : m.optJSONObject("car");
        JSONArray tr = m == null ? null : m.optJSONArray("tr");
        int[] t = new int[tr == null ? 0 : tr.length()];
        for (int i = 0; i < t.length; i++) t[i] = tr.optInt(i);
        trail = t;
        invalidate();
    }

    void setTrailShown(boolean on) {
        showTrail = on;
        invalidate();
    }

    /** "Where did I see...?" finds to pin on the map; focus: the one to make big (-1: none). */
    void setPins(JSONArray list, int focus) {
        pins = list == null ? new JSONArray() : list;
        focusPin = focus;
        invalidate();
    }

    /** Moves the view to a place (and stops following the character), zoomed in at least this far. */
    void focusOn(float x, float y, float zoom) {
        setFollow(false);
        cx = x;
        cy = y;
        scale = Math.max(scale, zoom * density);
        checkPlanWanted();
        invalidate();
    }

    /** How searched the building at a spot is (0..100), or -1 when the character hasn't been in it. */
    int searchedAt(float wx, float wy) {
        for (int i = 0; i + 4 < stamps.length; i += 5) {
            if (wx >= stamps[i] && wy >= stamps[i + 1] && wx < stamps[i] + stamps[i + 2] && wy < stamps[i + 1] + stamps[i + 3]) return stamps[i + 4];
        }
        return -1;
    }

    private void drawStamps(Canvas canvas, Palette p) {
        float zoom = scale / density;
        if (stamps.length == 0 || zoom < 0.55f || zoom >= PLAN_ZOOM) return;
        float r = 8 * density * Math.max(0.85f, Math.min(1.3f, (float) Math.sqrt(zoom)));
        int ink = 0xFF3E7C47;
        for (int i = 0; i + 4 < stamps.length; i += 5) {
            int s = stamps[i + 4];
            if (s <= 0) continue;
            float x = sx(stamps[i] + stamps[i + 2] / 2f), y = sy(stamps[i + 1] + stamps[i + 3] / 2f);
            if (x < -20 || y < -20 || x > getWidth() + 20 || y > getHeight() + 20) continue;
            markPaint.setStyle(Paint.Style.FILL);
            markPaint.setColor(p.paper ? 0xFFFFFFFF : 0xFF0D0F12);
            canvas.drawCircle(x, y, r + 2 * density, markPaint);
            if (s >= 80) {
                markPaint.setColor(ink);
                canvas.drawCircle(x, y, r, markPaint);
                markPath.rewind();
                markPath.moveTo(x - r * 0.45f, y + r * 0.02f);
                markPath.lineTo(x - r * 0.1f, y + r * 0.38f);
                markPath.lineTo(x + r * 0.5f, y - r * 0.32f);
                markPaint.setStyle(Paint.Style.STROKE);
                markPaint.setStrokeCap(Paint.Cap.ROUND);
                markPaint.setStrokeJoin(Paint.Join.ROUND);
                markPaint.setStrokeWidth(2.2f * density);
                markPaint.setColor(0xFFFFFFFF);
                canvas.drawPath(markPath, markPaint);
            } else {
                // partly searched: how much, as a slice
                markPaint.setStyle(Paint.Style.STROKE);
                markPaint.setStrokeWidth(2 * density);
                markPaint.setColor(ink);
                canvas.drawCircle(x, y, r - density, markPaint);
                markPaint.setStyle(Paint.Style.FILL);
                rect.set(x - r + 2.5f * density, y - r + 2.5f * density, x + r - 2.5f * density, y + r - 2.5f * density);
                canvas.drawArc(rect, -90, 360f * s / 100f, true, markPaint);
            }
        }
    }

    private void drawCar(Canvas canvas, Palette p) {
        if (car == null || scale / density < 0.3f) return;
        float x = sx((float) car.optDouble("x") + 0.5f), y = sy((float) car.optDouble("y") + 0.5f);
        if (x < -60 || y < -60 || x > getWidth() + 60 || y > getHeight() + 60) return;
        float r = 11 * density;
        int blue = 0xFF2F5D8A;
        markPaint.setStyle(Paint.Style.FILL);
        markPaint.setColor(p.paper ? 0xFFFFFFFF : 0xFF0D0F12);
        canvas.drawCircle(x, y, r + 2.5f * density, markPaint);
        markPaint.setColor(blue);
        canvas.drawCircle(x, y, r, markPaint);
        // a little car, from the side
        markPaint.setColor(0xFFFFFFFF);
        rect.set(x - r * 0.62f, y - r * 0.08f, x + r * 0.62f, y + r * 0.32f);
        canvas.drawRoundRect(rect, r * 0.12f, r * 0.12f, markPaint);
        markPath.rewind();
        markPath.moveTo(x - r * 0.36f, y - r * 0.08f);
        markPath.lineTo(x - r * 0.2f, y - r * 0.38f);
        markPath.lineTo(x + r * 0.22f, y - r * 0.38f);
        markPath.lineTo(x + r * 0.4f, y - r * 0.08f);
        markPath.close();
        canvas.drawPath(markPath, markPaint);
        markPaint.setColor(blue);
        canvas.drawCircle(x - r * 0.34f, y + r * 0.34f, r * 0.15f, markPaint);
        canvas.drawCircle(x + r * 0.34f, y + r * 0.34f, r * 0.15f, markPaint);
        if (scale / density > 0.45f) drawTag(canvas, p, car.optString("n", "Your car"), x, y + r * 2.1f, blue);
    }

    private void drawTrail(Canvas canvas, Palette p) {
        if (trail.length < 4 || scale / density < 0.2f) return;
        linePaint.setColor(p.player);
        linePaint.setAlpha(140);
        linePaint.setStrokeWidth(2.2f * density);
        linePaint.setPathEffect(new DashPathEffect(new float[]{2 * density, 4 * density}, 0));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        markPath.rewind();
        markPath.moveTo(sx(trail[0] + 0.5f), sy(trail[1] + 0.5f));
        for (int i = 2; i + 1 < trail.length; i += 2) markPath.lineTo(sx(trail[i] + 0.5f), sy(trail[i + 1] + 0.5f));
        canvas.drawPath(markPath, linePaint);
        linePaint.setPathEffect(null);
        linePaint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawPins(Canvas canvas, Palette p) {
        for (int i = 0; i < pins.length(); i++) {
            if (i == focusPin) continue;
            drawPin(canvas, p, pins.optJSONObject(i), false);
        }
        if (focusPin >= 0 && focusPin < pins.length()) drawPin(canvas, p, pins.optJSONObject(focusPin), true);
    }

    private void drawPin(Canvas canvas, Palette p, JSONObject o, boolean big) {
        if (o == null) return;
        float x = sx((float) o.optDouble("x") + 0.5f), y = sy((float) o.optDouble("y") + 0.5f);
        if (x < -60 || y < -60 || x > getWidth() + 60 || y > getHeight() + 60) return;
        float r = (big ? 11 : 7) * density;
        int color = 0xFF8B3FB0;
        markPaint.setStyle(Paint.Style.FILL);
        markPaint.setColor(p.paper ? 0xFFFFFFFF : 0xFF0D0F12);
        canvas.drawCircle(x, y, r + 2.5f * density, markPaint);
        markPaint.setColor(color);
        canvas.drawCircle(x, y, r, markPaint);
        markPaint.setColor(0xFFFFFFFF);
        canvas.drawCircle(x, y, r * 0.38f, markPaint);
        if (big) {
            String n = o.optString("n");
            int c = o.optInt("c", 1);
            drawTag(canvas, p, c > 1 ? n + " ×" + c : n, x, y + r * 2.2f, color);
        }
    }

    boolean loaded() {
        return data != null;
    }

    // ------------------------------------------------------------------ data

    /** Decodes the mod's map format; runs on a background thread. */
    void load(byte[] raw) {
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (b.get() != 'K' || b.get() != 'L' || b.get() != 'M' || b.get() != '1') throw new IllegalArgumentException("not a map");
        Data d = new Data();
        int count = b.getInt();
        int p0 = b.position();
        int rings = 0, points = 0;
        for (int f = 0; f < count; f++) {
            b.position(b.position() + 2);
            int rc = b.getShort() & 0xffff;
            for (int r = 0; r < rc; r++) {
                int n = b.getInt();
                b.position(b.position() + 8 + (n - 1) * 4);
                rings++;
                points += n;
            }
        }
        d.count = count;
        d.kind = new byte[count];
        d.geom = new byte[count];
        d.fRing = new int[count + 1];
        d.rPt = new int[rings + 1];
        d.pts = new float[points * 2];
        d.bbox = new float[count * 4];
        b.position(p0);
        int ri = 0, pi = 0;
        for (int f = 0; f < count; f++) {
            d.kind[f] = b.get();
            d.geom[f] = b.get();
            int rc = b.getShort() & 0xffff;
            d.fRing[f] = ri;
            float minX = 1e9f, minY = 1e9f, maxX = -1e9f, maxY = -1e9f;
            for (int r = 0; r < rc; r++) {
                int n = b.getInt();
                int x = b.getInt(), y = b.getInt();
                d.rPt[ri++] = pi;
                for (int k = 0; k < n; k++) {
                    if (k > 0) {
                        x += b.getShort();
                        y += b.getShort();
                    }
                    d.pts[pi * 2] = x;
                    d.pts[pi * 2 + 1] = y;
                    pi++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
            d.bbox[f * 4] = minX;
            d.bbox[f * 4 + 1] = minY;
            d.bbox[f * 4 + 2] = maxX;
            d.bbox[f * 4 + 3] = maxY;
        }
        d.fRing[count] = ri;
        d.rPt[ri] = pi;
        if (b.remaining() >= 4) {
            int len = b.getInt();
            byte[] js = new byte[Math.min(len, b.remaining())];
            b.get(js);
            try {
                JSONArray a = new JSONArray(new String(js, "UTF-8"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject l = a.getJSONObject(i);
                    String k = l.optString("k", "");
                    d.labelText.add(l.optString("t"));
                    d.labelPos.add(new float[]{(float) l.optDouble("x"), (float) l.optDouble("y"), (float) l.optDouble("s", 1),
                            (float) l.optDouble("r", 0), k.contains("water") ? 1 : 0});
                }
            } catch (Exception ignored) {
            }
        }
        for (int i = 0; i < ORDER.length; i++) d.rank[ORDER[i]] = i;
        HashMap<Integer, List<Integer>> tmp = new HashMap<>();
        for (int f = 0; f < count; f++) {
            int c = cellKey((int) Math.floor((d.bbox[f * 4] + d.bbox[f * 4 + 2]) / 2 / CELL),
                    (int) Math.floor((d.bbox[f * 4 + 1] + d.bbox[f * 4 + 3]) / 2 / CELL));
            List<Integer> l = tmp.get(c);
            if (l == null) tmp.put(c, l = new ArrayList<>());
            l.add(f);
        }
        d.cells = new HashMap<>(tmp.size() * 2);
        for (Map.Entry<Integer, List<Integer>> e : tmp.entrySet()) {
            int[] a = new int[e.getValue().size()];
            for (int i = 0; i < a.length; i++) a[i] = e.getValue().get(i);
            d.cells.put(e.getKey(), a);
        }
        data = d;
        main.post(() -> {
            synchronized (tiles) {
                tiles.clear();
            }
            invalidate();
        });
    }

    private static int cellKey(int x, int y) {
        return x * 4096 + y;
    }

    private int[] featuresIn(Data d, float x0, float y0, float x1, float y1) {
        int c0 = (int) Math.floor(x0 / CELL) - 1, c1 = (int) Math.floor(x1 / CELL) + 1;
        int r0 = (int) Math.floor(y0 / CELL) - 1, r1 = (int) Math.floor(y1 / CELL) + 1;
        int[] out = new int[256];
        int n = 0;
        for (int cx = c0; cx <= c1; cx++) {
            for (int cy = r0; cy <= r1; cy++) {
                int[] list = d.cells.get(cellKey(cx, cy));
                if (list == null) continue;
                for (int f : list) {
                    if (d.bbox[f * 4 + 2] < x0 || d.bbox[f * 4] > x1 || d.bbox[f * 4 + 3] < y0 || d.bbox[f * 4 + 1] > y1) continue;
                    if (n == out.length) out = java.util.Arrays.copyOf(out, n * 2);
                    out[n++] = f;
                }
            }
        }
        Integer[] boxed = new Integer[n];
        for (int i = 0; i < n; i++) boxed[i] = out[i];
        final int[] rank = d.rank;
        final byte[] kind = d.kind;
        java.util.Arrays.sort(boxed, (a, b2) -> rank[kind[a]] - rank[kind[b2]]);
        int[] res = new int[n];
        for (int i = 0; i < n; i++) res[i] = boxed[i];
        return res;
    }

    // ------------------------------------------------------------------ tiles

    private static int levelOf(float s) {
        return Math.max(-8, Math.min(4, (int) Math.ceil(Math.log(s) / Math.log(2) - 0.15)));
    }

    private Tile tile(int level, int tx, int ty) {
        String key = level + ":" + tx + ":" + ty;
        Tile t;
        synchronized (tiles) {
            t = tiles.get(key);
            if (t == null) {
                t = new Tile(level, tx, ty);
                tiles.put(key, t);
                if (tiles.size() > MAX_TILES) {
                    // no recycle(): the last frame may still be drawing it; the GC frees it
                    tiles.remove(tiles.keySet().iterator().next());
                }
            }
        }
        if ((t.bitmap == null || t.gen != generation) && !t.queued) {
            t.queued = true;
            final Tile tt = t;
            final int gen = generation;
            final Palette p = pal;
            workerHandler.post(() -> render(tt, gen, p));
        }
        return t;
    }

    private Tile peekTile(int level, int tx, int ty) {
        synchronized (tiles) {
            return tiles.get(level + ":" + tx + ":" + ty);
        }
    }

    /** Draws one tile (background thread). */
    private void render(Tile t, int gen, Palette p) {
        Data d = data;
        if (d == null) {
            t.queued = false;
            return;
        }
        boolean visible;
        synchronized (tiles) {
            visible = tiles.containsValue(t);
        }
        if (!visible) {
            t.queued = false;
            return;
        }
        float sL = (float) Math.pow(2, t.level);
        float span = TILE / sL;
        float x0 = t.tx * span, y0 = t.ty * span;
        Bitmap bmp = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.translate(-x0 * sL, -y0 * sL);
        c.scale(sL, sL);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        Path path = new Path();
        path.setFillType(Path.FillType.EVEN_ODD);
        int[] list = featuresIn(d, x0, y0, x0 + span, y0 + span);
        float minPx = 0.6f / sL, decim = 0.7f / sL;
        boolean outline = t.level >= 1;
        for (int f : list) {
            int kd = d.kind[f];
            float w = d.bbox[f * 4 + 2] - d.bbox[f * 4], h = d.bbox[f * 4 + 3] - d.bbox[f * 4 + 1];
            if (w < minPx && h < minPx) continue;
            path.rewind();
            for (int r = d.fRing[f]; r < d.fRing[f + 1]; r++) {
                int a = d.rPt[r], bEnd = d.rPt[r + 1];
                float lx = d.pts[a * 2], ly = d.pts[a * 2 + 1];
                path.moveTo(lx, ly);
                for (int q = a + 1; q < bEnd; q++) {
                    float x = d.pts[q * 2], y = d.pts[q * 2 + 1];
                    if (q < bEnd - 1 && Math.abs(x - lx) < decim && Math.abs(y - ly) < decim) continue;
                    path.lineTo(x, y);
                    lx = x;
                    ly = y;
                }
                if (d.geom[f] == 1) path.close();
            }
            int color = p.kind[kd] != 0 ? p.kind[kd] : p.kind[OTHER];
            if (d.geom[f] == 2) {
                stroke.setColor(color);
                stroke.setStrokeWidth((kd == RAIL ? 1.6f : 2.2f) / sL);
                stroke.setPathEffect(kd == RAIL ? new DashPathEffect(new float[]{5 / sL, 4 / sL}, 0) : null);
                c.drawPath(path, stroke);
            } else {
                fill.setColor(color);
                c.drawPath(path, fill);
                if (outline && kd >= RES) {
                    stroke.setPathEffect(null);
                    stroke.setColor(p.outline);
                    stroke.setStrokeWidth(1f / sL);
                    c.drawPath(path, stroke);
                }
            }
        }
        main.post(() -> {
            t.bitmap = bmp;
            t.gen = gen;
            t.queued = false;
            invalidate();
        });
    }

    // ------------------------------------------------------------------ drawing

    private float[] toWorld(float px, float py) {
        return new float[]{(px - getWidth() / 2f) / scale + cx, (py - getHeight() / 2f) / scale + cy};
    }

    private float sx(float wx) {
        return (wx - cx) * scale + getWidth() / 2f;
    }

    private float sy(float wy) {
        return (wy - cy) * scale + getHeight() / 2f;
    }

    private void zoomAt(float f, float px, float py) {
        // all the way in: the floor plan, a square a finger wide
        float ns = Math.max(density / 48f, Math.min(MAX_ZOOM * density, scale * f));
        float[] w = toWorld(px, py);
        scale = ns;
        if (!follow) {
            cx = w[0] - (px - getWidth() / 2f) / ns;
            cy = w[1] - (py - getHeight() / 2f) / ns;
        }
        checkPlanWanted();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Palette p = pal;
        canvas.drawColor(p.land);
        Data d = data;
        boolean animate = false;
        if (player != null && shown != null) {
            float dx = player[0] - shown[0], dy = player[1] - shown[1];
            if (Math.abs(dx) > 0.01f || Math.abs(dy) > 0.01f || Math.abs(player[2] - shown[2]) > 0.5f) {
                boolean jump = dx * dx + dy * dy > 400;
                shown[0] = jump ? player[0] : shown[0] + dx * 0.25f;
                shown[1] = jump ? player[1] : shown[1] + dy * 0.25f;
                float dd = ((player[2] - shown[2] + 540) % 360) - 180;
                shown[2] = (shown[2] + dd * 0.3f + 360) % 360;
                animate = true;
            }
            if (follow && (Math.abs(cx - shown[0]) > 0.01f || Math.abs(cy - shown[1]) > 0.01f)) {
                cx += (shown[0] - cx) * 0.3f;
                cy += (shown[1] - cy) * 0.3f;
                animate = true;
            }
        }
        if (d != null) drawTiles(canvas);
        if (fog != null) {
            // unexplored ground, coloured like the game's own "unvisited" map areas
            rect.set(sx(0), sy(0), sx(fogW * fogUnit), sy(fogH * fogUnit));
            canvas.drawBitmap(fog, null, rect, fogPaint);
        }
        if (p.paper && paperTexture != null) canvas.drawRect(0, 0, getWidth(), getHeight(), paperPaint);
        drawPlan(canvas, p);   // over the paper: the game's own floors keep their colours
        if (d != null && labels && scale / density < PLAN_ZOOM) drawLabels(canvas, d, p);
        if (showTrail) drawTrail(canvas, p);
        drawStamps(canvas, p);
        drawMarks(canvas, p);
        drawBase(canvas, p);
        drawCar(canvas, p);
        drawWaypoint(canvas, p);
        drawTeam(canvas, p);
        drawPins(canvas, p);
        drawZombies(canvas, p);
        drawPlayer(canvas, p);
        if (dark) canvas.drawColor(0xE6050607);
        if (animate) postInvalidateOnAnimation();
    }

    private void drawTiles(Canvas canvas) {
        int level = levelOf(scale);
        float sL = (float) Math.pow(2, level);
        float span = TILE / sL;
        float[] a = toWorld(0, 0), b = toWorld(getWidth(), getHeight());
        int tx0 = (int) Math.floor(a[0] / span), tx1 = (int) Math.floor(b[0] / span);
        int ty0 = (int) Math.floor(a[1] / span), ty1 = (int) Math.floor(b[1] / span);
        float size = span * scale;
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int ty = ty0; ty <= ty1; ty++) {
                float x = sx(tx * span), y = sy(ty * span);
                rect.set(x, y, x + size + 0.5f, y + size + 0.5f);
                Tile t = tile(level, tx, ty);
                if (t.bitmap != null && !t.bitmap.isRecycled()) {
                    canvas.drawBitmap(t.bitmap, null, rect, tilePaint);
                    continue;
                }
                // until it's ready, stretch the best coarser tile we already have
                for (int up = 1; up <= 5; up++) {
                    int l2 = level - up;
                    float span2 = TILE / (float) Math.pow(2, l2);
                    int px = (int) Math.floor(tx * span / span2), py = (int) Math.floor(ty * span / span2);
                    Tile t2 = peekTile(l2, px, py);
                    if (t2 != null && t2.bitmap != null && !t2.bitmap.isRecycled()) {
                        float frac = span / span2;
                        int ox = (int) ((tx * span - px * span2) / span2 * TILE), oy = (int) ((ty * span - py * span2) / span2 * TILE);
                        int sz = Math.max(1, (int) (TILE * frac));
                        src.set(ox, oy, ox + sz, oy + sz);
                        canvas.drawBitmap(t2.bitmap, src, rect, tilePaint);
                        break;
                    }
                }
            }
        }
    }

    private void drawLabels(Canvas canvas, Data d, Palette p) {
        float s = scale / density;   // css-like zoom, to keep the same thresholds as the web app
        for (int i = 0; i < d.labelPos.size(); i++) {
            float[] l = d.labelPos.get(i);
            boolean big = l[2] >= 1.5f;
            if (big ? s > 1.1f : s < 0.3f) continue;
            float zoomFit = Math.min(1f, Math.max(0.6f, s * (big ? 2.5f : 1.4f)));
            float size = (big ? Math.min(30, 11 + l[2] * 5) : Math.min(16, 9 + l[2] * 5)) * zoomFit * density;
            float x = sx(l[0]), y = sy(l[1]);
            if (x < -300 || y < -60 || x > getWidth() + 300 || y > getHeight() + 60) continue;
            if (fogged(l[0], l[1])) continue;   // no names for places the character hasn't seen
            boolean water = l[4] > 0;
            labelPaint.setTextSize(size);
            labelPaint.setTypeface(Typeface.create("serif", water ? Typeface.BOLD_ITALIC : Typeface.BOLD));
            labelPaint.setColor(water ? p.labelWater : p.label);
            haloPaint.setTextSize(size);
            haloPaint.setTypeface(labelPaint.getTypeface());
            haloPaint.setStrokeWidth(4 * density);
            haloPaint.setColor(p.halo);
            canvas.save();
            canvas.translate(x, y);
            if (l[3] != 0) canvas.rotate(l[3]);
            String text = d.labelText.get(i);
            float base = size * 0.35f;
            canvas.drawText(text, 0, base, haloPaint);
            canvas.drawText(text, 0, base, labelPaint);
            canvas.restore();
        }
    }

    private void drawWaypoint(Canvas canvas, Palette p) {
        if (waypoint == null) return;
        float x = sx(waypoint[0] + 0.5f), y = sy(waypoint[1] + 0.5f);
        if (shown != null) {
            linePaint.setColor(p.wp);
            linePaint.setAlpha(190);
            linePaint.setStrokeWidth(2.5f * density);
            linePaint.setPathEffect(new DashPathEffect(new float[]{7 * density, 7 * density}, 0));
            canvas.drawLine(sx(shown[0]), sy(shown[1]), x, y, linePaint);
        }
        float r = 12 * density;
        markPath.rewind();
        markPath.moveTo(x, y);
        markPath.cubicTo(x - r * 0.33f, y - r * 0.66f, x - r, y - r * 1.15f, x - r, y - r * 1.85f);
        rect.set(x - r, y - r * 2.85f, x + r, y - r * 0.85f);
        markPath.arcTo(rect, 180, 180);
        markPath.cubicTo(x + r, y - r * 1.15f, x + r * 0.33f, y - r * 0.66f, x, y);
        markPath.close();
        markPaint.setStyle(Paint.Style.STROKE);
        markPaint.setStrokeWidth(3 * density);
        markPaint.setColor(p.halo);
        canvas.drawPath(markPath, markPaint);
        markPaint.setStyle(Paint.Style.FILL);
        markPaint.setColor(p.wp);
        canvas.drawPath(markPath, markPaint);
        markPaint.setColor(p.halo);
        canvas.drawCircle(x, y - r * 1.85f, r * 0.38f, markPaint);
    }

    private void drawPlayer(Canvas canvas, Palette p) {
        if (shown == null) return;
        float x = sx(shown[0]), y = sy(shown[1]);
        float r = 11 * density;
        markPaint.setStyle(Paint.Style.FILL);
        markPaint.setColor(p.player);
        markPaint.setAlpha(56);
        canvas.drawCircle(x, y, r * 2.4f, markPaint);
        markPaint.setAlpha(255);
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(shown[2]);
        markPath.rewind();
        markPath.moveTo(r * 1.5f, 0);
        markPath.lineTo(-r * 0.9f, r * 0.95f);
        markPath.lineTo(-r * 0.35f, 0);
        markPath.lineTo(-r * 0.9f, -r * 0.95f);
        markPath.close();
        markPaint.setStyle(Paint.Style.STROKE);
        markPaint.setStrokeJoin(Paint.Join.ROUND);
        markPaint.setStrokeWidth(4 * density);
        markPaint.setColor(0xFFFFFFFF);
        canvas.drawPath(markPath, markPaint);
        markPaint.setStyle(Paint.Style.FILL);
        markPaint.setColor(p.player);
        canvas.drawPath(markPath, markPaint);
        canvas.restore();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    private String buildingAt(float wx, float wy) {
        Data d = data;
        if (d == null) return null;
        int[] list = featuresIn(d, wx - 1, wy - 1, wx + 1, wy + 1);
        for (int i = list.length - 1; i >= 0; i--) {
            int f = list[i];
            int kd = d.kind[f];
            if (kd < RES) continue;
            boolean inside = false;
            for (int r = d.fRing[f]; r < d.fRing[f + 1]; r++) {
                int a = d.rPt[r], b = d.rPt[r + 1];
                for (int pI = a, q = b - 1; pI < b; q = pI++) {
                    float xi = d.pts[pI * 2], yi = d.pts[pI * 2 + 1], xj = d.pts[q * 2], yj = d.pts[q * 2 + 1];
                    if ((yi > wy) != (yj > wy) && wx < (xj - xi) * (wy - yi) / (yj - yi) + xi) inside = !inside;
                }
            }
            if (inside) {
                for (String[] k : KIND_NAMES) if (Integer.parseInt(k[0]) == kd) return k[1];
                return "Building";
            }
        }
        return null;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        worker.quitSafely();
    }
}
