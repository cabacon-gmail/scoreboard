package com.carolinarollergirls.scoreboard.reliability.runner;

import java.util.regex.Pattern;

/** One expected failure listed in {@code expected-failures.json}. */
public final class ManifestEntry {
    public enum Kind { SINGLE, RATE }

    public ManifestEntry(String test, String finding, Kind kind, Pattern messagePattern, Double baselineRate,
                         String notes) {
        this.test = test;
        this.finding = finding;
        this.kind = kind;
        this.messagePattern = messagePattern;
        this.baselineRate = baselineRate;
        this.notes = notes;
    }

    public String getTest() { return test; }
    public String getClassName() { return test.substring(0, test.indexOf('#')); }
    public String getFinding() { return finding; }
    public Kind getKind() { return kind; }
    /** Only set for {@link Kind#SINGLE} entries. */
    public Pattern getMessagePattern() { return messagePattern; }
    /** Only set for {@link Kind#RATE} entries. */
    public Double getBaselineRate() { return baselineRate; }
    public String getNotes() { return notes; }

    private final String test;
    private final String finding;
    private final Kind kind;
    private final Pattern messagePattern;
    private final Double baselineRate;
    private final String notes;
}
