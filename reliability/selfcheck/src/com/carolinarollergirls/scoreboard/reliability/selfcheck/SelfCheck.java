package com.carolinarollergirls.scoreboard.reliability.selfcheck;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import com.fasterxml.jackson.jr.ob.JSON;

/**
 * Programmatic version of the Phase 0 verification checklist (M1-M13) and pilot steps P1 and P3.
 * <p>
 * Every scenario runs the real {@code ReliabilityMain} in a child JVM against a temporary manifest and, where needed,
 * the self-check fixture classes. Nothing in the source tree is edited: manifests, work dirs, reports and the P3
 * patched class all live under {@code --work-root}.
 * </p>
 * <p>
 * Exit code 0 when every check passes or is skipped, 1 when any check fails, 2 on a usage error.
 * </p>
 */
public final class SelfCheck {
    static final String MAIN = "com.carolinarollergirls.scoreboard.reliability.runner.ReliabilityMain";
    static final String CANARY = "com.carolinarollergirls.scoreboard.reliability.canary.CanaryReliabilityTest";
    static final String SMOKE = "com.carolinarollergirls.scoreboard.reliability.smoke.ProductLoadsReliabilityTest";
    static final String PILOT = "com.carolinarollergirls.scoreboard.core.game.UndoReliabilityTest";
    static final String PILOT_TEST = PILOT + "#controlsStillWorkAfterUndoRestoreFails";
    static final String FIX = "com.carolinarollergirls.scoreboard.reliability.selfcheck.fixtures.";
    static final String BASIC = FIX + "BasicFixtureReliabilityTest";
    static final String PASS_ONLY = FIX + "PassFixtureReliabilityTest";
    static final String SLEEP = FIX + "SleepFixtureReliabilityTest";
    static final String RATE = FIX + "RateFixtureReliabilityTest";

    enum Status { PASS, FAIL, SKIP }

    static final class CheckResult {
        CheckResult(String id, String title, Status status, String detail) {
            this.id = id;
            this.title = title;
            this.status = status;
            this.detail = detail;
        }

        final String id;
        final String title;
        final Status status;
        final String detail;
    }

    /** Outcome of one child run of ReliabilityMain. */
    static final class SuiteRun {
        int exit;
        String output = "";
        File reportDir;
        File workDir;
        /** test -> verdict, from results.json (empty when the runner stopped before writing a report). */
        final Map<String, String> verdicts = new LinkedHashMap<>();
        final Map<String, String> failureTexts = new LinkedHashMap<>();

        String verdict(String test) { return verdicts.get(test); }

