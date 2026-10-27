package io.github.knoxlink.companion;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Knox Link for a dual-screen handheld's second screen (or any phone or tablet): a native app
 * that shows the live state of a Project Zomboid game running the Knox Link mod.
 */
public class MainActivity extends Activity implements Host, Link.Listener, Screens.Settings.Changed {
    // Each tab's picture is the game's own: its sidebar icons where it has one, else one of its item pictures
    // made into a sticker like them ("sticker:" + texture or ui/ file; grey when off, in colour when chosen).
    private static final String[][] TABS = {
            {"map", "Map", "ui/Sidebar/128/Map_Off_128.png", "ui/Sidebar/128/Map_On_128.png", "map"},
            {"vitals", "Vitals", "ui/Sidebar/128/Heart_Off_128.png", "ui/Sidebar/128/Heart_On_128.png", "vitals"},
            {"gear", "Gear", "ui/Sidebar/128/Inventory_Off_128.png", "ui/Sidebar/128/Inventory_On_128.png", "gear"},
            {"weapons", "Weapons", "ui/Sidebar/128/War_Off_128.png", "ui/Sidebar/128/War_On_128.png", "weapon"},
            {"skills", "Skills", "sticker:Item_Book", "sticker:Item_Book", "skills"},
            {"comms", "Radio", "sticker:Item_RadioRed", "sticker:Item_RadioRed", "radio"},
            {"base", "Base", "ui/Sidebar/128/Build_Off_128.png", "ui/Sidebar/128/Build_On_128.png", "home"},
            {"journal", "Journal", "sticker:Item_Diary2", "sticker:Item_Diary2", "book"},
            {"ask", "Ask", "sticker:ui/emotes/shrug.png", "sticker:ui/emotes/shrug.png", "ask"},
            {"car", "Car", "sticker:Item_CarKey", "sticker:Item_CarKey", "car"},
    };
    private static final String SETTINGS_ART = "sticker:ui/inventoryPanes/Button_Gear.png";

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Theme theme;
    private ArtCache art;
    private Link link;

    private FrameLayout root, content, sheetLayer;
    private LinearLayout sheetPanel, rail, toasts;
    private View scrim;
    private TextView clockTime, clockDate, mapStatus, wpText, placeChip;
    private GameArt.Watch watch;
    private ImageView weatherIcon;
    private View weatherPill, wpChip, conn;
    private RadarView radar;
    private JSONObject radarData;
    private LinearLayout speedRow;
    private final Map<String, View> tabButtons = new HashMap<>();
    private final Map<String, View> screens = new HashMap<>();
    private MapView map;
    private Screens.Vitals vitals;
    private Screens.Gear gear;
    private Screens.Skills skills;
    private Screens.Car car;
    private Screens.Comms comms;
    private Screens.Base baseScreen;
    private Screens.Settings settings;
    private Journal journal;
    private AskScreen ask;
    private WeaponsScreen weapons;
    private JSONObject lootData, craftData, journalData, weaponsData;
    // the floor plan, what the character remembers, the diaries they found, "Ask"'s situation
    private JSONObject planData, memoryData, diariesData, askData;
    /** The game server's key for this session (hello): photos and the way walked need it, from another device. */
    private String httpKey;
    private String forecastSig = "";
    private View followBtn;

    // map marks: the game's symbol pictures, and what the player can mark with
    private final Map<String, String> symbolTex = new HashMap<>();
    private final List<JSONObject> symbolDefs = new java.util.ArrayList<>();
    private String symbolsFor = "";
    private JSONObject marksData, radioData, chatData, baseData, teamData, outfitData;

    // alerts
    private LinearLayout alertBar;
    private Widgets.Moodle alertTile;
    private TextView alertTitle, alertText, alertAction;
    private final Map<String, Integer> moodleLevels = new HashMap<>();
    private boolean moodlesPrimed, arrivedShown, lowFuelShown;
    private int lastChatN = -1;
    private String wpKey = "";

    // connection overlay
    private FrameLayout overlay;
    private TextView ovTitle, ovSub;
    private View ovSpinner, pinBox, startGameBtn;
    private LinearLayout gamesBox;
    private EditText pinInput;
    private final Discovery discovery = new Discovery();
    private View mapNotice;
    private TextView mapNoticeText;
    private boolean fogWanted;

