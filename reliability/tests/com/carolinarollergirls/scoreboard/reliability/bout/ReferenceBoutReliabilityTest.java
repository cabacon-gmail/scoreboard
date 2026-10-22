package com.carolinarollergirls.scoreboard.reliability.bout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.carolinarollergirls.scoreboard.reliability.harness.CrewProfile;
import com.carolinarollergirls.scoreboard.reliability.harness.Oracles;
import com.carolinarollergirls.scoreboard.reliability.harness.ReferenceBout;
import com.carolinarollergirls.scoreboard.reliability.harness.Replayer;
import com.carolinarollergirls.scoreboard.reliability.harness.ServerLauncher;
import com.carolinarollergirls.scoreboard.reliability.harness.Trace;
import com.carolinarollergirls.scoreboard.reliability.harness.TraceWriter;
import com.carolinarollergirls.scoreboard.reliability.support.Slow;

/**
 * The reference bout with no faults: the baseline every other bout test compares against (Phase 2 gate). It must
 * pass on every OS. It records the baseline press-to-visible latency in {@code reference-bout-latency.json} in the
 * class's work directory.
 */
@Slow("about 6 minutes")
public class ReferenceBoutReliabilityTest {
    @Test
    public void referenceBoutReplaysConvergesAndKeepsInvariants() throws Exception {
        File repo = ServerLauncher.repoDirFromRunner();
        CrewProfile crew = CrewProfile.load(repo, "default");
        Trace trace = ReferenceBout.build(crew);
        TraceWriter.write(trace, new File("reference-bout.crgtrace.jsonl"));

        Replayer.Result r;
        List<String> converge;
        try (ServerLauncher server = ServerLauncher.start(repo, new File("server"), ReferenceBout.options(repo))) {
            r = Replayer.replay(trace, server, liveOptions());
            converge = Oracles.convergence(r, 2000);
        }
        assertTrue("reference bout: expects failed: " + r.failures, r.ok());
        assertEquals("reference bout: devices did not converge", Collections.emptyList(), converge);
        String game = String.valueOf(r.vars.get("GAME"));
        assertEquals("reference bout: game invariants broken", Collections.emptyList(),
                     Oracles.invariants(r.state(), game));
        Oracles.Latency latency = Oracles.scorePressLatency(r);
        String json = "{\"crew\":\"" + crew.name() + "\",\"samples\":" + latency.samples.size() + ",\"p50Ms\":" +
            latency.percentile(50) + ",\"p95Ms\":" + latency.percentile(95) + ",\"maxMs\":" + latency.percentile(100) +
            ",\"missed\":" + latency.missed + "}\n";
        Files.write(new File("reference-bout-latency.json").toPath(), json.getBytes(StandardCharsets.UTF_8));
        System.out.println("reference bout press-to-visible latency: " + latency);
        assertEquals("reference bout: some score presses never reached a device showing the score: " + latency, 0,
                     latency.missed);
    }

    /** Devices stay connected after the trace ends, so the oracles can still look at them. */
    static Replayer.Options liveOptions() {
        Replayer.Options o = new Replayer.Options();
        o.keepConnected = true;
        return o;
    }
}
