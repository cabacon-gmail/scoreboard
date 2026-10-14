package com.carolinarollergirls.scoreboard.reliability.runner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Compares test results with the manifest. A pure function with no I/O.
 */
public final class Evaluator {
    private Evaluator() {}

    /** Observed rate above this multiple of the baseline is reported as {@link Verdict#RATE_REGRESSION}. */
    public static final double RATE_REGRESSION_FACTOR = 1.5;

    /** One evaluated row: a test result, or a manifest entry whose test did not run. */
    public static final class Outcome {
        Outcome(String test, ManifestEntry entry, TestResult result, Verdict verdict, String message,
                RateOutcome rate) {
            this.test = test;
            this.entry = entry;
            this.result = result;
            this.verdict = verdict;
            this.message = message;
            this.rate = rate;
        }

        public String getTest() { return test; }
        /** The manifest entry, or null when the test is not listed. */
        public ManifestEntry getEntry() { return entry; }
        /** The result, or null for {@link Verdict#STALE_ENTRY}. */
        public TestResult getResult() { return result; }
        public Verdict getVerdict() { return verdict; }
        /** A short human-readable explanation of the verdict. */
        public String getMessage() { return message; }
        /** Parsed rate for rate entries that produced a rate message, otherwise null. */
        public RateOutcome getRate() { return rate; }

        private final String test;
        private final ManifestEntry entry;
        private final TestResult result;
        private final Verdict verdict;
        private final String message;
        private final RateOutcome rate;
    }

    /**
     * @param results       results of every test that ran
     * @param manifest      the expected failures
     * @param classesRun    names of the test classes that were selected to run
     * @param filtered      true when {@code --only} limited which classes ran
     * @return one outcome per result plus one per stale manifest entry, most severe first
     */
    public static List<Outcome> evaluate(Collection<TestResult> results, Manifest manifest,
                                         Collection<String> classesRun, boolean filtered) {
        List<Outcome> outcomes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TestResult r : results) {
            seen.add(r.getTest());
            outcomes.add(evaluateOne(r, manifest.get(r.getTest())));
        }
        Set<String> ran = new HashSet<>(classesRun);
        for (ManifestEntry e : manifest.getEntries()) {
            if (seen.contains(e.getTest())) { continue; }
            if (filtered && !ran.contains(e.getClassName())) { continue; }
            outcomes.add(new Outcome(
                e.getTest(), e, null, Verdict.STALE_ENTRY,
                "listed in the manifest (" + e.getFinding() + ") but no such test ran; remove or fix the entry", null));
        }
        outcomes.sort(Comparator.comparing(Outcome::getVerdict).thenComparing(Outcome::getTest));
        return outcomes;
    }

    static Outcome evaluateOne(TestResult r, ManifestEntry e) {
        String test = r.getTest();
        if (r.getStatus() == TestResult.Status.IGNORED) {
            return new Outcome(test, e, r, Verdict.SKIPPED, "ignored", null);
        }
        boolean failed = r.getStatus() == TestResult.Status.FAILED;
        if (e == null) {
            return failed ? new Outcome(test, null, r, Verdict.NEW_FAILURE, "failed and is not in the manifest", null)
                          : new Outcome(test, null, r, Verdict.PASS, "passed", null);
        }
        if (!failed) {
            return new Outcome(test, e, r, Verdict.UNEXPECTED_PASS,
                               "issue " + e.getFinding() + " looks fixed; remove its manifest entry", null);
        }
        if (e.getKind() == ManifestEntry.Kind.SINGLE) {
            String text = r.getFailureText() == null ? "" : r.getFailureText();
            if (e.getMessagePattern().matcher(text).find()) {
                return new Outcome(test, e, r, Verdict.KNOWN, "known issue " + e.getFinding(), null);
            }
            return new Outcome(test, e, r, Verdict.WRONG_FAILURE,
                               "failed, but not with the expected message for " + e.getFinding(), null);
        }
        RateOutcome rate = RateOutcome.parse(r.getFailureText());
        if (rate == null) {
            return new Outcome(test, e, r, Verdict.WRONG_FAILURE,
                               "failed, but not with a rate message for " + e.getFinding(), null);
        }
        String rates =
            String.format(Locale.ROOT, "observed rate %.6f, baseline %.6f", rate.getRate(), e.getBaselineRate());
        if (rate.getRate() > RATE_REGRESSION_FACTOR * e.getBaselineRate()) {
            return new Outcome(test, e, r, Verdict.RATE_REGRESSION,
                               "known issue " + e.getFinding() + " got worse: " + rates, rate);
        }
        return new Outcome(test, e, r, Verdict.KNOWN_RATE, "known issue " + e.getFinding() + ": " + rates, rate);
    }

    public static boolean isBlocking(Collection<Outcome> outcomes) {
        for (Outcome o : outcomes) {
            if (o.getVerdict().isBlocking()) { return true; }
        }
        return false;
    }
}
