package com.carolinarollergirls.scoreboard.reliability.recovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.jr.ob.JSON;

import com.carolinarollergirls.scoreboard.reliability.harness.DeviceClient;
import com.carolinarollergirls.scoreboard.reliability.harness.Messages;
import com.carolinarollergirls.scoreboard.reliability.harness.ReferenceBout;
import com.carolinarollergirls.scoreboard.reliability.harness.ServerLauncher;

/**
 * F19: no way back past the last minute.
 * <p>
 * When the game state goes wrong (an operator mistake, or a bug such as F6 or F9), the autosave keeps the wrong
 * state, and the only older copies are 60 s of autosaves and the per-game file of the last jam. Crews end up starting
 * a new game with just the score and the clock, losing everything else. These tests describe the fix decided on Oct
 * 21, 2026 (plan: docs/recovery-checkpoints.md): restore points at every jam, timeout and period boundary, kept for
 * the whole game, listed and restored through {@code /Recovery}, only from the scoreboard's own computer.
 * </p>
 */
public class RecoveryReliabilityTest {
    private ServerLauncher server;
    private DeviceClient sbo;
    private String game;
    private String g;

    @Before
    public void start() throws Exception {
        File repo = ServerLauncher.repoDirFromRunner();
        server = ServerLauncher.start(repo, new File("server-" + System.nanoTime()), ReferenceBout.options(repo));
        sbo = new DeviceClient("sbo", server.port(), "/nso/sbo", "test");
        sbo.connect(server.port());
        sbo.register("ScoreBoard");
        assertTrue("setup: no current game",
                   sbo.await(s -> s.get("ScoreBoard.CurrentGame.Game") instanceof String, 10_000));
        game = (String) sbo.get("ScoreBoard.CurrentGame.Game");
        g = "ScoreBoard.Game(" + game + ")";
        Thread.sleep(1500); // the quick-press guard
    }

    @After
    public void stop() {
        if (sbo != null) { sbo.close(); }
        if (server != null) { server.close(); }
    }

    /** Plays one jam: start, {@code home} and {@code away} points, stop. */
    private void jam(int home, int away) throws Exception {
        sbo.set(g + ".StartJam", true);
        assertTrue("setup: jam did not start", sbo.awaitValue(g + ".InJam", true, 3000));
        for (int i = 0; i < home; i++) { sbo.send(Messages.set(g + ".Team(1).TripScore", 1, "change")); }
        for (int i = 0; i < away; i++) { sbo.send(Messages.set(g + ".Team(2).TripScore", 1, "change")); }
        Thread.sleep(1200);
        sbo.set(g + ".StopJam", true);
        assertTrue("setup: jam did not stop", sbo.awaitValue(g + ".InJam", false, 3000));
        Thread.sleep(1200); // past the quick-press guard, and lets the restore point be written
    }

    private void penalty(int team, String code) throws Exception {
        String skater = null;
        for (String k : sbo.state().keySet()) {
            if (k.startsWith(g + ".Team(" + team + ").Skater(") && k.endsWith(".RosterNumber")) {
                skater = k.substring(0, k.length() - ".RosterNumber".length());
                break;
            }
        }
        int n = 1;
        while (sbo.get(skater + ".Penalty(" + n + ").Code") != null) { n++; }
        sbo.set(skater + ".Penalty(" + n + ").Code", code);
        Thread.sleep(300);
    }

    private long score(String gameKey, int team) {
        Object v = sbo.get(gameKey + ".Team(" + team + ").Score");
        return v instanceof Number ? ((Number) v).longValue() : -1;
    }

