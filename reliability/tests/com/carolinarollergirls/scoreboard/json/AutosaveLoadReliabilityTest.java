package com.carolinarollergirls.scoreboard.json;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Before;
import org.junit.Test;

import com.carolinarollergirls.scoreboard.core.ScoreBoardImpl;
import com.carolinarollergirls.scoreboard.reliability.support.CapturingLogger;
import com.carolinarollergirls.scoreboard.reliability.support.ProductFixture;

/**
 * F13: a rejected autosave leaks into the restored state.
 * <p>
 * When the newest autosave contains a value that cannot be parsed, the loader rejects it and falls back to the
 * previous file. But child entries from the rejected file were already created in the loader's first pass, and the
 * fallback never removes them, so state that exists only in the rejected file survives.
 * </p>
 * <p>
 * Expectations are provisional pending the program-level autosave-semantics decision.
 * </p>
 */
public class AutosaveLoadReliabilityTest {
    private File dir;
    private CapturingLogger log;

    /** Leaves file 0 holding marker B plus OnlyInNewest, and file 1 holding marker A only. */
    @Before
    public void setUp() throws Exception {
        log = new CapturingLogger();
        ScoreBoardImpl sb = ProductFixture.start().sb();
        dir = new File("autosave-f13");
        AutoSaveJSONState saver = new AutoSaveJSONState(sb.getJsm(), dir, false);
        sb.getSettings().set(AutosaveFiles.MARKER, "A");
        saver.run();
        sb.getSettings().set(AutosaveFiles.MARKER, "B");
        sb.getSettings().set(AutosaveFiles.ONLY_IN_NEWEST, "present");
        saver.run();
        log.clear();
    }

    @Test
    public void rejectedNewestAutosaveLeavesNoTrace() throws Exception {
        File newest = AutosaveFiles.newest(dir);
        String text = new String(Files.readAllBytes(newest.toPath()), StandardCharsets.UTF_8);
        Matcher m =
            Pattern.compile("(\"ScoreBoard\\.Game\\([^\"]*\\)\\.Clock\\(Jam\\)\\.MaximumTime\" : )\\d+").matcher(text);
        if (!m.find()) { fail("setup: no Clock(Jam).MaximumTime value found in " + newest); }
        text = text.substring(0, m.start()) + m.group(1) + "\"not-a-number\"" + text.substring(m.end());
        Files.write(newest.toPath(), text.getBytes(StandardCharsets.UTF_8));

        ScoreBoardImpl restored = AutosaveFiles.restore(dir);

        assertEquals("setup: loader did not fall back to the previous autosave", "A",
                     restored.getSettings().get(AutosaveFiles.MARKER));
        assertNull("F13: state from the rejected newest autosave leaked into the restored state",
                   restored.getSettings().get(AutosaveFiles.ONLY_IN_NEWEST));
    }

    /** Guard: a truncated newest file is rejected before anything is applied. Passes today; not in the manifest. */
    @Test
    public void truncatedNewestAutosaveFallsBackCleanly() throws Exception {
        File newest = AutosaveFiles.newest(dir);
        byte[] bytes = Files.readAllBytes(newest.toPath());
        byte[] half = new byte[bytes.length / 2];
        System.arraycopy(bytes, 0, half, 0, half.length);
        Files.write(newest.toPath(), half);

        ScoreBoardImpl restored = AutosaveFiles.restore(dir);

        assertEquals("F13 guard: truncated autosave did not fall back to the previous file", "A",
                     restored.getSettings().get(AutosaveFiles.MARKER));
        assertNull("F13 guard: state from a truncated autosave leaked into the restored state",
                   restored.getSettings().get(AutosaveFiles.ONLY_IN_NEWEST));
        assertTrue("F13 guard: the rejected file was not reported in the log",
                   log.contains("scoreboard-0-secs-ago.json"));
    }
}
