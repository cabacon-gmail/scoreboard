package com.carolinarollergirls.scoreboard.reliability.protocol;

import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Test;

import com.carolinarollergirls.scoreboard.reliability.harness.DeviceClient;
import com.carolinarollergirls.scoreboard.reliability.harness.Messages;
import com.carolinarollergirls.scoreboard.reliability.harness.ServerLauncher;
import com.carolinarollergirls.scoreboard.reliability.support.RateTest;

/**
 * F11: the {@code Register} race.
 * <p>
 * A new connection's per-client state snapshot is filled in asynchronously ({@code JSONStateManager.register}), and
 * a {@code Register} that arrives first is answered from whatever snapshot the socket holds at that moment. A display
 * that registers as soon as it connects can then miss values that never change again, such as the team names, and
 * shows blanks until someone reloads it. This runs while a jam is running, as at a venue when a display reconnects
 * after a Wi-Fi drop.
 * </p>
 */
public class RegisterRaceReliabilityTest {
    private static final int PARALLEL = 10;

    /**
     * Returns null if the display got both names within 2 s, else what it had. A connection the server refuses or
     * closes straight away (F14) is retried like WS.js would; only a display that stayed connected and still has no
     * names counts against F11.
     */
    private static String oneDisplay(int port, int id, String register, Object name1, Object name2) throws Exception {
        for (int attempt = 1;; attempt++) {
            try (DeviceClient d = new DeviceClient("display" + id + "." + attempt, port, "/scoreboard/", "trace")) {
                try {
                    d.connect(port);
                    d.send(register);
                } catch (java.io.IOException e) {
                    if (attempt >= 10) { throw e; }
                    Thread.sleep(100);
                    continue;
                }
                boolean ok = d.await(s -> name1.equals(s.get("ScoreBoard.CurrentGame.Team(1).Name")) &&
                                              name2.equals(s.get("ScoreBoard.CurrentGame.Team(2).Name")),
                                     2000);
                if (ok) { return null; }
                if (!d.isConnected() && attempt < 10) { continue; }
                return "got " + d.get("ScoreBoard.CurrentGame.Team(1).Name") + " / " +
                    d.get("ScoreBoard.CurrentGame.Team(2).Name") + ", connected=" + d.isConnected();
            }
        }
    }

    @Test
    public void displaysThatRegisterImmediatelyGetTheTeamNames() throws Exception {
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
            Object name1 = sbo.get("ScoreBoard.CurrentGame.Team(1).Name");
            Object name2 = sbo.get("ScoreBoard.CurrentGame.Team(2).Name");
            assertTrue("setup: team names are empty", name1 != null && name2 != null);

            String register = Messages.register("ScoreBoard.CurrentGame.Team(1).Name",
                                                "ScoreBoard.CurrentGame.Team(2).Name",
                                                "ScoreBoard.CurrentGame.Clock(Jam).Time");
            // Ten displays at once, as when the venue Wi-Fi comes back and every screen reconnects together.
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(PARALLEL);
            try {
                RateTest.run(50, i -> {
                    java.util.List<java.util.concurrent.Future<String>> results = new java.util.ArrayList<>();
                    for (int k = 0; k < PARALLEL; k++) {
                        int id = i * PARALLEL + k;
                        results.add(pool.submit(() -> oneDisplay(server.port(), id, register, name1, name2)));
                    }
                    int missing = 0;
                    String example = null;
                    for (java.util.concurrent.Future<String> f : results) {
                        String problem = f.get();
                        if (problem != null) {
                            missing++;
                            example = problem;
                        }
                    }
                    if (missing > 0) {
                        throw new AssertionError("F11: " + missing + " of " + PARALLEL +
                                                 " displays that registered immediately had no team names after 2 s ("
                                                 + example + ")");
                    }
                });
            } finally { pool.shutdownNow(); }
        }
    }
}
