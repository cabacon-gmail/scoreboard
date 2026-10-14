package com.carolinarollergirls.scoreboard.reliability.support;

import com.carolinarollergirls.scoreboard.reliability.runner.RateOutcome;

/**
 * Helper for race-dependent tests that report a failure rate instead of a single verdict.
 * <p>
 * The number of iterations is multiplied by the system property {@code reliability.rateScale} (default 1.0),
 * so longer runs can be requested without changing the test.
 * </p>
 */
public final class RateTest {
    public interface Iteration {
        void run(int index) throws Exception;
    }

    private RateTest() {}

    /**
     * Runs {@code body} {@code iterations × reliability.rateScale} times (at least once). Passes only if every
     * iteration passes; otherwise throws an AssertionError whose message starts with
     * {@code RATE failures=<f> iterations=<n> rate=<f/n>} followed by the first failure.
     */
    public static void run(int iterations, Iteration body) {
        int n = scaledIterations(iterations);
        int failures = 0;
        Throwable first = null;
        for (int i = 0; i < n; i++) {
            try {
                body.run(i);
            } catch (Throwable t) {
                failures++;
                if (first == null) { first = t; }
            }
        }
        if (failures > 0) {
            AssertionError e = new AssertionError(RateOutcome.format(failures, n) + "; first: " + first);
            e.initCause(first);
            throw e;
        }
    }

    public static int scaledIterations(int iterations) {
        double scale = 1.0;
        String s = System.getProperty("reliability.rateScale");
        if (s != null && !s.isEmpty()) {
            try {
                scale = Double.parseDouble(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("reliability.rateScale is not a number: " + s);
            }
        }
        return Math.max(1, (int) Math.round(iterations * scale));
    }
}
