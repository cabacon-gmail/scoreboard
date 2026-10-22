package com.carolinarollergirls.scoreboard.reliability.protocol;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.Test;

import com.carolinarollergirls.scoreboard.reliability.harness.DeviceClient;
import com.carolinarollergirls.scoreboard.reliability.harness.ServerLauncher;
import com.carolinarollergirls.scoreboard.reliability.support.RateTest;

/**
 * F14 over the wire: the fan-out list is iterated without its lock.
 * <p>
 * Found while stressing F11 (Oct 20, 2026). When several devices connect at the same moment, as when venue Wi-Fi
 * comes back and every screen reconnects together, {@code JSONStateManager.updateState} iterates the listener map
 * while another connection adds itself. The {@code ConcurrentModificationException} escapes into the code that
 * registers the new client, and the server refuses that device's WebSocket handshake. The Phase 1 test shows the
 * same race inside one JVM; this one shows what a crew sees.
 * </p>
 */
public class ReconnectStormReliabilityTest {
    private static final int PARALLEL = 10;

    @Test
    public void devicesReconnectingTogetherAreAllAccepted() throws Exception {
        try (ServerLauncher server = ServerLauncher.start(ServerLauncher.repoDirFromRunner(), new File("server"));
             DeviceClient sbo = new DeviceClient("sbo", server.port(), "/nso/sbo", "trace")) {
            sbo.connect(server.port());
            sbo.register("ScoreBoard.CurrentGame");
            assertTrue("setup: no current game",
                       sbo.await(s -> s.get("ScoreBoard.CurrentGame.Game") instanceof String, 10_000));
            String game = (String) sbo.get("ScoreBoard.CurrentGame.Game");
            Thread.sleep(1500); // the quick-press guard drops a press in the first second
            sbo.set("ScoreBoard.Game(" + game + ").StartJam", true);
            assertTrue("setup: jam did not start", sbo.awaitValue("ScoreBoard.CurrentGame.InJam", true, 3000));

            ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
            try {
                RateTest.run(50, i -> {
                    List<Future<String>> results = new ArrayList<>();
                    for (int k = 0; k < PARALLEL; k++) {
                        int id = i * PARALLEL + k;
                        results.add(pool.submit(() -> {
                            try (DeviceClient d = new DeviceClient("display" + id, server.port(), "/scoreboard/",
                                                                   "trace")) {
                                d.connect(server.port());
                                return null;
                            } catch (java.io.IOException e) { return e.getMessage(); }
                        }));
                    }
                    int refused = 0;
                    String example = null;
                    for (Future<String> f : results) {
                        String problem = f.get();
                        if (problem != null) {
                            refused++;
                            example = problem;
                        }
                    }
                    if (refused > 0) {
                        throw new AssertionError("F14: " + refused + " of " + PARALLEL +
                                                 " devices reconnecting together were refused (" + example + ")");
                    }
                });
            } finally { pool.shutdownNow(); }
        }
    }
}