    private String tab = "map";
    private String autoCarFrom;
    private boolean carOpenedThisDrive;
    private boolean authed, socketUp;
    private JSONObject core, gearData, bodyData, skillsData;
    private long lastCoreAt;
    private String coreSig = "";
    private int nextCmd = 1;
    private final Map<Integer, String> pending = new HashMap<>();
    private boolean mapLoading;
    private String mapFor = "";

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("knoxlink", MODE_PRIVATE);
        takeIntent(getIntent());
        art = new ArtCache(this);
        link = new Link(this);
        tab = prefs.getString("tab", "map");
        if ("car".equals(tab)) tab = "map";
        build();
        main.postDelayed(watchdog, 1000);
        GameWatch.start(this);
        // the keyboard went away: give the keys back to the game
        getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
            boolean ime = insets.isVisible(WindowInsets.Type.ime());
            if (imeShown && !ime) stopTyping();
            imeShown = ime;
            return v.onApplyWindowInsets(insets);
        });
    }

    /** On screen right now (the game watcher doesn't reopen it then). */
    static volatile boolean visible;

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        // the Thor's "Clear all" force-stops apps, which also ends the game watcher: start it again
        GameWatch.start(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
    }

    /**
     * "knoxlink://connect?server=192.168.1.20:7878&pin=1234" (from a QR code or a link), or the
     * same as intent extras: connect to that game straight away.
     */
    private void takeIntent(Intent i) {
        if (i == null) return;
        String server = i.getStringExtra("server"), pin = i.getStringExtra("pin");
        if (i.getData() != null && "knoxlink".equals(i.getData().getScheme())) {
            server = i.getData().getQueryParameter("server");
            pin = i.getData().getQueryParameter("pin");
        }
        SharedPreferences.Editor e = prefs.edit();
        if (server != null && !server.isEmpty()) e.putString("server", server.trim());
        if (pin != null && pin.matches("\\d{4}")) e.putString("pin", pin);
        e.apply();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        takeIntent(intent);
        onServerChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        applyServer();
        link.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        link.stop();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive();
    }

    private long backAt;

    @Override
    public void onBackPressed() {
        // Back closes a sheet, then a weapon's page, then returns to the map; at the map, twice in a row closes Knox Link.
        if (sheetLayer.getVisibility() == View.VISIBLE) closeSheet();
        else if ("weapons".equals(tab) && weapons.back()) backAt = 0;
        else if (!"map".equals(tab)) setTab("map");
        else if (System.currentTimeMillis() - backAt < 2500) closeApp();
        else {
            backAt = System.currentTimeMillis();
            toast("Press Back again to close Knox Link", false);
        }
    }

    @Override
    public void closeApp() {
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
    }

    // ------------------------------------------------------------------ focus
    // On the second screen next to the game, Knox Link doesn't take the keys: otherwise a touch here
    // moves the controller's focus to this screen until the game's screen is touched again. A text
    // field still gets the keyboard: the window takes focus while typing and gives it back after.
    private boolean typing, imeShown;

    private boolean besideGame() {
        Display d = getDisplay();
        return d != null && d.getDisplayId() != Display.DEFAULT_DISPLAY && LaunchActivity.isNative(prefs);
    }

    private void applyFocusMode() {
        // Knox Link's own keyboard types without the window having the keys; the system one needs them,
        // and without Knox Link's keyboard it is an ordinary app that takes the controls when touched
        boolean keepControls = besideGame() && prefs.getBoolean("ownKeyboard", true);
        if (keepControls && !(typing && !ownKb)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    private float downX, downY;
    private boolean tapping;

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        // a tap on a field starts typing and a tap elsewhere stops it; a scroll that starts on a field does neither
        if (besideGame()) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    tapping = !inside(kbPanel, downX, downY);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > ViewConfiguration.get(this).getScaledTouchSlop()) tapping = false;
                    break;
                case MotionEvent.ACTION_UP:
                    if (tapping) {
                        EditText field = fieldAt(getWindow().getDecorView(), (int) downX, (int) downY);
                        if (field != null && (!typing || field != kbField)) startTyping(field);
                        else if (field == null && typing) stopTyping();
                    }
                    tapping = false;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    tapping = false;
                    break;
            }
        }
        return super.dispatchTouchEvent(e);
    }

    private static boolean inside(View v, float x, float y) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        return x >= at[0] && y >= at[1] && x < at[0] + v.getWidth() && y < at[1] + v.getHeight();
    }

    // ------------------------------------------------------------------ Knox Link's own keyboard

    private LinearLayout kbPanel;
    private KeyboardView kb;
    private TextView kbPreview;
    private EditText kbField;
    private boolean ownKb;

    private void showOwnKeyboard(EditText field) {
        if (kbPanel == null || kbPanel.getParent() != root) {
            kbPanel = Ui.col(this);
            kbPanel.setBackgroundColor(theme.surface2);
            kbPanel.setClickable(true);
            kbPanel.setElevation(theme.dpf(16));
            LinearLayout top = Ui.row(this);
            top.setPadding(theme.dp(14), theme.dp(6), theme.dp(6), 0);
            // what you're typing, with a caret: the field itself shows no cursor without the window's focus
            kbPreview = Ui.text(this, theme, "", 15, theme.text, false);
            kbPreview.setMaxLines(2);
            kbPreview.setEllipsize(android.text.TextUtils.TruncateAt.START);
            top.addView(kbPreview, Ui.weight(1));
            ImageView hide = new ImageView(this);
            hide.setImageDrawable(theme.icon("chevron", theme.text2));
            hide.setRotation(90);
            hide.setPadding(theme.dp(10), theme.dp(8), theme.dp(10), theme.dp(8));
            hide.setOnClickListener(v -> stopTyping());
            top.addView(hide, Ui.lp(theme.dp(44), theme.dp(40)));
            kbPanel.addView(top, Ui.lp(-1, -2));
            kb = new KeyboardView(this, theme, this::onOwnKey);
            kbPanel.addView(kb, Ui.lp(-1, -2));
            root.addView(kbPanel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        }
        kbField = field;
        ownKb = true;
        typing = true;
        applyFocusMode();
        int type = field.getInputType();
        boolean numbers = (type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER;
        // capitals at the start of a sentence only where people write sentences: not in a key, an address or a model's name
        int variation = type & InputType.TYPE_MASK_VARIATION;
        boolean sentences = (type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                && (type & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0
                && variation != InputType.TYPE_TEXT_VARIATION_PASSWORD && variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD && variation != InputType.TYPE_TEXT_VARIATION_URI
                && variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS && variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
        int action = field.getImeOptions() & EditorInfo.IME_MASK_ACTION;
        kb.configure(numbers, sentences, action == EditorInfo.IME_ACTION_SEND ? "Send" : action == EditorInfo.IME_ACTION_GO ? "Go" : "Done");
        kbPanel.setVisibility(View.VISIBLE);
        updatePreview();
        // the screens and sheets move up above it, and the field stays in sight
        kbPanel.post(() -> {
            int h = kbPanel.getHeight();
            content.setPadding(0, 0, 0, h);
            sheetLayer.setPadding(0, 0, 0, h);
            overlay.setPadding(0, 0, 0, h);
            field.requestRectangleOnScreen(new android.graphics.Rect(0, 0, field.getWidth(), field.getHeight()), false);
        });
    }

    private void hideOwnKeyboard() {
        ownKb = false;
        kbField = null;
        if (kbPanel != null) kbPanel.setVisibility(View.GONE);
        content.setPadding(0, 0, 0, 0);
        sheetLayer.setPadding(0, 0, 0, 0);
        overlay.setPadding(0, 0, 0, 0);
    }

    private void onOwnKey(String key) {
        EditText f = kbField;
        if (f == null) return;
        android.text.Editable text = f.getText();
        if ("\b".equals(key)) {
            if (text.length() > 0) text.delete(text.length() - 1, text.length());
        } else if ("\n".equals(key)) {
            // done: what the field's own done key does (a PIN connects, chat sends...), then away
            int action = f.getImeOptions() & EditorInfo.IME_MASK_ACTION;
            f.onEditorAction(action == 0 ? EditorInfo.IME_ACTION_DONE : action);
            stopTyping();
            return;
        } else {
            text.append(key);   // the field's own filters (a PIN's four digits) still apply
        }
        f.setSelection(f.getText().length());
        updatePreview();
    }

    private void updatePreview() {
        if (kbPreview == null || kbField == null) return;
        CharSequence s = kbField.getText();
        boolean secret = (kbField.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) == InputType.TYPE_TEXT_VARIATION_PASSWORD
                && (kbField.getInputType() & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT;
        StringBuilder dots = new StringBuilder();
        for (int i = 0; secret && i < s.length(); i++) dots.append('•');
        String shown = secret ? dots.toString() : s.toString();
        if (shown.isEmpty() && kbField.getHint() != null) {
            kbPreview.setTextColor(theme.text3);
            kbPreview.setText(kbField.getHint());
        } else {
            kbPreview.setTextColor(theme.text);
            kbPreview.setText(shown + "▏");
        }
    }

    private void startTyping(EditText field) {
        if (besideGame() && prefs.getBoolean("ownKeyboard", true)) {
            showOwnKeyboard(field);
            return;
        }
        typing = true;
        applyFocusMode();
        // a full-screen keyboard would cover the whole screen on a small landscape one
        field.setImeOptions(field.getImeOptions() | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        // the window can take focus once the flag change has reached the window manager
        main.postDelayed(() -> {
            if (!typing) return;
            field.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, 0);
        }, 150);
    }

    private void stopTyping() {
        if (!typing) return;
        typing = false;
        if (ownKb) {
            hideOwnKeyboard();
            applyFocusMode();
            return;
        }
        View f = getCurrentFocus();
        if (f != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
            f.clearFocus();
        }
        applyFocusMode();
    }

    /** The text field under a point of the screen, if a visible one is there and nothing covers it. */
    private static EditText fieldAt(View v, int x, int y) {
        View hit = touchedAt(v, x, y);
        return hit instanceof EditText && hit.isEnabled() ? (EditText) hit : null;
    }

    /** The top-most field or clickable view under a point: a sheet's backdrop hides the fields behind it. */
    private static View touchedAt(View v, int x, int y) {
        if (v.getVisibility() != View.VISIBLE) return null;
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        if (x < at[0] || y < at[1] || x >= at[0] + v.getWidth() || y >= at[1] + v.getHeight()) return null;
        if (v instanceof EditText) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View hit = touchedAt(g.getChildAt(i), x, y);
                if (hit != null) return hit;
            }
        }
        return v.isClickable() ? v : null;
    }

    private void immersive() {
        applyFocusMode();
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        if (prefs.getBoolean("wake", true)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void applyServer() {
        link.configure(prefs.getString("server", "127.0.0.1:7878"), prefs.getString("pin", ""));
        art.setBase(link.baseUrl());
        if (!mapFor.equals(link.baseUrl()) && map != null) loadMap();
    }

    // ------------------------------------------------------------------ Host

    @Override public Theme theme() { return theme; }
    @Override public ArtCache art() { return art; }
    @Override public SharedPreferences prefs() { return prefs; }

    @Override
    public void command(String cmd, JSONObject args, String label) {
        if (!authed) {
            toast("Not connected to the game", true);
            return;
        }
        int id = nextCmd++;
        pending.put(id, label == null ? "" : label);
        JSONObject m = Ui.args("t", "cmd", "id", id, "cmd", cmd, "args", args == null ? new JSONObject() : args);
        link.send(m);
        main.postDelayed(() -> {
            if (pending.remove(id) != null) toast("The game did not answer", true);
        }, 6000);
    }

    // the one menu a screen is waiting for (a newer ask replaces it)
    private MenuCallback menuWaiter;
    private int menuReq;

    @Override
    public void requestMenu(JSONObject args, MenuCallback cb) {
        menuReq++;
        menuWaiter = cb;
        try {
            args.put("req", menuReq);
        } catch (Exception ignored) {
        }
        command("menu", args, null);
    }

    @Override
    public String apiUrl(String pathAndQuery) {
        String key = httpKey == null || httpKey.isEmpty() ? "" : (pathAndQuery.contains("?") ? "&" : "?") + "k=" + httpKey;
        return link.baseUrl() + pathAndQuery + key;
    }

    @Override
    public void showOnMap(float x, float y, float zoom) {
        setTab("map");
        map.focusOn(x, y, zoom);
    }

    @Override
    public void openSheet(View contentView) {
        sheetPanel.removeAllViews();
        // a sheet with a title bar (Ui.sheetTitle, the game's look) is a window: no grip, the bar at the very top
        boolean titled = "titled".equals(contentView.getTag());
        sheetPanel.setPadding(theme.dp(18), titled ? 0 : theme.dp(10), theme.dp(18), theme.dp(20));
        if (!titled) {
            View grip = new View(this);
            grip.setBackground(theme.round(theme.pz ? 0xFF4A4A4A : theme.surface3, 4));
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(theme.dp(44), theme.dp(5));
            glp.gravity = Gravity.CENTER_HORIZONTAL;
            glp.bottomMargin = theme.dp(14);
            sheetPanel.addView(grip, glp);
        }
        sheetPanel.addView(contentView);
        boolean wasOpen = sheetLayer.getVisibility() == View.VISIBLE;
        sheetLayer.setVisibility(View.VISIBLE);
        if (!wasOpen) {
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(150).start();
            sheetPanel.setTranslationY(theme.dp(60));
            sheetPanel.setAlpha(0f);
            sheetPanel.animate().translationY(0).alpha(1f).setDuration(180).start();
        }
    }

    @Override
    public void closeSheet() {
        if (sheetLayer.getVisibility() != View.VISIBLE) return;
        stopTyping();
        sheetLayer.setVisibility(View.GONE);
        if (vitals != null) vitals.sheetClosed();
    }

    @Override
    public void toast(String text, boolean bad) {
        TextView v;
        if (theme.pz) {
            // like the game's own notes: white text on a black panel with its grey border (red for trouble)
            v = Ui.text(this, theme, text, 15, 0xFFFFFFFF, true);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(bad ? 0xF02A0B08 : 0xF0000000);
            g.setStroke(theme.dp(1), bad ? theme.bad : theme.line);
            v.setBackground(g);
        } else {
            v = Ui.text(this, theme, text, 15, bad ? 0xFFFFFFFF : theme.bg, true);
            v.setBackground(theme.round(bad ? theme.bad : theme.text, 14));
            v.setElevation(theme.dpf(6));
        }
        v.setPadding(theme.dp(18), theme.dp(11), theme.dp(18), theme.dp(11));
        toasts.addView(v, Ui.margins(Ui.lp(-2, -2), 0, theme.dp(8), 0, 0));
        while (toasts.getChildCount() > 3) toasts.removeViewAt(0);
        v.setAlpha(0f);
        v.setTranslationY(theme.dp(12));
        v.animate().alpha(1f).translationY(0).setDuration(180).start();
        main.postDelayed(() -> v.animate().alpha(0f).setDuration(280).withEndAction(() -> toasts.removeView(v)).start(), 2200);
    }

    // ------------------------------------------------------------------ settings

    @Override
    public void onSettingsChanged(boolean rebuild) {
        if (prefs.getBoolean("watch", true)) GameWatch.start(this);
        else GameWatch.stop(this);
        Fmt.imperial = "imperial".equals(prefs.getString("units", defaultUnits()));
        Fmt.clock24 = "24".equals(prefs.getString("clock", defaultClock()));
        if (rebuild) {
            build();
            setTab("settings");
            return;
        }
        if (map != null) {
            map.setStyle(!"dark".equals(prefs.getString("map", "paper")));
            map.setLabels(prefs.getBoolean("labels", true));
        }
        immersive();
        if (core != null) onCore(core);
    }

    @Override
    public void onServerChanged() {
        authed = false;
        // nothing from the previous game may stay on screen (or be acted on) in the next one
        core = gearData = bodyData = skillsData = null;
        marksData = radioData = chatData = baseData = teamData = outfitData = null;
        lootData = craftData = journalData = radarData = null;
        if (radar != null) radar.update(null);
        lastCoreAt = 0;
        coreSig = "";
        lastChatN = -1;
        moodleLevels.clear();
        moodlesPrimed = false;
        symbolDefs.clear();
        symbolTex.clear();
        symbolsFor = "";
        build();
        applyServer();
        link.stop();
        link.start();
        showOverlay("wait", "Connecting…", link.host() + ":" + link.port());
    }

    private static String defaultUnits() {
        String c = java.util.Locale.getDefault().getCountry();
        return ("US".equals(c) || "LR".equals(c) || "MM".equals(c)) ? "imperial" : "metric";
    }

    private static String defaultClock() {
        String c = java.util.Locale.getDefault().getCountry();
        return ("US".equals(c) || "CA".equals(c) || "AU".equals(c) || "PH".equals(c) || "IN".equals(c)) ? "12" : "24";
    }

    // ------------------------------------------------------------------ layout

    private void build() {
        theme = Theme.of(this, prefs.getString("theme", "dark"), prefs.getString("scale", "m"));
        Fmt.imperial = "imperial".equals(prefs.getString("units", defaultUnits()));
        Fmt.clock24 = "24".equals(prefs.getString("clock", defaultClock()));
        getWindow().setNavigationBarColor(theme.bg);
        getWindow().getDecorView().setBackgroundColor(theme.bg);
        tabButtons.clear();
        screens.clear();

        root = new FrameLayout(this);
        root.setBackgroundColor(theme.bg);
        LinearLayout shell = Ui.col(this);
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        shell.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, theme.dp(60)));
        View topLine = new View(this);
        topLine.setBackgroundColor(theme.line);
        shell.addView(topLine, new LinearLayout.LayoutParams(-1, theme.dp(1)));

        LinearLayout body = Ui.row(this);
        body.setGravity(Gravity.TOP);
        shell.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(buildRail(), new LinearLayout.LayoutParams(theme.dp(84), -1));
        View railLine = new View(this);
        railLine.setBackgroundColor(theme.line);
        body.addView(railLine, new LinearLayout.LayoutParams(theme.dp(1), -1));
        content = new FrameLayout(this);
        body.addView(content, new LinearLayout.LayoutParams(0, -1, 1));

        content.addView(buildMap(), Screens.match());
        screens.put("map", content.getChildAt(0));
        vitals = new Screens.Vitals(this, this);
        gear = new Screens.Gear(this, this);
        skills = new Screens.Skills(this, this);
        comms = new Screens.Comms(this, this);
        baseScreen = new Screens.Base(this, this);
        journal = new Journal(this, this);
        ask = new AskScreen(this, this);
        weapons = new WeaponsScreen(this, this);
        car = new Screens.Car(this, this);
        settings = new Screens.Settings(this, this, this);
        addScreen("vitals", vitals);
        addScreen("gear", gear);
        addScreen("weapons", weapons);
        addScreen("skills", skills);
        addScreen("comms", comms);
        addScreen("base", baseScreen);
        addScreen("journal", journal);
        addScreen("ask", ask);
        addScreen("car", car);
        addScreen("settings", settings);
        buildAlert();

        buildSheet();
        toasts = Ui.col(this);
        toasts.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tlp.bottomMargin = theme.dp(22);
        root.addView(toasts, tlp);
        buildOverlay();

        setContentView(root);
        setTab(tab);
        if (core != null) onCore(core);
        if (bodyData != null) vitals.body(bodyData);
        if (gearData != null) gear.gear(gearData);
        if (skillsData != null) skills.skills(skillsData);
        if (outfitData != null) { vitals.outfit(outfitData); gear.outfit(outfitData); }
        if (lootData != null) gear.loot(lootData);
        if (craftData != null) gear.craft(craftData);
        if (journalData != null) journal.journal(journalData);
        if (diariesData != null) journal.diaries(diariesData);
        if (askData != null) ask.situation(askData);
        if (weaponsData != null) weapons.weapons(weaponsData);
        if (planData != null) map.setPlan(planData);
        if (memoryData != null) map.setMemory(memoryData);
        map.setTrailShown(prefs.getBoolean("trail", false));
        forecastSig = "";
        if (radioData != null) comms.radio(radioData);
        if (chatData != null) comms.chat(chatData);
        if (baseData != null) baseScreen.base(baseData);
        if (marksData != null) onMarks(marksData);
        if (teamData != null) map.setTeam(Screens.Json.arr(teamData.opt("list")));
        for (Map.Entry<String, String> e : symbolTex.entrySet()) {
            Bitmap b = art.peek("m", e.getValue());
            if (b != null) map.setSymbolArt(e.getKey(), b);
        }
        if (authed) hideOverlay();
        loadMap();
    }

    private void addScreen(String key, View v) {
        v.setVisibility(View.GONE);
        content.addView(v, Screens.match());
        screens.put(key, v);
    }

    private static final String[] SPEED_ART = {"Pause", "Play", "FFwd1", "FFwd2"};
    private static final String[] SPEED_ICON = {"pause", "play", "ff", "fff"};
    private int speedShown = -1;

    private View buildTopBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(theme.surface);
        bar.setPadding(theme.dp(12), 0, theme.dp(12), 0);
        // the game's own digital watch: time, AM/PM, temperature and date
        watch = new GameArt.Watch(this, theme, art);
        bar.addView(watch, new LinearLayout.LayoutParams(-2, theme.dp(46)));

        LinearLayout info = Ui.row(this);
        weatherIcon = Ui.icon(this, theme, "sun", theme.accent, theme.pz ? 30 : 22);
        info.addView(weatherIcon);
        LinearLayout col = Ui.col(this);
        clockTime = Ui.ellipsize(Ui.text(this, theme, "Knox Link", 16, theme.text, true));
        clockDate = Ui.ellipsize(Ui.text(this, theme, "Waiting for the game", 12.5f, theme.text2, false));
        col.addView(clockTime, Ui.lp(-1, -2));
        col.addView(clockDate, Ui.margins(Ui.lp(-1, -2), 0, theme.dp(3), 0, 0));
        info.addView(col, Ui.margins(Ui.weight(1), theme.dp(9), 0, 0, 0));
        weatherPill = info;
        // the weather: tap for the forecast heard on the radio
        info.setBackground(Ui.pressable(theme, 0x00000000, 12));
        info.setOnClickListener(v -> Forecast.sheet(this, this, core));
        // the info column takes the free space, so long names shorten with "…" instead of wrapping
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, -2, 1);
        ilp.leftMargin = theme.dp(12);
        ilp.rightMargin = theme.dp(10);
        bar.addView(info, ilp);

        // the danger radar, on every tab: the zombies the character has seen or heard (tap for a bigger one)
        radar = new RadarView(this, this);
        bar.addView(radar, Ui.margins(Ui.lp(theme.dp(46), theme.dp(46)), 0, 0, theme.dp(10), 0));
        if (radarData != null) radar.update(radarData);

        // the game's own speed buttons (media/ui/speedControls), with plain icons until they arrive
        speedRow = Ui.row(this);
        // the game shows its speed buttons on their own; the app's look keeps its dark pill
        if (!theme.pz) speedRow.setBackground(theme.round(0xFF0E0F11, 12));
        speedRow.setPadding(theme.dp(4), theme.dp(3), theme.dp(4), theme.dp(3));
        for (int i = 0; i < 4; i++) {
            final int s = i;
            ImageView b = new ImageView(this);
            b.setImageDrawable(theme.icon(SPEED_ICON[i], theme.text2));
            b.setScaleType(ImageView.ScaleType.FIT_CENTER);
            b.setPadding(theme.dp(8), theme.dp(8), theme.dp(8), theme.dp(8));
            b.setOnClickListener(v -> {
                markSpeed(s);
                command("speed", Ui.args("s", s), null);
            });
            art.media("ui/speedControls/" + SPEED_ART[i] + "_On.png", bm -> { speedShown = -2; markSpeed(lastSpeed); });
            art.media("ui/speedControls/" + SPEED_ART[i] + "_Off.png", bm -> { speedShown = -2; markSpeed(lastSpeed); });
            speedRow.addView(b, new LinearLayout.LayoutParams(theme.dp(i >= 2 ? 52 : 44), theme.dp(40)));
        }
        bar.addView(speedRow);

        ImageView stop = new ImageView(this);
        stop.setImageDrawable(theme.icon("stop", theme.text2));
        stop.setScaleType(ImageView.ScaleType.CENTER);
        stop.setBackground(Ui.pressable(theme, theme.surface2, 13));
        stop.setOnClickListener(v -> command("stop", new JSONObject(), "Stopped"));
        bar.addView(stop, Ui.margins(Ui.lp(theme.dp(44), theme.dp(44)), theme.dp(10), 0, 0, 0));

        conn = new View(this);
        conn.setBackground(theme.circle(theme.text3));
        bar.addView(conn, Ui.margins(Ui.lp(theme.dp(11), theme.dp(11)), theme.dp(14), 0, theme.dp(4), 0));
        return bar;
    }

    private int lastSpeed = 1;
    private String weatherShown;
    private final Map<String, Bitmap> weatherPics = new HashMap<>();

    /**
     * The weather picture in the top bar: the game's pixel art when it has one for this weather (drawn crisp).
     * Its pictures are 43x43 layers drawn over each other (the game's season.lua drew rain as the heavy cloud
     * with the rain over it), so a picture here is a list of them, in order.
     */
    private void showWeather(String[] layers, String icon) {
        String key = layers != null ? String.join("+", layers) : "icon:" + icon;
        if (key.equals(weatherShown)) return;
        weatherShown = key;
        Bitmap pic = layers == null ? null : weatherPics.get(key);
        if (pic == null && layers != null) {
            Bitmap[] got = new Bitmap[layers.length];
            int w = 0, h = 0;
            for (int i = 0; i < layers.length; i++) {
                got[i] = art.peek("i", layers[i]);
                if (got[i] == null) break;
                w = Math.max(w, got[i].getWidth());
                h = Math.max(h, got[i].getHeight());
            }
            if (got[layers.length - 1] != null) {
                pic = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(pic);
                for (Bitmap g : got) cv.drawBitmap(g, 0, 0, null);
                weatherPics.put(key, pic);
            }
        }
        if (pic != null) {
            android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(getResources(), pic);
            d.setFilterBitmap(false);
            weatherIcon.setImageDrawable(d);
            return;
        }
        weatherIcon.setImageDrawable(theme.icon(icon, theme.accent));
        if (layers != null) {
            for (String tex : layers) {
                art.icon(tex, bm -> {
                    if (bm == null || !key.equals(weatherShown)) return;
                    weatherShown = null;   // look again: when the last layer arrives, the picture is made
                    showWeather(layers, icon);
                });
            }
        }
    }

    private void markSpeed(int s) {
        lastSpeed = s;
        if (s == speedShown) return;
        speedShown = s;
        for (int i = 0; i < speedRow.getChildCount(); i++) {
            ImageView b = (ImageView) speedRow.getChildAt(i);
            boolean on = i == s;
            Bitmap bm = art.peek("m", "ui/speedControls/" + SPEED_ART[i] + (on ? "_On.png" : "_Off.png"));
            if (bm != null) {
                // pixel art: scale without smoothing so it stays crisp
                android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(getResources(), bm);
                d.setFilterBitmap(false);
                d.setAntiAlias(false);
                b.setImageDrawable(d);
                // the game marks the speed with its red "on" picture alone
                b.setBackground(on && !theme.pz ? theme.round(0xFF2A1A18, 9) : null);
            } else {
                b.setImageDrawable(theme.icon(SPEED_ICON[i], on ? theme.accentInk : theme.text2));
                b.setBackground(on ? theme.round(theme.accent, 10) : null);
            }
        }
    }

    private View buildRail() {
        rail = Ui.col(this);
        rail.setPadding(theme.dp(6), theme.dp(6), theme.dp(6), theme.dp(6));
        for (String[] t : TABS) rail.addView(railButton(t[0], t[1], t[2], t[3], t[4]), Ui.margins(Ui.lp(-1, -2), 0, 0, 0, theme.dp(2)));
        rail.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        rail.addView(railButton("settings", null, SETTINGS_ART, SETTINGS_ART, "settings"));
        tabButtons.get("car").setVisibility(View.GONE);
        // scrolls only if a small screen can't fit every tab
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.setBackgroundColor(theme.surface);
        sv.addView(rail);
        return sv;
    }

    private View railButton(String key, String label, String artOff, String artOn, String fallback) {
        LinearLayout b = Ui.col(this);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        // compact enough for nine tabs on a handheld's second screen
        b.setPadding(0, theme.dp(label == null ? 6 : 4), 0, theme.dp(label == null ? 6 : 3));
        FrameLayout iconBox = new FrameLayout(this);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(theme.icon(fallback, theme.text3));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconBox.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        // "something new" dot
        View badge = new View(this);
        badge.setBackground(theme.circle(theme.bad));
        badge.setVisibility(View.GONE);
        iconBox.addView(badge, new FrameLayout.LayoutParams(theme.dp(9), theme.dp(9), Gravity.TOP | Gravity.END));
        int box = artOff != null ? 30 : 26;
        b.addView(iconBox, new LinearLayout.LayoutParams(theme.dp(box), theme.dp(box)));
        TextView tv = null;
        if (label != null) {
            tv = Ui.text(this, theme, label, 10.5f, theme.text3, true);
            b.addView(tv, Ui.margins(Ui.lp(-2, -2), 0, theme.dp(2), 0, 0));
        }
        b.setTag(new Object[]{iv, tv, artOff, artOn, fallback, badge});
        b.setOnClickListener(v -> setTab(key));
        tabButtons.put(key, b);
        if (artOff != null) {
            // the game's own pictures; the plain icon stays until they arrive
            loadRailArt(artOff, false);
            loadRailArt(artOn, true);
        }
        return b;
    }

    private void loadRailArt(String spec, boolean on) {
        if (spec.startsWith("sticker:")) art.sticker(spec.substring(8), on, bm -> styleRail());
        else art.media(spec, bm -> styleRail());
    }

    private Bitmap railArt(String spec, boolean on) {
        if (spec == null) return null;
        if (spec.startsWith("sticker:")) return art.peek(on ? "s1" : "s0", spec.substring(8));
        return art.peek("m", spec);
    }

    private void styleRail() {
        for (Map.Entry<String, View> e : tabButtons.entrySet()) {
            boolean on = e.getKey().equals(tab);
            Object[] tag = (Object[]) e.getValue().getTag();
            ImageView iv = (ImageView) tag[0];
            TextView tv = (TextView) tag[1];
            Bitmap bm = railArt((String) (on ? tag[3] : tag[2]), on);
            if (bm != null) {
                if (iv.getTag() != bm) {
                    android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(getResources(), bm);
                    d.setFilterBitmap(true);
                    iv.setImageDrawable(d);
                    iv.setTag(bm);
                }
                iv.setImageAlpha(on ? 255 : 210);
            } else {
                iv.setImageDrawable(theme.icon((String) tag[4], on ? theme.accent : theme.text3));
                iv.setTag(null);
            }
            if (theme.pz) {
                // the chosen tab: the game's selection, an orange-brown fill with an accent border
                if (tv != null) tv.setTextColor(on ? 0xFFFFFFFF : theme.text2);
                android.graphics.drawable.GradientDrawable sel = null;
                if (on) {
                    sel = new android.graphics.drawable.GradientDrawable();
                    sel.setColor(theme.selection);
                    sel.setStroke(theme.dp(1), theme.accent);
                }
                e.getValue().setBackground(sel);
            } else {
                if (tv != null) tv.setTextColor(on ? theme.accent : theme.text3);
                e.getValue().setBackground(on ? theme.round(Theme.alpha(theme.accent, 0.15f), 14) : null);
            }
        }
    }

    private void setBadge(String key, boolean on) {
        View b = tabButtons.get(key);
        if (b != null) ((View) ((Object[]) b.getTag())[5]).setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void setRailLabel(String key, String label) {
        View b = tabButtons.get(key);
        TextView tv = b == null ? null : (TextView) ((Object[]) b.getTag())[1];
        if (tv != null && !label.contentEquals(tv.getText())) tv.setText(label);
    }

    private View buildMap() {
        FrameLayout f = new FrameLayout(this);
        map = new MapView(this);
        map.setArt(art);   // the game's own floors, for the floor plan
        map.setStyle(!"dark".equals(prefs.getString("map", "paper")));
        map.setLabels(prefs.getBoolean("labels", true));
        map.setListener(new MapView.Listener() {
            @Override
            public void onLongPress(int x, int y) {
                spotSheet(x, y);
            }

            @Override
            public void onTapThing(String kind, JSONObject thing) {
                if ("mark".equals(kind)) markSheet(thing);
                else if ("team".equals(kind)) teamSheet(thing);
                else if ("car".equals(kind)) carSheet(thing);
                else if ("seen".equals(kind)) seenSheet(thing);
                else baseSheet();
            }

            @Override
            public void onPlanWanted(boolean on) {
                if ("map".equals(tab)) subscribe();
            }

            @Override
            public void onTapBuilding(String name) {
                if (name == null) {
                    placeChip.setVisibility(View.GONE);
                    return;
                }
                placeChip.setText(name);
                placeChip.setVisibility(View.VISIBLE);
                main.removeCallbacks(hidePlace);
                main.postDelayed(hidePlace, 3500);
            }

            @Override
            public void onFollowChanged(boolean follow) {
                if (followBtn != null) ((ImageView) followBtn).setImageDrawable(theme.icon("locate", follow ? theme.accent : theme.text2));
            }
        });
        f.addView(map, Screens.match());
        art.media("textures/worldMap/Paper.png", map::setPaper);

        mapStatus = Ui.text(this, theme, "Loading the map…", 15, theme.text2, true);
        mapStatus.setBackground(theme.card());
        mapStatus.setPadding(theme.dp(18), theme.dp(12), theme.dp(18), theme.dp(12));
        f.addView(mapStatus, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // shown when the world's rules hide the map (no world map, or too dark to read it)
        LinearLayout notice = Ui.card(this, theme);
        notice.setGravity(Gravity.CENTER_HORIZONTAL);
        notice.addView(Ui.icon(this, theme, "map", theme.text3, 34));
        mapNoticeText = Ui.text(this, theme, "", 16, theme.text2, true);
        mapNoticeText.setGravity(Gravity.CENTER);
        notice.addView(mapNoticeText, Ui.margins(Ui.lp(-2, -2), 0, theme.dp(10), 0, 0));
        notice.setVisibility(View.GONE);
        mapNotice = notice;
        f.addView(notice, new FrameLayout.LayoutParams(theme.dp(320), -2, Gravity.CENTER));

        LinearLayout top = Ui.row(this);
        top.setPadding(theme.dp(14), theme.dp(14), theme.dp(80), 0);
        LinearLayout wp = Ui.row(this);
        wp.setBackground(theme.card());
        wp.setElevation(theme.dpf(4));
        wp.setPadding(theme.dp(12), theme.dp(6), theme.dp(6), theme.dp(6));
        wp.addView(Ui.icon(this, theme, "pin", theme.accent, 20));
        wpText = Ui.text(this, theme, "Waypoint", 15, theme.text, true);
        wp.addView(wpText, Ui.margins(Ui.lp(-2, -2), theme.dp(8), 0, theme.dp(8), 0));
        ImageView clear = new ImageView(this);
        clear.setImageDrawable(theme.icon("x", theme.text2));
        clear.setScaleType(ImageView.ScaleType.CENTER);
        clear.setBackground(theme.round(theme.surface3, 16));
        clear.setOnClickListener(v -> command("waypointClear", new JSONObject(), "Waypoint cleared"));
        wp.addView(clear, Ui.lp(theme.dp(32), theme.dp(32)));
        wp.setVisibility(View.GONE);
        wpChip = wp;
        top.addView(wp);
        placeChip = Ui.text(this, theme, "", 15, theme.text, true);
        placeChip.setBackground(theme.card());
        placeChip.setElevation(theme.dpf(4));
        placeChip.setPadding(theme.dp(14), theme.dp(9), theme.dp(14), theme.dp(9));
        placeChip.setVisibility(View.GONE);
        top.addView(placeChip, Ui.margins(Ui.lp(-2, -2), theme.dp(8), 0, 0, 0));
        f.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout ctrl = Ui.col(this);
        String[] icons = {"plus", "minus", "locate", "layers", "search"};
        for (int i = 0; i < icons.length; i++) {
            ImageView b = new ImageView(this);
            b.setImageDrawable(theme.icon(icons[i], i == 2 ? theme.accent : theme.text2));
            b.setScaleType(ImageView.ScaleType.CENTER);
            b.setBackground(Ui.pressable(theme, theme.surface, 16));
            b.setElevation(theme.dpf(4));
            final int k = i;
            b.setOnClickListener(v -> {
                if (k == 0) map.zoom(1.6f);
                else if (k == 1) map.zoom(1 / 1.6f);
                else if (k == 2) map.setFollow(!map.isFollowing());
                else if (k == 3) showLegend();
                else SeenSheet.open(this, this, map);   // "Where did I see...?"
            });
            if (i == 2) followBtn = b;
            ctrl.addView(b, Ui.margins(Ui.lp(theme.dp(52), theme.dp(52)), 0, 0, 0, theme.dp(8)));
        }
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clp.setMargins(0, theme.dp(14), theme.dp(14), 0);
        f.addView(ctrl, clp);
        return f;
    }

    private final Runnable hidePlace = () -> { if (placeChip != null) placeChip.setVisibility(View.GONE); };

    private void showLegend() {
        LinearLayout sheet = Ui.col(this);
        sheet.addView(Ui.text(this, theme, "Map key", 20, theme.text, true), Ui.margins(Ui.lp(-2, -2), 0, 0, 0, theme.dp(12)));
        int[][] kinds = {{MapView.RES}, {MapView.RETAIL}, {MapView.FOOD}, {MapView.MEDICAL}, {MapView.COMMUNITY},
                {MapView.INDUSTRIAL}, {MapView.HOSPITALITY}, {MapView.PRIMARY}, {MapView.WATER}, {MapView.FOREST}};
        String[] names = {"Residential", "Shops & offices", "Restaurants & bars", "Medical", "Police, fire, schools",
                "Industrial", "Hotels & motels", "Road", "Water", "Forest"};
        Widgets.Flow flow = new Widgets.Flow(this, theme.dp(18), theme.dp(10));
        for (int i = 0; i < names.length; i++) {
            LinearLayout r = Ui.row(this);
            View sw = new View(this);
            sw.setBackground(theme.round(map.colorOf(kinds[i][0]), 4));
            r.addView(sw, Ui.lp(theme.dp(20), theme.dp(14)));
            r.addView(Ui.text(this, theme, names[i], 15, theme.text2, false), Ui.margins(Ui.lp(-2, -2), theme.dp(8), 0, 0, 0));
            flow.addView(r);
        }
        sheet.addView(flow);
        sheet.addView(Ui.text(this, theme, "Press and hold on the map to set a waypoint, draw on your map or set your base. Tap a building to see what it is. "
                        + "A green tick: a building you've searched. Zoom all the way in for the floor plan around you.", 13.5f, theme.text3, false),
                Ui.margins(Ui.lp(-1, -2), 0, theme.dp(16), 0, theme.dp(12)));
        boolean trailOn = prefs.getBoolean("trail", false);
        TextView trail = Ui.action(this, theme, "map", trailOn ? "Hide the way you walked" : "Show the way you walked");
        trail.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("trail", false);
            prefs.edit().putBoolean("trail", on).apply();
            map.setTrailShown(on);
            closeSheet();
        });
        sheet.addView(trail);
        openSheet(sheet);
    }

    /** The car the character left: where, and a waypoint to it. */
    private void carSheet(JSONObject car) {
        LinearLayout sheet = Ui.col(this);
        int x = car.optInt("x"), y = car.optInt("y");
        String far = distanceFrom(x + 0.5, y + 0.5);
        sheetTitle(sheet, car.optString("n", "Your car"), far == null ? "Where you left it" : "Where you left it · " + far);
        sheetAction(sheet, "pin", "Set a waypoint to it", () -> { command("waypoint", Ui.args("x", x, "y", y), "Waypoint set"); closeSheet(); });
        sheetAction(sheet, "x", "Close", this::closeSheet);
        openSheet(sheet);
    }

    /** A "Where did I see...?" find on the map. */
    private void seenSheet(JSONObject o) {
        LinearLayout sheet = Ui.col(this);
        int n = o.optInt("c", 1);
        StringBuilder where = new StringBuilder(o.optString("b", ""));
        if (o.has("rm")) where.append(" · ").append(o.optString("rm"));
        if (o.has("town")) where.append(" · ").append(o.optString("town"));
        sheetTitle(sheet, o.optString("n") + (n > 1 ? "  ×" + n : ""), where + " · seen " + SeenSheet.ago(o.optDouble("h", 0)));
        int x = o.optInt("x"), y = o.optInt("y");
        sheetAction(sheet, "pin", "Set a waypoint there", () -> { command("waypoint", Ui.args("x", x, "y", y), "Waypoint set"); closeSheet(); });
        sheetAction(sheet, "search", "Search again…", () -> SeenSheet.open(this, this, map));
        sheetAction(sheet, "x", "Clear the pins", () -> { map.setPins(null, -1); closeSheet(); });
        openSheet(sheet);
    }

    // ------------------------------------------------------------------ map sheets

    private TextView sheetTitle(LinearLayout sheet, String title, String sub) {
        sheet.addView(Ui.text(this, theme, title, 20, theme.text, true));
        TextView s = Ui.text(this, theme, sub == null ? "" : sub, 13.5f, theme.text3, false);
        sheet.addView(s, Ui.margins(Ui.lp(-1, -2), 0, theme.dp(4), 0, theme.dp(14)));
        if (sub == null) s.setVisibility(View.GONE);
        return s;
    }

    private void sheetAction(LinearLayout sheet, String icon, String label, Runnable r) {
        TextView a = Ui.action(this, theme, icon, label);
        a.setOnClickListener(v -> r.run());
        sheet.addView(a, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, theme.dp(8)));
    }

    private String distanceFrom(double x, double y) {
        JSONObject p = core == null ? null : core.optJSONObject("p");
        if (p == null) return null;
        double dx = x - p.optDouble("x"), dy = y - p.optDouble("y");
        double d = Math.hypot(dx, dy);
        return d < 6 ? "Right where you are" : Fmt.dist(d) + " " + Fmt.compass(dx, dy) + " of you";
    }

    /** Press and hold on the map. */
    private void spotSheet(int x, int y) {
        LinearLayout sheet = Ui.col(this);
        sheetTitle(sheet, "This spot", distanceFrom(x, y));
        sheetAction(sheet, "pin", "Set a waypoint here", () -> { command("waypoint", Ui.args("x", x, "y", y), "Waypoint set"); closeSheet(); });
        sheetAction(sheet, "flag", "Draw a symbol on your map…", () -> symbolSheet(x, y));
        sheetAction(sheet, "pen", "Write a note on your map…", () -> noteSheet(x, y));
        boolean hasBase = core != null && core.optJSONObject("base") != null;
        boolean safehouse = hasBase && core.optJSONObject("base").optBoolean("safehouse");
        if (!safehouse) {
            sheetAction(sheet, "home", hasBase ? "Move my base here" : "Make this my base",
                    () -> { command("baseSet", Ui.args("x", x, "y", y), hasBase ? "Base moved" : "Base set"); closeSheet(); });
        }
        openSheet(sheet);
    }

    private JSONObject markTools() {
        return marksData == null ? null : marksData.optJSONObject("tools");
    }

    private static final String[][] INKS = {
            {"Pen", "Pen", "212121"}, {"Pencil", "Pencil", "333333"}, {"RedPen", "Red pen", "a60e0e"},
            {"BluePen", "Blue pen", "28307d"}, {"GreenPen", "Green pen", "0f632b"}};
    private String ink = "Pen";

    /** The pens and pencils the character carries, as a row to pick from. Null if none. */
    private View inkRow(JSONObject tools) {
        return inkRow(tools, null);
    }

    private View inkRow(JSONObject tools, Runnable changed) {
        org.json.JSONArray have = tools == null ? new org.json.JSONArray() : Screens.Json.arr(tools.opt("inks"));
        if (have.length() == 0) return null;
        java.util.Set<String> set = new java.util.HashSet<>();
        for (int i = 0; i < have.length(); i++) set.add(have.optString(i));
        if (!set.contains(ink)) ink = have.optString(0);
        Widgets.Flow row = new Widgets.Flow(this, theme.dp(8), theme.dp(8));
        List<TextView> chips = new java.util.ArrayList<>();
        for (String[] k : INKS) {
            if (!set.contains(k[0])) continue;
            TextView chip = Ui.text(this, theme, k[1], 14, theme.text, true);
            chip.setPadding(theme.dp(12), theme.dp(8), theme.dp(14), theme.dp(8));
            android.graphics.drawable.GradientDrawable dot = theme.round(0xFF000000 | Integer.parseInt(k[2], 16), 99);
            dot.setSize(theme.dp(14), theme.dp(14));
            dot.setStroke(theme.dp(1), 0x55FFFFFF);
            chip.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null);
            chip.setCompoundDrawablePadding(theme.dp(8));
            chip.setTag(k[0]);
            chip.setOnClickListener(v -> {
                ink = k[0];
                for (TextView c : chips) styleInk(c);
                if (changed != null) changed.run();
            });
            chips.add(chip);
            styleInk(chip);
            row.addView(chip);
        }
        return row;
    }

    private void styleInk(TextView chip) {
        boolean on = ink.equals(chip.getTag());
        android.graphics.drawable.GradientDrawable g = theme.round(on ? Theme.alpha(theme.accent, 0.18f) : theme.surface2, 99);
        if (on) g.setStroke(theme.dp(2), theme.accent);
        chip.setBackground(g);
    }

    private int inkColor() {
        for (String[] k : INKS) if (k[0].equals(ink)) return 0xFF000000 | Integer.parseInt(k[2], 16);
        return 0xFF212121;
    }

    private void noPen(LinearLayout sheet) {
        sheet.addView(Ui.text(this, theme, "You need a pen or a pencil to draw on your map, like in the game.", 15, theme.text2, false));
        openSheet(sheet);
    }

    private void symbolSheet(int x, int y) {
        LinearLayout sheet = Ui.col(this);
        sheetTitle(sheet, "Draw on your map", "It shows on the map in the game too.");
        List<ImageView> cells = new java.util.ArrayList<>();
        // symbols are drawn on paper-coloured tiles in the chosen ink, as on the game's map
        Runnable recolor = () -> { for (ImageView iv : cells) iv.setColorFilter(inkColor()); };
        View inks = inkRow(markTools(), recolor);
        if (inks == null) { noPen(sheet); return; }
        sheet.addView(inks, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, theme.dp(14)));
        if (symbolDefs.isEmpty()) {
            sheet.addView(Ui.text(this, theme, "Loading the game's map symbols…", 14, theme.text3, false));
            loadSymbols();
        }
        Map<String, List<JSONObject>> byTab = new java.util.LinkedHashMap<>();
        for (JSONObject d : symbolDefs) {
            String tabName = d.optString("tab", "");
            if (!byTab.containsKey(tabName)) byTab.put(tabName, new java.util.ArrayList<>());
            byTab.get(tabName).add(d);
        }
        for (Map.Entry<String, List<JSONObject>> group : byTab.entrySet()) {
            if (!group.getKey().isEmpty()) sheet.addView(Ui.label(this, theme, group.getKey()), Ui.margins(Ui.lp(-2, -2), theme.dp(2), theme.dp(6), 0, theme.dp(8)));
            Widgets.Flow grid = new Widgets.Flow(this, theme.dp(6), theme.dp(6));
            sheet.addView(grid, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, theme.dp(8)));
            for (JSONObject d : group.getValue()) {
                String id = d.optString("id");
                ImageView iv = new ImageView(this);
                iv.setBackground(Ui.pressable(theme, 0xFFDBD7C0, 10));
                int pad = theme.dp(7);
                iv.setPadding(pad, pad, pad, pad);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setColorFilter(inkColor());
                iv.setContentDescription(symbolName(id));
                Bitmap b = art.peek("m", d.optString("tex"));
                if (b != null) iv.setImageBitmap(b);
                else art.media(d.optString("tex"), bm -> { if (bm != null) iv.setImageBitmap(bm); });
                iv.setOnClickListener(v -> {
                    command("markAdd", Ui.args("sym", id, "x", x + 0.5, "y", y + 0.5, "ink", ink), "Drawn on your map");
                    closeSheet();
                });
                cells.add(iv);
                grid.addView(iv, Ui.lp(theme.dp(46), theme.dp(46)));
            }
        }
        openSheet(sheet);
    }

    private void noteSheet(int x, int y) {
        LinearLayout sheet = Ui.col(this);
        sheetTitle(sheet, "Write on your map", "A short note, in your character's handwriting.");
        JSONObject tools = markTools();
        View inks = inkRow(tools);
        if (inks == null) { noPen(sheet); return; }
        if (tools != null && !tools.optBoolean("notes", true)) {
            sheet.addView(Ui.text(this, theme, "Your character can't read or write.", 15, theme.text2, false));
            openSheet(sheet);
            return;
        }
        sheet.addView(inks, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, theme.dp(12)));
        EditText note = new EditText(this);
        note.setHint("Loot here, Danger, …");
        note.setHintTextColor(theme.text3);
        note.setTextColor(theme.text);
        note.setTextSize(theme.sp(16));
        note.setSingleLine(true);
        note.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        note.setBackground(theme.round(theme.surface2, 12));
        note.setPadding(theme.dp(12), theme.dp(12), theme.dp(12), theme.dp(12));
        note.setImeOptions(EditorInfo.IME_ACTION_DONE);
        sheet.addView(note, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, theme.dp(12)));
        Runnable add = () -> {
            String s = note.getText().toString().trim();
            if (s.isEmpty()) return;
            command("markAdd", Ui.args("text", s, "x", x + 0.5, "y", y + 0.5, "ink", ink), "Written on your map");
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(note.getWindowToken(), 0);
            closeSheet();
        };
        note.setOnEditorActionListener((v, a, e) -> { add.run(); return true; });
        sheetAction(sheet, "pen", "Write it", add);
        openSheet(sheet);
        note.requestFocus();
    }

    private void markSheet(JSONObject m) {
        LinearLayout sheet = Ui.col(this);
        String title = m.has("text") ? "“" + m.optString("text") + "”" : symbolName(m.optString("sym"));
        sheetTitle(sheet, title, distanceFrom(m.optDouble("x"), m.optDouble("y")));
        int x = (int) Math.floor(m.optDouble("x")), y = (int) Math.floor(m.optDouble("y"));
        sheetAction(sheet, "pin", "Set a waypoint here", () -> { command("waypoint", Ui.args("x", x, "y", y), "Waypoint set"); closeSheet(); });
        JSONObject tools = markTools();
        if (tools != null && tools.optBoolean("erase")) {
            sheetAction(sheet, "trash", "Rub it out", () -> {
                command("markRemove", Ui.args("i", m.optInt("i"), "x", m.optDouble("x"), "y", m.optDouble("y")), "Rubbed out");
                closeSheet();
            });
        } else {
            sheet.addView(Ui.text(this, theme, "You need an eraser to rub it out.", 14, theme.text3, false), Ui.margins(Ui.lp(-1, -2), theme.dp(4), theme.dp(4), 0, 0));
        }
        openSheet(sheet);
    }

    private static String symbolName(String id) {
        // "MedCross" -> "Med cross"
        String s = id.replaceAll("([a-z])([A-Z])", "$1 $2");
        return s.isEmpty() ? "Mark" : s.substring(0, 1) + s.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private void teamSheet(JSONObject m) {
        LinearLayout sheet = Ui.col(this);
        sheetTitle(sheet, m.optString("n"), distanceFrom(m.optDouble("x"), m.optDouble("y")));
        int x = (int) Math.floor(m.optDouble("x")), y = (int) Math.floor(m.optDouble("y"));
        sheetAction(sheet, "pin", "Set a waypoint to where they are", () -> {
            command("waypoint", Ui.args("x", x, "y", y, "label", m.optString("n")), "Waypoint set");
            closeSheet();
        });
        openSheet(sheet);
    }

    private void baseSheet() {
        JSONObject b = core == null ? null : core.optJSONObject("base");
        if (b == null) return;
        LinearLayout sheet = Ui.col(this);
        String name = b.optString("n", "");
        sheetTitle(sheet, name.isEmpty() ? (b.optBoolean("safehouse") ? "Safehouse" : "Base") : name, distanceFrom(b.optDouble("x"), b.optDouble("y")));
        sheetAction(sheet, "pin", "Go there", () -> {
            command("waypoint", Ui.args("x", b.optInt("x"), "y", b.optInt("y"), "label", "Base"), "Waypoint set");
            closeSheet();
        });
        sheetAction(sheet, "home", "Open the Base tab", () -> { closeSheet(); setTab("base"); });
        openSheet(sheet);
    }

    /** The game's own map symbols (id, picture, group), once per game. */
    private void loadSymbols() {
        final String base = link.baseUrl();
        if (base.equals(symbolsFor)) return;
        symbolsFor = base;
        new Thread(() -> {
            byte[] raw = ArtCache.fetchFrom(base + "/api/map/symbols");
            if (raw == null) {
                main.post(() -> symbolsFor = "");
                return;
            }
            try {
                org.json.JSONArray list = new org.json.JSONArray(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
                main.post(() -> {
                    symbolDefs.clear();
                    symbolTex.clear();
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject d = list.optJSONObject(i);
                        if (d == null) continue;
                        symbolDefs.add(d);
                        symbolTex.put(d.optString("id"), d.optString("tex"));
                    }
                    if (marksData != null) onMarks(marksData);
                });
            } catch (Exception e) {
                main.post(() -> symbolsFor = "");
            }
        }, "knoxlink-symbols").start();
    }

    private void onMarks(JSONObject d) {
        marksData = d;
        org.json.JSONArray list = Screens.Json.arr(d.opt("list"));
        map.setMarks(list);
        for (int i = 0; i < list.length(); i++) {
            JSONObject m = list.optJSONObject(i);
            if (m == null || !m.has("sym")) continue;
            String id = m.optString("sym");
            String tex = symbolTex.get(id);
            if (tex == null) continue;
            Bitmap b = art.peek("m", tex);
            if (b != null) map.setSymbolArt(id, b);
            else art.media(tex, bm -> map.setSymbolArt(id, bm));
        }
    }

    // ------------------------------------------------------------------ alerts

    private void buildAlert() {
        alertBar = Ui.row(this);
        alertBar.setBackground(theme.card());
        if (!theme.pz) alertBar.setElevation(theme.dpf(10));
        alertBar.setPadding(theme.dp(10), theme.dp(10), theme.dp(8), theme.dp(10));
        alertTile = new Widgets.Moodle(this);
        alertBar.addView(alertTile, Ui.lp(theme.dp(42), theme.dp(42)));
        LinearLayout txt = Ui.col(this);
        alertTitle = Ui.ellipsize(Ui.text(this, theme, "", 16, theme.text, true));
        alertText = Ui.text(this, theme, "", 13, theme.text2, false);
        alertText.setMaxLines(2);
        txt.addView(alertTitle);
        txt.addView(alertText, Ui.margins(Ui.lp(-1, -2), 0, theme.dp(3), 0, 0));
        alertBar.addView(txt, Ui.margins(Ui.weight(1), theme.dp(12), 0, theme.dp(8), 0));
        alertAction = Ui.text(this, theme, "", 14, theme.accentInk, true);
        alertAction.setGravity(Gravity.CENTER);
        alertAction.setPadding(theme.dp(14), 0, theme.dp(14), 0);
        alertAction.setBackground(Ui.pressable(theme, theme.accent, 12));
        alertBar.addView(alertAction, Ui.lp(-2, theme.dp(42)));
        ImageView close = new ImageView(this);
        close.setImageDrawable(theme.icon("x", theme.text3));
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setOnClickListener(v -> hideAlert());
        alertBar.addView(close, Ui.margins(Ui.lp(theme.dp(36), theme.dp(42)), theme.dp(4), 0, 0, 0));
        alertBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(theme.dp(10), theme.dp(10), theme.dp(10), 0);
        content.addView(alertBar, lp);
    }

    private final Runnable alertTimeout = this::hideAlert;

    private void hideAlert() {
        if (alertBar == null || alertBar.getVisibility() != View.VISIBLE) return;
        alertBar.animate().alpha(0f).translationY(-theme.dp(16)).setDuration(180).withEndAction(() -> alertBar.setVisibility(View.GONE)).start();
    }

    /**
     * Shows an alert across the top of every tab. moodleIcon: a media/ui/Moodles/128 icon (or null
     * for a plain icon); action: a quick action to offer ("eat", "tab:comms", ...) or null.
     */
    private void alert(String moodleIcon, String plainIcon, int color, String title, String text, String actionLabel, String action, boolean strong) {
        if (!prefs.getBoolean("alerts", true)) return;
        alertTitle.setText(title);
        alertText.setText(text == null ? "" : text);
        alertText.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
        Bitmap bg = art.peek("m", "ui/Moodles/128/_Moodles_BGsolid.png");
        if (moodleIcon != null) {
            alertTile.set(bg, null, color);
            art.media("ui/Moodles/128/" + moodleIcon + ".png", b -> alertTile.set(art.peek("m", "ui/Moodles/128/_Moodles_BGsolid.png"), b, color));
        } else {
            Bitmap icon = null;
            android.graphics.drawable.Drawable d = theme.icon(plainIcon, 0xFF1A1206);
            icon = Bitmap.createBitmap(theme.dp(42), theme.dp(42), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(icon);
            int pad = theme.dp(10);
            d.setBounds(pad, pad, icon.getWidth() - pad, icon.getHeight() - pad);
            d.draw(cv);
            alertTile.set(bg, icon, color);
        }
        if (actionLabel != null) {
            alertAction.setText(actionLabel);
            alertAction.setVisibility(View.VISIBLE);
            alertAction.setOnClickListener(v -> {
                hideAlert();
                if (action.startsWith("tab:")) setTab(action.substring(4));
                else command("quick", Ui.args("a", action), actionLabel);
            });
        } else {
            alertAction.setVisibility(View.GONE);
        }
        main.removeCallbacks(alertTimeout);
        if (alertBar.getVisibility() != View.VISIBLE) {
            alertBar.setAlpha(0f);
            alertBar.setTranslationY(-theme.dp(16));
            alertBar.setVisibility(View.VISIBLE);
        }
        alertBar.animate().alpha(1f).translationY(0).setDuration(200).start();
        main.postDelayed(alertTimeout, strong ? 12000 : 7000);
        buzz(strong);
    }

    private void buzz(boolean strong) {
        if (!prefs.getBoolean("vibrate", true)) return;
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        long[] pattern = strong ? new long[]{0, 140, 90, 200} : new long[]{0, 80};
        try {
            v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1));
        } catch (Exception ignored) {
        }
    }

    /** level at which a bad moodle is worth an alert (0 = never), and the quick action it offers */
    private static final Map<String, Object[]> ALERTS = new HashMap<>();

    static {
        Object[][] a = {
                {"BLEEDING", 1, "Bandage", "bandage"}, {"ZOMBIE", 1, null, null}, {"INJURED", 2, "Bandage", "bandage"},
                {"PAIN", 2, "Painkiller", "pills"}, {"SICK", 2, null, null}, {"HAS_A_COLD", 2, null, null},
                {"HUNGRY", 3, "Eat", "eat"}, {"THIRST", 3, "Drink", "drink"}, {"TIRED", 3, null, null},
                {"ENDURANCE", 3, "Rest", "sit"}, {"PANIC", 3, null, null}, {"HYPOTHERMIA", 2, null, null},
                {"HYPERTHERMIA", 2, null, null}, {"WINDCHILL", 3, null, null}, {"HEAVY_LOAD", 3, "Open bag", "tab:gear"},
                {"NOXIOUS_SMELL", 3, null, null}, {"WET", 4, null, null}, {"DRUNK", 4, null, null},
                {"UNHAPPY", 4, null, null}, {"STRESS", 4, null, null}, {"BORED", 4, null, null}, {"ANGRY", 4, null, null}};
        for (Object[] x : a) ALERTS.put((String) x[0], new Object[]{x[1], x[2], x[3]});
    }

    private void checkAlerts(JSONObject c) {
        org.json.JSONArray ms = Screens.Json.arr(c.opt("m"));
        Map<String, Integer> now = new HashMap<>();
        JSONObject worst = null;
        int worstRank = -1;
        for (int i = 0; i < ms.length(); i++) {
            JSONObject m = ms.optJSONObject(i);
            if (m == null) continue;
            String id = m.optString("id");
            int lvl = m.optInt("l");
            now.put(id, lvl);
            Object[] rule = ALERTS.get(id);
            if (!moodlesPrimed || rule == null || m.optInt("g") != 2) continue;
            int before = moodleLevels.containsKey(id) ? moodleLevels.get(id) : 0;
            if (lvl > before && lvl >= (Integer) rule[0]) {
                int rank = lvl * 10 + ("BLEEDING".equals(id) || "ZOMBIE".equals(id) ? 50 : 0);
                if (rank > worstRank) { worstRank = rank; worst = m; }
            }
        }
        moodleLevels.clear();
        moodleLevels.putAll(now);
        moodlesPrimed = true;
        if (worst != null) {
            String id = worst.optString("id");
            Object[] rule = ALERTS.get(id);
            String icon = Screens.Vitals.MOODLE_ICON.get(id);
            if ("INJURED".equals(id) && worst.optInt("l") >= 3) icon = "Status_InjuredMajor";
            boolean strong = "BLEEDING".equals(id) || "ZOMBIE".equals(id) || worst.optInt("l") >= 4;
            String label = (String) rule[1], action = (String) rule[2];
            JSONObject pl = c.optJSONObject("p");
            if ("TIRED".equals(id) && pl != null && pl.optBoolean("bed")) { label = "Sleep"; action = "sleep"; }
            // only offer an item action the character has something for (the game says what it has)
            JSONObject can = c.optJSONObject("can");
            if (action != null && can != null && can.has(action) && !can.optBoolean(action)) { label = null; action = null; }
            alert(icon, "vitals", Screens.Vitals.moodleColor(2, worst.optInt("l")), worst.optString("n"), worst.optString("d"),
                    label, action, strong);
            return;
        }

        // arriving at the waypoint
        JSONObject wp = c.optJSONObject("wp"), p = c.optJSONObject("p");
        String key = wp == null ? "" : wp.optInt("x") + "," + wp.optInt("y");
        if (!key.equals(wpKey)) { wpKey = key; arrivedShown = false; }
        if (wp != null && p != null) {
            double d = Math.hypot(wp.optDouble("x") - p.optDouble("x"), wp.optDouble("y") - p.optDouble("y"));
            if (d < 6 && !arrivedShown) {
                arrivedShown = true;
                alert(null, "pin", theme.accent, "You've arrived", wp.optString("label", ""), null, null, false);
                return;
            }
        }

        // low fuel while driving
        JSONObject veh = c.optJSONObject("veh");
        if (veh == null || veh.optDouble("fuel", 1) > 0.2) lowFuelShown = false;
        else if (veh.optBoolean("drv") && veh.optDouble("fuel", 1) < 0.1 && !lowFuelShown) {
            lowFuelShown = true;
            alert(null, "fuel", Screens.Vitals.moodleColor(2, 3), "Low on fuel", "About " + Fmt.pct(veh.optDouble("fuel")) + "% left in the tank.", null, null, false);
        }
    }

    /** New chat lines: a badge, and an alert when the chat isn't on screen. */
    private void onChat(JSONObject d) {
        chatData = d;
        comms.chat(d);
        int n = d.optInt("n");
        org.json.JSONArray list = Screens.Json.arr(d.opt("list"));
        if (lastChatN >= 0 && n > lastChatN && list.length() > 0) {
            JSONObject m = list.optJSONObject(0);
            boolean onScreen = "comms".equals(tab) && "chat".equals(comms.mode());
            if (m != null && !m.optBoolean("me") && !onScreen) {
                String who = m.optString("a", "");
                alert(null, "chat", Screens.Comms.channelColor(theme, m.optString("k")), who.isEmpty() ? "New message" : who,
                        m.optString("s"), "Reply", "tab:comms", false);
            }
        }
        lastChatN = n;
    }

    private void updateCommsBadge() {
        JSONObject cnt = core == null ? null : core.optJSONObject("cnt");
        if (cnt == null) return;
        int radio = cnt.optInt("radio"), chat = cnt.optInt("chat");
        if ("comms".equals(tab)) {
            prefs.edit().putInt("seenRadio", radio).putInt("seenChat", chat).apply();
            setBadge("comms", false);
            return;
        }
        int seenRadio = prefs.getInt("seenRadio", 0), seenChat = prefs.getInt("seenChat", 0);
        // a new session counts from zero again
        if (seenRadio > radio || seenChat > chat) {
            prefs.edit().putInt("seenRadio", Math.min(seenRadio, radio)).putInt("seenChat", Math.min(seenChat, chat)).apply();
        }
        setBadge("comms", radio > seenRadio || chat > seenChat);
    }

    private void buildSheet() {
        sheetLayer = new FrameLayout(this);
        sheetLayer.setVisibility(View.GONE);
        scrim = new View(this);
        scrim.setBackgroundColor(0x73000000);
        scrim.setOnClickListener(v -> closeSheet());
        sheetLayer.addView(scrim, Screens.match());
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sheetPanel = Ui.col(this);
        sheetPanel.setPadding(theme.dp(18), theme.dp(10), theme.dp(18), theme.dp(20));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(theme.surface);
        float r = theme.pz ? 0 : theme.dpf(22);   // the game's windows are square
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(theme.dp(1), theme.line);
        sv.setBackground(bg);
        sv.addView(sheetPanel);
        sheetPanel.setClickable(true);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(theme.dp(560), getResources().getDisplayMetrics().widthPixels), -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sheetLayer.addView(sv, lp);
        if (!theme.pz) sv.setElevation(theme.dpf(12));
        root.addView(sheetLayer, Screens.match());
    }

    private void buildOverlay() {
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Theme.alpha(theme.bg, 0.94f));
        overlay.setClickable(true);
        LinearLayout card = Ui.card(this, theme);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(theme.dp(24), theme.dp(22), theme.dp(24), theme.dp(22));
        LinearLayout brand = Ui.row(this);
        ImageView logo = new ImageView(this);
        logo.setImageResource(getResources().getIdentifier("ic_launcher_fg", "drawable", getPackageName()));
        logo.setBackground(theme.round(theme.surface2, 12));
        brand.addView(logo, Ui.lp(theme.dp(46), theme.dp(46)));
        LinearLayout bt = Ui.col(this);
        bt.addView(Ui.text(this, theme, "Knox Link", 19, theme.text, true));
        bt.addView(Ui.text(this, theme, "Second screen for Project Zomboid", 13, theme.text3, false), Ui.margins(Ui.lp(-2, -2), 0, theme.dp(3), 0, 0));
        brand.addView(bt, Ui.margins(Ui.lp(-2, -2), theme.dp(12), 0, 0, 0));
        card.addView(brand, Ui.margins(Ui.lp(-2, -2), 0, 0, 0, theme.dp(18)));

        Widgets.Spinner spin = new Widgets.Spinner(this, theme.accent, theme.dpf(3.5f));
        ovSpinner = spin;
        card.addView(spin, Ui.lp(theme.dp(40), theme.dp(40)));
        ovTitle = Ui.text(this, theme, "Connecting to the game…", 17, theme.text, true);
        ovTitle.setGravity(Gravity.CENTER);
        card.addView(ovTitle, Ui.margins(Ui.lp(-2, -2), 0, theme.dp(12), 0, theme.dp(4)));
        ovSub = Ui.text(this, theme, "", 14, theme.text2, false);
        ovSub.setGravity(Gravity.CENTER);
        card.addView(ovSub, Ui.lp(-2, -2));

        LinearLayout pin = Ui.col(this);
        pin.setGravity(Gravity.CENTER_HORIZONTAL);
        pinInput = new EditText(this);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        pinInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        pinInput.setGravity(Gravity.CENTER);
        pinInput.setTextSize(theme.sp(28));
        pinInput.setTextColor(theme.text);
        pinInput.setLetterSpacing(0.4f);
        pinInput.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        pinInput.setBackground(theme.round(theme.surface2, 14));
        pinInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pinInput.setOnEditorActionListener((v, a, e) -> { submitPin(); return true; });
        pin.addView(pinInput, Ui.margins(Ui.lp(theme.dp(200), theme.dp(64)), 0, theme.dp(14), 0, theme.dp(12)));
        TextView go = Ui.action(this, theme, null, "Connect");
        go.setGravity(Gravity.CENTER);
        go.setBackground(Ui.pressable(theme, theme.accent, 12));
        go.setTextColor(theme.accentInk);
        go.setOnClickListener(v -> submitPin());
        pin.addView(go, Ui.lp(theme.dp(200), theme.dp(50)));
        pin.setVisibility(View.GONE);
        pinBox = pin;
        card.addView(pin);

        // games found on the Wi-Fi (streaming from a PC), then one button per game/streaming app installed here
        gamesBox = Ui.col(this);
        card.addView(gamesBox, Ui.margins(Ui.lp(-1, -2), 0, theme.dp(16), 0, 0));
        LinearLayout launchers = Ui.col(this);
        for (String[] app : LAUNCHABLE) {
            if (getPackageManager().getLaunchIntentForPackage(app[0]) == null) continue;
            TextView b = Ui.action(this, theme, "play", app[1]);
            b.setTag(app[0]);
            b.setOnClickListener(v -> launchOnOtherScreen(app[0]));
            launchers.addView(b, Ui.margins(Ui.lp(-1, -2), 0, theme.dp(8), 0, 0));
        }
        startGameBtn = launchers;
        card.addView(launchers, Ui.margins(Ui.lp(-1, -2), 0, theme.dp(8), 0, 0));

        LinearLayout links = Ui.row(this);
        TextView settingsLink = Ui.text(this, theme, "Settings", 14, theme.accent, true);
        settingsLink.setPadding(theme.dp(14), theme.dp(12), theme.dp(14), theme.dp(4));
        settingsLink.setOnClickListener(v -> { hideOverlay(); setTab("settings"); });
        links.addView(settingsLink);
        TextView closeLink = Ui.text(this, theme, "Close", 14, theme.text2, true);
        closeLink.setPadding(theme.dp(14), theme.dp(12), theme.dp(14), theme.dp(4));
        closeLink.setOnClickListener(v -> closeApp());
        links.addView(closeLink);
        card.addView(links);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(theme.dp(440), getResources().getDisplayMetrics().widthPixels - theme.dp(32)), -2, Gravity.CENTER);
        overlay.addView(card, lp);
        root.addView(overlay, Screens.match());
        showOverlay("wait", "Connecting to the game…", "Keep Project Zomboid running with Knox Link enabled.");
    }

    private void submitPin() {
        String v = pinInput.getText().toString().trim();
        if (!v.matches("\\d{4}")) return;
        prefs.edit().putString("pin", v).apply();
        link.configure(prefs.getString("server", "127.0.0.1:7878"), v);
        link.hello();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(pinInput.getWindowToken(), 0);
        showOverlay("wait", "Connecting…", "");
    }

    private void showOverlay(String mode, String title, String sub) {
        overlay.setVisibility(View.VISIBLE);
        boolean pin = "pin".equals(mode);
        pinBox.setVisibility(pin ? View.VISIBLE : View.GONE);
        ovSpinner.setVisibility(pin ? View.GONE : View.VISIBLE);
        if (title != null) ovTitle.setText(title);
        if (sub != null) ovSub.setText(sub);
        boolean waiting = !pin && !authed;
        startGameBtn.setVisibility(waiting && otherDisplay() != null ? View.VISIBLE : View.GONE);
        // the game is already open (loading, or at its menu): its start button would only cover it
        View startGame = startGameBtn.findViewWithTag(LaunchActivity.GAME);
        if (startGame != null) startGame.setVisibility(GameWatch.gameOpen(this) ? View.GONE : View.VISIBLE);
        gamesBox.setVisibility(waiting ? View.VISIBLE : View.GONE);
        if (waiting) discovery.start(this::showGames);
        else discovery.stop();
        if (pin) pinInput.requestFocus();
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
        discovery.stop();
    }

    /** Lists the games the network search found; one tap connects. */
    private void showGames(List<Discovery.Game> games) {
        gamesBox.removeAllViews();
        String current = link.host() + ":" + link.port();
        for (Discovery.Game g : games) {
            if (g.address.equals(current) && socketUp) continue;
            TextView b = Ui.action(this, theme, "wifi", "Connect to " + g.name + "  ·  " + g.address);
            b.setOnClickListener(v -> {
                prefs.edit().putString("server", g.address).apply();
                onServerChanged();
            });
            gamesBox.addView(b, Ui.margins(Ui.lp(-1, -2), 0, theme.dp(8), 0, 0));
        }
    }

    // ------------------------------------------------------------------ tabs

    private void setTab(String key) {
        stopTyping();
        if (!screens.containsKey(key)) key = "map";
        if (!"car".equals(key)) autoCarFrom = null;
        tab = key;
        for (Map.Entry<String, View> e : screens.entrySet()) e.getValue().setVisibility(e.getKey().equals(key) ? View.VISIBLE : View.GONE);
        styleRail();
        subscribe();
        if ("ask".equals(key)) ask.shown();
        if ("map".equals(key) && fogWanted) main.post(fogPoll);
        prefs.edit().putString("tab", key).apply();
    }

    private void subscribe() {
        org.json.JSONArray c = new org.json.JSONArray();
        if ("vitals".equals(tab)) { c.put("body"); c.put("outfit"); }
        boolean avatar = "vitals".equals(tab) && vitals != null && vitals.wantsAvatar();
        if (avatar) c.put("avatar");   // the game draws the live character only while someone watches
        if ("gear".equals(tab)) {
            c.put("gear");
            c.put("outfit");
            c.put("loot");
            if (gear != null && "craft".equals(gear.segment())) c.put("craft");
        }
        if ("journal".equals(tab)) { c.put("journal"); c.put("diaries"); }
        if ("ask".equals(tab)) c.put("ask");
        if ("weapons".equals(tab)) c.put("weapons");
        if ("skills".equals(tab)) c.put("skills");
        if ("map".equals(tab)) {
            c.put("marks");
            c.put("team");
            c.put("memory");
            if (map != null && map.planWanted()) c.put("plan");   // zoomed all the way in
        }
        // answers the game sends once, when a screen asks: a search, a diary; and a character's recap when they die
        c.put("seen");
        c.put("diary");
        c.put("recap");
        if ("comms".equals(tab)) c.put("radio");
        if ("base".equals(tab)) c.put("base");
        // multiplayer chat is followed everywhere, for its alerts
        boolean mp = core != null && core.optJSONObject("game") != null && core.optJSONObject("game").optBoolean("mp");
        if (mp || "comms".equals(tab)) c.put("chat");
        c.put("menu");   // the game's own menus, sent once each when a screen asks for one
        c.put("radar");  // the danger radar shows on every tab
        subscribedMp = mp;
        link.send(Ui.args("t", "sub", "c", c));
        if (avatar) main.post(avatarPoll);
    }

    private boolean subscribedMp;

    @Override
    public void resubscribe() {
        if (link != null && vitals != null) subscribe();
    }

    /** Fetches the live character picture a few times a second while it's on screen. */
    private final Runnable avatarPoll = new Runnable() {
        private boolean busy;

        @Override
        public void run() {
            main.removeCallbacks(this);
            if (busy || !authed || !"vitals".equals(tab) || vitals == null || !vitals.wantsAvatar()) return;
            busy = true;
            final String url = link.baseUrl() + "/api/avatar.png";
            new Thread(() -> {
                int status = 0;
                Bitmap frame = null;
                try {
                    java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    con.setConnectTimeout(2000);
                    con.setReadTimeout(3000);
                    status = con.getResponseCode();
                    if (status == 200) {
                        try (java.io.InputStream in = con.getInputStream()) {
                            frame = android.graphics.BitmapFactory.decodeStream(in);
                        }
                    }
                    con.disconnect();
                } catch (Exception ignored) {
                }
                final int st = status;
                final Bitmap b = frame;
                main.post(() -> {
                    busy = false;
                    if (st == 410 || st == 404) {
                        vitals.avatarUnavailable();   // the game can't draw it (or an older server add-on)
                        return;
                    }
                    vitals.avatarFrame(b);
                    main.postDelayed(avatarPoll, b != null ? 140 : 700);
                });
            }, "knoxlink-avatar").start();
        }
    };

    // ------------------------------------------------------------------ messages

    @Override
    public void onSocket(boolean up) {
        socketUp = up;
        if (!up) {
            authed = false;
            conn.setBackground(theme.circle(theme.bad));
            main.postDelayed(() -> {
                if (!authed) showOverlay("wait", "Waiting for the game…", "Start Project Zomboid with Knox Link enabled. This screen connects by itself.");
            }, 1200);
        }
    }

    @Override
    public void onMessage(JSONObject m) {
        switch (m.optString("t")) {
            case "hello":
                if (m.optBoolean("ok")) {
                    authed = true;
                    httpKey = m.optString("key", "");
                    conn.setBackground(theme.circle(theme.good));
                    art.retryNow();   // pictures asked for before the game's server was up
                    subscribe();
                    if (core == null || System.currentTimeMillis() - lastCoreAt > 3000) {
                        showOverlay("wait", "Waiting for a game…", "Load a save or start a new game. This screen fills in by itself.");
                    } else {
                        hideOverlay();
                    }
                    if (!map.loaded() && !mapLoading) loadMap();
                    loadSymbols();
                } else if ("locked".equals(m.optString("why"))) {
                    showOverlay("pin", "Enter the PIN", "Too many wrong PINs. Wait a minute, then try again.");
                } else {
                    showOverlay("pin", "Enter the PIN", "wrong".equals(m.optString("why"))
                            ? "That PIN did not match. Check the Knox Link panel in the game (F8)."
                            : "It is shown in the game: press F8 to open the Knox Link panel.");
                }
                break;
            case "s":
                onState(m.optString("c"), m.optJSONObject("d"));
                break;
            case "ack": {
                String label = pending.remove(m.optInt("id"));
                if (label == null) break;
                if (m.optBoolean("ok")) {
                    if (!label.isEmpty()) toast(label + (m.has("msg") && !m.optString("msg").isEmpty() ? " · " + m.optString("msg") : ""), false);
                } else {
                    toast(m.optString("msg", "Could not do that"), true);
                }
                break;
            }
            case "e": {
                JSONObject d = m.optJSONObject("d");
                if (d != null && d.has("toast")) toast(d.optString("toast"), d.optBoolean("bad"));
                break;
            }
            default:
                break;
        }
    }

    private void onState(String channel, JSONObject d) {
        if (d == null) return;
        switch (channel) {
            case "core": {
                lastCoreAt = System.currentTimeMillis();
                if (authed) hideOverlay();
                // the game saying it's still there with nothing new: no redraw (this screen draws on the CPU)
                String sig = d.toString();
                if (sig.equals(coreSig)) break;
                coreSig = sig;
                core = d;
                onCore(d);
                break;
            }
            case "body":
                bodyData = d;
                vitals.body(d);
                break;
            case "gear":
                gearData = d;
                gear.gear(d);
                break;
            case "skills":
                skillsData = d;
                skills.skills(d);
                break;
            case "marks":
                onMarks(d);
                break;
            case "outfit":
                outfitData = d;
                vitals.outfit(d);
                gear.outfit(d);
                break;
            case "loot":
                lootData = d;
                gear.loot(d);
                break;
            case "radar":
                radarData = d;
                if (radar != null) radar.update(d);
                if (map != null) map.setZombies(d);
                break;
            case "plan":
                planData = d;
                map.setPlan(d);
                break;
            case "memory":
                memoryData = d;
                map.setMemory(d);
                break;
            case "seen":
                SeenSheet.onSeen(d);
                break;
            case "diaries":
                diariesData = d;
                journal.diaries(d);
                break;
            case "diary":
                Diaries.onDiary(this, this, d);
                break;
            case "recap":
                Recap.maybeShow(this, this, d);
                break;
            case "ask":
                askData = d;
                ask.situation(d);
                break;
            case "weapons":
                weaponsData = d;
                weapons.weapons(d);
                break;
            case "menu":
                // the game's own menu for something a screen asked about
                if (menuWaiter != null && d.optInt("req") == menuReq) {
                    MenuCallback cb = menuWaiter;
                    menuWaiter = null;
                    cb.onMenu(d);
                }
                break;
            case "craft":
                craftData = d;
                gear.craft(d);
                break;
            case "journal":
                journalData = d;
                journal.journal(d);
                break;
            case "team":
                teamData = d;
                map.setTeam(Screens.Json.arr(d.opt("list")));
                break;
            case "radio":
                radioData = d;
                comms.radio(d);
                break;
            case "chat":
                onChat(d);
                break;
            case "base":
                baseData = d;
                baseScreen.base(d);
                break;
            default:
                break;
        }
    }

    private void onCore(JSONObject c) {
        JSONObject time = c.optJSONObject("time");
        JSONObject wx = c.optJSONObject("wx");
        JSONObject pl = c.optJSONObject("p");
        // Immersion: no watch -> the watch stays dark; analog -> time only; digital -> time, date, temperature
        if (time != null) {
            watch.set(time.optInt("h"), time.optInt("mi"), time.optInt("mo", 0), time.optInt("d", 0),
                    wx == null || !wx.has("t") ? Double.NaN : wx.optDouble("t"), Fmt.clock24, Fmt.imperial);
        } else {
            watch.set(-1, 0, 0, 0, Double.NaN, Fmt.clock24, Fmt.imperial);
        }
        if (time != null && time.has("ds")) clockTime.setText("Day " + ((int) Math.floor(time.optDouble("ds")) + 1));
        else if (time == null) clockTime.setText("No watch");
        else clockTime.setText(pl != null ? pl.optString("name", "") : "");

        JSONObject rules = c.optJSONObject("rules");
        JSONObject mr = rules == null ? null : rules.optJSONObject("map");
        boolean mapAllowed = mr == null || mr.optBoolean("allowed", true);
        boolean mapDark = mr != null && mr.optBoolean("dark");
        boolean wantFog = mapAllowed && mr != null && !mr.optBoolean("allKnown", true);
        map.setDark(mapDark);
        if (!mapAllowed || mapDark) {
            mapNoticeText.setText(!mapAllowed ? "The world map is turned off in this world's settings." : "Too dark to read the map.");
            mapNotice.setVisibility(View.VISIBLE);
        } else {
            mapNotice.setVisibility(View.GONE);
        }
        map.setVisibility(mapAllowed ? View.VISIBLE : View.INVISIBLE);
        if (wantFog && !fogWanted) main.post(fogPoll);
        if (!wantFog && fogWanted) map.setFog(null);
        fogWanted = wantFog;
        if (wx != null) {
            int h = time == null ? 12 : time.optInt("h");
            boolean night = h < 6 || h >= 20;
            String icon, word;
            if (wx.optBoolean("storm")) { icon = "storm"; word = "Thunderstorm"; }
            else if (wx.optDouble("snow") > 0.1) { icon = "snow"; word = "Snow"; }
            else if (wx.optDouble("rain") > 0.1) { icon = "rain"; word = wx.optDouble("rain") > 0.6 ? "Heavy rain" : "Rain"; }
            else if (wx.optDouble("fog") > 0.35) { icon = "fog"; word = "Fog"; }
            else if (wx.optDouble("cloud") > 0.6) { icon = "cloud"; word = "Cloudy"; }
            else { icon = night ? "moon" : "sun"; word = night ? "Clear night" : wx.optDouble("cloud") > 0.3 ? "Partly cloudy" : "Clear"; }
            // the game's own weather pictures where it has one (UI2.pack), the plain icon otherwise
            String[] layers = "storm".equals(icon) || "rain".equals(icon) ? new String[]{"Weather_Cloud_Heavy", "Weather_Rain"}
                    : "cloud".equals(icon) ? new String[]{"Weather_Cloud_Heavy"}
                    : "sun".equals(icon) ? (wx.optDouble("cloud") > 0.3 ? new String[]{"Weather_Sun_Peek", "Weather_Cloud_Light"} : new String[]{"Weather_Sun"})
                    : null;
            showWeather(layers, icon);
            boolean imm = rules != null && rules.optBoolean("imm");
            String wind = "";
            if (wx.has("wind") && imm) wind = " · windy";
            else if (wx.optDouble("wind") >= 1) wind = " · wind " + (Fmt.imperial ? Math.round(wx.optDouble("wind") * 0.621) + " mph" : Math.round(wx.optDouble("wind")) + " km/h");
            clockDate.setText(word + wind);
        }
        JSONObject g = c.optJSONObject("game");
        if (g != null) {
            speedRow.setAlpha(g.optBoolean("mp") ? 0.35f : 1f);
            markSpeed(Math.min(3, g.optInt("speed", 1)));
        }
        JSONObject p = c.optJSONObject("p");
        if (p != null) map.setPlayer(p.optDouble("x"), p.optDouble("y"), p.optDouble("dir"));
        JSONObject wp = c.optJSONObject("wp");
        map.setWaypoint(wp);
        if (wp != null && p != null) {
            double dx = wp.optDouble("x") - p.optDouble("x"), dy = wp.optDouble("y") - p.optDouble("y");
            double dist = Math.hypot(dx, dy);
            wpText.setText(dist < 6 ? "You are there" : wp.optString("label", "Waypoint") + " · " + Fmt.dist(dist) + " " + Fmt.compass(dx, dy));
            wpChip.setVisibility(View.VISIBLE);
        } else {
            wpChip.setVisibility(View.GONE);
        }
        vitals.core(c);
        gear.core(c);
        car.core(c);
        baseScreen.core(c);
        settings.perf(c);
        map.setBase(c.optJSONObject("base"));
        boolean mp = g != null && g.optBoolean("mp");
        comms.setMp(mp);
        setRailLabel("comms", mp ? "Comms" : "Radio");
        if (mp != subscribedMp) subscribe();
        updateCommsBadge();
        String fs = String.valueOf(c.opt("fc")) + c.optInt("di");
        if (!fs.equals(forecastSig)) {
            forecastSig = fs;
            comms.setForecast(Forecast.card(this, this, c));
        }
        checkAlerts(c);
        boolean bleeding = false;
        org.json.JSONArray ms = Screens.Json.arr(c.opt("m"));
        for (int i = 0; i < ms.length(); i++) {
            JSONObject mo = ms.optJSONObject(i);
            if (mo != null && "BLEEDING".equals(mo.optString("id"))) bleeding = true;
        }
        View vb = tabButtons.get("vitals");
        if (vb != null) ((TextView) ((Object[]) vb.getTag())[1]).setTextColor(bleeding ? theme.bad : ("vitals".equals(tab) ? theme.accent : theme.text3));

        JSONObject veh = c.optJSONObject("veh");
        View carBtn = tabButtons.get("car");
        carBtn.setVisibility(veh != null ? View.VISIBLE : View.GONE);
        // the dashboard opens once when you start driving; leave it and it stays closed until the next drive
        boolean driving = veh != null && veh.optBoolean("drv");
        if (driving && !carOpenedThisDrive && prefs.getBoolean("autoCar", true) && !"car".equals(tab)) {
            String from = tab;
            setTab("car");
            autoCarFrom = from;
        }
        if (driving) carOpenedThisDrive = true;
        if (veh == null) {
            carOpenedThisDrive = false;
            if ("car".equals(tab)) {
                String back = autoCarFrom != null ? autoCarFrom : "map";
                autoCarFrom = null;
                setTab(back);
            }
        }
    }

    /** Refreshes the explored-area mask while the map is on screen. */
    private final Runnable fogPoll = new Runnable() {
        @Override
        public void run() {
            main.removeCallbacks(this);
            if (fogWanted && authed && "map".equals(tab)) {
                final String base = link.baseUrl();
                new Thread(() -> {
                    byte[] f = ArtCache.fetchFrom(base + "/api/map/fog");
                    main.post(() -> { if (fogWanted && f != null) map.setFog(f); });
                }, "knoxlink-fog").start();
            }
            if (fogWanted) main.postDelayed(this, 8000);
        }
    };

    // the game on this device was connected, and when its connection dropped
    private boolean hadGame;
    private long lostAt, waitShownAt;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (authed && lastCoreAt > 0 && now - lastCoreAt > 4000 && overlay.getVisibility() != View.VISIBLE) {
                showOverlay("wait", "Waiting for a game…", "Knox Link is connected. Load a save or start a new game.");
            }
            // the game on this device is open but its server isn't up yet: it starts with a save
            if (!socketUp && overlay.getVisibility() == View.VISIBLE && pinBox.getVisibility() != View.VISIBLE
                    && LaunchActivity.isNative(prefs) && now - waitShownAt > 2000) {
                waitShownAt = now;
                if (GameWatch.gameOpen(MainActivity.this)) {
                    showOverlay("wait", "Waiting for a save…", "Continue or load a save in Project Zomboid. This screen connects by itself.");
                }
            }
            // the game on this device closed (its server is gone and its screen with it): close as well
            if (authed && LaunchActivity.isNative(prefs)) hadGame = true;
            if (socketUp) lostAt = 0;
            else if (hadGame && lostAt == 0) lostAt = now;
            if (lostAt > 0 && now - lostAt > 5000 && prefs.getBoolean("closeWithGame", true) && !GameWatch.gameOpen(MainActivity.this)) {
                closeApp();
                return;
            }
            main.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ map data

    private void loadMap() {
        if (map == null || mapLoading) return;
        mapLoading = true;
        final String base = link.baseUrl();
        mapStatus.setVisibility(map.loaded() ? View.GONE : View.VISIBLE);
        new Thread(() -> {
            java.io.File cache = new java.io.File(getCacheDir(), "map.bin");
            byte[] data = null;
            for (int attempt = 0; attempt < 600 && data == null; attempt++) {
                data = ArtCache.fetchFrom(base + "/api/map");
                if (data == null) {
                    if (attempt == 0 && cache.isFile() && !map.loaded()) {
                        // show the copy from last time while the game starts
                        try {
                            byte[] old = java.nio.file.Files.readAllBytes(cache.toPath());
                            map.load(old);
                            main.post(() -> mapStatus.setVisibility(View.GONE));
                        } catch (Exception ignored) {
                        }
                    }
                    main.post(() -> { if (!map.loaded()) mapStatus.setText("Waiting for the game's map…"); });
                    try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                    if (!base.equals(link.baseUrl())) break;
                }
            }
            if (data != null) {
                try {
                    map.load(data);
                    java.nio.file.Files.write(cache.toPath(), data);
                    mapFor = base;
                    main.post(() -> mapStatus.setVisibility(View.GONE));
                } catch (Exception e) {
                    main.post(() -> mapStatus.setText("The map could not be read."));
                }
            }
            mapLoading = false;
        }, "knoxlink-mapload").start();
    }

    // ------------------------------------------------------------------ dual screen

    private Display otherDisplay() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return null;
        int mine = getDisplay() == null ? Display.DEFAULT_DISPLAY : getDisplay().getDisplayId();
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != mine && d.getState() != Display.STATE_OFF) return d;
        }
        return null;
    }

    /** The game itself (Zomdroid) and the common game-streaming apps, in the order they are offered. */
    private static final String[][] LAUNCHABLE = {
            {"com.zomdroid", "Start Project Zomboid on the other screen"},
            {"com.limelight.noir", "Open Artemis on the other screen"},
            {"com.limelight", "Open Moonlight on the other screen"},
            {"com.limelight.root", "Open Moonlight on the other screen"},
            {"com.valvesoftware.steamlink", "Open Steam Link on the other screen"},
            {"com.parsec.app", "Open Parsec on the other screen"},
    };

    /** Opens an app (the game, or a streaming app) on the other screen of a dual-screen handheld. */
    private void launchOnOtherScreen(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        Display d = otherDisplay();
        if (i == null || d == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        ActivityOptions o = ActivityOptions.makeBasic();
        o.setLaunchDisplayId(d.getDisplayId());
        try {
            startActivity(i, o.toBundle());
        } catch (Exception e) {
            toast("Could not open it on the other screen", true);
        }
    }

}
