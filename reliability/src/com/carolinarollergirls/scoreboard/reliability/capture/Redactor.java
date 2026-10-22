package com.carolinarollergirls.scoreboard.reliability.capture;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.jr.ob.JSON;

/**
 * Replaces names and free text in a capture with placeholders, consistently: the same name always becomes the same
 * placeholder ({@code Name-7}), so a redacted trace still replays to the same game. The mapping exists only in
 * memory; it is never written.
 * <p>
 * What counts as a name is an allow-list of state properties (below), plus prepared-team IDs, which are often team
 * names ({@code PreparedTeam(Black)}). Anything else passes through. It is best-effort: check a capture before
 * sharing it outside the crew.
 * </p>
 */
final class Redactor {
    /** Properties whose string values are names or free text. */
    static final Set<String> PROPERTIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "Name", "FullName", "LeagueName", "TeamName", "League", "Pronouns", "CurrentOfficialName", "Initials",
        "Comment", "Annotation", "LtAnnotation", "SkAnnotation", "ExtraInfo", "FileName", "Filename",
        "AbortReason", "OsOffsetReason")));
    /** Map-like properties whose every value is free text, e.g. {@code EventInfo(Venue)}, {@code AlternateName(scoreboard)}. */
    static final Set<String> TEXT_MAPS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("EventInfo", "AlternateName")));

    private static final Pattern PREPARED_TEAM = Pattern.compile("PreparedTeam\\(([^)]*)\\)");
    private static final Pattern PREPARED_TEAM_ID = Pattern.compile("^ScoreBoard\\.PreparedTeam\\([^)]*\\)\\.Id$");

    /** Returns the redacted form of one {@code Set} message, {@code Register} message, or any other frame. */
    @SuppressWarnings("unchecked")
    synchronized String message(String text) {
        Object parsed;
        try {
            parsed = JSON.std.anyFrom(text);
        } catch (IOException e) { return "{\"redacted\":\"unparseable message\"}"; }
        if (!(parsed instanceof Map)) { return "{\"redacted\":\"unparseable message\"}"; }
        Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) parsed);
        Object action = m.get("action");
        if ("Set".equals(action) && m.get("key") instanceof String) {
            String key = (String) m.get("key");
            m.put("key", key(key));
            m.put("value", value(key, m.get("value")));
        } else if ("Register".equals(action) && m.get("paths") instanceof List) {
            List<Object> paths = new ArrayList<>();
            for (Object p : (List<Object>) m.get("paths")) { paths.add(p instanceof String ? key((String) p) : p); }
            m.put("paths", paths);
        } else if ("StartNewGame".equals(action) && m.get("data") instanceof Map) {
            Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) m.get("data"));
            for (String t : new String[] { "Team1", "Team2" }) {
                if (data.get(t) instanceof String) { data.put(t, teamId((String) data.get(t))); }
            }
            m.put("data", data);
        }
        try {
            return JSON.std.with(JSON.Feature.WRITE_NULL_PROPERTIES).asString(m);
        } catch (IOException e) { return "{\"redacted\":\"unwritable message\"}"; }
    }

    /** Redacts a whole state map (a seed, or an uploaded JSON game). */
    synchronized Map<String, Object> state(Map<String, Object> state) {
        Map<String, Object> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : state.entrySet()) { out.put(key(e.getKey()), value(e.getKey(), e.getValue())); }
        return out;
    }

    /** Replaces prepared-team IDs inside a key or path. */
    String key(String key) {
        Matcher m = PREPARED_TEAM.matcher(key);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String id = m.group(1);
            String r = id.equals("*") || id.isEmpty() ? id : teamId(id);
            m.appendReplacement(sb, Matcher.quoteReplacement("PreparedTeam(" + r + ")"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The redacted value for {@code key}, or the value itself if the key isn't a name or free text. */
    Object value(String key, Object value) {
        if (!(value instanceof String) || ((String) value).isEmpty()) { return value; }
        String prop = property(key);
        if (prop.equals("PreparedTeam") || PREPARED_TEAM_ID.matcher(key).matches()) {
            return teamId((String) value);
        }
        if (PROPERTIES.contains(prop) || mapProperty(key)) { return name((String) value); }
        return value;
    }

    /** The last segment of a key without its argument: {@code ...Skater(x).Name} gives {@code Name}. */
    static String property(String key) {
        String last = key.substring(key.lastIndexOf('.') + 1);
        int p = last.indexOf('(');
        return p >= 0 ? last.substring(0, p) : last;
    }

    private static boolean mapProperty(String key) {
        String last = key.substring(key.lastIndexOf('.') + 1);
        int p = last.indexOf('(');
        return p > 0 && TEXT_MAPS.contains(last.substring(0, p));
    }

    private String name(String s) { return names.computeIfAbsent(s, k -> "Name-" + (names.size() + 1)); }

    private String teamId(String s) { return teams.computeIfAbsent(s, k -> "Team-" + (teams.size() + 1)); }

    private final Map<String, String> names = new HashMap<>();
    private final Map<String, String> teams = new HashMap<>();
}
