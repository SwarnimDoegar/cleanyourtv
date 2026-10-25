package com.gathin.tvhome;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * cricket_strip (config.xml, local): one line under the clock for an India or IPL match, from ESPN's public cricket
 * scoreboard. A live match first, then one starting within 12 hours, then one that ended in the last 6 hours;
 * nothing otherwise. MainActivity fetches it only while Home is on screen.
 */
final class CricketStrip {
    static final String FEED =
            "https://site.web.api.espn.com/apis/v2/scoreboard/header?sport=cricket&lang=en&region=in";
    /** Refresh while a match is live, and while none is. */
    static final long LIVE_MS = 15_000L, IDLE_MS = 10 * 60_000L, RETRY_MS = 2 * 60_000L;

    private static final long UPCOMING_MS = 12 * 3_600_000L, RESULT_MS = 6 * 3_600_000L;
    private static final int LIVE_RED = Color.parseColor("#FF453A");
    private static final int FORMAT_GREY = Color.parseColor("#99FFFFFF");

    private CricketStrip() {
    }

    /** What to show: the line, and whether a match is live (refresh sooner). */
    static final class Line {
        final CharSequence text;
        final boolean live;

        Line(CharSequence text, boolean live) {
            this.text = text;
            this.live = live;
        }
    }

    /** The scoreboard JSON. Runs off the main thread. */
    static String fetch() throws IOException {
        return Http.get(FEED);
    }

    /** The line for the first match that qualifies, or null when none does. */
    static Line pick(String json, List<String> teams, List<String> leagues, long nowMs, ZoneId zone,
            boolean hours24) throws JSONException {
        JSONObject live = null, upcoming = null, result = null;
        long nextStart = Long.MAX_VALUE;
        JSONArray sports = new JSONObject(json).optJSONArray("sports");
        for (int s = 0; sports != null && s < sports.length(); s++) {
            JSONArray groups = sports.getJSONObject(s).optJSONArray("leagues");
            for (int g = 0; groups != null && g < groups.length(); g++) {
                JSONObject league = groups.getJSONObject(g);
                JSONArray events = league.optJSONArray("events");
                for (int e = 0; events != null && e < events.length(); e++) {
                    JSONObject event = events.getJSONObject(e);
                    if (!follows(event, league.optString("name"), teams, leagues)) continue;
                    String status = event.optString("status");
                    long start = time(event.optString("date"));
                    if ("in".equals(status)) {
                        if (live == null) live = event;
                    } else if ("pre".equals(status)) {
                        if (start > nowMs && start - nowMs <= UPCOMING_MS && start < nextStart) {
                            upcoming = event;
                            nextStart = start;
                        }
                    } else if ("post".equals(status) && result == null) {
                        long end = time(event.optString("endDate"));
                        if (end > 0 && nowMs - end >= 0 && nowMs - end <= RESULT_MS) result = event;
                    }
                }
            }
        }
        if (live != null) return new Line(liveLine(live), true);
        if (upcoming != null) return new Line(upcomingLine(upcoming, nowMs, zone, hours24), false);
        if (result != null) return new Line(label("RESULT", Color.WHITE, format(result), scores(result), summary(result)), false);
        return null;
    }

    /** A team whose name holds one of the team words ("India" matches India A and India Women), or a league. */
    private static boolean follows(JSONObject event, String league, List<String> teams, List<String> leagues) {
        for (String name : leagues) {
            if (!name.isEmpty() && league.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) return true;
        }
        JSONArray competitors = event.optJSONArray("competitors");
        for (int c = 0; competitors != null && c < competitors.length(); c++) {
            String team = competitors.optJSONObject(c).optString("displayName");
            for (String word : teams) {
                if (!word.isEmpty() && Pattern.compile("\\b" + Pattern.quote(word) + "\\b",
                        Pattern.CASE_INSENSITIVE).matcher(team).find()) return true;
            }
        }
        return false;
    }

