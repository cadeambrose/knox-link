package knoxlink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import zombie.characters.Faction;
import zombie.characters.IsoPlayer;
import zombie.iso.areas.SafeHouse;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.ServerOptions;
import zombie.worldMap.WorldMapRemotePlayer;
import zombie.worldMap.WorldMapRemotePlayers;

/**
 * Other survivors for the map: split-screen players on this machine, and in multiplayer the
 * players the game's own world map would show (the server's MapRemotePlayerVisibility rule:
 * nobody, your faction and safehouse, those plus who you can see, or everyone).
 * Runs on the game thread (called from Lua).
 */
final class Team {
    private static String last = "";

    private Team() {}

    static void sync() {
        IsoPlayer me = IsoPlayer.getInstance();
        if (me == null) return;
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < IsoPlayer.players.length; i++) {
            IsoPlayer p = IsoPlayer.players[i];
            if (p == null || p == me || p.isDead()) continue;
            list.add(entry(p.getDescriptor().getForename() + " " + p.getDescriptor().getSurname(), p.getX(), p.getY(), "local"));
        }
        if (GameClient.client) {
            GameServer.MapRemotePlayerVisibility rule = visibility();
            for (WorldMapRemotePlayer rp : WorldMapRemotePlayers.instance.getPlayers()) {
                if (!rp.hasFullData() || rp.isInvisible()) continue;
                String user = rp.getUsername();
                if (user == null || user.equals(me.getUsername())) continue;
                if (!shows(rule, me, rp)) continue;
                String name = (rp.getForename() + " " + rp.getSurname()).trim();
                list.add(entry(name.isEmpty() ? user : name, rp.getX(), rp.getY(), user));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        String json = Json.write(out);
        if (json.equals(last)) return;
        last = json;
        KnoxServer.get().publish("team", json);
    }

    private static Map<String, Object> entry(String name, float x, float y, String user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", name);
        m.put("u", user);
        m.put("x", Math.round(x * 10) / 10.0);
        m.put("y", Math.round(y * 10) / 10.0);
        return m;
    }

    private static GameServer.MapRemotePlayerVisibility visibility() {
        try {
            int v = ServerOptions.getInstance().mapRemotePlayerVisibility.getValue();
            GameServer.MapRemotePlayerVisibility[] all = GameServer.MapRemotePlayerVisibility.values();
            return all[Math.max(0, Math.min(all.length - 1, v - 1))];
        } catch (Throwable t) {
            return GameServer.MapRemotePlayerVisibility.FACTION_ONLY;
        }
    }

    private static boolean shows(GameServer.MapRemotePlayerVisibility rule, IsoPlayer me, WorldMapRemotePlayer rp) {
        switch (rule) {
            case ALL:
                return true;
            case NONE:
                return false;
            case FACTION_AND_VISIBLE_ONLY:
                if (friend(me, rp)) return true;
                for (IsoPlayer p : GameClient.instance.getPlayers()) {
                    if (rp.getUsername().equals(p.getUsername())) return me.checkCanSeeClient(p) && me.CanSee(p);
                }
                return false;
            default:
                return friend(me, rp);
        }
    }

    private static boolean friend(IsoPlayer me, WorldMapRemotePlayer rp) {
        try {
            return Faction.isInSameFaction(me, rp.getUsername()) || SafeHouse.isInSameSafehouse(me.getUsername(), rp.getUsername());
        } catch (Throwable t) {
            return false;
        }
    }
}
