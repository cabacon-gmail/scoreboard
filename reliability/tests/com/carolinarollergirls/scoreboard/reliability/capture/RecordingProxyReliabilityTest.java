package com.carolinarollergirls.scoreboard.reliability.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.carolinarollergirls.scoreboard.reliability.harness.DeviceClient;
import com.carolinarollergirls.scoreboard.reliability.harness.Replayer;
import com.carolinarollergirls.scoreboard.reliability.harness.ServerLauncher;
import com.carolinarollergirls.scoreboard.reliability.harness.Trace;
import com.carolinarollergirls.scoreboard.reliability.harness.TraceReader;

/**
 * The recording proxy end to end: a small game played through the proxy is captured as a trace, and replaying that
 * trace on a fresh server seeded from the capture ends in the same state.
 */
public class RecordingProxyReliabilityTest {
    @Test
    public void capturedGameReplaysToTheSameState() throws Exception {
        File repo = ServerLauncher.repoDirFromRunner();
        File capture = new File("capture");
        Map<String, Object> original;
        String game;
        try (ServerLauncher crg = ServerLauncher.start(repo, new File("server-original"))) {
            RecordingProxy proxy = new RecordingProxy("127.0.0.1", 0, "127.0.0.1", crg.port(), capture);
            proxy.start();
            int port = proxy.port();
            try (DeviceClient sbo = new DeviceClient("sbo", port, "/nso/sbo", "test");
                 DeviceClient sk = new DeviceClient("sk", port, "/nso/sk?team=1", "test")) {
                sbo.connect(port);
                sbo.register("ScoreBoard.CurrentGame");
                assertTrue("setup: no current game through the proxy",
                           sbo.await(s -> s.get("ScoreBoard.CurrentGame.Game") instanceof String, 10_000));
                game = (String) sbo.get("ScoreBoard.CurrentGame.Game");
                String g = "ScoreBoard.Game(" + game + ")";
                sk.connect(port);
                sk.register(g + ".Team(1)");
                Thread.sleep(1500); // the quick-press guard
                sbo.set(g + ".StartJam", true);
                assertTrue("setup: jam did not start", sbo.awaitValue("ScoreBoard.CurrentGame.InJam", true, 3000));
                for (int i = 1; i <= 3; i++) {
                    sk.send(com.carolinarollergirls.scoreboard.reliability.harness.Messages.set(
                        g + ".Team(1).TripScore", 1, "change"));
                    assertTrue("setup: point " + i + " not applied", sk.awaitValue(g + ".Team(1).Score", i, 3000));
                }
                // The SK's Wi-Fi drops and it reconnects: it must stay one trace device.
                sk.disconnect();
                Thread.sleep(300);
                sk.connect(port);
                sk.register(g + ".Team(1)");
                assertTrue("setup: SK did not get its state back after reconnecting",
                           sk.await(s -> s.containsKey(g + ".Team(1).Score"), 3000));
                Map<String, String> form = new LinkedHashMap<>();
                form.put("media", "images");
                form.put("type", "teamlogo");
                DeviceClient.HttpResult up = sbo.http(
                    "POST", "/Media/upload", form,
                    Collections.singletonList(new DeviceClient.Upload(
                        "file", "captured.png", "not really a png".getBytes(StandardCharsets.UTF_8))));
                assertEquals("setup: upload through the proxy failed: " + up, 200, up.status);
                DeviceClient.HttpResult urls = sbo.http("GET", "/urls", null, null);
                assertTrue("/urls should list the proxy's port, got " + urls.body,
                           urls.body.contains(":" + port + "/"));
                Thread.sleep(1000);
                sbo.set(g + ".StopJam", true);
                assertTrue("setup: jam did not stop", sbo.awaitValue("ScoreBoard.CurrentGame.InJam", false, 3000));
                Thread.sleep(500);
            }
            Thread.sleep(500);
            proxy.stop();
            original = snapshot(crg, game);
        }

        Trace trace = TraceReader.read(new File(capture, "trace.crgtrace.jsonl"));
        assertEquals("captured devices (a reconnect continues the same device): " + trace.devices().keySet(), 2,
                     trace.devices().size());
        assertEquals("redacted header", Boolean.TRUE, trace.header().get("redacted"));
        String seedText = new String(java.nio.file.Files.readAllBytes(new File(capture, "seed.json").toPath()),
                                     StandardCharsets.UTF_8);
        String traceText = new String(
            java.nio.file.Files.readAllBytes(new File(capture, "trace.crgtrace.jsonl").toPath()), StandardCharsets.UTF_8);
        for (String name : new String[] { "\"Black\"", "\"White\"", "(Black)", "(White)" }) {
            assertTrue("the seed still contains the team name " + name, !seedText.contains(name));
            assertTrue("the trace still contains the team name " + name, !traceText.contains(name));
        }
        long sends = trace.events().stream().filter(e -> "send".equals(e.ev)).count();
        long https = trace.events().stream().filter(e -> "http".equals(e.ev)).count();
        assertTrue("expected the WebSocket messages in the trace, got " + sends, sends >= 7);
        assertEquals("expected exactly the upload as an http event", 1, https);
        assertTrue("the trace must not contain the session cookie",
                   !new String(java.nio.file.Files.readAllBytes(new File(capture, "trace.crgtrace.jsonl").toPath()),
                               StandardCharsets.UTF_8)
                        .contains("CRG_SCOREBOARD"));

        ServerLauncher.Options seeded = new ServerLauncher.Options();
        seeded.seed = new File(capture, "seed.json");
        Replayer.Options ro = new Replayer.Options();
        ro.baseDir = capture;
        File replayDir = new File("server-replay");
        try (ServerLauncher crg = ServerLauncher.start(repo, replayDir, seeded)) {
            Replayer.Result r = Replayer.replay(trace, crg, ro);
            assertTrue("replay failed: " + r.failures, r.ok());
            assertEquals("replayed upload status", 200, r.http.get(0).response.status);
            Thread.sleep(500);
            assertEquals("replayed state differs from the original", original, snapshot(crg, game));
            assertTrue("the uploaded file was not replayed",
                       new File(replayDir, "html/images/teamlogo/captured.png").isFile());
        }
    }

    /** The values a crew would compare: score, jam, period and the jam clock's running state. */
    private static Map<String, Object> snapshot(ServerLauncher crg, String game) throws Exception {
        try (DeviceClient d = new DeviceClient("check", crg.port(), "/", "test")) {
            d.connect(crg.port());
            String g = "ScoreBoard.Game(" + game + ")";
            d.register(g);
            assertTrue("setup: no state for game " + game, d.await(s -> s.containsKey(g + ".Team(1).Score"), 10_000));
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : new String[] { ".Team(1).Score", ".Team(2).Score", ".InJam", ".CurrentPeriodNumber",
                                           ".Clock(Jam).Number", ".Clock(Jam).Running" }) {
                m.put(k, d.get(g + k));
            }
            return m;
        }
    }
}
