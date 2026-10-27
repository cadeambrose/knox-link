package io.github.knoxlink.companion;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;

/**
 * The game's own fonts, shipped with the app (assets/fonts, SIL Open Font License; the licence texts are next to
 * them): Noto Sans SemiBold and Bold, which the game writes its UI in (zomboidSmall/Medium/Large), and Caveat,
 * its handwriting (handwritten.fnt, the notes on its maps). The system's fonts stand in if one can't be read.
 */
final class Fonts {
    private Fonts() {}

    /** Caveat is small for its size next to the system's handwriting; text written in it grows by this much. */
    static final float HAND_SCALE = 1.22f;

    private static Typeface semi, bold, hand, handBold;
    private static boolean loaded;

    static synchronized void init(Context c) {
        if (loaded) return;
        loaded = true;
        AssetManager a = c.getApplicationContext().getAssets();
        semi = fromAsset(a, "fonts/NotoSans-SemiBold.ttf");
        bold = fromAsset(a, "fonts/NotoSans-Bold.ttf");
        hand = caveat(a, 600);
        handBold = caveat(a, 700);
    }

    private static Typeface fromAsset(AssetManager a, String path) {
        try {
            return Typeface.createFromAsset(a, path);
        } catch (Exception e) {
            return null;
        }
    }

    private static Typeface caveat(AssetManager a, int weight) {
        try {
            return new Typeface.Builder(a, "fonts/Caveat.ttf").setFontVariationSettings("'wght' " + weight).build();
        } catch (Exception e) {
            return null;
        }
    }

    /** The game's UI font: SemiBold, or Bold for the biggest emphasis. */
    static Typeface ui(boolean strong) {
        Typeface f = strong ? bold : semi;
        return f != null ? f : Typeface.create("sans-serif-medium", strong ? Typeface.BOLD : Typeface.NORMAL);
    }

    /** The game's handwriting. */
    static Typeface hand(boolean strong) {
        Typeface f = strong ? handBold : hand;
        return f != null ? f : Typeface.create("casual", strong ? Typeface.BOLD : Typeface.NORMAL);
    }

    /** Whether the handwriting is the game's Caveat (then text sizes grow by {@link #HAND_SCALE}). */
    static boolean caveat() {
        return hand != null;
    }

    /** Writes a text view in the game's handwriting, a little larger so it reads like the system's hand did. */
    static void handwrite(android.widget.TextView v, boolean strong) {
        v.setTypeface(hand(strong));
        if (caveat()) v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, v.getTextSize() * HAND_SCALE);
    }
}