    /** "● LIVE  NZ-A 171/5 (52.6 ov) v IND-A 113 · NZ A lead by 58 runs"; Stumps, Lunch and Tea in place of LIVE. */
    private static CharSequence liveLine(JSONObject event) {
        String state = event.optString("summary");
        boolean playing = state.isEmpty() || "Live".equalsIgnoreCase(state);
        return playing ? label("● LIVE", LIVE_RED, format(event), scores(event), summary(event))
                : label(state.toUpperCase(Locale.ROOT), Color.WHITE, format(event), scores(event), summary(event));
    }

    /** "TODAY 14:00  IND-W v ZIM-W · 2nd ODI (D/N)", in the TV's own time zone and 12/24-hour style. */
    private static CharSequence upcomingLine(JSONObject event, long nowMs, ZoneId zone, boolean hours24) {
        ZonedDateTime start = Instant.ofEpochMilli(time(event.optString("date"))).atZone(zone);
        long days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(), start.toLocalDate());
        String when = (days == 0 ? "TODAY " : "TOMORROW ") + start.format(DateTimeFormatter.ofPattern(hours24 ? "HH:mm" : "h:mm a", Locale.ENGLISH));
        String kind = event.optString("description");
        int comma = kind.indexOf(',');
        if (comma > 0) kind = kind.substring(0, comma);
        return label(when, Color.WHITE, format(event), event.optString("shortName"), kind);
    }

    /** "NZ-A 171/5 (52.6 ov) v IND-A 113": each side's short name and its score so far. */
    private static String scores(JSONObject event) {
        StringBuilder line = new StringBuilder();
        JSONArray competitors = event.optJSONArray("competitors");
        for (int c = 0; competitors != null && c < competitors.length(); c++) {
            JSONObject team = competitors.optJSONObject(c);
            if (line.length() > 0) line.append("  v  ");
            line.append(team.optString("abbreviation", team.optString("displayName")));
            String score = team.optString("score").trim();
            if (!score.isEmpty()) line.append(' ').append(score);
        }
        return line.toString();
    }

    /** "NZ A lead by 58 runs" or "India won by 5 wickets", when the feed has one. */
    private static String summary(JSONObject event) {
        JSONObject full = event.optJSONObject("fullStatus");
        return full == null ? "" : full.optString("summary");
    }

    /** The match type from the feed's class: TEST, FIRST-CLASS, ODI, ONE-DAY (List A) or T20; "" when unknown. */
    static String format(JSONObject event) {
        JSONObject kind = event.optJSONObject("class");
        if (kind == null) return "";
        String card = kind.optString("generalClassCard");
        String type = kind.optString("eventType");
        if ("Test".equalsIgnoreCase(card)) return "TEST";
        if (card.toLowerCase(Locale.ROOT).contains("first-class")) return "FIRST-CLASS";
        if (card.toLowerCase(Locale.ROOT).contains("list a")) return "ONE-DAY";
        if (card.contains("ODI") || "ODI".equalsIgnoreCase(type)) return "ODI";
        if (card.contains("T20") || card.contains("Twenty20") || type.startsWith("T20")) return "T20";
        if ("Test".equalsIgnoreCase(type)) return "TEST";
        return type.toUpperCase(Locale.ROOT);
    }

    private static CharSequence label(String tag, int tagColor, String format, String main, String detail) {
        SpannableStringBuilder line = new SpannableStringBuilder(tag);
        line.setSpan(new ForegroundColorSpan(tagColor), 0, tag.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!format.isEmpty()) {
            int at = line.length() + 3;
            line.append("   ").append(format);
            line.setSpan(new ForegroundColorSpan(FORMAT_GREY), at, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        line.append("   ").append(main);
        if (detail != null && !detail.trim().isEmpty()) line.append("   ·   ").append(detail.trim());
        return line;
    }

    private static long time(String iso) {
        try {
            return iso.isEmpty() ? 0 : Instant.parse(iso).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
