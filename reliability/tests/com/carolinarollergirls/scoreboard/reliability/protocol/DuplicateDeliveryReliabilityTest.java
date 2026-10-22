package com.carolinarollergirls.scoreboard.reliability.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Test;

import com.carolinarollergirls.scoreboard.reliability.harness.Bout;
import com.carolinarollergirls.scoreboard.reliability.harness.Messages;
import com.carolinarollergirls.scoreboard.reliability.harness.Replayer;
import com.carolinarollergirls.scoreboard.reliability.harness.ServerLauncher;
import com.carolinarollergirls.scoreboard.reliability.harness.Trace;
import com.carolinarollergirls.scoreboard.reliability.harness.TraceWriter;

/**
 * F10: relative updates are not idempotent.
 * <p>
 * The SK screen's "Trip Pts +1" sends {@code Set TripScore 1 flag=change}. If a flaky connection delivers that
 * frame twice (a client retry, or a reconnect that resends), the team gets 2 points instead of 1. Nothing in the
 * message lets the server recognise a repeat. Probed on dev, Oct 20, 2026.
 * </p>
 */
public class DuplicateDeliveryReliabilityTest {
    @Test
    public void aRepeatedPlusOneIsAppliedOnce() throws Exception {
        String plusOne = Messages.set("ScoreBoard.Game($GAME).Team(1).TripScore", 1, "change");
        // clang-format off
        Trace trace = Bout.create("F10: the same +1 trip point frame arrives twice")
            .device("sk1", "/nso/sk?game=$GAME&team=1")
            .at(0).on("sk1").connect().register("ScoreBoard.Game($GAME).Team(1)")
            .at(1500).on("sk1").startJam()
            .after(100).expect(2000, "ScoreBoard.Game($GAME).InJam", true)
            .at(3000).mark("jammer scores one point").on("sk1").send(plusOne)
            .after(1000).mark("the same frame again, as a retry would send it").send(plusOne)
            .at(5000).mark("end")
            .build();
        // clang-format on
        TraceWriter.write(trace, new File("f10.crgtrace.jsonl"));

        Replayer.Result r;
        try (ServerLauncher server = ServerLauncher.start(ServerLauncher.repoDirFromRunner(), new File("server"))) {
            r = Replayer.replay(trace, server);
        }
        assertTrue("setup: trace did not replay cleanly: " + r.failures, r.ok());
        String game = (String) r.vars.get("GAME");
        Object score = r.state().get("ScoreBoard.Game(" + game + ").Team(1).Score");
        assertEquals("F10: a repeated +1 trip point frame was applied twice (score " + score + ")", 1,
                     ((Number) score).intValue());
    }
}
