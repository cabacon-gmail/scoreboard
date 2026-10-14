package com.carolinarollergirls.scoreboard.reliability.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Test;

import com.carolinarollergirls.scoreboard.core.ScoreBoardImpl;
import com.carolinarollergirls.scoreboard.utils.Version;

/**
 * Proves the child JVM can start the product: classes, release.properties and lib/deps are on the classpath, and
 * the product's base path (the current directory) is this class's own work directory, not the repository.
 */
public class ProductLoadsReliabilityTest {
    @Test
    public void scoreBoardStartsInWorkDir() throws Exception {
        String workDir = System.getProperty("reliability.workDir");
        assertNotNull("reliability.workDir is not set; run through ReliabilityMain", workDir);
        assertEquals("current directory is not the per-class work dir", new File(workDir).getCanonicalFile(),
                     new File(".").getCanonicalFile());

        assertTrue("Version.load() failed: release.properties is not on the classpath", Version.load());
        ScoreBoardImpl sb = new ScoreBoardImpl(false);
        assertNotNull(sb.getCurrentGame());

        File teamLogos = new File("html/images/teamlogo");
        assertTrue("media directories were not created in the work dir", teamLogos.isDirectory());
        String repoDir = System.getProperty("reliability.repoDir");
        if (repoDir != null) {
            assertTrue("product wrote into the repository instead of the work dir",
                       !new File(workDir).getCanonicalFile().equals(new File(repoDir).getCanonicalFile()));
        }
    }
}
