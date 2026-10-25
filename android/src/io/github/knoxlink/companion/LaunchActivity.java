package io.github.knoxlink.companion;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;

/**
 * Where Knox Link starts. On a dual-screen handheld it puts the game on the main screen and
 * Knox Link on the other one, wherever you tapped the icon, then gets out of the way.
 */
public class LaunchActivity extends Activity {
    static final String GAME = "com.zomdroid";
    static final String ACTION_PLAY = "io.github.knoxlink.PLAY";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences prefs = getSharedPreferences("knoxlink", MODE_PRIVATE);
        Intent in = getIntent();
        boolean play = ACTION_PLAY.equals(in.getAction());
        boolean fromGame = in.getBooleanExtra("fromGame", false);

        Display main = mainDisplay(this), other = otherDisplay(this, main);
        // the game too, when it's on this device, wanted, and not running already (its menu would cover it)
        if (!fromGame && (play || prefs.getBoolean("autoGame", true)) && isNative(prefs)
                && getPackageManager().getLaunchIntentForPackage(GAME) != null && !gameRunning(prefs)) {
            launch(this, getPackageManager().getLaunchIntentForPackage(GAME), main);
        }
        Intent app = new Intent(this, MainActivity.class);
        if (in.getData() != null) app.setData(in.getData());
        if (in.getExtras() != null) app.putExtras(in.getExtras());
        boolean bottom = prefs.getBoolean("bottom", true) && other != null;
        launch(this, app, bottom ? other : null);
        finish();
        overridePendingTransition(0, 0);
    }

    /** The game is already up: Knox Link's server answers on this device, or Android lists the game's process. */
    static boolean gameRunning(SharedPreferences prefs) {
        final boolean[] up = {false};
        String s = prefs.getString("server", "127.0.0.1:7878");
        int port = 7878;
        try { port = Integer.parseInt(s.substring(s.lastIndexOf(':') + 1).trim()); } catch (Exception ignored) {}
        final int p = port;
        Thread t = new Thread(() -> {
            try (java.net.Socket sock = new java.net.Socket()) {
                sock.connect(new java.net.InetSocketAddress("127.0.0.1", p), 250);
                up[0] = true;
            } catch (Exception ignored) {
            }
        });
        t.start();
        try { t.join(400); } catch (InterruptedException ignored) {}
        return up[0];
    }

    /** The game runs on this device (not streamed from a PC). */
    static boolean isNative(SharedPreferences prefs) {
        String s = prefs.getString("server", "127.0.0.1:7878");
        return s.startsWith("127.0.0.1") || s.startsWith("localhost");
    }

    static Display mainDisplay(Context c) {
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        return dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
    }

    /** The other screen of a dual-screen device, if it's on. */
    static Display otherDisplay(Context c, Display not) {
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return null;
        for (Display d : dm.getDisplays()) {
            if ((not == null || d.getDisplayId() != not.getDisplayId()) && d.getState() != Display.STATE_OFF) return d;
        }
        return null;
    }

    static void launch(Context c, Intent i, Display d) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (d != null) {
                ActivityOptions o = ActivityOptions.makeBasic();
                o.setLaunchDisplayId(d.getDisplayId());
                c.startActivity(i, o.toBundle());
            } else {
                c.startActivity(i);
            }
        } catch (Exception e) {
            try { c.startActivity(i); } catch (Exception ignored) {}
        }
    }
}
