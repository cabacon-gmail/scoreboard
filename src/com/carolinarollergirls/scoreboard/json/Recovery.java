package com.carolinarollergirls.scoreboard.json;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.jr.ob.JSON;

import com.carolinarollergirls.scoreboard.core.interfaces.Game;
import com.carolinarollergirls.scoreboard.core.interfaces.ScoreBoard;
import com.carolinarollergirls.scoreboard.event.ScoreBoardEventProvider.Source;
import com.carolinarollergirls.scoreboard.utils.BasePath;

/**
 * Lists the restore points of every game and restores a game to one of them (the Recovery screen).
 * <p>
 * Restore points come from three places: the per-game checkpoints ({@link Checkpoints}), the last minute of
 * autosaves, and the autosaves backed up at each start-up. A restore loads the chosen game, as it was then, as a new
 * game with the original's name and statsbook file, abandons the game it replaces, and makes the new game current.
 * </p>
 */
public final class Recovery {
    private Recovery() {}

    static final String AUTOSAVE_DIR = "config/autosave";
    /** How many start-up backups to offer (newest first). */
    static final int BACKUPS = 10;

    /** One row per game, grouped by statsbook file name, each with its restore points, newest first. */
    public static List<Map<String, Object>> list(ScoreBoard sb) {
        Map<String, Map<String, Object>> games = new LinkedHashMap<>();
        // Live games first, so every game shows even before it has a restore point.
        Map<String, Object> live = sb.getJsm().getState();
        String current =
            sb.getCurrentGame().get(com.carolinarollergirls.scoreboard.core.interfaces.CurrentGame.GAME) == null
                ? ""
                : sb.getCurrentGame().get(com.carolinarollergirls.scoreboard.core.interfaces.CurrentGame.GAME).getId();
        for (String id : Checkpoints.games(live)) {
            Map<String, Object> s = Checkpoints.summarize(live, id);
            Map<String, Object> g = group(games, s);
            g.put("live", s);
            if (id.equals(current)) { g.put("current", true); }
        }
        // Checkpoints, from each folder's index.
        File[] folders = Checkpoints.dir().listFiles(File::isDirectory);
        if (folders != null) {
            for (File folder : folders) {
                File index = new File(folder, Checkpoints.INDEX);
                if (!index.isFile()) { continue; }
                try (BufferedReader r = new BufferedReader(
                         new InputStreamReader(new FileInputStream(index), StandardCharsets.UTF_8))) {
                    for (String line = r.readLine(); line != null; line = r.readLine()) {
                        if (line.trim().isEmpty()) { continue; }
                        Map<String, Object> s = JSON.std.mapFrom(line);
                        if (!new File(folder, String.valueOf(s.get("file"))).isFile()) { continue; }
                        s.put("source", "checkpoint");
                        s.put("path", Checkpoints.DIR + "/" + folder.getName() + "/" + s.get("file"));
                        points(group(games, s)).add(s);
                    }
                } catch (IOException e) { /* skip an unreadable index */
                }
            }
        }
        // The last minute of autosaves, and the newest start-up backups.
        File autosave = new File(BasePath.get(), AUTOSAVE_DIR);
        File[] saves = autosave.listFiles((d, n) -> n.startsWith("scoreboard-") && n.endsWith("-secs-ago.json"));
        if (saves != null) {
            for (File f : saves) { addFromAutosave(games, f, "autosave", AUTOSAVE_DIR + "/" + f.getName()); }
        }
        File[] backups = new File(autosave, "backup").listFiles(File::isDirectory);
        if (backups != null) {
            Arrays.sort(backups, Comparator.comparing(File::getName).reversed());
            for (int i = 0; i < Math.min(BACKUPS, backups.length); i++) {
                File f = new File(backups[i], "scoreboard-0-secs-ago.json");
                if (f.isFile()) {
                    addFromAutosave(games, f, "backup",
                                    AUTOSAVE_DIR + "/backup/" + backups[i].getName() + "/" + f.getName());
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : games.values()) {
            points(g).sort(
                Comparator.comparing((Map<String, Object> p) -> ((Number) p.get("savedAt")).longValue()).reversed());
            out.add(g);
        }
        out.sort(Comparator.comparing((Map<String, Object> g) -> latest(g)).reversed());
        return out;
    }

    private static long latest(Map<String, Object> g) {
        long t = g.containsKey("current") ? Long.MAX_VALUE : 0;
        for (Map<String, Object> p : points(g)) { t = Math.max(t, ((Number) p.get("savedAt")).longValue()); }
        return t;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> points(Map<String, Object> g) {
        return (List<Map<String, Object>>) g.get("points");
    }

    private static Map<String, Object> group(Map<String, Map<String, Object>> games, Map<String, Object> s) {
        String key = String.valueOf(s.get("filename"));
        Map<String, Object> g = games.get(key);
        if (g == null) {
            g = new LinkedHashMap<>();
            g.put("filename", key);
            g.put("points", new ArrayList<Map<String, Object>>());
            games.put(key, g);
        }
        // The newest description of the game wins: name, date, teams.
        for (String k : new String[] {"name", "date", "startTime", "team1", "team2"}) {
            if (s.get(k) != null && !"".equals(s.get(k))) { g.put(k, s.get(k)); }
        }
        return g;
    }

    @SuppressWarnings("unchecked")
    private static void addFromAutosave(Map<String, Map<String, Object>> games, File f, String source, String path) {
        try {
            Map<String, Object> state = (Map<String, Object>) JSON.std.mapFrom(f).get("state");
            if (state == null) { return; }
            for (String id : Checkpoints.games(state)) {
                Map<String, Object> s = Checkpoints.summarize(state, id);
                if ("Prepared".equals(String.valueOf(s.get("state")))) {
                    continue;
                } // not started: nothing to
                  // restore
                s.put("source", source);
                s.put("path", path);
                s.put("reason", source);
                s.put("savedAt", f.lastModified());
                points(group(games, s)).add(s);
            }
        } catch (Exception e) { /* skip a file that can't be read */
        }
    }

    /**
     * Restores game {@code gameId} from the restore point at {@code path} (relative to the scoreboard's folder, as
     * {@link #list} gives it). Returns the new game's ID.
     */
    @SuppressWarnings("unchecked")
    public static String restore(final ScoreBoard sb, String path, final String gameId) throws IOException {
        File base = BasePath.get().getCanonicalFile();
        File file = new File(base, path).getCanonicalFile();
        boolean allowed = file.toPath().startsWith(new File(base, Checkpoints.DIR).getCanonicalFile().toPath()) ||
                          file.toPath().startsWith(new File(base, AUTOSAVE_DIR).getCanonicalFile().toPath());
        if (!allowed || !file.isFile() || !file.getName().endsWith(".json")) {
            throw new IOException("Not a restore point: " + path);
        }
        Map<String, Object> state = (Map<String, Object>) JSON.std.mapFrom(file).get("state");
        if (state == null || !Checkpoints.games(state).contains(gameId)) {
            throw new IOException("That restore point has no game " + gameId);
        }
        ScoreBoardJSONSetter.updateToCurrentVersion(state);
        final String newId = java.util.UUID.randomUUID().toString();
        final Map<String, Object> copy = Checkpoints.copyGame(state, gameId, newId);
        final String filename = String.valueOf(state.get("ScoreBoard.Game(" + gameId + ").Filename"));
        final String label = new SimpleDateFormat("HH:mm").format(new Date());
        sb.runInBatch(new Runnable() {
            @Override
            public void run() {
                // Set aside the game being replaced (by ID, or any live game still using the statsbook file name).
                for (Game g : new ArrayList<>(sb.getAll(ScoreBoard.GAME))) {
                    if (g.getId().equals(gameId) || filename.equals(g.getFilename())) { g.abandon(label); }
                }
                ScoreBoardJSONSetter.set(sb, new TreeMap<>(copy), Source.JSON);
                sb.cleanupAliases();
                Game restored = sb.getGame(newId);
                restored.set(Game.FILENAME, filename, Source.OTHER,
                             com.carolinarollergirls.scoreboard.event.ScoreBoardEventProvider.Flag.SPECIAL_CASE);
                sb.getCurrentGame().load(restored);
                restored.saveRestorePoint("restored");
            }
        });
        return newId;
    }
}
