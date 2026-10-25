package io.github.knoxlink.companion;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The weather forecast the character last heard on the Automated Emergency Broadcast: the same
 * numbers the radio read out for today and tomorrow, nothing they didn't hear.
 */
final class Forecast {
    private Forecast() {}

    /** [label, day] for the forecast days that are still ahead, from core.fc and core.di. */
    static List<Object[]> days(JSONObject core) {
        List<Object[]> out = new ArrayList<>();
        JSONObject fc = core == null ? null : core.optJSONObject("fc");
        if (fc == null) return out;
        int age = core.optInt("di") - fc.optInt("di");
        JSONArray ds = Screens.Json.arr(fc.opt("days"));
        for (int k = 0; k < ds.length(); k++) {
            int rel = k - age;
            if (rel < 0 || rel > 1) continue;
            out.add(new Object[]{rel == 0 ? "Today" : "Tomorrow", ds.optJSONObject(k)});
        }
        return out;
    }

    static String sky(JSONObject d) {
        boolean wx = d.has("wx");
        if (d.optBoolean("storm")) return "storm";
        if (d.optBoolean("blizzard") || (wx && d.optBoolean("snow"))) return "snow";
        if (wx || d.optBoolean("heavy")) return "rain";
        if (d.optBoolean("fog")) return "fog";
        if (d.optDouble("cloud") > 0.4) return "cloud";
        return "sun";
    }

    private static String segment(int hour) {
        if (hour <= 4 || hour >= 23) return "night";
        if (hour < 8) return "early morning";
        if (hour < 12) return "morning";
        if (hour < 18) return "afternoon";
        return "evening";
    }

    /** "Rain from the afternoon", "Overcast", "Clear"… worded like the broadcast. */
    static String headline(JSONObject d) {
        String what;
        if (d.optBoolean("storm")) what = "Storms";
        else if (d.optBoolean("blizzard")) what = "Blizzard";
        else if (d.optBoolean("heavy")) what = "Heavy rain";
        else if (d.has("wx")) what = d.optBoolean("snow") ? "Snow" : "Rain";
        else if (d.optBoolean("fog")) return "Fog";
        else if (d.optDouble("cloud") > 0.7) return "Overcast";
        else if (d.optDouble("cloud") > 0.4) return "Partly cloudy";
        else return "Clear skies";
        if ("from".equals(d.optString("wx"))) return what + " from the " + segment(d.optInt("at"));
        if ("until".equals(d.optString("wx"))) return d.optInt("at") >= 22 ? what + " all day" : what + " until the " + segment(d.optInt("at"));
        return what;
    }

    static String temps(JSONObject d) {
        if (!d.has("tmin")) return "";
        return Fmt.temp(d.optDouble("tmin")) + " to " + Fmt.temp(d.optDouble("tmax"));
    }

    static String wind(JSONObject d) {
        double w = d.optDouble("wind", 0);
        if (w > 0.75) return "Gale-force winds";
        if (w > 0.5) return "Strong winds";
        if (w > 0.25) return "Windy";
        return "Light winds";
    }

    /** "Heard on the Emergency Broadcast at 7:00 AM" (the time only if they could read it). */
    static String heard(JSONObject core) {
        JSONObject fc = core.optJSONObject("fc");
        if (fc == null) return "";
        int age = core.optInt("di") - fc.optInt("di");
        JSONObject at = fc.optJSONObject("t");
        String when = age <= 0 ? "today" : age == 1 ? "yesterday" : age + " days ago";
        if (at != null && at.optBoolean("w")) {
            when += " at " + Fmt.clock(at.optInt("h"), at.optInt("mi"));
        }
        return "Heard on the Emergency Broadcast " + when;
    }

    private static View dayRow(Context c, Theme t, String label, JSONObject d) {
        LinearLayout r = Ui.row(c);
        r.setBackground(t.round(t.surface2, 14));
        r.setPadding(t.dp(12), t.dp(10), t.dp(14), t.dp(10));
        r.addView(Ui.icon(c, t, sky(d), t.accent, 30));
        LinearLayout txt = Ui.col(c);
        txt.addView(Ui.text(c, t, label, 12, t.text3, true));
        txt.addView(Ui.ellipsize(Ui.text(c, t, headline(d), 16, t.text, true)), Ui.margins(Ui.lp(-1, -2), 0, t.dp(2), 0, t.dp(2)));
        txt.addView(Ui.ellipsize(Ui.text(c, t, wind(d), 12.5f, t.text2, false)));
        r.addView(txt, Ui.margins(Ui.weight(1), t.dp(12), 0, t.dp(8), 0));
        TextView temp = Ui.text(c, t, temps(d), 14, t.text, true);
        temp.setGravity(Gravity.END);
        r.addView(temp);
        return r;
    }

    /** Tapping the weather in the top bar. */
    static void sheet(Context c, Host host, JSONObject core) {
        Theme t = host.theme();
        LinearLayout sheet = Ui.col(c);
        sheet.addView(Ui.text(c, t, "Forecast", 20, t.text, true));
        List<Object[]> days = days(core);
        if (days.isEmpty()) {
            boolean old = core != null && core.optJSONObject("fc") != null;
            sheet.addView(Ui.text(c, t, old ? "The last forecast you heard is out of date." : "You haven't heard a forecast yet.", 14, t.text3, false),
                    Ui.margins(Ui.lp(-1, -2), 0, t.dp(4), 0, t.dp(14)));
            LinearLayout tip = Ui.row(c);
            tip.setBackground(t.round(t.surface2, 14));
            tip.setPadding(t.dp(12), t.dp(12), t.dp(14), t.dp(12));
            tip.addView(Ui.icon(c, t, "radio", t.accent, 26));
            TextView s = Ui.text(c, t, "Tune a radio to the Automated Emergency Broadcast. It reads out the weather for today and tomorrow every hour.", 14, t.text2, false);
            tip.addView(s, Ui.margins(Ui.weight(1), t.dp(12), 0, 0, 0));
            sheet.addView(tip);
        } else {
            sheet.addView(Ui.text(c, t, heard(core), 13.5f, t.text3, false), Ui.margins(Ui.lp(-1, -2), 0, t.dp(4), 0, t.dp(14)));
            for (Object[] d : days) sheet.addView(dayRow(c, t, (String) d[0], (JSONObject) d[1]), Ui.margins(Ui.lp(-1, -2), 0, 0, 0, t.dp(8)));
        }
        host.openSheet(sheet);
    }

    /** A small card for the top of the radio log, when there's a forecast to show. */
    static View card(Context c, Host host, JSONObject core) {
        Theme t = host.theme();
        List<Object[]> days = days(core);
        if (days.isEmpty()) return null;
        LinearLayout card = Ui.card(c, t);
        TextView[] h = Ui.cardHead(c, t, card, "Forecast");
        h[1].setText("Emergency Broadcast");
        for (int i = 0; i < days.size(); i++) {
            Object[] d = days.get(i);
            card.addView(dayRow(c, t, (String) d[0], (JSONObject) d[1]), Ui.margins(Ui.lp(-1, -2), 0, i == 0 ? 0 : t.dp(8), 0, 0));
        }
        return card;
    }
}
