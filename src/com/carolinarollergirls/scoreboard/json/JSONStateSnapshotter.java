package com.carolinarollergirls.scoreboard.json;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.jr.ob.JSON;

import com.carolinarollergirls.scoreboard.core.interfaces.Game;
import com.carolinarollergirls.scoreboard.utils.BasePath;
import com.carolinarollergirls.scoreboard.utils.Logger;

import io.prometheus.client.Histogram;

public final class JSONStateSnapshotter implements JSONStateListener {

    public JSONStateSnapshotter(JSONStateManager jsm, Game g, boolean useMetrics) {
        this.directory = BasePath.get();
        filename = g.getFilename();
        this.useMetrics = useMetrics;
        if (useMetrics && updateStateDuration == null) {
            updateStateDuration = Histogram.build()
                                      .name("crg_json_state_disk_snapshot_duration_seconds")
                                      .help("Time spent writing JSON state snapshots to disk")
                                      .register();
        }
        game = g;
        gameId = g.getId();
        filters.add("ScoreBoard.Version");
        filters.add("ScoreBoard.Game(" + g.getId() + ")");
        jsm.register(this);
    }

    @Override
    public void sendUpdates(StateTrie newState, StateTrie changedState) {
        state = newState;
        String reason = writeOnNextUpdate.getAndSet(null);
        if (reason != null) { writeFile(reason); }
    }

    /**
     * Writes the game's file, and keeps a restore point, once the current batch of changes has been applied.
     * {@code reason} names the moment ({@code jam-end}, {@code timeout-end}, {@code official-score}); an empty
     * reason writes the game's file only.
     */
    public void writeOnNextUpdate(String reason) {
        // An empty reason writes the file without a restore point, and never replaces a pending reason.
        writeOnNextUpdate.updateAndGet(cur -> reason.isEmpty() && cur != null ? cur : reason);
    }

    public synchronized void setFilename(String newName) { filename = newName; }

    public synchronized void writeFile(String reason) {
        Histogram.Timer timer = useMetrics ? updateStateDuration.startTimer() : null;

        File file = new File(new File(directory, "html/game-data/json"), filename + ".json");
        File prev = new File(new File(directory, "html/game-data/json"), filename + "_prev.json");
        file.getParentFile().mkdirs();

        File tmp = null;
        OutputStreamWriter out = null;
        try {
            // Put inside a "state" entry to match the WS.
            Map<String, Object> gameState = state.filter(filters, true);
            String json = JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT)
                              .composeString()
                              .startObject()
                              .putObject("state", gameState)
                              .end()
                              .finish();
            tmp = File.createTempFile(file.getName(), ".tmp", directory);
            out = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
            out.write(json);
            out.close();
            prev.delete();
            file.renameTo(prev);
            if (tmp.renameTo(file)) { prev.delete(); }
            // Also keep this version as a restore point for the Recovery screen.
            if (!reason.isEmpty()) {
                String error = Checkpoints.write(filename, gameId, gameState, json, reason);
                reportCheckpointError(error == null ? "" : error);
            }
        } catch (Exception e) { Logger.printStackTrace("writing JSON snapshot", e); } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception e) {}
            }
            if (tmp != null) {
                try {
                    tmp.delete();
                } catch (Exception e) {}
            }
        }
        if (useMetrics) { timer.observeDuration(); }
    }

    private void reportCheckpointError(String error) {
        if (!error.equals(lastCheckpointError)) {
            lastCheckpointError = error;
            game.set(Game.CHECKPOINT_ERROR, error);
        }
    }

    private final Game game;
    private final String gameId;
    private String lastCheckpointError = "";
    private File directory;
    private String filename;
    private AtomicReference<String> writeOnNextUpdate = new AtomicReference<>(null);
    private StateTrie state = new StateTrie();
    private PathTrie filters = new PathTrie();

    private boolean useMetrics;
    private static Histogram updateStateDuration = null;
}
