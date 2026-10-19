package com.carolinarollergirls.scoreboard.json;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.carolinarollergirls.scoreboard.json.JSONStateListener.StateTrie;
import com.carolinarollergirls.scoreboard.reliability.support.RateTest;

/**
 * F14: the state fan-out races with clients connecting and disconnecting.
 * <p>
 * {@code JSONStateManager.updateState} iterates the live key set of its listener map outside the lock while
 * {@code register}/{@code unregister} modify it. The resulting ConcurrentModificationException escapes to the caller,
 * which in the real server is the core thread in the middle of a clock tick or an operator action.
 * </p>
 */
public class FanOutReliabilityTest {
    static final int ITERATIONS = 200;
    static final int UPDATES_PER_ITERATION = 500;
    static final int LISTENERS_PER_CHURN = 20;

    @Test
    public void updatesSurviveClientsConnectingAndDisconnecting() {
        RateTest.run(ITERATIONS, i -> {
            final JSONStateManager jsm = new JSONStateManager(false);
            final AtomicBoolean stop = new AtomicBoolean();
            final AtomicReference<Throwable> churnFailure = new AtomicReference<>();
            Thread churn = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (!stop.get()) {
                            List<JSONStateListener> clients = new ArrayList<>();
                            for (int c = 0; c < LISTENERS_PER_CHURN; c++) {
                                // Distinct instances: non-capturing lambdas would all be one shared object.
                                JSONStateListener l = new JSONStateListener() {
                                    @Override
                                    public void sendUpdates(StateTrie state, StateTrie changed) {}
                                };
                                jsm.register(l);
                                clients.add(l);
                            }
                            for (JSONStateListener l : clients) { jsm.unregister(l); }
                        }
                    } catch (Throwable t) { churnFailure.compareAndSet(null, t); }
                }
            }, "F14-churn");
            churn.start();
            Throwable failure = null;
            try {
                for (int n = 0; n < UPDATES_PER_ITERATION; n++) { jsm.updateState("ScoreBoard.Reliability.Value", n); }
            } catch (Throwable t) { failure = t; } finally {
                stop.set(true);
                churn.join(10_000L);
            }
            if (failure != null) {
                throw new AssertionError("F14: updateState threw while clients connected/disconnected: " + failure);
            }
        });
    }
}
