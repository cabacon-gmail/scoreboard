package com.carolinarollergirls.scoreboard.reliability.runner;

/** How a test result compares with the manifest. Declared in severity order, most severe first. */
public enum Verdict {
    NEW_FAILURE(true),
    WRONG_FAILURE(true),
    UNEXPECTED_PASS(true),
    STALE_ENTRY(true),
    RATE_REGRESSION(false),
    SKIPPED(false),
    KNOWN_RATE(false),
    KNOWN(false),
    PASS(false);

    Verdict(boolean blocking) { this.blocking = blocking; }

    /** A blocking verdict makes the whole run fail (exit code 1). */
    public boolean isBlocking() { return blocking; }

    private final boolean blocking;
}
