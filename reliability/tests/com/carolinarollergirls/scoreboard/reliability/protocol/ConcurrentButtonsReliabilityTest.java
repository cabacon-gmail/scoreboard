package com.carolinarollergirls.scoreboard.reliability.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com.carolinarollergirls.scoreboard.reliability.harness.Bout;
import com.carolinarollergirls.scoreboard.reliability.harness.Replayer;
import com.carolinarollergirls.scoreboard.reliability.harness.ServerLauncher;
import com.carolinarollergirls.scoreboard.reliability.harness.Trace;
import com.carolinarollergirls.scoreboard.reliability.harness.TraceWriter;

/**
 * F6: commands are judged against the state when they arrive, not the state the operator saw.
 * <p>
 * Phase 2 pilot. One whistle, two devices: the SBO calls a timeout and assigns it to Team 1; the JT, reacting to the
 * same whistle, presses Timeout 1.2 s later. That is just outside the 1 s quick-press guard, so the server treats it
 * as a deliberate "re-timeout": it ends Team 1's timeout and starts a second, ownerless one. The scoreboard stops
 * showing Team 1 in timeout, and the extra timeout is recorded.
 * </p>
 */
public class ConcurrentButtonsReliabilityTest {
    private static final Pattern TIMEOUT_ID =
        Pattern.compile("^ScoreBoard\\.Game\\([^)]*\\)\\.Period\\(1\\)\\.Timeout\\(([^)]+)\\)\\.Id$");

    @Test
    public void lateSecondTimeoutPressDoesNotStartAnotherTimeout() throws Exception {
        // clang-format off
        Trace trace = Bout.create("F6 pilot: SBO calls a team timeout; JT presses Timeout for the same whistle 1.2 s later")
            .device("sbo", "/nso/sbo?game=$GAME")
            .device("jt", "/nso/jt?game=$GAME")
            .at(0).on("sbo").connect().register("ScoreBoard.Game($GAME)")
                  .on("jt").connect().register("ScoreBoard.Game($GAME)")
            .at(1500).on("sbo").startJam()
            .after(100).expect(2000, "ScoreBoard.Game($GAME).InJam", true)
            .at(4000).mark("whistle: Team 1 calls a timeout").on("sbo").timeout()
            .at(4400).on("sbo").teamTimeout(1)
            .after(100).expect(1000, "ScoreBoard.Game($GAME).Team(1).InTimeout", true)
            .at(5200).on("jt").timeout()
            .at(6500).mark("end")
            .build();
        // clang-format on
        TraceWriter.write(trace, new File("f6-pilot.crgtrace.jsonl"));

        Replayer.Result r;
        try (ServerLauncher server = ServerLauncher.start(ServerLauncher.repoDirFromRunner(), new File("server"))) {
            r = Replayer.replay(trace, server);
        }
        assertTrue("setup: trace did not replay cleanly: " + r.failures, r.ok());

        String game = (String) r.vars.get("GAME");
        Map<String, Object> s = r.state();
        TreeSet<String> timeouts = timeoutIds(s);
        Object current = s.get("ScoreBoard.Game(" + game + ").CurrentTimeout");
        Object owner = s.get("ScoreBoard.Game(" + game + ").Period(1).Timeout(" + current + ").Owner");
        boolean team1InTimeout = Boolean.TRUE.equals(s.get("ScoreBoard.Game(" + game + ").Team(1).InTimeout"));

        assertEquals("F6: a Timeout press from another device 1.2 s later started a second timeout (" +
                         timeouts.size() + " timeouts, running one owned by '" + owner +
                         "', Team 1 in timeout=" + team1InTimeout + ")",
                     1, timeouts.size());
        assertTrue("F6: Team 1's timeout lost its owner after another device's Timeout press (owner '" + owner + "')",
                   String.valueOf(owner).endsWith("_1") && team1InTimeout);
    }

    /**
     * F6, scenario 2 (probed on dev, Oct 20, 2026). The SBO calls a timeout during a jam; 600 ms later the JT, who
     * saw the jam end, presses Stop. Different buttons within 1 s look like one operator correcting a mistake, so the
     * server undoes the SBO's timeout and applies Stop instead: the timeout is gone, and a lineup runs.
     */
    @Test
    public void stopFromAnotherDeviceDoesNotDeleteATimeout() throws Exception {
        // clang-format off
        Trace trace = Bout.create("F6 #2: SBO calls a timeout during a jam; JT presses Stop 600 ms later")
            .device("sbo", "/nso/sbo?game=$GAME")
            .device("jt", "/nso/jt?game=$GAME")
            .at(0).on("sbo").connect().register("ScoreBoard.Game($GAME)")
                  .on("jt").connect().register("ScoreBoard.Game($GAME)")
            .at(1500).on("sbo").startJam()
            .after(100).expect(2000, "ScoreBoard.Game($GAME).InJam", true)
            .at(4000).mark("whistle: timeout").on("sbo").timeout()
            .after(100).expect(1000, "ScoreBoard.Game($GAME).Clock(Timeout).Running", true)
            .at(4600).on("jt").stopJam()
            .at(6000).mark("end")
            .build();
        // clang-format on
        TraceWriter.write(trace, new File("f6-replace.crgtrace.jsonl"));

        Replayer.Result r;
        try (ServerLauncher server = ServerLauncher.start(ServerLauncher.repoDirFromRunner(), new File("server2"))) {
            r = Replayer.replay(trace, server);
        }
        assertTrue("setup: trace did not replay cleanly: " + r.failures, r.ok());
        int timeouts = timeoutIds(r.state()).size();
        String game = (String) r.vars.get("GAME");
        Object inTimeout = r.state().get("ScoreBoard.Game(" + game + ").Clock(Timeout).Running");
        assertEquals("F6: a Stop press from another device 600 ms after a Timeout press deleted the timeout (" +
                         timeouts + " timeouts, timeout clock running=" + inTimeout + ")",
                     1, timeouts);
    }

    private static TreeSet<String> timeoutIds(Map<String, Object> s) {
        TreeSet<String> ids = new TreeSet<>();
        for (String k : s.keySet()) {
            Matcher m = TIMEOUT_ID.matcher(k);
            if (m.matches()) { ids.add(m.group(1)); }
        }
        return ids;
    }
}
