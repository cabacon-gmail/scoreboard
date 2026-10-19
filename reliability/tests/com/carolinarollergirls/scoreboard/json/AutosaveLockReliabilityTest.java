package com.carolinarollergirls.scoreboard.json;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileInputStream;

import org.junit.Test;

import com.carolinarollergirls.scoreboard.core.ScoreBoardImpl;
import com.carolinarollergirls.scoreboard.reliability.support.Platform;
import com.carolinarollergirls.scoreboard.reliability.support.ProductFixture;

/**
 * F12: while another process holds the newest autosave file open, autosaving silently stops.
 * <p>
 * {@code AutoSaveJSONState.writeAutoSave} renames file 0 to file 1 before moving the new temp file onto file 0, and
 * ignores the result of every rename. On Windows an open file cannot be renamed, so both renames fail, the temp file
 * is deleted, and the latest state is never written. Antivirus, backup and sync tools hold files open like this.
 * </p>
 * <p>
 * Expectation is provisional pending the program-level autosave-semantics decision.
 * </p>
 */
public class AutosaveLockReliabilityTest {
    @Test
    @SuppressWarnings("try") // the stream is held open only for its lock
    public void latestChangeSurvivesWhenNewestAutosaveIsLocked() throws Exception {
        Platform.assumeWindows("F12's file lock only blocks renames on Windows");

        ScoreBoardImpl sb = ProductFixture.start().sb();
        File dir = new File("autosave-f12");
        AutoSaveJSONState saver = new AutoSaveJSONState(sb.getJsm(), dir, false);

        sb.getSettings().set(AutosaveFiles.MARKER, "A");
        saver.run();

        // java.io streams open files without delete-sharing on Windows, which blocks renames. (NIO would not.)
        try (FileInputStream lock = new FileInputStream(AutosaveFiles.newest(dir))) {
            sb.getSettings().set(AutosaveFiles.MARKER, "B");
            saver.run();
        }

        ScoreBoardImpl restored = AutosaveFiles.restore(dir);
        assertEquals("F12: the latest change was not autosaved while the previous autosave file was locked", "B",
                     restored.getSettings().get(AutosaveFiles.MARKER));
    }
}
