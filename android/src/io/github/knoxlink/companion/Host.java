package io.github.knoxlink.companion;

import android.content.SharedPreferences;
import android.view.View;

import org.json.JSONObject;

/** What the screens need from the activity. */
interface Host {
    Theme theme();

    ArtCache art();

    SharedPreferences prefs();

    /** Sends a command to the game; label is shown when it succeeds (null = quiet). */
    void command(String cmd, JSONObject args, String label);

    void openSheet(View content);

    void closeSheet();

    void toast(String text, boolean bad);

    /** A screen's needs changed (e.g. the live character picture was turned on): resend the subscriptions. */
    void resubscribe();

    interface MenuCallback {
        void onMenu(JSONObject menu);
    }

    /** Asks the game for its own right-click menu of an item or object (args: kind, id/ids or x,y,z,i). */
    void requestMenu(JSONObject args, MenuCallback cb);

    /** The game server's address for one of the character's own things ("/api/photo?id=..."), with this session's key. */
    String apiUrl(String pathAndQuery);

    /** Shows the map, centred on a place, zoomed in at least this far (device pixels per square, over density). */
    void showOnMap(float x, float y, float zoom);
}
