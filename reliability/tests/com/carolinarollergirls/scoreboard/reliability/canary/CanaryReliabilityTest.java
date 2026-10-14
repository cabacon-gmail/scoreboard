package com.carolinarollergirls.scoreboard.reliability.canary;

import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Permanent canary for the expected-failures mechanism.
 * <p>
 * {@link #plantedFailureIsReportedAsKnown} is listed in the manifest under F0 and must be reported as KNOWN;
 * {@link #plantedPassIsReportedAsPass} is not listed and must be reported as PASS. If the manifest mechanism ever
 * breaks, one of them turns the run red.
 * </p>
 */
public class CanaryReliabilityTest {
    @Test
    public void plantedFailureIsReportedAsKnown() {
        fail("canary: intentional failure");
    }

    @Test
    public void plantedPassIsReportedAsPass() {}
}
