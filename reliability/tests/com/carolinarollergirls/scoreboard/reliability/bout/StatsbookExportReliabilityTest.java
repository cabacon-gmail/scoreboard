package com.carolinarollergirls.scoreboard.reliability.bout;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.junit.Assume;
import org.junit.Test;

import com.carolinarollergirls.scoreboard.reliability.harness.Bout;
import com.carolinarollergirls.scoreboard.reliability.harness.CrewProfile;
import com.carolinarollergirls.scoreboard.reliability.harness.DeviceClient;
import com.carolinarollergirls.scoreboard.reliability.harness.ReferenceBout;
import com.carolinarollergirls.scoreboard.reliability.harness.Replayer;
import com.carolinarollergirls.scoreboard.reliability.harness.ServerLauncher;
import com.carolinarollergirls.scoreboard.reliability.harness.Trace;
import com.carolinarollergirls.scoreboard.reliability.harness.TraceWriter;
import com.carolinarollergirls.scoreboard.reliability.runner.RateOutcome;

/**
 * F2: the statsbook export holds the core lock.
 * <p>
 * {@code StatsbookExporter} fills the workbook inside {@code synchronized (coreLock)}, the same lock every button
 * press and clock tick needs. While it runs, the jam clock on every display stops updating and presses queue up.
 * Here the nine-device crew plays two jams late in period 2 (from a seed started mid-game at jam 20, with full
 * rosters); during the second jam the SBO exports the statsbook,
 * and an SK enters a point 100 ms later, four times. How long the stall lasts varies with the machine and the run,
 * so the result is a rate: the share of exports that froze the clock for more than 1.2 s or delayed the press by
 * more than 500 ms.
 * </p>
 * Needs a blank WFTDA StatsBook: {@code -Dreliability.statsbook=<path to .xlsx>}. It is uploaded the way the
 * Games / Teams / Rulesets screen does ({@code POST /Load/blank_xlsx}); the test is SKIPPED, naming the reason, when
 * no path is given or CRG rejects the workbook.
 */
public class StatsbookExportReliabilityTest {
    /** Late in period 2 (jam 20), so the export has a realistic number of jam rows to fill. */
    static final String SEED = "reliability/seeds/late-second-period.json";
    static final long MAX_CLOCK_GAP_MS = 1200;
    static final long MAX_PRESS_MS = 500;
    /** Four exports during one jam: each is one sample, reported as a rate (the stall varies run to run). */
    static final long[] EXPORTS_AT = { 27_000, 33_000, 39_000, 45_000 };

