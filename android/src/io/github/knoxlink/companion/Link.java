package io.github.knoxlink.companion;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Connection to the game. The mod's server accepts "KNOXLINK 1" followed by one JSON message per
 * line in each direction. Reconnects by itself; every callback arrives on the main thread.
 */
final class Link {
    interface Listener {
        void onSocket(boolean up);

        void onMessage(JSONObject m);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private volatile String host = "127.0.0.1";
    private volatile int port = 7878;
    private volatile String pin = "";
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean running;
    private Thread thread;

    Link(Listener listener) {
        this.listener = listener;
    }

    void configure(String address, String pin) {
        String a = address == null ? "" : address.trim();
        int colon = a.lastIndexOf(':');
        String h = a;
        int p = 7878;
        if (colon > 0) {
            h = a.substring(0, colon);
            try {
                p = Integer.parseInt(a.substring(colon + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        if (h.isEmpty()) h = "127.0.0.1";
        boolean changed = !h.equals(host) || p != port;
        host = h;
        port = p;
        this.pin = pin == null ? "" : pin;
        if (changed) dropSocket();
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    String baseUrl() {
        return "http://" + host + ":" + port;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "knoxlink-link");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        running = false;
        dropSocket();
        if (thread != null) thread.interrupt();
    }

    /** Re-sends the hello (e.g. with a new PIN) on the open connection, or waits for the next one. */
    void hello() {
        JSONObject h = new JSONObject();
        try {
            h.put("t", "hello");
            h.put("pin", pin);
            h.put("client", "android");
        } catch (Exception ignored) {
        }
        send(h);
    }

    void send(JSONObject m) {
        final String line = m.toString() + "\n";
        writer.execute(() -> {
            OutputStream o = out;
            if (o == null) return;
            try {
                o.write(line.getBytes(StandardCharsets.UTF_8));
                o.flush();
            } catch (Exception e) {
                dropSocket();
            }
        });
    }

    private void dropSocket() {
        Socket s = socket;
        socket = null;
        out = null;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private void loop() {
        int attempt = 0;
        while (running) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, port), 2500);
                s.setTcpNoDelay(true);
                s.setSoTimeout(40000);   // the server sends a keep-alive every 15 s
                socket = s;
                out = s.getOutputStream();
                out.write("KNOXLINK 1\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                attempt = 0;
                main.post(() -> listener.onSocket(true));
                hello();
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8), 1 << 16);
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    final JSONObject m;
                    try {
                        m = new JSONObject(line);
                    } catch (Exception e) {
                        continue;
                    }
                    main.post(() -> listener.onMessage(m));
                }
            } catch (Exception ignored) {
            } finally {
                boolean wasUp = socket == s;
                dropSocket();
                try { s.close(); } catch (Exception ignored) {}
                if (wasUp) main.post(() -> listener.onSocket(false));
            }
            if (!running) break;
            long delay = Math.min(4000, 300L * (1L << Math.min(attempt++, 4)));
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                // stop() or a new address: loop again right away
            }
        }
    }
}
