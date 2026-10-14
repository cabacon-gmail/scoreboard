package com.carolinarollergirls.scoreboard.core.game;

import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.carolinarollergirls.scoreboard.core.ScoreBoardImpl;
import com.carolinarollergirls.scoreboard.core.interfaces.CurrentGame;
import com.carolinarollergirls.scoreboard.core.interfaces.Game;
import com.carolinarollergirls.scoreboard.event.ScoreBoardEvent;
import com.carolinarollergirls.scoreboard.event.ScoreBoardListener;
import com.carolinarollergirls.scoreboard.utils.ScoreBoardClock;
import com.carolinarollergirls.scoreboard.utils.Version;

/**
 * F7 (pilot): an exception during an undo must not leave the clock controls dead.
 * <p>
 * {@code GameImpl.restoreSnapshot} sets {@code restoreRunning} and clears it at the end without a
 * try/finally, and {@code clockUndo} stops the global clock before restoring. If any listener throws while the
 * snapshot is being restored, Start Jam, Stop Jam and Timeout silently do nothing for the rest of the session.
 * </p>
 */
public class UndoReliabilityTest {
    private GameImpl g;
    private boolean armed;

    @Before
    public void setUp() throws Exception {
        ScoreBoardClock.getInstance().stop();
        GameImpl.setQuickClockThreshold(0L);
        Version.load();
        ScoreBoardImpl sb = new ScoreBoardImpl(false);
        sb.postAutosaveUpdate();
        g = (GameImpl) sb.getCurrentGame().get(CurrentGame.GAME);
    }

    @Test
    public void controlsStillWorkAfterUndoRestoreFails() {
        // 1. Start a jam; this also records the undo snapshot.
        g.startJam();
        assertTrue("setup: Start Jam did not start a jam", g.isInJam());

        // 2. A one-shot listener that fails while the undo restores IN_JAM, standing in for any listener bug.
        g.addScoreBoardListener(new ScoreBoardListener() {
            @Override
            public void scoreBoardChange(ScoreBoardEvent<?> event) {
                if (armed && event.getProperty() == Game.IN_JAM) {
                    armed = false;
                    throw new IllegalStateException("injected listener failure");
                }
            }
        });

        // 3. Undo. The real server swallows the exception in its WebSocket / JSON-setter handlers; so do we.
        armed = true;
        try {
            g.clockUndo(false);
        } catch (RuntimeException expected) {
            // Logged so the class log shows the injected failure; otherwise ignored on purpose.
            System.err.println("UndoReliabilityTest: undo threw " + expected);
        }

        // 4. The operator presses Start Jam again. It must work.
        g.startJam();
        assertTrue("F7: Start Jam had no effect after an undo whose restore failed", g.isInJam());
    }
}
