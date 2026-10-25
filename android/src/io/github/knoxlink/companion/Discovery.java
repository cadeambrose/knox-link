package io.github.knoxlink.companion;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds Knox Link games on the local network: the mod's server answers a "KNOXLINK?" broadcast
 * on UDP port 7878 with its computer name. Used for streaming setups (game on a PC, this screen
 * on a handheld or phone) so nobody has to type an address.
 */
final class Discovery {
    static final class Game {
        final String name, address;

        Game(String name, String address) {
            this.name = name;
            this.address = address;
        }
    }

    interface Listener {
        void onGames(List<Game> games);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private Thread thread;

    synchronized void start(Listener l) {
        if (running) return;
        running = true;
        thread = new Thread(() -> loop(l), "knoxlink-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void loop(Listener l) {
        while (running) {
            Map<String, Game> found = new LinkedHashMap<>();
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                s.setSoTimeout(400);
                byte[] q = "KNOXLINK?".getBytes(StandardCharsets.UTF_8);
                for (int port = 7878; port <= 7880; port++) {
                    s.send(new DatagramPacket(q, q.length, InetAddress.getByName("255.255.255.255"), port));
                }
                long until = System.currentTimeMillis() + 1500;
                byte[] buf = new byte[512];
                while (System.currentTimeMillis() < until) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        JSONObject o = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                        if (!"knoxlink".equals(o.optString("t"))) continue;
                        String ip = p.getAddress().getHostAddress();
                        if (ip.startsWith("127.")) continue;
                        String addr = ip + ":" + o.optInt("port", 7878);
                        found.put(addr, new Game(o.optString("name", "Project Zomboid"), addr));
                    } catch (java.net.SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            final List<Game> list = new ArrayList<>(found.values());
            main.post(() -> l.onGames(list));
            try {
                Thread.sleep(3500);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
