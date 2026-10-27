package io.github.knoxlink.companion;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The scrapbook's photos on the second screen: a strip of them on a journal page, a viewer with
 * the caption, and saving one to the device's pictures. They come from the game's server
 * (/api/photo, with the session's key); the game keeps them in the save.
 */
final class Photos {
    private Photos() {}

    interface Cb {
        void done(Bitmap b);
    }

    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService pool = Executors.newFixedThreadPool(2);
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** A photo about maxW pixels wide (halved from the full picture as often as fits), from memory when it's there. */
    static void load(Host host, String id, int maxW, Cb cb) {
        String key = id + "@" + maxW;
        Bitmap hit = cache.get(key);
        if (hit != null) {
            cb.done(hit);
            return;
        }
        String url = host.apiUrl("/api/photo?id=" + Uri.encode(id));
        pool.execute(() -> {
            Bitmap b = null;
            byte[] jpg = fetch(url);
            if (jpg != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
                int s = 1;
                while (o.outWidth / (s * 2) >= maxW) s *= 2;
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = s;
                b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, d);
                if (b != null) cache.put(key, b);
            }
            final Bitmap r = b;
            main.post(() -> cb.done(r));
        });
    }

    static byte[] fetch(String url) {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(3000);
            con.setReadTimeout(8000);
            if (con.getResponseCode() != 200) {
                con.disconnect();
                return null;
            }
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            } finally {
                con.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** A page's photos side by side; a tap opens one. editable: the character's own (caption, throw away). */
    static View strip(Context c, Host host, JSONArray ph, boolean editable) {
        Theme t = host.theme();
        HorizontalScrollView sv = new HorizontalScrollView(c);
        sv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(c);
        for (int i = 0; i < ph.length(); i++) {
            JSONObject p = ph.optJSONObject(i);
            if (p == null) continue;
            LinearLayout cell = Ui.col(c);
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFF2B2620);
            // a print: a white border, a little shadow
            cell.setBackgroundColor(0xFFFAF7EF);
            cell.setPadding(t.dp(5), t.dp(5), t.dp(5), t.dp(5));
            cell.setElevation(t.dpf(2));
            cell.addView(iv, Ui.lp(t.dp(150), t.dp(85)));
            String cap = p.optString("c", "");
            if (!cap.isEmpty()) {
                TextView ct = Ui.ellipsize(Ui.text(c, t, cap, 11.5f, 0xFF4A4235, false));
                ct.setTypeface(android.graphics.Typeface.create("casual", android.graphics.Typeface.NORMAL));
                ct.setMaxWidth(t.dp(150));
                cell.addView(ct, Ui.margins(Ui.lp(t.dp(150), -2), 0, t.dp(4), 0, 0));
            }
            final String id = p.optString("id");
            load(host, id, 320, b -> { if (b != null) iv.setImageBitmap(b); });
            cell.setOnClickListener(v -> open(c, host, p, editable));
            row.addView(cell, Ui.margins(Ui.lp(-2, -2), 0, t.dp(4), t.dp(10), t.dp(4)));
        }
        sv.addView(row);
        return sv;
    }

    /** The viewer: the photo, its caption, and what can be done with it. */
    static void open(Context c, Host host, JSONObject p, boolean editable) {
        Theme t = host.theme();
        String id = p.optString("id");
        LinearLayout sheet = Ui.col(c);
        ImageView iv = new ImageView(c);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(0xFF0D0F12);
        iv.setMinimumHeight(t.dp(160));
        sheet.addView(iv, Ui.lp(-1, -2));
        load(host, id, 1600, b -> { if (b != null) iv.setImageBitmap(b); });
        if (p.optBoolean("w") || p.has("h")) {
            String when = p.optBoolean("w") ? Fmt.clock(p.optInt("h"), p.optInt("mi")) : null;
            if (when != null) sheet.addView(Ui.text(c, t, when, 12.5f, t.text3, false), Ui.margins(Ui.lp(-2, -2), 0, t.dp(8), 0, 0));
        }
        if (editable) {
            EditText cap = new EditText(c);
            cap.setText(p.optString("c", ""));
            cap.setHint("Add a caption…");
            cap.setTextColor(t.text);
            cap.setHintTextColor(t.text3);
            cap.setTextSize(t.sp(15));
            cap.setSingleLine(true);
            cap.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            cap.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            cap.setBackground(t.round(t.surface2, 10));
            cap.setPadding(t.dp(12), t.dp(10), t.dp(12), t.dp(10));
            cap.setOnEditorActionListener((v, a, e) -> {
                host.command("photoCaption", Ui.args("id", id, "c", cap.getText().toString().trim()), "Caption saved");
                return false;
            });
            sheet.addView(cap, Ui.margins(Ui.lp(-1, -2), 0, t.dp(12), 0, t.dp(10)));
        } else if (!p.optString("c", "").isEmpty()) {
            TextView ct = Ui.text(c, t, p.optString("c"), 15, t.text2, false);
            sheet.addView(ct, Ui.margins(Ui.lp(-1, -2), 0, t.dp(10), 0, t.dp(10)));
        }
        TextView save = Ui.action(c, t, "camera", "Save to this device's pictures");
        save.setOnClickListener(v -> saveToPictures(c, host, id));
        sheet.addView(save, Ui.margins(Ui.lp(-1, -2), 0, t.dp(4), 0, t.dp(8)));
        if (editable) {
            TextView del = Ui.action(c, t, "trash", "Throw this photo away");
            del.setTextColor(t.bad);
            del.setOnClickListener(v -> {
                host.command("photoDelete", Ui.args("id", id), "Photo thrown away");
                host.closeSheet();
            });
            sheet.addView(del, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
        }
        TextView close = Ui.action(c, t, "x", "Close");
        close.setOnClickListener(v -> host.closeSheet());
        sheet.addView(close);
        host.openSheet(sheet);
    }

    /** The photo's JPEG into the device's Pictures/Knox Link, for a gallery or to share. */
    static void saveToPictures(Context c, Host host, String id) {
        String url = host.apiUrl("/api/photo?id=" + Uri.encode(id));
        pool.execute(() -> {
            byte[] jpg = fetch(url);
            String result = jpg == null ? null : savePicture(c, "KnoxLink-" + id + ".jpg", "image/jpeg", jpg);
            main.post(() -> host.toast(result != null ? "Saved to Pictures/Knox Link" : "Couldn't save it", result == null));
        });
    }

    /** Writes a picture into Pictures/Knox Link; returns where (a content address or a path), or null. */
    static String savePicture(Context c, String name, String mime, byte[] data) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, mime);
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Knox Link");
                Uri uri = c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return null;
                try (OutputStream out = c.getContentResolver().openOutputStream(uri)) {
                    if (out == null) return null;
                    out.write(data);
                }
                return uri.toString();
            }
            File dir = new File(c.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Knox Link");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File f = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
            return f.getPath();
        } catch (Exception e) {
            return null;
        }
    }
}
