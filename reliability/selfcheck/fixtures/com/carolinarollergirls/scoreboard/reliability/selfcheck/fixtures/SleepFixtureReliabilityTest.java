package com.carolinarollergirls.scoreboard.reliability.selfcheck.fixtures;

import org.junit.Test;

/** Self-check fixture: a test that outlives any short class timeout. Never part of the real suite. */
public class SleepFixtureReliabilityTest {
    @Test
    public void sleepsSixtySeconds() throws Exception {
        Thread.sleep(60_000L);
    }
}