        String describe() {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, String> e : verdicts.entrySet()) {
                parts.add(shortName(e.getKey()) + "=" + e.getValue());
            }
            return "exit " + exit + "; " + (parts.isEmpty() ? "no verdicts" : String.join(", ", parts));
        }
    }

    private final File repoDir;
    private final String runClasspath;
    private final File testClasses;
    private final File fixtureClasses;
    private final File realManifest;
    private final File workRoot;
    private final String baseRef;
    private final String rootBuild;
    private final List<CheckResult> results = new ArrayList<>();

    SelfCheck(Map<String, String> opts) {
        repoDir = new File(opts.get("--repo-dir")).getAbsoluteFile();
        runClasspath = opts.get("--run-classpath");
        testClasses = new File(opts.get("--test-classes")).getAbsoluteFile();
        fixtureClasses = new File(opts.get("--fixture-classes")).getAbsoluteFile();
        realManifest = new File(opts.get("--manifest")).getAbsoluteFile();
        workRoot = new File(opts.get("--work-root")).getAbsoluteFile();
        baseRef = opts.containsKey("--base-ref") ? opts.get("--base-ref") : "origin/dev";
        rootBuild = opts.containsKey("--root-build") ? opts.get("--root-build") : "skipped";
    }

    public static void main(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) { opts.put(args[i], args[i + 1]); }
        for (String required : Arrays.asList("--repo-dir", "--run-classpath", "--test-classes", "--fixture-classes",
                                             "--manifest", "--work-root")) {
            if (!opts.containsKey(required)) {
                System.err.println("SelfCheck: missing " + required);
                System.exit(2);
            }
        }
        int code;
        try {
            code = new SelfCheck(opts).runAll();
        } catch (Throwable t) {
            System.err.println("SelfCheck failed: " + t);
            t.printStackTrace();
            code = 2;
        }
        System.exit(code);
    }

    int runAll() throws Exception {
        deleteRecursively(workRoot.toPath());
        workRoot.mkdirs();
        Map<String, Object> manifestJson = readJson(realManifest);

        // M1-M3: the real suite with the real manifest.
        List<String> htmlBefore = listTree(new File(repoDir, "html"));
        SuiteRun m1 = run("m1-real-suite", realManifest, testClasses, null);
        List<String> htmlAfter = listTree(new File(repoDir, "html"));
        checkM1(m1, manifestJson);
        checkM2(m1);
        checkM3(m1, htmlBefore, htmlAfter);

        // M4-M11: runner behaviour, using fixture tests and temporary manifests.
        checkM4();
        checkM5();
        checkM6();
        checkM7();
        checkM8();
        checkM9();
        checkM10();
        checkM11();

        // M12-M13: the existing build is untouched.
        checkM12();
        checkM13();

        // Pilot steps that can be automated.
        checkP1(manifestJson);
        checkP3();

        return report();
    }

    // ---------------------------------------------------------------- checks

    void checkM1(SuiteRun r, Map<String, Object> manifestJson) {
        List<String> problems = new ArrayList<>();
        if (r.exit != 0) { problems.add("exit " + r.exit + " (expected 0)"); }
        expectVerdict(problems, r, CANARY + "#plantedFailureIsReportedAsKnown", "KNOWN");
        expectVerdict(problems, r, CANARY + "#plantedPassIsReportedAsPass", "PASS");
        expectVerdict(problems, r, SMOKE + "#scoreBoardStartsInWorkDir", "PASS");
        for (Object e : (List<?>) manifestJson.get("expectedFailures")) {
            Map<?, ?> entry = (Map<?, ?>) e;
            String test = (String) entry.get("test");
            String v = r.verdict(test);
            if (!"KNOWN".equals(v) && !"KNOWN_RATE".equals(v) && !"RATE_REGRESSION".equals(v)) {
                problems.add(test + " is " + v + " (expected a known verdict for " + entry.get("finding") + ")");
            }
        }
        for (Map.Entry<String, String> v : r.verdicts.entrySet()) {
            if (Arrays.asList("NEW_FAILURE", "WRONG_FAILURE", "UNEXPECTED_PASS", "STALE_ENTRY")
                    .contains(v.getValue())) {
                problems.add(v.getKey() + " is " + v.getValue());
            }
        }
        add("M1", "Normal run: exit 0, every listed issue known, canary and smoke as expected", problems,
            r.verdicts.size() + " tests, exit " + r.exit);
    }

    void checkM2(SuiteRun r) throws IOException {
        List<String> problems = new ArrayList<>();
        for (String f : Arrays.asList("results.json", "summary.txt", "scorecard.json")) {
            if (!new File(r.reportDir, f).isFile()) { problems.add("missing " + f); }
        }
        File logs = new File(r.reportDir, "logs");
        for (String cls : Arrays.asList(CANARY, SMOKE, PILOT)) {
            if (!new File(logs, cls + ".log").isFile()) { problems.add("missing logs/" + cls + ".log"); }
        }
        File scorecard = new File(r.reportDir, "scorecard.json");
        if (scorecard.isFile()) {
            Map<String, Object> s = readJson(scorecard);
            for (String field : Arrays.asList("scorecardVersion", "startedAt", "durationSeconds", "gitCommit",
                                              "gitBranch", "host", "os", "javaVersion", "manifestSchemaVersion",
                                              "wifiProfileVersion", "verdictCounts", "knownIssuesOpen", "blocking")) {
                if (!s.containsKey(field)) { problems.add("scorecard.json lacks '" + field + "'"); }
            }
            if (!Boolean.FALSE.equals(s.get("blocking"))) {
                problems.add("scorecard blocking is " + s.get("blocking"));
            }
        }
        add("M2", "Report files, per-class logs and every scorecard field present; blocking false", problems,
            "report in " + relative(r.reportDir));
    }

    void checkM3(SuiteRun r, List<String> htmlBefore, List<String> htmlAfter) {
        List<String> problems = new ArrayList<>();
        File smokeWork = new File(r.workDir, SMOKE);
        if (!new File(smokeWork, "html/images/teamlogo").isDirectory()) {
            problems.add("smoke work dir has no html/images/teamlogo");
        }
        for (String cls : Arrays.asList(CANARY, SMOKE, PILOT)) {
            File cfg = new File(new File(r.workDir, cls), "config");
            if (!cfg.isDirectory()) { problems.add(cls + " work dir has no config/ copy"); }
            if (new File(cfg, "autosave").exists()) { problems.add(cls + " work dir copied config/autosave"); }
        }
        if (!htmlBefore.equals(htmlAfter)) {
            List<String> added = new ArrayList<>(htmlAfter);
            added.removeAll(htmlBefore);
            problems.add("repo html/ changed during the run: " + shorten(added.toString()));
        }
        add("M3", "Product writes only into per-class work dirs (with config/ copied), never the repo's html/",
            problems, "repo html/ unchanged (" + htmlAfter.size() + " entries)");
    }

    void checkM4() throws Exception {
        File m = manifest("m4", Collections.<Map<String, Object>>emptyList());
        SuiteRun r = run("m4-new-failure", m, fixtureClasses, null, "--only", "BasicFixture");
        List<String> p = new ArrayList<>();
        expectExit(p, r, 1);
        expectVerdict(p, r, BASIC + "#alwaysFails", "NEW_FAILURE");
        add("M4", "Unlisted failing test -> NEW_FAILURE, run fails", p, r.describe());
    }

    void checkM5() throws Exception {
        File m = manifest("m5", Arrays.asList(single(BASIC + "#alwaysFails", "F0", BasicFixtureMessage()),
                                              single(BASIC + "#alwaysPasses", "F0", "anything")));
        SuiteRun r = run("m5-unexpected-pass", m, fixtureClasses, null, "--only", "BasicFixture");
        List<String> p = new ArrayList<>();
        expectExit(p, r, 1);
        expectVerdict(p, r, BASIC + "#alwaysPasses", "UNEXPECTED_PASS");
        expectVerdict(p, r, BASIC + "#alwaysFails", "KNOWN");
        expectOutput(p, r, "looks fixed");
        add("M5", "Listed test that passes -> UNEXPECTED_PASS ('looks fixed'), run fails", p, r.describe());
    }

    void checkM6() throws Exception {
        File m = manifest("m6", Collections.singletonList(single(BASIC + "#alwaysFails", "F0", "something else")));
        SuiteRun r = run("m6-wrong-failure", m, fixtureClasses, null, "--only", "BasicFixture");
        List<String> p = new ArrayList<>();
        expectExit(p, r, 1);
        expectVerdict(p, r, BASIC + "#alwaysFails", "WRONG_FAILURE");
        add("M6", "Listed test failing with a different message -> WRONG_FAILURE, run fails", p, r.describe());
    }

    void checkM7() throws Exception {
        File m = manifest("m7", Arrays.asList(single(BASIC + "#alwaysFails", "F0", BasicFixtureMessage()),
                                              single(BASIC + "#noSuchMethod", "F0", "x")));
        SuiteRun r = run("m7-stale-entry", m, fixtureClasses, null, "--only", "BasicFixture");
        List<String> p = new ArrayList<>();
        expectExit(p, r, 1);
        expectVerdict(p, r, BASIC + "#noSuchMethod", "STALE_ENTRY");
        add("M7", "Entry for a test that did not run -> STALE_ENTRY, run fails", p, r.describe());
    }

    void checkM8() throws Exception {
        File m = manifest("m8", Arrays.asList(single(BASIC + "#alwaysFails", "F0", BasicFixtureMessage()),
                                              single(BASIC + "#noSuchMethod", "F0", "x")));
        SuiteRun r = run("m8-only-filter", m, fixtureClasses, null, "--only", "PassFixture");
        List<String> p = new ArrayList<>();
        expectExit(p, r, 0);
        expectVerdict(p, r, PASS_ONLY + "#passes", "PASS");
        for (String test : r.verdicts.keySet()) {
            if (!test.startsWith(PASS_ONLY + "#")) { p.add("unexpected result for " + test); }
        }
        add("M8", "--only runs just the matching class; entries of filtered-out classes are not STALE_ENTRY", p,
            r.describe());
    }

    void checkM9() throws Exception {
        List<String> p = new ArrayList<>();
        Map<String, String> cases = new LinkedHashMap<>();
        String good = "{\"test\": \"" + BASIC + "#alwaysFails\", \"finding\": \"F0\", \"kind\": \"single\", "
                      + "\"messagePattern\": \"x\"}";
        cases.put("invalid JSON", "{\"schemaVersion\": 1 \"expectedFailures\": []}");
        cases.put("duplicate entry", "{\"schemaVersion\": 1, \"expectedFailures\": [" + good + ", " + good + "]}");
        cases.put("bad finding",
                  "{\"schemaVersion\": 1, \"expectedFailures\": [" + good.replace("\"F0\"", "\"X1\"") + "]}");
        cases.put("bad regex", "{\"schemaVersion\": 1, \"expectedFailures\": [" +
                                   good.replace("\"messagePattern\": \"x\"", "\"messagePattern\": \"(\"") + "]}");
        cases.put("unknown field",
                  "{\"schemaVersion\": 1, \"expectedFailures\": [" + good.replace("}", ", \"owner\": \"me\"}") + "]}");
        Map<String, String> expectedText = new LinkedHashMap<>();
        expectedText.put("invalid JSON", "not valid JSON");
        expectedText.put("duplicate entry", "duplicate entry");
        expectedText.put("bad finding", "'finding' must match");
        expectedText.put("bad regex", "not a valid regex");
        expectedText.put("unknown field", "unknown field 'owner'");
        int i = 0;
        for (Map.Entry<String, String> c : cases.entrySet()) {
            File m = new File(workRoot, "manifests/m9-" + (++i) + ".json");
            m.getParentFile().mkdirs();
            Files.write(m.toPath(), c.getValue().getBytes(StandardCharsets.UTF_8));
            SuiteRun r = run("m9-invalid-" + i, m, fixtureClasses, null, "--only", "BasicFixture");
            if (r.exit != 2) { p.add(c.getKey() + ": exit " + r.exit + " (expected 2)"); }
            if (!r.verdicts.isEmpty() || r.output.contains("Running ")) {
                p.add(c.getKey() + ": tests ran despite the invalid manifest");
            }
            if (!r.output.contains(expectedText.get(c.getKey()))) {
                p.add(c.getKey() + ": message lacks '" + expectedText.get(c.getKey()) + "'");
            }
            if (!c.getKey().equals("invalid JSON") && !r.output.contains("Manifest entry #")) {
                p.add(c.getKey() + ": message does not name the entry");
            }
        }
        add("M9",
            "Invalid manifests (bad JSON, duplicate, bad finding, bad regex, unknown field) -> exit 2 before "
                + "any test, naming the entry",
            p, cases.size() + " manifests rejected");
    }

    void checkM10() throws Exception {
        File m = manifest("m10", Collections.<Map<String, Object>>emptyList());
        long start = System.currentTimeMillis();
        SuiteRun r =
            run("m10-timeout", m, fixtureClasses, null, "--only", "SleepFixture|PassFixture", "--class-timeout", "5");
        long secs = (System.currentTimeMillis() - start) / 1000;
        List<String> p = new ArrayList<>();
        expectExit(p, r, 1);
        expectVerdict(p, r, SLEEP + "#sleepsSixtySeconds", "NEW_FAILURE");
        String text = r.failureTexts.get(SLEEP + "#sleepsSixtySeconds");
        if (text == null || !text.contains("TIMEOUT after 5s")) { p.add("failure text is " + text); }
        expectVerdict(p, r, PASS_ONLY + "#passes", "PASS");
        if (secs > 50) { p.add("run took " + secs + "s; the timeout did not cut the 60 s test short"); }
        add("M10", "A class that exceeds --class-timeout is killed and reported as TIMEOUT; other classes still run", p,
            r.describe() + ", " + secs + "s");
    }

    void checkM11() throws Exception {
        List<String> p = new ArrayList<>();
        File a = manifest("m11a", Collections.singletonList(rate(RATE + "#failsOnceInTen", "F99", 0.1)));
        SuiteRun ra = run("m11a-known-rate", a, fixtureClasses, null, "--only", "RateFixture");
        if (ra.exit != 0) { p.add("baseline 0.1: exit " + ra.exit); }
        if (!"KNOWN_RATE".equals(ra.verdict(RATE + "#failsOnceInTen"))) {
            p.add("baseline 0.1: verdict " + ra.verdict(RATE + "#failsOnceInTen") + " (expected KNOWN_RATE)");
        }
        File b = manifest("m11b", Collections.singletonList(rate(RATE + "#failsOnceInTen", "F99", 0.05)));
        SuiteRun rb = run("m11b-rate-regression", b, fixtureClasses, null, "--only", "RateFixture");
        if (rb.exit != 0) { p.add("baseline 0.05: exit " + rb.exit + " (a regression is a warning only)"); }
        if (!"RATE_REGRESSION".equals(rb.verdict(RATE + "#failsOnceInTen"))) {
            p.add("baseline 0.05: verdict " + rb.verdict(RATE + "#failsOnceInTen") + " (expected RATE_REGRESSION)");
        }
        add("M11", "Rate test at 0.1: KNOWN_RATE against baseline 0.1, RATE_REGRESSION warning against 0.05", p,
            "0.1 -> " + ra.verdict(RATE + "#failsOnceInTen") + ", 0.05 -> " + rb.verdict(RATE + "#failsOnceInTen"));
    }

    void checkM12() {
        String title = "Root 'ant compile' and 'ant test' still pass";
        if ("passed".equals(rootBuild)) {
            results.add(
                new CheckResult("M12", title, Status.PASS, "root compile and test targets ran before this check"));
        } else {
            results.add(new CheckResult("M12", title, Status.SKIP,
                                        "skipped with -Dverify.skip.roottests=true; run 'ant test' to cover it"));
        }
    }

    void checkM13() {
        String title = "No file outside reliability/ differs from " + baseRef;
        String resolved = git("rev-parse", "--verify", "--quiet", baseRef + "^{commit}");
        if (resolved == null) {
            results.add(
                new CheckResult("M13", title, Status.SKIP,
                                "git ref '" + baseRef + "' not found; fetch it or pass -Dverify.base.ref=<ref>"));
            return;
        }
        String diff = git("diff", "--name-only", baseRef + "...HEAD", "--", ".", ":(exclude)reliability");
        if (diff == null) {
            results.add(new CheckResult("M13", title, Status.FAIL, "git diff failed"));
        } else if (diff.trim().isEmpty()) {
            results.add(new CheckResult("M13", title, Status.PASS, "no changes outside reliability/"));
        } else {
            results.add(
                new CheckResult("M13", title, Status.FAIL, "changed: " + shorten(diff.trim().replace('\n', ' '))));
        }
    }

    void checkP1(Map<String, Object> manifestJson) throws Exception {
        String title = "Pilot P1: F7 test, unlisted, fails as NEW_FAILURE with the F7 message; log shows the injected "
                       + "exception";
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object e : (List<?>) manifestJson.get("expectedFailures")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) e;
            if (!PILOT_TEST.equals(entry.get("test"))) { entries.add(entry); }
        }
        File m = manifest("p1", entries);
        SuiteRun r = run("p1-pilot-unlisted", m, testClasses, null, "--only", "UndoReliability");
        List<String> p = new ArrayList<>();
        expectExit(p, r, 1);
        expectVerdict(p, r, PILOT_TEST, "NEW_FAILURE");
        String text = r.failureTexts.get(PILOT_TEST);
        if (text == null || !text.contains("F7: Start Jam had no effect")) { p.add("failure text is " + text); }
        File log = new File(r.reportDir, "logs/" + PILOT + ".log");
        String logText = log.isFile() ? new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8) : "";
        if (!logText.contains("injected listener failure")) { p.add("class log does not show the injected exception"); }
        add("P1", title, p, r.describe());
    }

    /** P3: compile a patched GameImpl into an overlay directory (never the source tree) and expect F7 to flip. */
    void checkP3() throws Exception {
        String title = "Pilot P3: a try/finally fix in GameImpl flips F7 to UNEXPECTED_PASS";
        File source = new File(repoDir, "src/com/carolinarollergirls/scoreboard/core/game/GameImpl.java");
        if (!source.isFile()) {
            results.add(new CheckResult("P3", title, Status.SKIP, "GameImpl.java not found"));
            return;
        }
        String code = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        String[][] edits = {
            {"    protected void restoreSnapshot() {\n        restoreRunning = true;\n",
             "    protected void restoreSnapshot() {\n        restoreRunning = true;\n        try {\n"},
            {"        restoreRunning = false;\n    }\n    protected void finishReplace() {",
             "        } finally { restoreRunning = false; }\n    }\n    protected void finishReplace() {"},
            {"                ScoreBoardClock.getInstance().stop();\n                restoreSnapshot();\n",
             "                ScoreBoardClock.getInstance().stop();\n                try {\n"
                 + "                    restoreSnapshot();\n                } catch (RuntimeException e) {\n"
                 + "                    ScoreBoardClock.getInstance().start(true);\n"
                 + "                    snapshot = null;\n                    throw e;\n                }\n"}};
        String normalized = code.replace("\r\n", "\n");
        for (String[] edit : edits) {
            if (!normalized.contains(edit[0])) {
                results.add(new CheckResult("P3", title, Status.SKIP,
                                            "GameImpl.java changed upstream; the simulated fix no longer applies"));
                return;
            }
            normalized = normalized.replace(edit[0], edit[1]);
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            results.add(new CheckResult("P3", title, Status.SKIP, "no Java compiler available (running on a JRE?)"));
            return;
        }
        File patchDir = new File(workRoot, "p3-patch/src/com/carolinarollergirls/scoreboard/core/game");
        patchDir.mkdirs();
        File patched = new File(patchDir, "GameImpl.java");
        Files.write(patched.toPath(), normalized.getBytes(StandardCharsets.UTF_8));
        File overlay = new File(workRoot, "p3-patch/classes");
        overlay.mkdirs();
        int rc = javac.run(null, null, null, "-nowarn", "-source", "8", "-target", "8", "-cp", runClasspath, "-d",
                           overlay.getPath(), patched.getPath());
        if (rc != 0) {
            results.add(new CheckResult("P3", title, Status.FAIL, "patched GameImpl did not compile"));
            return;
        }
        SuiteRun r = run("p3-simulated-fix", realManifest, testClasses, overlay.getPath(), "--only", "UndoReliability");
        List<String> p = new ArrayList<>();
        expectExit(p, r, 1);
        expectVerdict(p, r, PILOT_TEST, "UNEXPECTED_PASS");
        expectOutput(p, r, "issue F7 looks fixed");
        add("P3", title, p, r.describe());
    }

    // ---------------------------------------------------------------- helpers

    private static String BasicFixtureMessage() { return "fixture: intentional failure"; }

    private void add(String id, String title, List<String> problems, String okDetail) {
        if (problems.isEmpty()) {
            results.add(new CheckResult(id, title, Status.PASS, okDetail));
        } else {
            results.add(new CheckResult(id, title, Status.FAIL, String.join("; ", problems)));
        }
    }

    private static void expectExit(List<String> p, SuiteRun r, int expected) {
        if (r.exit != expected) { p.add("exit " + r.exit + " (expected " + expected + ")"); }
    }

    private static void expectVerdict(List<String> p, SuiteRun r, String test, String expected) {
        String v = r.verdict(test);
        if (!expected.equals(v)) { p.add(shortName(test) + " is " + v + " (expected " + expected + ")"); }
    }

    private static void expectOutput(List<String> p, SuiteRun r, String text) {
        if (!r.output.contains(text)) { p.add("output lacks '" + text + "'"); }
    }

    private static Map<String, Object> single(String test, String finding, String pattern) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("test", test);
        m.put("finding", finding);
        m.put("kind", "single");
        m.put("messagePattern", pattern);
        return m;
    }

    private static Map<String, Object> rate(String test, String finding, double baseline) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("test", test);
        m.put("finding", finding);
        m.put("kind", "rate");
        m.put("baselineRate", baseline);
        return m;
    }

    private File manifest(String name, List<Map<String, Object>> entries) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("expectedFailures", entries);
        File f = new File(workRoot, "manifests/" + name + ".json");
        f.getParentFile().mkdirs();
        Files.write(f.toPath(),
                    JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT).asString(root).getBytes(StandardCharsets.UTF_8));
        return f;
    }

    /** Runs ReliabilityMain in a child JVM. {@code prepend} goes in front of the classpath (P3's patched class). */
    SuiteRun run(String label, File manifest, File classesDir, String prepend, String... extra) throws Exception {
        File dir = new File(workRoot, label);
        SuiteRun r = new SuiteRun();
        r.reportDir = new File(dir, "report");
        r.workDir = new File(dir, "work");
        String cp = runClasspath + File.pathSeparator + fixtureClasses.getPath();
        if (prepend != null) { cp = prepend + File.pathSeparator + cp; }

        List<String> cmd = new ArrayList<>();
        cmd.add(new File(new File(System.getProperty("java.home"), "bin"), "java").getPath());
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add(MAIN);
        cmd.addAll(Arrays.asList("--manifest", manifest.getPath(), "--test-classes", classesDir.getPath(),
                                 "--report-dir", r.reportDir.getPath(), "--work-dir", r.workDir.getPath(), "--repo-dir",
                                 repoDir.getPath(), "--classpath", cp));
        cmd.addAll(Arrays.asList(extra));

        System.out.println("  [" + label + "] running ...");
        dir.mkdirs();
        File out = new File(dir, "runner-output.txt");
        Process proc = new ProcessBuilder(cmd).directory(repoDir).redirectErrorStream(true).redirectOutput(out).start();
        if (!proc.waitFor(15, TimeUnit.MINUTES)) {
            proc.destroyForcibly();
            r.exit = -1;
        } else {
            r.exit = proc.exitValue();
        }
        r.output = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        File resultsFile = new File(r.reportDir, "results.json");
        if (resultsFile.isFile()) {
            for (Object o : (List<?>) JSON.std.anyFrom(resultsFile)) {
                Map<?, ?> m = (Map<?, ?>) o;
                r.verdicts.put((String) m.get("test"), (String) m.get("verdict"));
                r.failureTexts.put((String) m.get("test"), (String) m.get("failureText"));
            }
        }
        return r;
    }

    int report() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator()).append("Phase 0 verification").append(System.lineSeparator());
        int failed = 0;
        int skipped = 0;
        List<Object> json = new ArrayList<>();
        for (CheckResult c : results) {
            sb.append(String.format("%-4s %-5s %s%n           %s%n", c.id, c.status, c.title, c.detail));
            if (c.status == Status.FAIL) { failed++; }
            if (c.status == Status.SKIP) { skipped++; }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id);
            m.put("status", c.status.name());
            m.put("check", c.title);
            m.put("detail", c.detail);
            json.add(m);
        }
        sb.append(System.lineSeparator());
        sb.append(String.format("VERIFY: %s (%d passed, %d failed, %d skipped)%n", failed == 0 ? "OK" : "FAILED",
                                results.size() - failed - skipped, failed, skipped));
        sb.append("Details: ").append(relative(workRoot)).append(System.lineSeparator());
        System.out.print(sb);
        Files.write(new File(workRoot, "verify-summary.txt").toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(new File(workRoot, "verify-results.json").toPath(),
                    JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT).asString(json).getBytes(StandardCharsets.UTF_8));
        return failed == 0 ? 0 : 1;
    }

    private String git(String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(Arrays.asList(args));
            File out = File.createTempFile("selfcheck-git", ".txt", workRoot);
            Process p =
                new ProcessBuilder(cmd).directory(repoDir).redirectErrorStream(true).redirectOutput(out).start();
            boolean done = p.waitFor(60, TimeUnit.SECONDS);
            String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
            Files.delete(out.toPath());
            return done && p.exitValue() == 0 ? text : null;
        } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(File f) throws IOException {
        return (Map<String, Object>) JSON.std.anyFrom(f);
    }

    private static List<String> listTree(File dir) throws IOException {
        TreeSet<String> names = new TreeSet<>();
        if (dir.isDirectory()) {
            final Path root = dir.toPath();
            try (Stream<Path> s = Files.walk(root)) {
                s.forEach(p -> names.add(root.relativize(p).toString().replace(File.separatorChar, '/')));
            }
        }
        return new ArrayList<>(names);
    }

    private String relative(File f) {
        Path r = repoDir.toPath();
        Path p = f.getAbsoluteFile().toPath();
        return p.startsWith(r) ? r.relativize(p).toString() : p.toString();
    }

    private static String shortName(String test) {
        int dot = test.lastIndexOf('.', test.indexOf('#'));
        return dot >= 0 ? test.substring(dot + 1) : test;
    }

    private static String shorten(String s) { return s.length() > 300 ? s.substring(0, 300) + "..." : s; }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) { return; }
        try (Stream<Path> s = Files.walk(path)) {
            List<Path> all = new ArrayList<>();
            s.forEach(all::add);
            Collections.reverse(all);
            for (Path p : all) { Files.delete(p); }
        }
    }
}
