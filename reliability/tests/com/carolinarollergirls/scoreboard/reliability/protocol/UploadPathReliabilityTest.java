package com.carolinarollergirls.scoreboard.reliability.protocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

import com.carolinarollergirls.scoreboard.reliability.harness.Bout;
import com.carolinarollergirls.scoreboard.reliability.harness.Replayer;
import com.carolinarollergirls.scoreboard.reliability.harness.ServerLauncher;
import com.carolinarollergirls.scoreboard.reliability.harness.Trace;
import com.carolinarollergirls.scoreboard.reliability.harness.TraceWriter;

/**
 * F17: media upload path traversal.
 * <p>
 * {@code MediaImpl.validFileName} uses {@code String.matches}, which must match the <em>whole</em> name, so a name
 * containing {@code ../} passes, and {@code MediaServlet.createFile} writes wherever the name points. Any device with
 * write access, on the venue network, can then overwrite files outside the media folder, including autosaves. The
 * same check guards entries inside an uploaded zip.
 * </p>
 */
public class UploadPathReliabilityTest {
    private static final String TARGET = "../../../config/autosave/reliability-f17";

    @Test
    public void uploadedFileNameCannotEscapeTheMediaFolder() throws Exception {
        assertNothingEscapes("plain", Bout.textFile("file", TARGET + "-plain.json", "{\"planted\":true}"),
                             "reliability-f17-plain.json");
    }

    @Test
    public void zipEntryNameCannotEscapeTheMediaFolder() throws Exception {
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zip)) {
            z.putNextEntry(new ZipEntry(TARGET + "-zip.json"));
            z.write("{\"planted\":true}".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        assertNothingEscapes("zip", Bout.binaryFile("file", "logos.zip", zip.toByteArray()),
                             "reliability-f17-zip.json");
    }

    private void assertNothingEscapes(String kind, Map<String, Object> file, String plantedName) throws Exception {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("media", "images");
        form.put("type", "teamlogo");
        // clang-format off
        Trace trace = Bout.create("F17 (" + kind + "): upload a team logo whose name climbs out of the media folder")
            .device("laptop", "/settings/")
            // The server only knows a device once its page has opened the WebSocket.
            .at(0).on("laptop").connect().register("ScoreBoard.Media")
            .after(500).http("POST", "/Media/upload", form,
                             Collections.singletonList(Bout.textFile("file", "control.png", "not really a png")))
            .after(200).http("POST", "/Media/upload", form, Collections.singletonList(file))
            .build();
        // clang-format on
        TraceWriter.write(trace, new File("f17-" + kind + ".crgtrace.jsonl"));

        File dir = new File("server-" + kind);
        Replayer.Result r;
        try (ServerLauncher server = ServerLauncher.start(ServerLauncher.repoDirFromRunner(), dir)) {
            r = Replayer.replay(trace, server);
        }
        assertTrue("setup: trace did not replay cleanly: " + r.failures, r.ok());
        assertTrue("setup: an ordinary upload failed, so the test proves nothing: " + r.http.get(0).response,
                   r.http.get(0).response.status == 200 && new File(dir, "html/images/teamlogo/control.png").isFile());
        File media = new File(dir, "html/images/teamlogo").getCanonicalFile();
        File planted = new File(dir, "config/autosave/" + plantedName);
        // Anything new outside the media folder counts, wherever the name resolved to.
        boolean escaped = planted.exists();
        for (File f : listAll(dir)) {
            if (f.getName().equals(plantedName) && !f.getCanonicalPath().startsWith(media.getPath())) {
                escaped = true;
                planted = f;
            }
        }
        assertFalse("F17: an uploaded " + kind + " file was written outside the media folder, to " + planted +
                        " (server answered " + r.http.get(1).response + ")",
                    escaped);
    }

    private static java.util.List<File> listAll(File dir) {
        java.util.List<File> out = new java.util.ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) { return out; }
        for (File c : children) {
            out.add(c);
            if (c.isDirectory()) { out.addAll(listAll(c)); }
        }
        return out;
    }

}
