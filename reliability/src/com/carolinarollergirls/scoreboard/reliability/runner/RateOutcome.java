package com.carolinarollergirls.scoreboard.reliability.runner;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and formats the failure message written by {@code RateTest}. */
public final class RateOutcome {
    private static final Pattern MESSAGE =
        Pattern.compile("RATE failures=([0-9]+) iterations=([0-9]+) rate=([0-9]+(?:\\.[0-9]+)?)");

    private RateOutcome(int failures, int iterations, double rate) {
        this.failures = failures;
        this.iterations = iterations;
        this.rate = rate;
    }

    public int getFailures() { return failures; }
    public int getIterations() { return iterations; }
    public double getRate() { return rate; }

    /** The message prefix {@code RateTest} throws, without the first failure appended. */
    public static String format(int failures, int iterations) {
        return String.format(Locale.ROOT, "RATE failures=%d iterations=%d rate=%.6f", failures, iterations,
                             iterations == 0 ? 0.0 : (double) failures / iterations);
    }

    /** @return the parsed outcome, or null when {@code failureText} is not a rate message. */
    public static RateOutcome parse(String failureText) {
        if (failureText == null) { return null; }
        Matcher m = MESSAGE.matcher(failureText);
        if (!m.find()) { return null; }
        try {
            return new RateOutcome(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                                   Double.parseDouble(m.group(3)));
        } catch (NumberFormatException e) { return null; }
    }

    private final int failures;
    private final int iterations;
    private final double rate;
}
