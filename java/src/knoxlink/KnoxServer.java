package knoxlink;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/**
 * A deliberately small HTTP + WebSocket server.
 *
 * HTTP serves the companion web app (bundled in this jar under /web) and the map data.
 * One WebSocket per screen carries game state out (latest value per channel, so a slow
 * phone never builds a backlog) and button presses in. Devices other than this one must
 * send the PIN before they get anything.
 */
final class KnoxServer {
    static final String VERSION = "1.2.0";

    private static final KnoxServer INSTANCE = new KnoxServer();

    static KnoxServer get() {
        return INSTANCE;
    }

    private Config config;
    private volatile ServerSocket serverSocket;
    private volatile int boundPort = -1;
    private volatile String startError;

    private final List<Client> clients = new CopyOnWriteArrayList<>();
    /** Last published value of every channel; new screens get these straight away. */
    private final Map<String, String> latest = new ConcurrentHashMap<>();
    /** Commands from screens, drained by Lua on the game thread. */
    final ConcurrentLinkedQueue<Command> commands = new ConcurrentLinkedQueue<>();
    private final Map<Integer, Client> commandOwners = new ConcurrentHashMap<>();
    private final AtomicInteger nextCommandId = new AtomicInteger(1);
    private final Map<String, int[]> failedPins = new ConcurrentHashMap<>();   // ip -> {count, lockedUntilSecond}
    private final Map<String, byte[][]> staticCache = new ConcurrentHashMap<>(); // path -> {plain, gzip}
    /**
     * Handed to a screen once it's in (hello): the HTTP requests for the character's own things
     * (photos, the way they walked) carry it as k=, so they need the PIN like everything else.
     */
    private final String sessionKey = newKey();

    private static String newKey() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private boolean keyOk(Socket s, String requestTarget) {
        if (s.getInetAddress().isLoopbackAddress()) return true;
        String k = query(requestTarget, "k");
        return k != null && java.security.MessageDigest.isEqual(k.getBytes(StandardCharsets.UTF_8),
                sessionKey.getBytes(StandardCharsets.UTF_8));
    }

    static final class Command {
        final int id;
        final String name;
        final Map<String, Object> args;

        Command(int id, String name, Map<String, Object> args) {
            this.id = id;
            this.name = name;
            this.args = args;
        }
    }

    private KnoxServer() {}

    // ------------------------------------------------------------------ lifecycle

    synchronized void start() {
        if (serverSocket != null) return;
        config = Config.load();
        bind();
        MapData.get().buildInBackground();
    }

