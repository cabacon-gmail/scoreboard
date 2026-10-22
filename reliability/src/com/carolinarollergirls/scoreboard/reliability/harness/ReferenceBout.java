package com.carolinarollergirls.scoreboard.reliability.harness;

import java.io.File;

/**
 * The reference bout (Phase 2 design, section 7): a whole two-period game for a crew, on the short-periods seed, in
 * about 5 1/2 minutes. Played by the baseline, F12 (kill -9) and F15 (stalled client) tests.
 *
 * <pre>
 *   P1  jam 1 (3 s)  official timeout  jam 2 (58 s)  jam 3 (113 s; the period clock runs out during it)
 *   intermission (1 min)
 *   P2  jam 4 (200 s)  jam 5 (255 s)  jam 6 (310 s; the period clock runs out during it)  end of game (335 s)
 * </pre>
 *
 * In every jam, SK1 enters 4 points (four +1 presses) and SK2 enters 3; after each jam the observer must show the
 * running totals. The JT starts and stops jams, the SBO calls the timeout, and everyone else watches.
 */
public final class ReferenceBout {
    public static final String SEED = "reliability/seeds/two-teams-short-periods.json";
    public static final int JAMS = 6;
    public static final int TEAM1_POINTS_PER_JAM = 4;
    public static final int TEAM2_POINTS_PER_JAM = 3;
    /** When the last event happens, in ms from the start of the trace. */
    public static final long END_MS = 340_000L;

    private ReferenceBout() {}

    /** The seed file of the reference bout. */
    public static File seed(File repoDir) { return new File(repoDir, SEED); }

    /** Server options with the reference bout's seed installed. */
    public static ServerLauncher.Options options(File repoDir) {
        ServerLauncher.Options o = new ServerLauncher.Options();
        o.seed = seed(repoDir);
        return o;
    }

    /** The bout as a builder, so tests can add their own events (a kill, a stall) before {@code build()}. */
    public static Bout bout(CrewProfile crew) {
        long[] jamStarts = { 3_000, 58_000, 113_000, 200_000, 255_000, 310_000 };
        // clang-format off
        Bout b = Bout.create("Reference bout: " + crew.name() + " crew, two short periods, " + JAMS + " jams")
            .seed(SEED)
            .crew(crew)
            .at(0).connectCrew(100);
        // clang-format on
        int t1 = 0, t2 = 0;
        for (int j = 0; j < JAMS; j++) {
            long s = jamStarts[j];
            b.at(s).mark("jam " + (j + 1)).on("jt").startJam();
            b.after(200).expect(3000, "ScoreBoard.Game($GAME).InJam", true);
            if (j == 3) { b.expect(1000, "ScoreBoard.Game($GAME).CurrentPeriodNumber", 2); }
            b.at(s + 10_000);
            for (int k = 0; k < TEAM1_POINTS_PER_JAM; k++) { b.after(k == 0 ? 0 : 500).on("sk1").tripScoreUp(1); }
            b.at(s + 15_000);
            for (int k = 0; k < TEAM2_POINTS_PER_JAM; k++) { b.after(k == 0 ? 0 : 500).on("sk2").tripScoreUp(2); }
            t1 += TEAM1_POINTS_PER_JAM;
            t2 += TEAM2_POINTS_PER_JAM;
            b.at(s + 25_000).on("jt").stopJam();
            b.after(200)
                .expect(3000, "ScoreBoard.Game($GAME).InJam", false)
                .expect(3000, "ScoreBoard.Game($GAME).Team(1).Score", t1)
                .expect(3000, "ScoreBoard.Game($GAME).Team(2).Score", t2);
            if (j == 0) {
                // An official timeout after jam 1, ended by the SBO before jam 2.
                b.at(s + 27_000).mark("official timeout").on("sbo").timeout();
                b.after(1_500).officialTimeout();
                b.after(200).expect(2000, "ScoreBoard.Game($GAME).Clock(Timeout).Running", true);
                b.at(s + 37_000).on("sbo").stopJam();
                // The timeout clock keeps running after a timeout by default; the lineup clock shows it ended.
                b.after(200).expect(2000, "ScoreBoard.Game($GAME).Clock(Lineup).Running", true);
            }
        }
        b.at(END_MS).mark("end of the reference bout");
        return b;
    }

    public static Trace build(CrewProfile crew) { return bout(crew).build(); }
}
