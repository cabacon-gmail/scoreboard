package com.carolinarollergirls.scoreboard.reliability.selfcheck.fixtures;

import org.junit.Test;

import com.carolinarollergirls.scoreboard.reliability.support.RateTest;

/** Self-check fixture: a rate test that fails exactly 1 of 10 iterations. Never part of the real suite. */
public class RateFixtureReliabilityTest {
    @Test
    public void failsOnceInTen() {
        RateTest.run(10, i -> {
            if (i == 3) { throw new AssertionError("iteration 3 fails"); }
        });
    }
}
