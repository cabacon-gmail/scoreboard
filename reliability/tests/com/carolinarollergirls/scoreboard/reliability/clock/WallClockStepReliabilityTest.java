package com.carolinarollergirls.scoreboard.reliability.clock;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.concurrent.TimeUnit;

import org.junit.Assume;
import org.junit.Test;

import com.carolinarollergirls.scoreboard.reliability.harness.Bout;
import com.carolinarollergirls.scoreboard.reliability.harness.ClockControl;
import com.carolinarollergirls.scoreboard.reliability.harness.DeviceClient;
import com.carolinarollergirls.scoreboard.reliability.harness.Replayer;
import com.carolinarollergirls.scoreboard.reliability.harness.ServerLauncher;
import com.carolinarollergirls.scoreboard.reliability.harness.Trace;
import com.carolinarollergirls.scoreboard.reliability.harness.TraceWriter;

/**
 * F1: game time follows the wall clock.
 * <p>
 * {@code ScoreBoardClock} computes game time as wall time minus an offset. When the wall clock steps (NTP
 * correcting a laptop that was offline, or someone fixing the time), game clocks jump: forward drains them, and
 * backward freezes them until the wall clock catches up. Here the server's wall clock steps +5 min, then -5 min,
 * during a jam. The jam and period clocks must stay within 1 s of the harness's own monotonic clock.
 * </p>
 * Linux and macOS: libfaketime (see {@link ClockControl}). Windows: the real system clock, only with
 * {@code -Dreliability.allowClockChanges=true} in an elevated shell. Otherwise SKIPPED, naming the reason.
 */
public class WallClockStepReliabilityTest {
    static final long STEP_MS = 5 * 60 * 1000L;
    static final long TOLERANCE_MS = 1000L;

    @Test
    public void gameClocksIgnoreWallClockSteps() throws Exception {
        ClockControl clock = ClockControl.detect();
        Assume.assumeTrue("F1: " + clock.reason(), clock.available());
        try {
            run(clock);
        } finally { clock.close(); }
    }

    private void run(ClockControl clock) throws Exception {
        // clang-format off
        Trace trace = Bout.create("F1: wall clock steps +5 min, then -5 min, during a jam")
            .device("sbo", "/nso/sbo?game=$GAME")
            .at(0).on("sbo").connect().register("ScoreBoard.Game($GAME)")
            .at(1500).on("sbo").startJam()
            .after(200).expect(2000, "ScoreBoard.Game($GAME).InJam", true)
            .at(6000).mark("NTP corrects the clock forward").clock(STEP_MS)
            .at(14000).mark("and back again").clock(-STEP_MS)
            .at(24000).mark("end")
            .build();
        // clang-format on
        TraceWriter.write(trace, new File("f1.crgtrace.jsonl"));

        File dir = new File("server");
        ServerLauncher.Options so = new ServerLauncher.Options();
        clock.configure(so, dir);
        Replayer.Options ro = new Replayer.Options();
        ro.clock = clock;
        long[] sawStep = { Long.MIN_VALUE };
        Replayer.Result r;
        try (ServerLauncher server = ServerLauncher.start(ServerLauncher.repoDirFromRunner(), dir, so)) {
            ro.afterEvent = (e, res) -> {
                if ("clock".equals(e.ev) && ((Number) e.get("step")).longValue() > 0) {
                    // Prove the step reached the server, so a pass can't come from a clock control that did nothing.
                    sawStep[0] = server.serverWallMillis() - System.currentTimeMillis();
                }
            };
            r = Replayer.replay(trace, server, ro);
        }
        boolean systemClock = clock.mode() == ClockControl.Mode.SYSTEM_CLOCK;
        // With the real system clock, the harness's own wall clock moves too, so the Date header shows no offset.
        Assume.assumeTrue("F1: the clock step did not reach the server (Date header offset " + sawStep[0] +
                              " ms); " + clock.reason() + " is not effective here",
                          systemClock || Math.abs(sawStep[0] - STEP_MS) < 5000);
        assertTrue("setup: trace did not replay cleanly: " + r.failures, r.ok());

        String game = String.valueOf(r.vars.get("GAME"));
        long startPress = -1;
        for (DeviceClient.Frame f : r.devices.get("sbo").frames()) {
            if (f.outgoing && f.text.contains(".StartJam")) { startPress = f.nanos; }
        }
        long jamOff = clockElapsed(r, game, "Jam") - sinceLastUpdate(r, game, "Jam", startPress);
        long periodOff = clockElapsed(r, game, "Period") - sinceLastUpdate(r, game, "Period", startPress);
        Object inJam = r.state().get("ScoreBoard.Game(" + game + ").InJam");
        assertTrue("F1: a wall-clock step of +5 min then -5 min moved the game clocks (jam clock off by " + jamOff +
                       " ms, period clock off by " + periodOff + " ms, still in jam: " + inJam + ")",
                   Math.abs(jamOff) <= TOLERANCE_MS && Math.abs(periodOff) <= TOLERANCE_MS &&
                       Boolean.TRUE.equals(inJam));
    }

    /** Monotonic ms from the Start Jam press to the observer's last update of that clock's time. */
    private static long sinceLastUpdate(Replayer.Result r, String game, String id, long startPress) {
        String key = "ScoreBoard.Game(" + game + ").Clock(" + id + ").Time";
        long last = startPress;
        for (DeviceClient.Frame f : r.observer.frames()) {
            if (!f.outgoing && f.text.contains(key)) { last = f.nanos; }
        }
        return TimeUnit.NANOSECONDS.toMillis(last - startPress);
    }

    /** How much time the clock has counted since it started: its maximum minus its time (both count down). */
    private static long clockElapsed(Replayer.Result r, String game, String id) {
        String p = "ScoreBoard.Game(" + game + ").Clock(" + id + ").";
        Object max = r.state().get(p + "MaximumTime");
        Object time = r.state().get(p + "Time");
        Object dir = r.state().get(p + "Direction");
        long m = ((Number) max).longValue(), t = ((Number) time).longValue();
        return Boolean.TRUE.equals(dir) ? m - t : t;
    }
}
