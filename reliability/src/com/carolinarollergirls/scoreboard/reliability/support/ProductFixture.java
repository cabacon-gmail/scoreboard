package com.carolinarollergirls.scoreboard.reliability.support;

import java.io.IOException;

import com.carolinarollergirls.scoreboard.core.ScoreBoardImpl;
import com.carolinarollergirls.scoreboard.core.game.GameImpl;
import com.carolinarollergirls.scoreboard.core.interfaces.CurrentGame;
import com.carolinarollergirls.scoreboard.json.ScoreBoardJSONListener;
import com.carolinarollergirls.scoreboard.utils.ScoreBoardClock;
import com.carolinarollergirls.scoreboard.utils.Version;

/**
 * Starts the product the way {@code Main} does, minus the web server, so tests don't hand-roll it.
 * <p>
 * The global clock is stopped (tests advance time explicitly) and quick-clock detection is off. The
 * {@code ScoreBoardJSONListener} is attached, as in {@code Main}; without it the JSON state, and therefore every
 * autosave, stays empty. Must run inside the test class's own work dir (the runner arranges this), because the
 * product resolves {@code config/} and {@code html/} relative to the current directory.
 * </p>
 */
public final class ProductFixture {
    private ProductFixture(ScoreBoardImpl sb) { this.sb = sb; }

    public static ProductFixture start() throws IOException {
        CapturingLogger.installIfAbsent();
        ScoreBoardClock.getInstance().stop();
        GameImpl.setQuickClockThreshold(0L);
        if (!Version.load()) { throw new IllegalStateException("setup: release.properties not on the classpath"); }
        return new ProductFixture(newScoreBoard());
    }

    /** A further, independent scoreboard with the JSON listener attached but no autosave loaded. */
    public static ScoreBoardImpl newScoreBoard() {
        ScoreBoardImpl sb = new ScoreBoardImpl(false);
        new ScoreBoardJSONListener(sb, sb.getJsm());
        return sb;
    }

    /** Finishes start-up for a scoreboard created with {@link #newScoreBoard()}. */
    public static void finishStartup(ScoreBoardImpl sb) { sb.postAutosaveUpdate(); }

    public ScoreBoardImpl sb() {
        ensureStarted();
        return sb;
    }

    /** The current game. */
    public GameImpl game() {
        ensureStarted();
        return (GameImpl) sb.getCurrentGame().get(CurrentGame.GAME);
    }

    private void ensureStarted() {
        if (!started) {
            sb.postAutosaveUpdate();
            started = true;
        }
    }

    private final ScoreBoardImpl sb;
    private boolean started;
}
