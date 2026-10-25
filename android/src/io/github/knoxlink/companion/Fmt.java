package io.github.knoxlink.companion;

import java.util.Calendar;
import java.util.Locale;

/** Number, unit and date formatting, following the user's unit and clock settings. */
final class Fmt {
    private Fmt() {}

    static boolean imperial;
    static boolean clock24;

    private static final String[] DAYS = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    static String temp(double c) {
        return imperial ? Math.round(c * 9 / 5 + 32) + "°F" : Math.round(c) + "°C";
    }

    static String bodyTemp(double c) {
        return imperial ? String.format(Locale.US, "%.1f°F", c * 9 / 5 + 32) : String.format(Locale.US, "%.1f°C", c);
    }

    static long speed(double kmh) {
        return Math.round(imperial ? kmh * 0.621371 : kmh);
    }

    static String speedUnit() {
        return imperial ? "MPH" : "KM/H";
    }

    static String dist(double squares) {
        if (imperial) {
            double yd = squares * 1.0936;
            return yd < 1000 ? (Math.round(yd / 10) * 10) + " yd" : String.format(Locale.US, "%.1f mi", yd / 1760);
        }
        return squares < 1000 ? (Math.round(squares / 10) * 10) + " m" : String.format(Locale.US, "%.1f km", squares / 1000);
    }

    /** A distance of a few steps, to the step: "Here", "2 yd", "3 m". */
    static String near(double squares) {
        if (squares < 1.2) return "Here";
        return imperial ? Math.round(squares * 1.0936) + " yd" : Math.round(squares) + " m";
    }

    static String clock(int h, int m) {
        String mm = (m < 10 ? "0" : "") + m;
        if (clock24) return (h < 10 ? "0" : "") + h + ":" + mm;
        return (((h + 11) % 12) + 1) + ":" + mm + (h < 12 ? " AM" : " PM");
    }

    static String date(int y, int mo, int d, double daysSurvived, boolean withWeekday) {
        String day = MONTHS[Math.max(0, Math.min(11, mo - 1))] + " " + d + " · Day " + ((int) Math.floor(daysSurvived) + 1);
        if (!withWeekday) return day;
        Calendar c = Calendar.getInstance();
        c.set(y, mo - 1, d);
        return DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + ", " + day;
    }

    /** 0 = east, 90 = south: world y grows downwards, like the game's map. */
    static String compass(double dx, double dy) {
        double a = (Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360;
        String[] dirs = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
        return dirs[(int) Math.round(a / 45) % 8];
    }

    static int pct(double v) {
        return (int) Math.round(v * 100);
    }

    static String num(double v) {
        if (v == Math.rint(v)) return Long.toString((long) v);
        return String.format(Locale.US, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
