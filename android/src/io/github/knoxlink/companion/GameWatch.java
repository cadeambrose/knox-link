package io.github.knoxlink.companion;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;

/**
 * Opens Knox Link on the second screen when the game (Zomdroid) comes to the front, so starting
 * the game from any launcher sets up both screens. It looks at which app is in front every two
 * seconds ("usage access") and needs "display over other apps" to open from the background.
 */
public class GameWatch extends Service {
    private static final String CHANNEL = "watch";
    private static final String GAME_SCREEN = "com.zomdroid.GameActivity";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean gameInFront;
    private long openedAt;

    /** Starts watching if the user wants it and Android allows it. */
    static void start(Context c) {
        SharedPreferences prefs = c.getSharedPreferences("knoxlink", MODE_PRIVATE);
        if (!prefs.getBoolean("watch", true) || !allowed(c)) return;
        if (c.getPackageManager().getLaunchIntentForPackage(LaunchActivity.GAME) == null) return;
        try {
            c.startForegroundService(new Intent(c, GameWatch.class));
        } catch (Exception ignored) {
            // not allowed right now (background start limits); the app starts it next time it opens
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, GameWatch.class));
    }

    /** Both permissions are in place. */
    static boolean allowed(Context c) {
        return hasUsageAccess(c) && Settings.canDrawOverlays(c);
    }

    /** The game is open right now (its screen came up and hasn't closed), as far as usage access tells. */
    static boolean gameOpen(Context c) {
        if (!hasUsageAccess(c)) return false;
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return false;
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - 6 * 3600_000L, now);
        UsageEvents.Event e = new UsageEvents.Event();
        boolean open = false;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (!LaunchActivity.GAME.equals(e.getPackageName()) || !GAME_SCREEN.equals(e.getClassName())) continue;
            int type = e.getEventType();
            if (type == 1) open = true;            // came to the front
            else if (type == 23 || type == 24) open = false;   // stopped / destroyed
        }
        return open;
    }

    static boolean hasUsageAccess(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) return false;
        int mode = Build.VERSION.SDK_INT >= 29
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Ready for the game", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle("Knox Link")
                .setContentText("Opens on the second screen when Project Zomboid starts")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
        handler.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            SharedPreferences prefs = getSharedPreferences("knoxlink", MODE_PRIVATE);
            if (!prefs.getBoolean("watch", true) || !allowed(GameWatch.this)) {
                stopSelf();
                return;
            }
            check();
            handler.postDelayed(this, 2000);
        }
    };

    private void check() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return;
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - 5000, now);
        UsageEvents.Event e = new UsageEvents.Event();
        boolean sawGame = false;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            // the game's own screen, not Zomdroid's launcher (opened for its settings, mods, ...)
            if (!LaunchActivity.GAME.equals(e.getPackageName()) || !GAME_SCREEN.equals(e.getClassName())) continue;
            int type = e.getEventType();   // 1 = came to the front, 2 = left it, 23 = stopped (older names: MOVE_TO_FOREGROUND/BACKGROUND)
            if (type == 1) { gameInFront = true; sawGame = true; }
            else if (type == 2 || type == 23) gameInFront = false;
        }
        // the game just came to the front and Knox Link isn't on screen: open it on the other screen
        if (sawGame && gameInFront && !MainActivity.visible && now - openedAt > 10000) open(now);
        // ... or a save with Knox Link just started (the game's server answers on this device)
        if (now >= nextProbe && !probing) {
            nextProbe = now + 4000;
            probing = true;
            new Thread(() -> {
                boolean up = serverAnswers();
                handler.post(() -> {
                    probing = false;
                    if (up && !serverUp && !MainActivity.visible && System.currentTimeMillis() - openedAt > 10000) open(System.currentTimeMillis());
                    serverUp = up;
                });
            }, "knoxlink-probe").start();
        }
    }

    private long nextProbe;
    private boolean probing, serverUp = true;   // "up" at start: don't pop up for a game that was already running

    private boolean serverAnswers() {
        SharedPreferences prefs = getSharedPreferences("knoxlink", MODE_PRIVATE);
        if (!LaunchActivity.isNative(prefs)) return false;
        String s = prefs.getString("server", "127.0.0.1:7878");
        int port = 7878;
        try { port = Integer.parseInt(s.substring(s.lastIndexOf(':') + 1).trim()); } catch (Exception ignored) {}
        try (java.net.Socket sock = new java.net.Socket()) {
            sock.connect(new java.net.InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void open(long now) {
        openedAt = now;
        Intent i = new Intent(this, LaunchActivity.class);
        i.putExtra("fromGame", true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
