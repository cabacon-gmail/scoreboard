package com.carolinarollergirls.scoreboard.reliability.bout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import com.carolinarollergirls.scoreboard.reliability.harness.Bout;
import com.carolinarollergirls.scoreboard.reliability.harness.CrewProfile;
import com.carolinarollergirls.scoreboard.reliability.harness.Oracles;
import com.carolinarollergirls.scoreboard.reliability.harness.ReferenceBout;
import com.carolinarollergirls.scoreboard.reliability.harness.Replayer;
import com.carolinarollergirls.scoreboard.reliability.harness.ServerLauncher;
import com.carolinarollergirls.scoreboard.reliability.harness.Trace;
import com.carolinarollergirls.scoreboard.reliability.harness.TraceWriter;
import com.carolinarollergirls.scoreboard.reliability.support.Slow;

/**
 * F15: per-client send queues are unbounded.
 * <p>
 * Each client's updates go through its own executor and Jetty's async send, with no limit. A device that stops
 * reading (a streaming PC that hangs, a tablet whose browser froze but kept the socket open) makes the server queue
 * everything for it. Here the overlay stops reading for 5 minutes of the reference bout, on a server limited to
 * 256 MB. The rest of the crew must not notice: their p95 press-to-visible latency stays within 1.5 times a run
 * without the stall (with a 50 ms floor, since localhost latencies are tiny), the server stays up, and the overlay
 * catches up within 10 s of reading again.
 * </p>
 * Two full reference bouts: about 12 minutes.
 */
@Slow("about 12 minutes")
public class StalledClientReliabilityTest {
    static final long STALL_START_MS = 20_000L;
    static final long STALL_MS = 300_000L;
    static final Set<String> STALLED = Collections.singleton("overlay");

    @Test
    public void aStalledOverlayDoesNotSlowTheCrew() throws Exception {
        File repo = ServerLauncher.repoDirFromRunner();
        CrewProfile crew = CrewProfile.load(repo, "default");

        Oracles.Latency baseline = play(repo, ReferenceBout.build(crew), "baseline", false).latency;

        Bout b = ReferenceBout.bout(crew);
        b.at(STALL_START_MS).mark("the overlay stops reading").on("overlay").stall(STALL_MS);
        Trace stalled = b.build();
        TraceWriter.write(stalled, new File("f15.crgtrace.jsonl"));
        Run run = play(repo, stalled, "stalled", true);

        assertTrue("F15: the server died while the overlay was stalled: " + run.result.failures, run.serverAlive);
        assertTrue("setup: the stalled bout did not replay cleanly: " + run.result.failures, run.result.ok());
        long base = baseline.percentile(95), now = run.latency.percentile(95);
        long allowed = Math.max(base * 3 / 2, base + 50);
        System.out.println("F15: crew p95 latency baseline " + baseline + "; with a stalled overlay " + run.latency);
        assertEquals("F15: the stalled overlay did not catch up within 10 s of reading again", Collections.emptyList(),
                     run.convergence);
        assertTrue("F15: a stalled overlay slowed the rest of the crew: p95 press-to-visible " + now +
                       " ms against " + base + " ms without the stall (allowed " + allowed + " ms)",
                   now <= allowed);
    }

    static final class Run {
        Replayer.Result result;
        Oracles.Latency latency;
        List<String> convergence;
        boolean serverAlive;
    }

    private static Run play(File repo, Trace trace, String name, boolean stalled) throws Exception {
        ServerLauncher.Options so = ReferenceBout.options(repo);
        so.jvmOptions.add("-Xmx256m");
        Replayer.Options ro = new Replayer.Options();
        ro.keepConnected = true;
        Run run = new Run();
        try (ServerLauncher server = ServerLauncher.start(repo, new File("server-" + name), so)) {
            run.result = Replayer.replay(trace, server, ro);
            run.serverAlive = server.isAlive();
            // The stall ends before the bout does (20 s + 300 s < 340 s); give the overlay its 10 s to catch up.
            run.convergence = run.serverAlive ? Oracles.convergence(run.result, 10_000) : Collections.emptyList();
            run.result.close();
        }
        run.latency = Oracles.scorePressLatency(run.result, stalled ? STALLED : Collections.emptySet());
        return run;
    }
}
