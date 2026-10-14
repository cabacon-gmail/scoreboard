package com.carolinarollergirls.scoreboard.reliability.selfcheck.fixtures;

import static org.junit.Assert.fail;

import org.junit.Test;

/** Self-check fixture: one test that always fails, one that always passes. Never part of the real suite. */
public class BasicFixtureReliabilityTest {
    public static final String FAILURE_MESSAGE = "fixture: intentional failure";

    @Test
    public void alwaysFails() {
        fail(FAILURE_MESSAGE);
    }

    @Test
    public void alwaysPasses() {}
}
