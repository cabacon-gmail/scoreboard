package com.carolinarollergirls.scoreboard.json;

import java.io.File;

import com.carolinarollergirls.scoreboard.core.ScoreBoardImpl;
import com.carolinarollergirls.scoreboard.reliability.support.ProductFixture;

/** Shared helpers for the autosave tests (F12, F13). Not a test class itself. */
final class AutosaveFiles {
    private AutosaveFiles() {}

    static final String MARKER = "Reliability.Marker";
    static final String ONLY_IN_NEWEST = "Reliability.OnlyInNewest";

    static File newest(File dir) { return new File(dir, "scoreboard-0-secs-ago.json"); }

    /** Restores {@code dir} into a fresh scoreboard, the way Main does at start-up. */
    static ScoreBoardImpl restore(File dir) {
        final ScoreBoardImpl sb = ProductFixture.newScoreBoard();
        final boolean[] loaded = new boolean[1];
        sb.runInBatch(new Runnable() {
            @Override
            public void run() {
                loaded[0] = AutoSaveJSONState.loadAutoSave(sb, dir);
            }
        });
        ProductFixture.finishStartup(sb);
        if (!loaded[0]) { throw new IllegalStateException("setup: no autosave in " + dir + " could be loaded"); }
        return sb;
    }
}
