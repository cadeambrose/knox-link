package io.github.knoxlink.companion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/**
 * The game's own artwork, fetched from the mod's server on first use and kept on the device:
 * loose UI images (moodles, body outline, sidebar, car overlays, paper map) and item icons cut
 * out of the texture pack pages. Callbacks run on the main thread; a missing image gives null.
 */
final class ArtCache {
    interface Cb {
        void done(Bitmap b);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Map<String, List<Cb>> waiting = new HashMap<>();
    /** Asked while the game's server couldn't be reached (Knox Link opens before a save loads): asked again once it can. */
    private final Map<String, Object[]> retry = new HashMap<>();
    private final Map<Integer, BitmapRegionDecoder> decoders = new HashMap<>();
    private final File root;
    /** Files of the game copy we're talking to (art/<version>): one game's pages never meet another's index. */
    private volatile File dir;
    private volatile String base = "http://127.0.0.1:7878";
    private volatile Map<String, int[]> index;
    private volatile boolean indexFailed;

    ArtCache(Context ctx) {
        root = new File(ctx.getCacheDir(), "art");
        root.mkdirs();
        dir = new File(root, "unknown");
    }

    void setBase(String baseUrl) {
        if (baseUrl.equals(base)) return;
        base = baseUrl;
        // another game (or another copy of it): forget everything cut from the old one
        synchronized (this) {
            index = null;
            indexFailed = false;
        }
        synchronized (decoders) {
            for (BitmapRegionDecoder d : decoders.values()) d.recycle();
            decoders.clear();
        }
        memory.evictAll();
    }

    /** The game's server answers now: ask again for what couldn't be fetched before. */
    void retryNow() {
        List<Object[]> again;
        synchronized (retry) {
            again = new ArrayList<>(retry.values());
            retry.clear();
        }
        for (Object[] r : again) {
            @SuppressWarnings("unchecked") List<Cb> cbs = (List<Cb>) r[1];
            for (Cb cb : cbs) load((String) r[0], cb, (Job) r[2]);
        }
    }

    /** The server couldn't be reached at all (as opposed to answering that it has no such image). */
    private static final class Unreachable extends Exception {
        Unreachable(Throwable cause) {
            super(cause);
        }
    }

    /** A loose PNG under the game's media folder, e.g. "ui/Moodles/128/Status_Bleeding.png". */
    void media(String path, Cb cb) {
        load("m:" + path, cb, () -> {
            loadIndex();   // learns which game copy this is, so the file lands in the right place
            File f = new File(dir, "m_" + path.replaceAll("[^A-Za-z0-9._-]", "_"));
            byte[] png = f.isFile() ? readFile(f) : null;
            if (png == null) {
                png = fetch("/api/art/media?p=" + URLEncoder.encode(path, "UTF-8"));
                if (png != null) writeFile(f, png);
            }
            return png == null ? null : BitmapFactory.decodeByteArray(png, 0, png.length);
        });
    }

    /** An item icon by texture name ("Item_Bat"), padded to its full size like the game draws it. */
    void icon(String tex, Cb cb) {
        if (tex == null || tex.isEmpty()) {
            cb.done(null);
            return;
        }
        load("i:" + tex, cb, () -> {
            Map<String, int[]> idx = loadIndex();
            int[] r = idx == null ? null : idx.get(tex);
            if (r == null) {
                // not in the texture packs: newer and modded items keep a loose icon file
                File f = new File(dir, "l_" + tex.replaceAll("[^A-Za-z0-9._-]", "_") + ".png");
                byte[] png = f.isFile() ? readFile(f) : fetch("/api/art/icon?n=" + URLEncoder.encode(tex, "UTF-8"));
                if (png == null) return null;
                if (!f.isFile()) writeFile(f, png);
                return BitmapFactory.decodeByteArray(png, 0, png.length);
            }
            BitmapRegionDecoder dec = decoder(r[0]);
            if (dec == null) return null;
            Bitmap part;
            synchronized (dec) {
                part = dec.decodeRegion(new Rect(r[1], r[2], r[1] + r[3], r[2] + r[4]), null);
            }
            if (part == null) return null;
            int fw = Math.max(r[7], r[3]), fh = Math.max(r[8], r[4]);
            if (fw == r[3] && fh == r[4]) return part;
            Bitmap full = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
            new Canvas(full).drawBitmap(part, r[5], r[6], null);
            part.recycle();
            return full;
        });
    }

