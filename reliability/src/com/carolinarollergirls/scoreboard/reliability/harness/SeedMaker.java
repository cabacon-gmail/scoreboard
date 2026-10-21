package com.carolinarollergirls.scoreboard.reliability.harness;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.jr.ob.JSON;

/**
 * Generates the seed autosaves in {@code reliability/seeds/} by driving a real server the way the settings screens
 * do, then saving its state through {@code /SaveJSON} (without the device and client records).
 *
 * <pre>
 *   ant -f reliability/build.xml seeds
 * </pre>
 *
 * Seeds are committed. Regenerate them when the product's state format changes; game and skater IDs change each
 * time, which is fine because traces refer to the game as {@code $GAME}.
 */
public final class SeedMaker {
    private SeedMaker() {}

    public static void main(String[] args) throws Exception {
        File repo = new File(args.length > 0 ? args[0] : ".").getAbsoluteFile();
        File out = new File(repo, "reliability/seeds");
        out.mkdirs();
        File work = new File(repo, "reliability/build/seeds");
        deleteTree(work);
        twoTeamsShortPeriods(repo, new File(work, "two-teams-short-periods"),
                             new File(out, "two-teams-short-periods.json"), null);
        twoTeamsShortPeriods(repo, new File(work, "late-second-period"), new File(out, "late-second-period.json"),
                             new int[] { 2, 20 });
        System.exit(0);
    }

    /**
     * Two teams of 15 skaters (optionally started mid-game, see {@code advanceTo}) and a test ruleset (2-minute periods, 30-second jams, 30-second lineups, 1-minute
     * intermission), with a new game between them as the current game.
     */
    static void twoTeamsShortPeriods(File repo, File dir, File target, int[] advanceTo) throws Exception {
        try (ServerLauncher server = ServerLauncher.start(repo, dir);
             DeviceClient d = new DeviceClient("seed", server.port(), "/settings/teams", "seed-maker")) {
            d.connect(server.port());
            d.register("ScoreBoard.CurrentGame", "ScoreBoard.PreparedTeam", "ScoreBoard.Rulesets.Ruleset");
            String[][] teams = { { "Seed-Home", "Home Testers", "Reliability League" },
                                 { "Seed-Away", "Away Testers", "Harness Roller Derby" } };
            for (String[] t : teams) {
                String p = "ScoreBoard.PreparedTeam(" + t[0] + ")";
                d.set(p + ".Id", t[0]);
                d.set(p + ".LeagueName", t[2]);
                d.set(p + ".TeamName", t[1]);
                for (int i = 1; i <= 15; i++) {
                    String sk = p + ".Skater(" + t[0].toLowerCase() + "-" + i + ")";
                    d.set(sk + ".RosterNumber", String.valueOf(i * 7 % 100));
                    d.set(sk + ".Name", t[1].split(" ")[0] + " Skater " + i);
                    d.set(sk + ".Pronouns", "");
                    d.set(sk + ".Flags", "");
                }
            }
            String rs = "ScoreBoard.Rulesets.Ruleset(ShortPeriods)";
            d.set(rs + ".Id", "ShortPeriods");
            d.set(rs + ".Name", "Reliability: short periods");
            d.set(rs + ".Parent", "WFTDARuleset");
            d.set(rs + ".Rule(Period.Duration)", "2:00");
            d.set(rs + ".Rule(Jam.Duration)", "0:30");
            d.set(rs + ".Rule(Lineup.Duration)", "0:30");
            d.set(rs + ".Rule(Intermission.Durations)", "1:00,60:00");
            if (!d.awaitValue(rs + ".Rule(Period.Duration)", "2:00", 10_000)) {
                throw new IOException("ruleset was not created");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("Team1", "Seed-Home");
            data.put("Team2", "Seed-Away");
            data.put("Ruleset", "ShortPeriods");
            data.put("Advance", advanceTo != null);
            if (advanceTo != null) {
                // "Start mid-game" on the SBO: the game begins in period advanceTo[0], before jam advanceTo[1], so
                // the statsbook has a realistic number of jam rows to fill.
                data.put("Period", advanceTo[0]);
                data.put("Jam", advanceTo[1]);
                data.put("PeriodClock", "60000");
                data.put("Points1", 87);
                data.put("Points2", 95);
                data.put("TO1", 1);
                data.put("TO2", 2);
                data.put("OR1", 0);
                data.put("OR2", 1);
            }
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("action", "StartNewGame");
            msg.put("data", data);
            d.send(Messages.write(msg));
            if (!d.awaitValue("ScoreBoard.CurrentGame.Team(1).PreparedTeam", "Seed-Home", 10_000) ||
                !d.awaitValue("ScoreBoard.CurrentGame.Clock(Period).MaximumTime", 120000, 10_000)) {
                throw new IOException("the new game did not become current with the seed ruleset: team " +
                                      d.get("ScoreBoard.CurrentGame.Team(1).PreparedTeam") + ", period " +
                                      d.get("ScoreBoard.CurrentGame.Clock(Period).MaximumTime"));
            }
            Thread.sleep(1000);
            DeviceClient.HttpResult r = d.http("GET", "/SaveJSON", null, null);
            if (r.status != 200) { throw new IOException("/SaveJSON answered " + r); }
            @SuppressWarnings("unchecked")
            Map<String, Object> state = (Map<String, Object>) JSON.std.mapFrom(r.body).get("state");
            TreeMap<String, Object> kept = new TreeMap<>();
            for (Map.Entry<String, Object> e : state.entrySet()) {
                if (!e.getKey().startsWith("ScoreBoard.Clients.")) { kept.put(e.getKey(), e.getValue()); }
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("state", kept);
            JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT).write(root, target);
            System.out.println("wrote " + target + " (" + kept.size() + " keys)");
        }
    }

    static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) { deleteTree(c); }
        }
        f.delete();
    }
}