    /** (Re)binds the listening socket, e.g. after the LAN switch changed. */
    synchronized void bind() {
        closeServerSocket();
        startError = null;
        InetAddress addr;
        try {
            addr = config.lan ? null : InetAddress.getLoopbackAddress();
        } catch (Exception e) {
            addr = null;
        }
        IOException last = null;
        for (int port = config.port; port < config.port + 10; port++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(addr == null ? new InetSocketAddress(port) : new InetSocketAddress(addr, port), 16);
                serverSocket = ss;
                boundPort = port;
                Thread t = new Thread(() -> acceptLoop(ss), "KnoxLink-accept");
                t.setDaemon(true);
                t.start();
                if (config.lan) startDiscovery(port);
                Log.info("listening on port " + port + (config.lan ? " (Wi-Fi devices allowed, PIN " + config.pin + ")" : " (this device only)"));
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        boundPort = -1;
        startError = "No free port near " + config.port + (last == null ? "" : ": " + last.getMessage());
        Log.error(startError, null);
    }

    private void closeServerSocket() {
        ServerSocket ss = serverSocket;
        serverSocket = null;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) {}
        }
        java.net.DatagramSocket d = discovery;
        discovery = null;
        if (d != null) d.close();
    }

    // ------------------------------------------------------------------ discovery

    private volatile java.net.DatagramSocket discovery;

    /**
     * Answers "KNOXLINK?" broadcasts on the same port number (UDP), so the Android app can list
     * games on the network without anyone typing an address.
     */
    private void startDiscovery(int port) {
        try {
            java.net.DatagramSocket d = new java.net.DatagramSocket(null);
            d.setReuseAddress(true);
            d.bind(new InetSocketAddress(port));
            discovery = d;
            Thread t = new Thread(() -> {
                byte[] buf = new byte[256];
                while (!d.isClosed()) {
                    try {
                        java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                        d.receive(p);
                        String q = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        if (!q.startsWith("KNOXLINK?")) continue;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("t", "knoxlink");
                        m.put("name", hostName());
                        m.put("port", (double) boundPort);
                        m.put("version", VERSION);
                        byte[] reply = Json.write(m).getBytes(StandardCharsets.UTF_8);
                        d.send(new java.net.DatagramPacket(reply, reply.length, p.getAddress(), p.getPort()));
                    } catch (Exception e) {
                        if (d.isClosed()) return;
                    }
                }
            }, "KnoxLink-discovery");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            Log.info("network discovery unavailable (" + e.getMessage() + "); the address still works");
        }
    }

    private static String hostName() {
        String n = System.getenv("COMPUTERNAME");
        if (n == null || n.isEmpty()) n = System.getenv("HOSTNAME");
        if (n == null || n.isEmpty()) {
            try {
                n = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                n = "";
            }
        }
        if (n == null || n.isEmpty() || n.equalsIgnoreCase("localhost")) n = System.getProperty("os.name", "Game");
        return n;
    }

    void setLan(boolean lan) {
        if (config == null || config.lan == lan) return;
        config.lan = lan;
        config.save();
        for (Client c : clients) {
            if (!c.local) c.close();
        }
        bind();
    }

    void newPin() {
        if (config == null) return;
        config.pin = Config.newPin();
        config.save();
        for (Client c : clients) {
            if (!c.local) c.close();   // everyone on Wi-Fi has to enter the new PIN
        }
    }

    // ------------------------------------------------------------------ status for Lua

    Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", VERSION);
        m.put("running", boundPort > 0);
        m.put("port", (double) boundPort);
        m.put("lan", config != null && config.lan);
        m.put("pin", config == null ? "" : config.pin);
        m.put("error", startError == null ? "" : startError);
        int screens = 0;
        for (Client c : clients) if (c.authed) screens++;
        m.put("screens", (double) screens);
        List<Object> urls = new ArrayList<>();
        if (boundPort > 0) {
            if (config.lan) {
                for (String ip : lanAddresses()) urls.add("http://" + ip + ":" + boundPort);
            }
            urls.add("http://127.0.0.1:" + boundPort);
        }
        m.put("urls", urls);
        m.put("mapState", MapData.get().state());
        return m;
    }

    static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs == null) return out;
            for (NetworkInterface ni : Collections.list(ifs)) {
                try {
                    if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                    String name = (ni.getDisplayName() + " " + ni.getName()).toLowerCase(Locale.ROOT);
                    // Hyper-V, WSL, VPN and docker adapters are rarely what a phone can reach.
                    if (name.contains("vethernet") || name.contains("virtual") || name.contains("vmware")
                            || name.contains("docker") || name.contains("wsl") || name.contains("tailscale")) continue;
                    for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                        if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                            out.add(a.getHostAddress());
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        // Home networks first (192.168.x, 10.x, 172.16-31.x), so the in-game panel shows the useful one on top.
        out.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        return out;
    }

    private static int rank(String ip) {
        if (ip.startsWith("192.168.")) return 0;
        if (ip.startsWith("10.")) return 1;
        if (ip.startsWith("172.")) return 2;
        return 3;
    }

    // ------------------------------------------------------------------ state from Lua

    // The game reports some things (its clock, the character) four times a second; when nothing changed, the screens
    // already have it, and every message wakes them up to redraw. The same message again goes out only this often,
    // so they still know the game is there (they call it quiet after 4 seconds).
    private static final long SAME_AGAIN_MS = 1500;
    private final Map<String, Long> sentAt = new ConcurrentHashMap<>();

    void publish(String channel, String json) {
        String before = latest.put(channel, json);
        long now = System.currentTimeMillis();
        if (json.equals(before)) {
            Long at = sentAt.get(channel);
            if (at != null && now - at < SAME_AGAIN_MS) return;
        }
        sentAt.put(channel, now);
        String msg = "{\"t\":\"s\",\"c\":" + quote(channel) + ",\"d\":" + json + "}";
        for (Client c : clients) {
            if (c.authed && c.wants(channel)) c.offer(channel, msg);
        }
    }

    /** One-off event (a toast, a sound cue); not remembered for late joiners. */
    void event(String json) {
        String msg = "{\"t\":\"e\",\"d\":" + json + "}";
        for (Client c : clients) {
            if (c.authed) c.offerOnce(msg);
        }
    }

    boolean anyoneWants(String channel) {
        for (Client c : clients) {
            if (c.authed && c.wants(channel)) return true;
        }
        return false;
    }

    int screenCount() {
        int n = 0;
        for (Client c : clients) if (c.authed) n++;
        return n;
    }

    void reply(int id, boolean ok, String message) {
        Client c = commandOwners.remove(id);
        int browserId = clientIdFor(id);
        if (c == null) return;
        StringBuilder sb = new StringBuilder("{\"t\":\"ack\",\"id\":").append(browserId).append(",\"ok\":").append(ok);
        if (message != null && !message.isEmpty()) {
            sb.append(",\"msg\":");
            Json.string(sb, message);
        }
        sb.append('}');
        c.offerOnce(sb.toString());
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        Json.string(sb, s);
        return sb.toString();
    }

    // ------------------------------------------------------------------ HTTP

    private void acceptLoop(ServerSocket ss) {
        while (!ss.isClosed()) {
            try {
                Socket s = ss.accept();
                Thread t = new Thread(() -> handle(s), "KnoxLink-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!ss.isClosed()) Log.error("accept failed", e);
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null) { s.close(); return; }
            if (requestLine.startsWith("KNOXLINK ")) {
                // The Android app speaks plain lines of JSON instead of WebSocket.
                s.setSoTimeout(0);
                Client c = new Client(s, in, out, true);
                clients.add(c);
                c.run();
                return;
            }
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !"GET".equals(parts[0])) {
                send(out, 405, "text/plain", "GET only".getBytes(StandardCharsets.UTF_8), false, null);
                s.close();
                return;
            }
            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            boolean gzipOk = headers.getOrDefault("accept-encoding", "").contains("gzip");

            if ("/ws".equals(path) && "websocket".equalsIgnoreCase(headers.getOrDefault("upgrade", ""))) {
                upgrade(s, in, out, headers);
                return;   // the client owns the socket now
            }
            if ("/api/map".equals(path)) {
                byte[] data = MapData.get().gzipped();
                if (data == null) {
                    send(out, 503, "application/json", ("{\"state\":" + quote(MapData.get().state()) + "}").getBytes(StandardCharsets.UTF_8), false, "Retry-After: 2\r\n");
                } else {
                    send(out, 200, "application/octet-stream", data, true, "Cache-Control: no-cache\r\n");
                }
            } else if ("/api/map/fog".equals(path)) {
                byte[] fog = Fog.maskGz();
                if (fog == null) send(out, 404, "text/plain", "no fog data".getBytes(StandardCharsets.UTF_8), false, null);
                else send(out, 200, "application/octet-stream", fog, true, "Cache-Control: no-store\r\n");
            } else if ("/api/debug/profile".equals(path)) {
                // what keeps the game busy, for tuning; only for the device itself
                if (!s.getInetAddress().isLoopbackAddress()) {
                    send(out, 403, "text/plain", "only from this device".getBytes(StandardCharsets.UTF_8), false, null);
                } else {
                    int secs = 10;
                    try { secs = Math.max(1, Math.min(60, Integer.parseInt(query(parts[1], "s")))); } catch (Exception ignored) {}
                    String report;
                    try { report = Profiler.sample(secs * 1000L, 20); } catch (InterruptedException e) { report = "interrupted"; }
                    send(out, 200, "text/plain; charset=utf-8", report.getBytes(StandardCharsets.UTF_8), false, "Cache-Control: no-store\r\n");
                }
            } else if ("/api/art/icon".equals(path)) {
                byte[] png = Art.looseIcon(query(parts[1], "n"));
                if (png == null) send(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), false, null);
                else send(out, 200, "image/png", png, false, "Cache-Control: max-age=86400\r\n");
            } else if ("/api/avatar.png".equals(path)) {
                byte[] img = Avatar.png();
                if (img != null) send(out, 200, "image/png", img, false, "Cache-Control: no-store\r\n");
                else send(out, Avatar.available() ? 503 : 410, "text/plain", "no picture".getBytes(StandardCharsets.UTF_8), false, "Cache-Control: no-store\r\n");
            } else if ("/api/art/floor".equals(path)) {
                // one of the game's floors, seen from above, for the floor plan
                byte[] png = FloorArt.png(query(parts[1], "n"));
                if (png == null) send(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), false, "Cache-Control: max-age=3600\r\n");
                else send(out, 200, "image/png", png, false, "Cache-Control: max-age=86400\r\n");
            } else if ("/api/photo".equals(path)) {
                byte[] jpg = keyOk(s, parts[1]) ? Photo.get(query(parts[1], "id")) : null;
                if (jpg == null) send(out, keyOk(s, parts[1]) ? 404 : 403, "text/plain", "no photo".getBytes(StandardCharsets.UTF_8), false, "Cache-Control: no-store\r\n");
                else send(out, 200, "image/jpeg", jpg, false, "Cache-Control: private, max-age=86400\r\n");
            } else if ("/api/trail".equals(path)) {
                // the way a character walked, for their recap: {"id":..,"p":[x,y,x,y,...]}
                if (!keyOk(s, parts[1])) {
                    send(out, 403, "text/plain", "PIN first".getBytes(StandardCharsets.UTF_8), false, null);
                } else {
                    String id = query(parts[1], "id");
                    int[] t = id == null || !id.matches("[A-Za-z0-9_-]{1,40}") ? null : Memory.trailOf(id);
                    StringBuilder sb = new StringBuilder(t == null ? 32 : t.length * 6 + 32).append("{\"id\":").append(quote(id == null ? "" : id)).append(",\"p\":[");
                    if (t != null) for (int i = 0; i < t.length; i++) sb.append(i == 0 ? "" : ",").append(t[i]);
                    sb.append("]}");
                    byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
                    if (gzipOk) {
                        ByteArrayOutputStream gz = new ByteArrayOutputStream(body.length / 3 + 64);
                        try (GZIPOutputStream g = new GZIPOutputStream(gz)) {
                            g.write(body);
                        }
                        body = gz.toByteArray();
                    }
                    send(out, 200, "application/json", body, gzipOk, "Cache-Control: no-store\r\n");
                }
            } else if ("/api/map/symbols".equals(path)) {
                String defs;
                try { defs = Marks.definitions(); } catch (Throwable t) { defs = "[]"; }
                send(out, 200, "application/json", defs.getBytes(StandardCharsets.UTF_8), false, "Cache-Control: max-age=3600\r\n");
            } else if ("/api/art/index".equals(path)) {
                send(out, 200, "application/json", Art.get().indexJsonGz(), true, "Cache-Control: max-age=3600\r\n");
            } else if ("/api/art/page".equals(path)) {
                byte[] png = null;
                try { png = Art.get().page(Integer.parseInt(query(parts[1], "i"))); } catch (Exception ignored) {}
                if (png == null) send(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), false, null);
                else send(out, 200, "image/png", png, false, "Cache-Control: max-age=86400\r\n");
            } else if ("/api/art/media".equals(path)) {
                byte[] png = Art.media(query(parts[1], "p"));
                if (png == null) send(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), false, null);
                else send(out, 200, "image/png", png, false, "Cache-Control: max-age=86400\r\n");
            } else if ("/api/info".equals(path)) {
                InetAddress ra = s.getInetAddress();
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", "Knox Link");
                info.put("version", VERSION);
                info.put("needsPin", !ra.isLoopbackAddress());
                info.put("map", MapData.get().state());
                send(out, 200, "application/json", Json.write(info).getBytes(StandardCharsets.UTF_8), false, "Cache-Control: no-store\r\n");
            } else {
                serveStatic(out, path, gzipOk);
            }
            out.flush();
            s.close();
        } catch (SocketTimeoutException e) {
            try { s.close(); } catch (IOException ignored) {}
        } catch (Exception e) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private void serveStatic(OutputStream out, String path, boolean gzipOk) throws IOException {
        if (path.equals("/") || path.isEmpty()) path = "/index.html";
        if (path.contains("..") || path.contains("\\")) {
            send(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), false, null);
            return;
        }
        String devDir = System.getProperty("knoxlink.webdir");   // web development: serve from disk, no cache
        if (devDir != null) {
            java.io.File f = new java.io.File(devDir, path.substring(1));
            if (!f.isFile()) {
                send(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), false, null);
                return;
            }
            send(out, 200, contentType(path), java.nio.file.Files.readAllBytes(f.toPath()), false, "Cache-Control: no-store\r\n");
            return;
        }
        byte[][] entry = staticCache.get(path);
        if (entry == null) {
            byte[] plain;
            try (InputStream r = KnoxServer.class.getResourceAsStream("/web" + path)) {
                if (r == null) {
                    send(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), false, null);
                    return;
                }
                plain = readAll(r);
            }
            byte[] gz = null;
            String type = contentType(path);
            if (type.startsWith("text/") || type.contains("javascript") || type.contains("json") || type.contains("svg") || type.equals("font/ttf")) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (GZIPOutputStream g = new GZIPOutputStream(bo)) {
                    g.write(plain);
                }
                gz = bo.toByteArray();
            }
            entry = new byte[][]{plain, gz};
            staticCache.put(path, entry);
        }
        String type = contentType(path);
        // index.html must always be re-checked so a mod update reaches the screen; the rest is versioned by query string.
        String cache = path.equals("/index.html") ? "Cache-Control: no-cache\r\n" : "Cache-Control: max-age=300\r\n";
        if (gzipOk && entry[1] != null) send(out, 200, type, entry[1], true, cache);
        else send(out, 200, type, entry[0], false, cache);
    }

    /** Value of a query parameter in a request target ("/a?x=1&y=2"), URL-decoded, or null. */
    private static String query(String target, String key) {
        int q = target.indexOf('?');
        if (q < 0) return null;
        for (String kv : target.substring(q + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(key)) {
                try {
                    return java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8");
                } catch (Exception ex) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String contentType(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json; charset=utf-8";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    private static void send(OutputStream out, int code, String type, byte[] body, boolean gzip, String extraHeaders) throws IOException {
        String reason = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 503 ? "Service Unavailable" : code == 405 ? "Method Not Allowed" : "Error";
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        if (gzip) h.append("Content-Encoding: gzip\r\n");
        if (extraHeaders != null) h.append(extraHeaders);
        h.append("X-Content-Type-Options: nosniff\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("header line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    // ------------------------------------------------------------------ WebSocket

    private void upgrade(Socket s, InputStream in, OutputStream out, Map<String, String> headers) throws Exception {
        String key = headers.get("sec-websocket-key");
        if (key == null) { s.close(); return; }
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        String accept = Base64.getEncoder().encodeToString(
                sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        s.setSoTimeout(0);
        Client c = new Client(s, in, out, false);
        clients.add(c);
        c.run();   // returns when the screen disconnects
    }

    private boolean pinLocked(String ip) {
        int[] f = failedPins.get(ip);
        return f != null && f[1] > System.currentTimeMillis() / 1000;
    }

    private void pinFailed(String ip) {
        int[] f = failedPins.computeIfAbsent(ip, k -> new int[2]);
        f[0]++;
        if (f[0] >= 5) {   // five wrong PINs: one minute timeout
            f[0] = 0;
            f[1] = (int) (System.currentTimeMillis() / 1000) + 60;
        }
    }

    /** One connected screen: a browser (WebSocket frames) or the Android app (raw = one JSON message per line). */
    private final class Client {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        final boolean raw;
        final boolean local;
        final String ip;
        volatile boolean authed;
        volatile boolean closed;
        volatile java.util.Set<String> subs = Collections.emptySet();
        private final LinkedHashMap<String, String> pending = new LinkedHashMap<>();
        private final java.util.ArrayDeque<String> once = new java.util.ArrayDeque<>();

        Client(Socket socket, InputStream in, OutputStream out, boolean raw) {
            this.socket = socket;
            this.in = in;
            this.out = out;
            this.raw = raw;
            this.local = socket.getInetAddress().isLoopbackAddress();
            this.ip = socket.getInetAddress().getHostAddress();
        }

        boolean wants(String channel) {
            // "core" (time, player position, vitals summary) always goes out; the rest on request.
            return "core".equals(channel) || subs.contains(channel);
        }

        void offer(String channel, String msg) {
            synchronized (this) {
                pending.put(channel, msg);   // replaces an unsent older value of the same channel
                notifyAll();
            }
        }

        void offerOnce(String msg) {
            synchronized (this) {
                if (once.size() > 200) once.pollFirst();
                once.addLast(msg);
                notifyAll();
            }
        }

        void run() {
            Thread writer = new Thread(this::writeLoop, "KnoxLink-ws-out");
            writer.setDaemon(true);
            writer.start();
            try {
                readLoop();
            } catch (Exception ignored) {
            } finally {
                close();
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            clients.remove(this);
            synchronized (this) { notifyAll(); }
            try { socket.close(); } catch (IOException ignored) {}
        }

        private void writeLoop() {
            try {
                while (!closed) {
                    List<String> batch = new ArrayList<>();
                    synchronized (this) {
                        while (!closed && pending.isEmpty() && once.isEmpty()) wait(15000);
                        if (closed) return;
                        while (!once.isEmpty()) batch.add(once.pollFirst());
                        batch.addAll(pending.values());
                        pending.clear();
                    }
                    if (batch.isEmpty()) {
                        frame(0x9, new byte[0]);   // ping keeps NAT and Wi-Fi power saving from dropping us
                        continue;
                    }
                    for (String m : batch) frame(0x1, m.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (Exception e) {
                close();
            }
        }

        private void frame(int opcode, byte[] payload) throws IOException {
            if (raw) {
                synchronized (out) {
                    if (opcode == 0x1) out.write(payload);
                    if (opcode == 0x1 || opcode == 0x9) out.write('\n');   // an empty line is the keep-alive
                    if (opcode != 0x1) out.flush();
                }
                return;
            }
            synchronized (out) {
                int n = payload.length;
                out.write(0x80 | opcode);
                if (n < 126) {
                    out.write(n);
                } else if (n < 65536) {
                    out.write(126);
                    out.write(n >>> 8);
                    out.write(n & 0xff);
                } else {
                    out.write(127);
                    for (int i = 7; i >= 0; i--) out.write((int) (((long) n >>> (8 * i)) & 0xff));
                }
                out.write(payload);
                if (opcode != 0x1) out.flush();
            }
        }

        private void readLoop() throws Exception {
            if (raw) {
                ByteArrayOutputStream line = new ByteArrayOutputStream(256);
                int b;
                while (!closed && (b = in.read()) >= 0) {
                    if (b == '\n') {
                        if (line.size() > 0) onMessage(line.toString("UTF-8"));
                        line.reset();
                    } else if (b != '\r') {
                        line.write(b);
                        if (line.size() > 1 << 20) return;
                    }
                }
                return;
            }
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            while (!closed) {
                int b0 = in.read();
                if (b0 < 0) return;
                int b1 = in.read();
                if (b1 < 0) return;
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0f;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7f;
                if (len == 126) len = (in.read() << 8) | in.read();
                else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
                }
                if (len > 1 << 20) return;   // nothing a screen sends is this big
                byte[] mask = new byte[4];
                if (masked) readFully(mask);
                byte[] data = new byte[(int) len];
                readFully(data);
                if (masked) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];

                if (opcode == 0x8) {   // close
                    try { frame(0x8, new byte[0]); } catch (IOException ignored) {}
                    return;
                } else if (opcode == 0x9) {   // ping
                    frame(0xA, data);
                } else if (opcode == 0x1 || opcode == 0x0) {
                    message.write(data);
                    if (fin) {
                        String text = message.toString("UTF-8");
                        message.reset();
                        onMessage(text);
                    }
                }
            }
        }

        private void readFully(byte[] b) throws IOException {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) throw new IOException("eof");
                off += n;
            }
        }

        @SuppressWarnings("unchecked")
        private void onMessage(String text) {
            Map<String, Object> m;
            try {
                Object o = Json.parse(text);
                if (!(o instanceof Map)) return;
                m = (Map<String, Object>) o;
            } catch (Exception e) {
                return;
            }
            String t = String.valueOf(m.get("t"));
            switch (t) {
                case "hello": {
                    String pin = m.get("pin") == null ? "" : String.valueOf(m.get("pin"));
                    boolean ok;
                    String why = "";
                    if (local) {
                        ok = true;
                    } else if (pinLocked(ip)) {
                        ok = false;
                        why = "locked";
                    } else if (pin.equals(config.pin)) {
                        ok = true;
                        failedPins.remove(ip);
                    } else {
                        ok = false;
                        why = pin.isEmpty() ? "pin" : "wrong";
                        if (!pin.isEmpty()) pinFailed(ip);
                    }
                    authed = ok;
                    StringBuilder sb = new StringBuilder("{\"t\":\"hello\",\"ok\":").append(ok)
                            .append(",\"version\":").append(quote(VERSION))
                            .append(",\"local\":").append(local);
                    if (!ok) sb.append(",\"why\":").append(quote(why));
                    else sb.append(",\"key\":").append(quote(sessionKey));
                    sb.append('}');
                    offerOnce(sb.toString());
                    if (ok) {
                        for (Map.Entry<String, String> e : latest.entrySet()) {
                            if (wants(e.getKey())) offer(e.getKey(), "{\"t\":\"s\",\"c\":" + quote(e.getKey()) + ",\"d\":" + e.getValue() + "}");
                        }
                    }
                    break;
                }
                case "sub": {
                    if (!authed) return;
                    java.util.Set<String> next = new java.util.HashSet<>();
                    Object c = m.get("c");
                    if (c instanceof List) for (Object x : (List<Object>) c) next.add(String.valueOf(x));
                    java.util.Set<String> before = subs;
                    subs = next;
                    for (String ch : next) {
                        if (!before.contains(ch)) {
                            String v = latest.get(ch);
                            if (v != null) offer(ch, "{\"t\":\"s\",\"c\":" + quote(ch) + ",\"d\":" + v + "}");
                        }
                    }
                    break;
                }
                case "cmd": {
                    if (!authed) return;
                    Object name = m.get("cmd");
                    if (name == null) return;
                    Object args = m.get("args");
                    int clientId = m.get("id") instanceof Double ? ((Double) m.get("id")).intValue() : 0;
                    int id = nextCommandId.getAndIncrement();
                    commandOwners.put(id, this);
                    idMap.put(id, clientId);
                    commands.add(new Command(id, String.valueOf(name), args instanceof Map ? (Map<String, Object>) args : new HashMap<>()));
                    if (commands.size() > 64) {   // the game is paused on a menu or Lua is not running
                        Command dropped = commands.poll();
                        if (dropped != null) reply(dropped.id, false, "busy");
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }

    /** Server command id -> the id the browser used, so acks match its own numbering. */
    private final Map<Integer, Integer> idMap = new ConcurrentHashMap<>();

    int clientIdFor(int serverId) {
        Integer v = idMap.remove(serverId);
        return v == null ? 0 : v;
    }
}
