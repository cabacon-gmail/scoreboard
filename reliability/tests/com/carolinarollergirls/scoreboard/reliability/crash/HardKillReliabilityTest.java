package com.carolinarollergirls.scoreboard.reliability.crash;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.carolinarollergirls.scoreboard.reliability.harness.DeviceClient;
import com.carolinarollergirls.scoreboard.reliability.harness.Messages;
import com.carolinarollergirls.scoreboard.reliability.harness.ServerLauncher;
import com.carolinarollergirls.scoreboard.reliability.support.RateTest;
import com.carolinarollergirls.scoreboard.reliability.support.Slow;

/**
 * F12: autosave is not durable.
 * <p>
 * The server keeps the game only in memory and writes the whole state every 10 s, rotating seven files by rename
 * with no fsync and unchecked results. A hard kill (a crash, the laptop's battery, someone closing the window the
 * wrong way) loses whatever happened since the last save, and a kill that lands during the rotation can lose more.
 * </p>
 * <p>
 * Each iteration plays a few seconds of scoring (one +1 a second, each confirmed by the server), kills the server
 * with {@code kill -9} at a random moment, restarts it on the same directory, and checks how far behind the
 * restored score is. It must be at most 10 s behind the kill. The kill times come from a fixed seed
 * ({@code -Dreliability.seed} to change it), so a run can be repeated. <b>Provisional</b> pending the
 * autosave-semantics decision.
 * </p>
 */
@Slow("about 3 minutes")
public class HardKillReliabilityTest {
    static final long MAX_BEHIND_MS = 10_000L;

    @Test
    public void restartAfterKillLosesAtMostTenSeconds() throws Exception {
        long seed = Long.getLong("reliability.seed", 12L);
        Random random = new Random(seed);
        try (ServerLauncher server = ServerLauncher.start(ServerLauncher.repoDirFromRunner(), new File("server"))) {
            String game = currentGame(server);
            RateTest.run(15, i -> {
                long playMs = 3_000 + random.nextInt(15_000);
                try (DeviceClient sk = new DeviceClient("sk1-" + i, server.port(), "/nso/sk?team=1", "trace")) {
                    connect(sk, server.port());
                    String scoreKey = "ScoreBoard.Game(" + game + ").Team(1).Score";
                    sk.register(scoreKey, "ScoreBoard.Game(" + game + ").InJam");
                    if (!sk.await(s -> s.get(scoreKey) instanceof Number, 10_000)) {
                        throw new IllegalStateException("setup: no score after restart");
                    }
                    if (!Boolean.TRUE.equals(sk.get("ScoreBoard.Game(" + game + ").InJam"))) {
                        sk.set("ScoreBoard.Game(" + game + ").StartJam", true);
                        sk.awaitValue("ScoreBoard.Game(" + game + ").InJam", true, 3000);
                    }
                    int score = ((Number) sk.get(scoreKey)).intValue();
                    List<Long> confirmed = new ArrayList<>(); // confirmed[k] = when score + k + 1 was confirmed
                    long start = System.nanoTime();
                    while (System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(playMs)) {
                        int want = score + confirmed.size() + 1;
                        sk.send(Messages.set("ScoreBoard.Game(" + game + ").Team(1).TripScore", 1, "change"));
                        if (sk.awaitValue(scoreKey, want, 2000)) { confirmed.add(System.nanoTime()); }
                        Thread.sleep(1000);
                    }
                    long killAt = System.nanoTime();
                    server.kill(ServerLauncher.Signal.KILL);
                    server.restart();
                    try (DeviceClient after = new DeviceClient("check-" + i, server.port(), "/nso/sbo", "trace")) {
                        connect(after, server.port());
                        after.register(scoreKey);
                        Object restored = after.await(s -> s.get(scoreKey) instanceof Number, 10_000)
                                              ? after.get(scoreKey) : null;
                        int got = restored == null ? -1 : ((Number) restored).intValue();
                        int lost = score + confirmed.size() - got;
                        if (restored == null || lost > confirmed.size()) {
                            throw new AssertionError("F12: after kill -9 the game was not restored (score " +
                                                     restored + ", game " + game + ")");
                        }
                        if (lost > 0) {
                            long firstLost = confirmed.get(confirmed.size() - lost);
                            long behind = TimeUnit.NANOSECONDS.toMillis(killAt - firstLost);
                            if (behind > MAX_BEHIND_MS) {
                                throw new AssertionError("F12: after kill -9 the restored game was " + behind +
                                                         " ms behind (" + lost + " points lost; seed " + seed + ")");
                            }
                        }
                    }
                }
            });
        }
    }

    /**
     * Connects, retrying refused handshakes as WS.js does: right after a restart, devices connecting together can
     * hit F14 ({@code ReconnectStormReliabilityTest}), which is not what this test measures.
     */
    private static void connect(DeviceClient d, int port) throws Exception {
        for (int attempt = 1;; attempt++) {
            try {
                d.connect(port);
                return;
            } catch (java.io.IOException e) {
                if (attempt >= 10) { throw e; }
                Thread.sleep(200);
            }
        }
    }

    private static String currentGame(ServerLauncher server) throws Exception {
        try (DeviceClient d = new DeviceClient("setup", server.port(), "/", "trace")) {
            d.connect(server.port());
            d.register("ScoreBoard.CurrentGame.Game");
            if (!d.await(s -> s.get("ScoreBoard.CurrentGame.Game") instanceof String, 10_000)) {
                throw new IllegalStateException("setup: no current game");
            }
            Thread.sleep(1500); // the quick-press guard drops a press in the first second
            return (String) d.get("ScoreBoard.CurrentGame.Game");
        }
    }
}
