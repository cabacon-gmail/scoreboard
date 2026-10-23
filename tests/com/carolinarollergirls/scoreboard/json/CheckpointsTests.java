package com.carolinarollergirls.scoreboard.json;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class CheckpointsTests {
    private static final String GAME = "11111111-2222-3333-4444-555555555555";
    private static final String JAM = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String RULESET = "99999999-8888-7777-6666-555555555555";

    private Map<String, Object> state() {
        Map<String, Object> s = new HashMap<>();
        String g = "ScoreBoard.Game(" + GAME + ")";
        s.put(g + ".Id", GAME);
        s.put(g + ".Name", "2026-10-21 Home vs Away");
        s.put(g + ".Filename", "STATS-2026-10-21_Home_vs_Away");
        s.put(g + ".State", "Running");
        s.put(g + ".EventInfo(Date)", "2026-10-21");
        s.put(g + ".Team(1).Id", GAME + "_1");
        s.put(g + ".Team(1).FullName", "Home");
        s.put(g + ".Team(1).Score", 12);
        s.put(g + ".Team(2).FullName", "Away");
        s.put(g + ".Team(2).Score", 7);
        s.put(g + ".CurrentPeriodNumber", 2);
        s.put(g + ".Clock(Jam).Number", 14);
        s.put(g + ".Clock(Period).Time", 1122000L);
        s.put(g + ".InJam", false);
        s.put(g + ".Period(1).Jam(3).Id", JAM);
        s.put(g + ".Period(1).Jam(3).TeamJam(1).Fielding(Jammer).Id", JAM + "_1_Jammer");
        s.put(g + ".Team(2).Skater(x).Penalty(1).Jam", JAM);
        s.put(g + ".Ruleset", RULESET); // not part of the game: must not change
        s.put("ScoreBoard.Game(other).Id", "other");
        s.put("ScoreBoard.Version(release)", "test");
        return s;
    }

    @Test
    public void summaryHasWhatTheRecoveryScreenShows() {
        Map<String, Object> s = Checkpoints.summarize(state(), GAME);
        assertEquals("Home", s.get("team1"));
        assertEquals("Away", s.get("team2"));
        assertEquals(12L, s.get("score1"));
        assertEquals(7L, s.get("score2"));
        assertEquals(2L, s.get("period"));
        assertEquals(14L, s.get("jam"));
        assertEquals(1122000L, s.get("periodClock"));
        assertEquals("2026-10-21", s.get("date"));
        assertEquals("STATS-2026-10-21_Home_vs_Away", s.get("filename"));
    }

    @Test
    public void gamesAreFound() {
        assertEquals(new java.util.TreeSet<>(Arrays.asList(GAME, "other")),
                     new java.util.TreeSet<>(Checkpoints.games(state())));
    }

    @Test
    public void copyGameGivesTheGameAndItsElementsNewIds() {
        String newGame = "00000000-0000-0000-0000-000000000001";
        Map<String, Object> c = Checkpoints.copyGame(state(), GAME, newGame);
        String g = "ScoreBoard.Game(" + newGame + ")";
        assertEquals(newGame, c.get(g + ".Id"));
        assertEquals(newGame + "_1", c.get(g + ".Team(1).Id"));
        assertEquals(12, c.get(g + ".Team(1).Score"));
        // Only the copied game is included.
        for (String k : c.keySet()) { assertTrue(k, k.startsWith(g)); }
        // Element IDs inside the game are new, and references to them follow.
        String jam = (String) c.get(g + ".Period(1).Jam(3).Id");
        assertFalse(JAM.equals(jam));
        assertEquals(jam + "_1_Jammer", c.get(g + ".Period(1).Jam(3).TeamJam(1).Fielding(Jammer).Id"));
        assertEquals(jam, c.get(g + ".Team(2).Skater(x).Penalty(1).Jam"));
        // References to things outside the game are left alone.
        assertEquals(RULESET, c.get(g + ".Ruleset"));
    }
}
