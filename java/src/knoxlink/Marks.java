package knoxlink;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import zombie.inventory.types.MapItem;
import zombie.worldMap.symbols.MapSymbolDefinitions;
import zombie.worldMap.symbols.WorldMapBaseSymbol;
import zombie.worldMap.symbols.WorldMapSymbols;
import zombie.worldMap.symbols.WorldMapTextSymbol;
import zombie.worldMap.symbols.WorldMapTextureSymbol;

/**
 * The player's own marks on the in-game world map (symbols and notes). Screens get the list
 * whenever it changes, and can add or erase marks: it is the same list the game saves, so a
 * mark made on the second screen is on the paper map in the game too, and the other way round.
 * Everything except {@link #definitions()} runs on the game thread (called from Lua).
 */
final class Marks {
    /** ISMap.SCALE: the size the game's own map tools give a new mark. */
    private static final float SCALE = 0.666f;

    private static WorldMapSymbols lastSymbols;
    private static long lastCount = -1;
    private static String lastTools = "";
    private static Field fx, fy, fr, fg, fb;
    private static boolean fieldsTried;

    private Marks() {}

    private static WorldMapSymbols symbols() {
        MapItem map = MapItem.getSingleton();
        return map == null ? null : map.getSymbols();
    }

    /** Publishes "marks" when the marks or the player's writing tools changed. */
    static void sync(Object tools) {
        WorldMapSymbols s = symbols();
        if (s == null) return;
        String toolsJson = Json.write(tools);
        long count = s.getModificationCount();
        if (s == lastSymbols && count == lastCount && toolsJson.equals(lastTools)) return;
        lastSymbols = s;
        lastCount = count;
        lastTools = toolsJson;

        List<Object> list = new ArrayList<>();
        int n = s.getSymbolCount();
        for (int i = 0; i < n; i++) {
            WorldMapBaseSymbol b = s.getSymbolByIndex(i);
            if (b == null || !b.isUserDefined()) continue;   // the map's built-in labels
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("i", i);
            if (b instanceof WorldMapTextureSymbol) {
                m.put("sym", ((WorldMapTextureSymbol) b).getSymbolID());
            } else if (b instanceof WorldMapTextSymbol) {
                WorldMapTextSymbol t = (WorldMapTextSymbol) b;
                String text = t.getTranslatedText();
                if (text == null || text.isEmpty()) text = t.getUntranslatedText();
                if (text == null || text.isEmpty()) continue;
                m.put("text", text);
            } else {
                continue;
            }
            float[] pos = position(b);
            if (pos == null) continue;
            m.put("x", Math.round(pos[0] * 10) / 10.0);
            m.put("y", Math.round(pos[1] * 10) / 10.0);
            m.put("c", String.format("%02x%02x%02x", byte255(pos[2]), byte255(pos[3]), byte255(pos[4])));
            float rot = b.getRotation();
            if (rot != 0) m.put("rot", Math.round(rot));
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("v", count);
        out.put("tools", tools);
        out.put("list", list);
        KnoxServer.get().publish("marks", Json.write(out));
    }

    /** x, y, r, g, b of a mark. The game keeps them in package-private fields. */
    private static float[] position(WorldMapBaseSymbol b) {
        if (!fieldsTried) {
            fieldsTried = true;
            try {
                fx = field("x");
                fy = field("y");
                fr = field("r");
                fg = field("g");
                fb = field("b");
            } catch (Throwable t) {
                Log.error("map marks: this game version stores them differently", t);
            }
        }
        if (fx == null || fy == null) return null;
        try {
            return new float[]{fx.getFloat(b), fy.getFloat(b),
                    fr == null ? 0 : fr.getFloat(b), fg == null ? 0 : fg.getFloat(b), fb == null ? 0 : fb.getFloat(b)};
        } catch (Throwable t) {
            return null;
        }
    }

    private static int byte255(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255)));
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field f = WorldMapBaseSymbol.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Adds a symbol (sym = an id from {@link #definitions()}) or a note; returns null or why not. */
    static String add(String sym, String text, float x, float y, float r, float g, float b) {
        WorldMapSymbols s = symbols();
        if (s == null) return "The world map isn't ready";
        if (sym != null && !sym.isEmpty()) {
            if (MapSymbolDefinitions.getInstance().getSymbolById(sym) == null) return "Unknown symbol";
            s.addTexture(sym, x, y, 0.5f, 0.5f, SCALE, r, g, b, 1f);
        } else if (text != null && !text.trim().isEmpty()) {
            String t = text.trim();
            if (t.length() > 60) t = t.substring(0, 60);
            s.addUntranslatedText(t, WorldMapSymbols.getDefaultTextLayerID(), x, y, r, g, b, 1f);
            WorldMapBaseSymbol added = s.getSymbolByIndex(s.getSymbolCount() - 1);
            if (added != null) added.setAnchor(0.5f, 0.5f);
        } else {
            return "Nothing to add";
        }
        s.invalidateLayout();
        return null;
    }

    /** Erases the mark at index i, if it is still the one at (x, y) the screen showed. */
    static String remove(int i, float x, float y) {
        WorldMapSymbols s = symbols();
        if (s == null) return "The world map isn't ready";
        if (i < 0 || i >= s.getSymbolCount()) return "That mark is gone";
        WorldMapBaseSymbol b = s.getSymbolByIndex(i);
        float[] pos = b == null ? null : position(b);
        if (pos == null || !b.isUserDefined() || Math.abs(pos[0] - x) > 1 || Math.abs(pos[1] - y) > 1) return "That mark moved";
        s.removeSymbolByIndex(i);
        s.invalidateLayout();
        return null;
    }

    /** Every symbol the game's map tools offer: id, picture (under media/) and group. */
    static String definitions() {
        List<Object> list = new ArrayList<>();
        try {
            MapSymbolDefinitions defs = MapSymbolDefinitions.getInstance();
            for (int i = 0; i < defs.getSymbolCount(); i++) {
                MapSymbolDefinitions.MapSymbolDefinition d = defs.getSymbolByIndex(i);
                if (d != null) list.add(definition(d.getId(), d.getTexturePath(), d.getTab()));
            }
        } catch (Throwable ignored) {
            // not loaded (dev server): read the game's definitions file below
        }
        if (list.isEmpty()) {
            // MapSymbolDefinitions.getInstance():addTexture("Apple", "media/ui/LootableMaps/map_apple.png", "Loot")
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("addTexture\\(\"([^\"]+)\",\\s*\"([^\"]+)\"(?:,\\s*\"([^\"]*)\")?");
            try {
                String lua = new String(java.nio.file.Files.readAllBytes(Paths.media("lua/shared/Definitions/MapSymbolDefinitions.lua").toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                java.util.regex.Matcher m = p.matcher(lua);
                while (m.find()) list.add(definition(m.group(1), m.group(2), m.group(3)));
            } catch (Throwable ignored) {
            }
        }
        return Json.write(list);
    }

    private static Map<String, Object> definition(String id, String tex, String tab) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        if (tex != null && tex.startsWith("media/")) tex = tex.substring(6);
        m.put("tex", tex);
        m.put("tab", tab == null ? "" : tab);
        return m;
    }
}