    @Test
    public void exportDoesNotFreezeClocksOrPresses() throws Exception {
        String path = System.getProperty("reliability.statsbook", "");
        Assume.assumeTrue("F2 needs a blank WFTDA StatsBook: -Dreliability.statsbook=<path to .xlsx>",
                          !path.isEmpty());
        File book = new File(path).getAbsoluteFile();
        Assume.assumeTrue("F2: -Dreliability.statsbook file not found: " + book, book.isFile());

        File repo = ServerLauncher.repoDirFromRunner();
        CrewProfile crew = CrewProfile.load(repo, "default");
        // clang-format off
        Bout b = Bout.create("F2: statsbook export during a jam, nine devices connected")
            .seed(SEED)
            .crew(crew)
            .at(0).connectCrew(100)
            .after(200).on("sbo").http("POST", "/Load/blank_xlsx", null,
                                       Collections.singletonList(Bout.fileFrom("file", book.getName(), book.getPath())))
            .after(100).expect(60_000, "ScoreBoard.BlankStatsbookFound", "true")
            .at(5_000).on("jt").startJam()
            .at(10_000).on("sk1").tripScoreUp(1).after(500).tripScoreUp(1)
            .at(20_000).on("jt").stopJam()
            .at(25_000).mark("jam with exports").on("jt").startJam()
            .after(200).expect(3000, "ScoreBoard.Game($GAME).InJam", true);
        // clang-format on
        for (long at : EXPORTS_AT) {
            // clang-format off
            b.at(at).mark("export").on("sbo").set("ScoreBoard.Game($GAME).Export", true)
                .after(100).mark("press during export").on("sk2").tripScoreUp(2)
                .expect(5_000, "ScoreBoard.Game($GAME).UpdateInProgress", true)
                .after(100).expect(120_000, "ScoreBoard.Game($GAME).UpdateInProgress", false);
            // clang-format on
        }
        b.after(2_000).mark("end");
        Trace trace = b.build();
        TraceWriter.write(trace, new File("f2.crgtrace.jsonl"));

        File dir = new File("server");
        Replayer.Result r;
        try (ServerLauncher server = ServerLauncher.start(repo, dir, seeded(repo))) {
            r = Replayer.replay(trace, server);
        }
        Object found = r.state().get("ScoreBoard.BlankStatsbookFound");
        Assume.assumeTrue("F2: CRG did not accept the workbook (BlankStatsbookFound=" + found + ")",
                          "true".equals(found));
        assertTrue("setup: trace did not replay cleanly: " + r.failures, r.ok());
        String game = String.valueOf(r.vars.get("GAME"));
        File[] exported = new File(dir, "html/game-data/xlsx").listFiles((d, n) -> n.endsWith(".xlsx") && !n.startsWith("~"));
        assertTrue("setup: the export wrote no statsbook (ExportBlockedBy=" +
                       r.state().get("ScoreBoard.Game(" + game + ").ExportBlockedBy") + ")",
                   exported != null && exported.length > 0);

        // Timeline from the observer: export start (UpdateInProgress true) and end, jam clock updates, the press.
        String clockKey = "ScoreBoard.Game(" + game + ").Clock(Jam).Time";
        String busyKey = "ScoreBoard.Game(" + game + ").UpdateInProgress";
        String scoreKey = "ScoreBoard.Game(" + game + ").Team(2).Score";
        List<Long> exportsSent = outgoing(r.devices.get("sbo"), ".Export\"");
        List<Long> presses = outgoing(r.devices.get("sk2"), "Team(2).TripScore");
        assertTrue("setup: expected " + EXPORTS_AT.length + " exports and presses, saw " + exportsSent.size() + " and " +
                       presses.size(),
                   exportsSent.size() == EXPORTS_AT.length && presses.size() == EXPORTS_AT.length);
        int failures = 0;
        StringBuilder worst = new StringBuilder();
        long worstGap = -1;
        for (int i = 0; i < exportsSent.size(); i++) {
            long sent = exportsSent.get(i), press = presses.get(i);
            long exportEnd = -1, lastClock = -1, maxGap = 0, scoreSeen = -1;
            boolean busy = false;
            for (DeviceClient.Frame f : r.observer.frames()) {
                if (f.outgoing || f.nanos < sent) { continue; }
                if (f.text.contains(busyKey + "\":true")) { busy = true; }
                if (busy && exportEnd < 0 && f.text.contains(busyKey + "\":false")) { exportEnd = f.nanos; }
                if (f.text.contains(clockKey) && (exportEnd < 0 || f.nanos <= exportEnd)) {
                    maxGap = Math.max(maxGap, f.nanos - (lastClock < 0 ? sent : lastClock));
                    lastClock = f.nanos;
                }
                if (f.nanos >= press && scoreSeen < 0 && f.text.contains(scoreKey + "\":")) { scoreSeen = f.nanos; }
            }
            assertTrue("setup: export " + (i + 1) + ": end or the press not seen", exportEnd > 0 && scoreSeen > 0);
            long gapMs = ms(maxGap), pressMs = ms(scoreSeen - press), exportMs = ms(exportEnd - sent);
            System.out.println("F2: export " + (i + 1) + " took " + exportMs + " ms; longest jam clock gap " + gapMs +
                               " ms; press applied after " + pressMs + " ms");
            if (gapMs > MAX_CLOCK_GAP_MS || pressMs > MAX_PRESS_MS) { failures++; }
            if (gapMs > worstGap) {
                worstGap = gapMs;
                worst.setLength(0);
                worst.append("export ").append(i + 1).append(" froze the jam clock for ").append(gapMs)
                    .append(" ms and delayed a press by ").append(pressMs).append(" ms (export took ").append(exportMs)
                    .append(" ms)");
            }
        }
        if (failures > 0) {
            throw new AssertionError(RateOutcome.format(failures, exportsSent.size()) + "; first: F2: " + worst);
        }
    }

    private static ServerLauncher.Options seeded(File repo) {
        ServerLauncher.Options o = new ServerLauncher.Options();
        o.seed = new File(repo, SEED);
        return o;
    }

    private static List<Long> outgoing(DeviceClient d, String needle) {
        List<Long> at = new ArrayList<>();
        for (DeviceClient.Frame f : d.frames()) {
            if (f.outgoing && f.text.contains(needle)) { at.add(f.nanos); }
        }
        return at;
    }

    private static long ms(long nanos) { return TimeUnit.NANOSECONDS.toMillis(nanos); }
}
