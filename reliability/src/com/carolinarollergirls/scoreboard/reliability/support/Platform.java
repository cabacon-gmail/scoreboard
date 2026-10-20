package com.carolinarollergirls.scoreboard.reliability.support;

import java.util.Locale;

import org.junit.Assume;

/** Operating-system checks for tests that can only reproduce their finding on one platform. */
public final class Platform {
    private Platform() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** Skips the calling test (reported as SKIPPED, which never fails a run) unless running on Windows. */
    public static void assumeWindows(String why) { Assume.assumeTrue(why, isWindows()); }
}
