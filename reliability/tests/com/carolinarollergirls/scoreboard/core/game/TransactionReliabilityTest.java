package com.carolinarollergirls.scoreboard.core.game;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import com.carolinarollergirls.scoreboard.core.interfaces.Clock;
import com.carolinarollergirls.scoreboard.core.interfaces.Game;
import com.carolinarollergirls.scoreboard.event.ScoreBoardEvent;
import com.carolinarollergirls.scoreboard.event.ScoreBoardListener;
import com.carolinarollergirls.scoreboard.reliability.support.ProductFixture;

/**
 * F9: operator actions are not transactional.
 * <p>
 * {@code runInBatch} has no rollback and listeners are not isolated, so an exception partway through an action
 * leaves it half-applied. Here a listener fails while Start Jam is in progress: the game says it is in a jam, but
 * the jam clock never started.
 * </p>
 */
public class TransactionReliabilityTest {
    private GameImpl g;
    private boolean armed;

    @Before
    public void setUp() throws Exception {
        g = ProductFixture.start().game();
    }

    @Test
    public void startJamIsAllOrNothingWhenAListenerFails() {
        Clock jc = g.getClock(Clock.ID_JAM);

        // A one-shot listener that fails as soon as the game enters a jam, standing in for any listener bug.
        g.addScoreBoardListener(new ScoreBoardListener() {
            @Override
            public void scoreBoardChange(ScoreBoardEvent<?> event) {
                if (armed && event.getProperty() == Game.IN_JAM && Boolean.TRUE.equals(event.getValue())) {
                    armed = false;
                    throw new IllegalStateException("injected listener failure");
                }
            }
        });

        armed = true;
        try {
            g.startJam();
        } catch (RuntimeException expected) {
            // The real server swallows this in its WebSocket handler; log it for the class log.
            System.out.println("TransactionReliabilityTest: startJam threw " + expected);
        }

        assertEquals("F9: Start Jam was half-applied after a listener failed (inJam=" + g.isInJam() +
                         ", jam clock running=" + jc.isRunning() + ")",
                     g.isInJam(), jc.isRunning());
    }
}
