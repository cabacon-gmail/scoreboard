package com.carolinarollergirls.scoreboard.json;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.jr.ob.JSON;

import com.carolinarollergirls.scoreboard.utils.BasePath;
import com.carolinarollergirls.scoreboard.utils.Logger;

/**
 * Restore points for games: a copy of each game's state at every jam, timeout and period boundary, kept for the
 * whole game, plus the helpers the Recovery screen uses to list and restore them.
 * <p>
 * Checkpoints live in {@code html/game-data/checkpoints/<game file name>/}. Each is in the same format as the game's
 * own JSON file (so it can also be loaded with Import JSON), and each folder has an {@code index.jsonl} with one
 * summary line per checkpoint, so the list doesn't have to read every file.
 * </p>
 */
public final class Checkpoints {
    private Checkpoints() {}

    public static final String DIR = "html/game-data/checkpoints";
    public static final String INDEX = "index.jsonl";
    /** Checkpoint folders whose newest file is older than this are deleted at start-up. */
    public static final int RETENTION_DAYS = 90;

    public static File dir() { return new File(BasePath.get(), DIR); }

    /**
     * Writes one checkpoint of a game and appends its summary to the folder's index. {@code state} is the game's
     * filtered state ({@code ScoreBoard.Game(<id>)} and {@code ScoreBoard.Version}), {@code json} its serialised
     * form. Failures are logged and returned as a message; null on success.
     */
    public static synchronized String write(String filename, String gameId, Map<String, Object> state, String json,
                                            String reason) {
        try {
            File folder = new File(dir(), filename);
            folder.mkdirs();
            Map<String, Object> summary = summarize(state, gameId);
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss").format(new Date());
            String base = stamp + "_P" + summary.get("period") + "J" + summary.get("jam") + "_" + reason;
            File file = new File(folder, base + ".json");
            for (int n = 2; file.exists(); n++) { file = new File(folder, base + "-" + n + ".json"); }
            File tmp = new File(folder, file.getName() + ".tmp");
            try (Writer out = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                out.write(json);
            }
            if (!tmp.renameTo(file)) { throw new IOException("could not rename " + tmp + " to " + file); }
            summary.put("file", file.getName());
            summary.put("reason", reason);
            summary.put("savedAt", System.currentTimeMillis());
            try (Writer idx = new OutputStreamWriter(new FileOutputStream(new File(folder, INDEX), true),
                                                     StandardCharsets.UTF_8)) {
                idx.write(JSON.std.asString(summary) + "\n");
            }
            return null;
        } catch (Exception e) {
            Logger.printStackTrace("writing checkpoint for " + filename, e);
            return "Could not save a restore point: " + e.getMessage();
        }
    }

    /** What the Recovery screen shows about a game in a state map: names, date, period, jam, score, clock. */
    public static Map<String, Object> summarize(Map<String, Object> state, String gameId) {
        String p = "ScoreBoard.Game(" + gameId + ").";
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("game", gameId);
        s.put("name", state.get(p + "Name"));
        s.put("filename", state.get(p + "Filename"));
        s.put("state", state.get(p + "State"));
        s.put("date", state.get(p + "EventInfo(Date)"));
        s.put("startTime", state.get(p + "EventInfo(StartTime)"));
        for (int t = 1; t <= 2; t++) {
            Object name = state.get(p + "Team(" + t + ").DisplayName");
            if (name == null || "".equals(name)) { name = state.get(p + "Team(" + t + ").FullName"); }
            if (name == null || "".equals(name)) { name = state.get(p + "Team(" + t + ").Name"); }
            s.put("team" + t, name);
            s.put("score" + t, number(state.get(p + "Team(" + t + ").Score")));
        }
        s.put("period", number(state.get(p + "CurrentPeriodNumber")));
        s.put("jam", number(state.get(p + "Clock(Jam).Number")));
        s.put("inJam", Boolean.valueOf(String.valueOf(state.get(p + "InJam"))));
        s.put("inPeriod", Boolean.valueOf(String.valueOf(state.get(p + "InPeriod"))));
        s.put("periodClock", number(state.get(p + "Clock(Period).Time")));
        s.put("officialScore", Boolean.valueOf(String.valueOf(state.get(p + "OfficialScore"))));
        Object err = state.get(p + "CheckpointError");
        s.put("checkpointError", err == null ? "" : err);
        return s;
    }

    private static long number(Object o) {
        if (o instanceof Number) { return ((Number) o).longValue(); }
        try {
            return o == null ? 0 : Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) { return 0; }
    }

    /** The IDs of the games in a state map. */
    public static List<String> games(Map<String, Object> state) {
        List<String> ids = new ArrayList<>();
        for (String k : state.keySet()) {
            if (k.startsWith("ScoreBoard.Game(") && k.endsWith(").Id") && k.indexOf(')') == k.length() - 4) {
                ids.add(String.valueOf(state.get(k)));
            }
        }
        return ids;
    }

    /** Deletes checkpoint folders whose newest file is older than {@link #RETENTION_DAYS}. */
    public static void cleanup() {
        File[] folders = dir().listFiles(File::isDirectory);
        if (folders == null) { return; }
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS);
        for (File f : folders) {
            long newest = 0;
            File[] files = f.listFiles();
            if (files != null) {
                for (File c : files) { newest = Math.max(newest, c.lastModified()); }
            }
            if (newest < cutoff) {
                if (files != null) {
                    for (File c : files) { c.delete(); }
                }
                if (f.delete()) { Logger.printMessage("Deleted restore points older than 90 days: " + f.getName()); }
            }
        }
    }

    private static final Pattern UUID =
        Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * Takes one game out of a state map and gives it, and every element inside it, new IDs, so it can be loaded next
     * to the original without the two sharing anything. References to things outside the game (rulesets, prepared
     * teams) are left alone: only IDs that belong to elements of this game are replaced.
     */
    public static Map<String, Object> copyGame(Map<String, Object> state, String gameId, String newGameId) {
        String prefix = "ScoreBoard.Game(" + gameId + ")";
        Map<String, Object> game = new TreeMap<>();
        for (Map.Entry<String, Object> e : state.entrySet()) {
            if (e.getKey().equals(prefix) || e.getKey().startsWith(prefix + ".")) {
                game.put(e.getKey(), e.getValue());
            }
        }
        // Every UUID that is part of an element ID in this game belongs to the game.
        Map<String, String> ids = new HashMap<>();
        ids.put(gameId, newGameId);
        for (Map.Entry<String, Object> e : game.entrySet()) {
            if (e.getKey().endsWith(".Id") && e.getValue() instanceof String) {
                Matcher m = UUID.matcher((String) e.getValue());
                while (m.find()) {
                    if (!ids.containsKey(m.group())) { ids.put(m.group(), java.util.UUID.randomUUID().toString()); }
                }
            }
        }
        Map<String, Object> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : game.entrySet()) {
            Object v = e.getValue();
            out.put(replaceIds(e.getKey(), ids), v instanceof String ? replaceIds((String) v, ids) : v);
        }
        return out;
    }

    private static String replaceIds(String s, Map<String, String> ids) {
        Matcher m = UUID.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String r = ids.get(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(r != null ? r : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