    private int penalties(String gameKey) {
        int n = 0;
        for (Map.Entry<String, Object> e : sbo.state().entrySet()) {
            if (e.getKey().startsWith(gameKey + ".Team(") && e.getKey().contains(".Penalty(") &&
                e.getKey().endsWith(".Code") && !"".equals(e.getValue())) {
                n++;
            }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> list() throws IOException {
        DeviceClient.HttpResult r = sbo.http("GET", "/Recovery/list", null, null);
        assertEquals("F19: the Recovery list is not available (" + r + ")", 200, r.status);
        return JSON.std.mapFrom(r.body);
    }

    /** The restore points of the game with this statsbook file name. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> points(String filename) throws IOException {
        for (Object o : (List<Object>) list().get("games")) {
            Map<String, Object> gm = (Map<String, Object>) o;
            if (filename.equals(gm.get("filename"))) { return (List<Map<String, Object>>) gm.get("points"); }
        }
        return new ArrayList<>();
    }

    private DeviceClient.HttpResult restore(Map<String, Object> point) throws IOException {
        String q = "?path=" + URLEncoder.encode(String.valueOf(point.get("path")), StandardCharsets.UTF_8) +
                   "&game=" + URLEncoder.encode(String.valueOf(point.get("game")), StandardCharsets.UTF_8);
        return sbo.http("POST", "/Recovery/restore" + q, null, null);
    }

    private static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : -1; }

    @Test
    public void everyJamAndTimeoutBoundaryIsARestorePoint() throws Exception {
        jam(3, 1);
        sbo.set(g + ".Timeout", true);
        Thread.sleep(1500);
        sbo.set(g + ".OfficialTimeout", true);
        Thread.sleep(1500);
        sbo.set(g + ".StopJam", true); // ends the timeout
        Thread.sleep(1500);
        jam(2, 4);
        String filename = (String) sbo.get(g + ".Filename");
        List<Map<String, Object>> points = points(filename);
        int jamEnds = 0, timeoutEnds = 0;
        for (Map<String, Object> p : points) {
            if (!"checkpoint".equals(p.get("source"))) { continue; }
            assertTrue("F19: restore point file missing: " + p.get("path"),
                       new File(server.dir(), String.valueOf(p.get("path"))).isFile());
            if ("jam-end".equals(p.get("reason"))) { jamEnds++; }
            if ("timeout-end".equals(p.get("reason"))) { timeoutEnds++; }
        }
        assertTrue("F19: expected a restore point at both jam ends and the timeout end, got " + jamEnds +
                       " jam ends and " + timeoutEnds + " timeout ends",
                   jamEnds == 2 && timeoutEnds == 1);
    }

    @Test
    public void listShowsWhatPeopleRecogniseAGameBy() throws Exception {
        jam(3, 1);
        String filename = (String) sbo.get(g + ".Filename");
        Map<String, Object> p = null;
        for (Map<String, Object> pt : points(filename)) {
            if ("jam-end".equals(pt.get("reason"))) { p = pt; }
        }
        assertNotNull("F19: no restore point listed for the jam just played", p);
        assertEquals("score in the list", 3L, num(p.get("score1")));
        assertEquals("score in the list", 1L, num(p.get("score2")));
        assertEquals("period in the list", 1L, num(p.get("period")));
        assertEquals("jam in the list", 1L, num(p.get("jam")));
        // The period clock keeps running in the lineup after the restore point, so allow a few seconds.
        long live = num(sbo.get(g + ".Clock(Period).Time"));
        assertTrue("period clock in the list: " + p.get("periodClock") + " vs now " + live,
                   num(p.get("periodClock")) >= live && num(p.get("periodClock")) - live <= 5000);
        assertEquals("date in the list", sbo.get(g + ".EventInfo(Date)"), p.get("date"));
        assertTrue("team names in the list: " + p.get("team1") + " / " + p.get("team2"),
                   String.valueOf(p.get("team1")).contains("Home Testers") &&
                       String.valueOf(p.get("team2")).contains("Away Testers"));
    }

    @Test
    public void restoringTheEndOfJamTwoKeepsEverythingUpToThenAndNothingAfter() throws Exception {
        jam(3, 1);
        jam(2, 2);
        String filename = (String) sbo.get(g + ".Filename");
        String name = (String) sbo.get(g + ".Name");
        jam(4, 0);
        penalty(2, "X");
        jam(1, 5);
        long[] finalScore = {score(g, 1), score(g, 2)};
        Map<String, Object> endOfJam2 = null;
        for (Map<String, Object> p : points(filename)) {
            if ("jam-end".equals(p.get("reason")) && num(p.get("jam")) == 2) { endOfJam2 = p; }
        }
        assertNotNull("F19: no restore point for the end of jam 2", endOfJam2);
        DeviceClient.HttpResult r = restore(endOfJam2);
        assertEquals("F19: restore failed: " + r, 200, r.status);

        assertTrue("F19: the restored game did not become current",
                   sbo.await(s -> !game.equals(s.get("ScoreBoard.CurrentGame.Game")), 5000));
        String restored = "ScoreBoard.Game(" + sbo.get("ScoreBoard.CurrentGame.Game") + ")";
        Thread.sleep(500);
        assertEquals("F19: restored score (team 1)", 5L, score(restored, 1));
        assertEquals("F19: restored score (team 2)", 3L, score(restored, 2));
        assertEquals("F19: the restored game has a penalty from after the restore point", 0, penalties(restored));
        assertEquals("F19: the restored game should keep the statsbook file name", filename,
                     sbo.get(restored + ".Filename"));
        assertEquals("F19: the restored game should keep the name", name, sbo.get(restored + ".Name"));
        // The game it replaced is kept, unchanged apart from its name and file name.
        assertEquals("F19: the replaced game's score changed", finalScore[0], score(g, 1));
        assertEquals("F19: the replaced game's score changed", finalScore[1], score(g, 2));
        assertEquals("F19: the replaced game lost its penalty", 1, penalties(g));
        assertTrue("F19: the replaced game should be marked abandoned: " + sbo.get(g + ".Name"),
                   String.valueOf(sbo.get(g + ".Name")).contains("(abandoned"));
        assertNotEquals("F19: the replaced game must not keep the statsbook file name", filename,
                        sbo.get(g + ".Filename"));
    }

    @Test
    public void restoreFromTheLastMinutesAutosave() throws Exception {
        jam(3, 2);
        Thread.sleep(11_000); // one autosave
        jam(1, 1);
        Map<String, Object> autosave = null;
        for (Map<String, Object> p : points((String) sbo.get(g + ".Filename"))) {
            if ("autosave".equals(p.get("source")) && num(p.get("score1")) == 3) { autosave = p; }
        }
        assertNotNull("F19: no autosave from after jam 1 listed", autosave);
        DeviceClient.HttpResult r = restore(autosave);
        assertEquals("F19: restore from autosave failed: " + r, 200, r.status);
        assertTrue("F19: the restored game did not become current",
                   sbo.await(s -> !game.equals(s.get("ScoreBoard.CurrentGame.Game")), 5000));
        String restored = "ScoreBoard.Game(" + sbo.get("ScoreBoard.CurrentGame.Game") + ")";
        Thread.sleep(500);
        assertEquals("F19: score after restoring the autosave", 3L, score(restored, 1));
    }

    @Test
    public void restoreFromABackupMadeAtStartUp() throws Exception {
        jam(2, 1);
        Thread.sleep(11_000); // one autosave
        sbo.close();
        server.kill(ServerLauncher.Signal.TERM);
        server.restart(); // the start-up copies the autosaves to a backup
        sbo = new DeviceClient("sbo2", server.port(), "/nso/sbo", "test");
        sbo.connect(server.port());
        sbo.register("ScoreBoard");
        assertTrue("setup: game not restored after restart",
                   sbo.await(s -> s.containsKey(g + ".Team(1).Score"), 10_000));
        Thread.sleep(1500);
        jam(4, 4);
        Map<String, Object> backup = null;
        for (Map<String, Object> p : points((String) sbo.get(g + ".Filename"))) {
            if ("backup".equals(p.get("source"))) { backup = p; }
        }
        assertNotNull("F19: no start-up backup listed", backup);
        DeviceClient.HttpResult r = restore(backup);
        assertEquals("F19: restore from backup failed: " + r, 200, r.status);
        assertTrue("F19: the restored game did not become current",
                   sbo.await(s -> !game.equals(s.get("ScoreBoard.CurrentGame.Game")), 5000));
        String restored = "ScoreBoard.Game(" + sbo.get("ScoreBoard.CurrentGame.Game") + ")";
        Thread.sleep(500);
        assertEquals("F19: score after restoring the backup", 2L, score(restored, 1));
    }

    @Test
    public void restoringIsOnlyPossibleOnTheScoreboardsOwnComputer() throws Exception {
        jam(1, 0);
        Map<String, Object> p = points((String) sbo.get(g + ".Filename")).get(0);
        // A device behind the recorder (or any proxy) is not the scoreboard's own computer.
        String q = "?path=" + URLEncoder.encode(String.valueOf(p.get("path")), StandardCharsets.UTF_8) +
                   "&game=" + URLEncoder.encode(String.valueOf(p.get("game")), StandardCharsets.UTF_8);
        java.net.http.HttpClient c = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpResponse<String> r =
            c.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(server.baseUrl() + "/Recovery/restore" + q))
                       .header("X-CRG-Forwarded-For", "192.0.2.7")
                       .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                       .build(),
                   java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals("F19: a restore from another device must be refused (" + r.body() + ")", 403, r.statusCode());
        assertEquals("the current game must not change", game, sbo.get("ScoreBoard.CurrentGame.Game"));
    }

    @Test
    public void restoringThroughTheRecorderIsRefused() throws Exception {
        jam(1, 0);
        Map<String, Object> p = points((String) sbo.get(g + ".Filename")).get(0);
        com.carolinarollergirls.scoreboard.reliability.capture.RecordingProxy proxy =
            new com.carolinarollergirls.scoreboard.reliability.capture.RecordingProxy(
                "127.0.0.1", 0, "127.0.0.1", server.port(), new File("capture-" + System.nanoTime()));
        proxy.start();
        try (DeviceClient viaProxy = new DeviceClient("tablet", proxy.port(), "/settings/recovery/", "test")) {
            viaProxy.connect(proxy.port()); // a real page opens its WebSocket first
            String q = "?path=" + URLEncoder.encode(String.valueOf(p.get("path")), StandardCharsets.UTF_8) +
                       "&game=" + URLEncoder.encode(String.valueOf(p.get("game")), StandardCharsets.UTF_8);
            DeviceClient.HttpResult r = viaProxy.http("POST", "/Recovery/restore" + q, null, null);
            assertEquals("F19: a restore through the recorder must be refused (" + r + ")", 403, r.status);
            DeviceClient.HttpResult list = viaProxy.http("GET", "/Recovery/list", null, null);
            assertTrue("the list says the device can't restore: " + list.body, list.body.contains("\"local\":false"));
        } finally { proxy.stop(); }
        assertEquals("the current game must not change", game, sbo.get("ScoreBoard.CurrentGame.Game"));
    }

    /**
     * Guard (passes on dev): a crash and restart already brings the whole game back, up to the last 10 s autosave.
     * Recovery work must not break this.
     */
    @Test
    public void aCrashAndRestartBringsTheGameBack() throws Exception {
        jam(3, 1);
        penalty(2, "X");
        sbo.set(g + ".StartJam", true); // a jam is running when the laptop crashes
        assertTrue("setup: jam did not start", sbo.awaitValue(g + ".InJam", true, 3000));
        Thread.sleep(12_000); // one autosave into the jam
        Map<String, Object> before = sbo.state();
        sbo.close();
        server.kill(ServerLauncher.Signal.KILL);
        Thread.sleep(3000);
        server.restart();
        sbo = new DeviceClient("after", server.port(), "/nso/sbo", "test");
        sbo.connect(server.port());
        sbo.register("ScoreBoard");
        assertTrue("restart: the game did not come back", sbo.await(s -> s.containsKey(g + ".Team(1).Score"), 10_000));
        Thread.sleep(500);
        for (String k : new String[] {".Team(1).Score", ".Team(2).Score", ".CurrentPeriodNumber", ".Clock(Jam).Number",
                                      ".InJam"}) {
            assertTrue("restart: " + k + " was " + before.get(g + k) + ", now " + sbo.get(g + k),
                       Messages.jsonEquals(before.get(g + k), sbo.get(g + k)));
        }
        assertEquals("restart: penalties", 1, penalties(g));
        long jamBefore = num(before.get(g + ".Clock(Jam).Time")), jamAfter = num(sbo.get(g + ".Clock(Jam).Time"));
        assertTrue("restart: jam clock was " + jamBefore + ", now " + jamAfter + " (at most 10 s apart)",
                   Math.abs(jamBefore - jamAfter) <= 10_000);
    }
}