    Bitmap peek(String kind, String name) {
        return memory.get(kind + ":" + name);
    }

    // ------------------------------------------------------------------ internals

    private interface Job {
        Bitmap run() throws Exception;
    }

    private void load(String key, Cb cb, Job job) {
        Bitmap hit = memory.get(key);
        if (hit != null) {
            cb.done(hit);
            return;
        }
        synchronized (waiting) {
            List<Cb> list = waiting.get(key);
            if (list != null) {
                list.add(cb);
                return;
            }
            list = new ArrayList<>();
            list.add(cb);
            waiting.put(key, list);
        }
        pool.execute(() -> {
            Bitmap b = null;
            boolean unreachable = false;
            try {
                b = job.run();
            } catch (Unreachable e) {
                unreachable = true;
            } catch (Throwable ignored) {
            }
            if (b != null) memory.put(key, b);
            final Bitmap result = b;
            final List<Cb> cbs;
            synchronized (waiting) {
                cbs = waiting.remove(key);
            }
            if (unreachable && cbs != null) {
                synchronized (retry) {
                    Object[] r = retry.get(key);
                    if (r == null) retry.put(key, new Object[]{key, new ArrayList<>(cbs), job});
                    else {
                        @SuppressWarnings("unchecked") List<Cb> l = (List<Cb>) r[1];
                        l.addAll(cbs);
                    }
                }
            }
            main.post(() -> {
                if (cbs != null) for (Cb c : cbs) c.done(result);
            });
        });
    }

    /** The game's icon index, asked for once per connection; it also names the game copy (its "v"). */
    private synchronized Map<String, int[]> loadIndex() throws Exception {
        if (index != null) return index;
        if (indexFailed) return null;
        byte[] json = fetch("/api/art/index");
        if (json == null) {
            indexFailed = true;   // the server answered without one (an older Knox Link)
            return null;
        }
        JSONObject o = new JSONObject(new String(json, "UTF-8"));
        String v = o.optString("v", "").replaceAll("[^A-Za-z0-9]", "");
        if (v.isEmpty()) v = "pre-" + Integer.toHexString(java.util.Arrays.hashCode(json));
        File d = new File(root, v);
        d.mkdirs();
        dir = d;
        // tidy up: copies cut from other game versions, and the flat cache of older app versions
        File[] old = root.listFiles();
        if (old != null) {
            for (File x : old) {
                if (x.equals(d)) continue;
                if (x.isDirectory()) {
                    File[] inner = x.listFiles();
                    if (inner != null) for (File y : inner) y.delete();
                }
                x.delete();
            }
        }
        JSONObject tex = o.getJSONObject("tex");
        Map<String, int[]> m = new HashMap<>(tex.length() * 2);
        java.util.Iterator<String> it = tex.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONArray a = tex.getJSONArray(k);
            int[] r = new int[9];
            for (int i = 0; i < 9 && i < a.length(); i++) r[i] = a.getInt(i);
            m.put(k, r);
        }
        index = m;
        return m;
    }

    private BitmapRegionDecoder decoder(int page) throws Exception {
        synchronized (decoders) {
            BitmapRegionDecoder d = decoders.get(page);
            if (d != null) return d;
        }
        File f = new File(dir, "page_" + page + ".png");
        if (!f.isFile()) {
            byte[] png = fetch("/api/art/page?i=" + page);
            if (png == null) return null;
            writeFile(f, png);
        }
        BitmapRegionDecoder d = BitmapRegionDecoder.newInstance(f.getAbsolutePath(), false);
        synchronized (decoders) {
            decoders.put(page, d);
        }
        return d;
    }

    /** The body of a 200 answer, null for any other answer; Unreachable when nothing answered. */
    private byte[] fetch(String path) throws Unreachable {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + path).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(20000);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } catch (java.io.IOException e) {
            throw new Unreachable(e);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static byte[] fetchFrom(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(30000);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readFile(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeFile(File f, byte[] data) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(data);
        } catch (Exception e) {
            return;
        }
        tmp.renameTo(f);
    }
}
