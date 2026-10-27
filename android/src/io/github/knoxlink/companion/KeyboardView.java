package io.github.knoxlink.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Knox Link's own keyboard, drawn by the app like everything else on this screen. Next to the game
 * on a dual-screen handheld the system keyboard would take the controls away from the game (and
 * on some renderers it is covered in streaks); this one types into the field without either.
 * Letters, numbers and symbols, shift (twice for caps lock), a held backspace repeats.
 */
final class KeyboardView extends View {
    interface Listener {
        /** A character to type, "\b" to delete one, or "\n" for the enter/done key. */
        void onKey(String key);
    }

    private static final String SHIFT = "⇧", BACK = "⌫", ENTER = "⏎", SPACE = " ", NUM = "?123", ABC = "ABC", SYM = "#+=";
    private static final String[][] LETTERS = {
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
            {SHIFT, "z", "x", "c", "v", "b", "n", "m", BACK},
            {NUM, ",", SPACE, ".", ENTER}};
    private static final String[][] NUMBERS = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"-", "/", ":", ";", "(", ")", "$", "&", "@", "\""},
            {SYM, ".", ",", "?", "!", "'", "#", "%", BACK},
            {ABC, ",", SPACE, ".", ENTER}};
    private static final String[][] SYMBOLS = {
            {"[", "]", "{", "}", "#", "%", "^", "*", "+", "="},
            {"_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•"},
            {NUM, ".", ",", "?", "!", "'", "*", "+", BACK},
            {ABC, ",", SPACE, ".", ENTER}};

    private final Theme t;
    private final Listener listener;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Key> keys = new ArrayList<>();
    private String[][] layer = LETTERS;
    private boolean shift, capsLock, numbersOnly, sentences;
    private long lastShiftAt;
    private String enterLabel = "Done";
    private Key down;

    private static final class Key {
        final String label;
        final RectF r = new RectF();

        Key(String label) {
            this.label = label;
        }
    }

    KeyboardView(Context c, Theme t, Listener listener) {
        super(c);
        this.t = t;
        this.listener = listener;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(t.pz ? Fonts.ui(false) : Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        setClickable(true);
    }

    /** Numbers first (a PIN), capitals for sentences (not a key or an address), and what the enter key says ("Done", "Send"). */
    void configure(boolean numbers, boolean sentences, String enter) {
        numbersOnly = numbers;
        this.sentences = sentences && !numbers;
        enterLabel = enter;
        layer = numbers ? NUMBERS : LETTERS;
        shift = this.sentences;   // a sentence starts with a capital
        capsLock = false;
        relayout();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, t.dp(4 * 50 + 3 * 7 + 20));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        relayout();
    }

    private void relayout() {
        keys.clear();
        int w = getWidth();
        if (w == 0) return;
        float pad = t.dpf(6), gap = t.dpf(6), rowH = t.dpf(50), rowGap = t.dpf(7);
        float unit = (w - 2 * pad - 9 * gap) / 10f;
        for (int row = 0; row < layer.length; row++) {
            String[] r = layer[row];
            float[] widths = new float[r.length];
            float total = 0;
            for (int i = 0; i < r.length; i++) {
                String k = r[i];
                float kw = unit;
                if (k.equals(SPACE)) kw = unit * 4 + gap * 3;
                else if (k.equals(SHIFT) || k.equals(BACK) || k.equals(SYM) || (row == 2 && k.equals(NUM))) kw = unit * 1.5f + gap * 0.5f;
                else if (k.equals(NUM) || k.equals(ABC) || k.equals(ENTER)) kw = unit * 2 + gap;
                widths[i] = kw;
                total += kw + (i > 0 ? gap : 0);
            }
            float x = (w - total) / 2f, y = pad + row * (rowH + rowGap);
            for (int i = 0; i < r.length; i++) {
                Key key = new Key(r[i]);
                key.r.set(x, y, x + widths[i], y + rowH);
                keys.add(key);
                x += widths[i] + gap;
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(t.surface2);
        // the game's look: square keys like its buttons, black with a grey border, Enter in the selection colours
        float radius = t.pz ? 0 : t.dpf(9);
        for (Key k : keys) {
            boolean special = k.label.length() > 1 || k.label.equals(SHIFT) || k.label.equals(BACK) || k.label.equals(ENTER);
            boolean accent = k.label.equals(ENTER) || (k.label.equals(SHIFT) && (shift || capsLock));
            if (t.pz) {
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(k == down ? 0xFF4D4D4D : accent ? t.selection : special ? 0xFF1C1C1C : 0xFF000000);
                canvas.drawRect(k.r, fill);
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(t.dpf(1));
                fill.setColor(accent ? t.accent : 0xFF5E5E5E);
                canvas.drawRect(k.r, fill);
                fill.setStyle(Paint.Style.FILL);
            } else {
                fill.setColor(k == down ? t.surface3 : accent ? t.accent : special ? t.surface3 : t.surface);
                canvas.drawRoundRect(k.r, radius, radius, fill);
            }
            String label = k.label.equals(ENTER) ? enterLabel : k.label.equals(SPACE) ? "space" : k.label;
            if (layer == LETTERS && k.label.length() == 1 && Character.isLetter(k.label.charAt(0)) && (shift || capsLock)) {
                label = label.toUpperCase(java.util.Locale.ROOT);
            }
            text.setColor(accent ? t.accentInk : special ? t.text2 : t.text);
            text.setTextSize(t.sp(label.length() > 1 ? 14 : 20) * getResources().getDisplayMetrics().scaledDensity);
            Paint.FontMetrics fm = text.getFontMetrics();
            canvas.drawText(label, k.r.centerX(), k.r.centerY() - (fm.ascent + fm.descent) / 2f, text);
        }
    }

    private Key keyAt(float x, float y) {
        for (Key k : keys) {
            if (k.r.contains(x, y)) return k;
        }
        // between keys: the nearest one in the row
        Key best = null;
        float bestD = Float.MAX_VALUE;
        for (Key k : keys) {
            if (y < k.r.top - t.dpf(4) || y > k.r.bottom + t.dpf(4)) continue;
            float d = Math.abs(k.r.centerX() - x);
            if (d < bestD) { bestD = d; best = k; }
        }
        return best;
    }

    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (down == null || !down.label.equals(BACK)) return;
            listener.onKey("\b");
            main.postDelayed(this, 60);
        }
    };

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down = keyAt(e.getX(), e.getY());
                if (down != null) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (down.label.equals(BACK)) {
                        listener.onKey("\b");
                        main.postDelayed(repeat, 420);
                    }
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                return true;
            case MotionEvent.ACTION_UP:
                main.removeCallbacks(repeat);
                Key k = down;
                down = null;
                if (k != null && !k.label.equals(BACK)) press(k.label);
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                main.removeCallbacks(repeat);
                down = null;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void press(String label) {
        switch (label) {
            case SHIFT:
                long now = System.currentTimeMillis();
                if (capsLock) { capsLock = false; shift = false; }
                else if (shift && now - lastShiftAt < 400) capsLock = true;
                else shift = !shift;
                lastShiftAt = now;
                invalidate();
                return;
            case NUM:
                layer = NUMBERS;
                relayout();
                return;
            case ABC:
                layer = LETTERS;
                relayout();
                return;
            case SYM:
                layer = SYMBOLS;
                relayout();
                return;
            case ENTER:
                listener.onKey("\n");
                return;
            default:
                String s = label;
                if (layer == LETTERS && (shift || capsLock)) s = s.toUpperCase(java.util.Locale.ROOT);
                listener.onKey(s);
                if (shift && !capsLock && layer == LETTERS) { shift = false; invalidate(); }
                // after a sentence ends, the next letter is a capital
                if ((s.equals(".") || s.equals("!") || s.equals("?")) && sentences) shift = true;
                if (layer != LETTERS && s.equals(" ") && !numbersOnly) { layer = LETTERS; relayout(); }
        }
    }
}
