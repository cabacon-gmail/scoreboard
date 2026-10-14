package com.carolinarollergirls.scoreboard.reliability.runner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.jr.ob.JSON;

/** Writes {@code results.json}, {@code summary.txt} and {@code scorecard.json} for one run. */
public final class Report {
    public static final int SCORECARD_VERSION = 1;

    /** Facts about the run that are not part of the evaluation. */
    public static final class RunInfo {
        public String startedAt;
        public double durationSeconds;
        public String gitCommit;
        public String gitBranch;
        public String host;
        public String os;
        public String javaVersion;
    }

    private Report() {}

    public static String summary(List<Evaluator.Outcome> outcomes) {
        int width = "TEST".length();
        for (Evaluator.Outcome o : outcomes) { width = Math.max(width, o.getTest().length()); }
        StringBuilder sb = new StringBuilder();
        String format = "%-16s  %-" + width + "s  %s%n";
        sb.append(String.format(format, "VERDICT", "TEST", "DETAIL"));
        for (Evaluator.Outcome o : outcomes) {
            String detail = o.getMessage();
            TestResult r = o.getResult();
            if (r != null && r.getFailureText() != null && o.getVerdict() != Verdict.KNOWN) {
                detail += " | " + firstLine(r.getFailureText());
            }
            sb.append(String.format(format, o.getVerdict(), o.getTest(), detail));
        }
        sb.append(System.lineSeparator());
        List<String> counts = new ArrayList<>();
        int blocking = 0;
        for (Map.Entry<Verdict, Integer> e : verdictCounts(outcomes).entrySet()) {
            if (e.getValue() > 0) { counts.add(e.getKey() + "=" + e.getValue()); }
            if (e.getKey().isBlocking()) { blocking += e.getValue(); }
        }
        sb.append("Counts: ").append(counts.isEmpty() ? "no tests" : String.join(", ", counts));
        sb.append(System.lineSeparator());
        sb.append(blocking == 0 ? "RESULT: OK" : "RESULT: FAILED (" + blocking + " blocking)");
        sb.append(System.lineSeparator());
        return sb.toString();
    }

    public static void write(File reportDir, List<Evaluator.Outcome> outcomes, RunInfo info) throws IOException {
        reportDir.mkdirs();
        JSON json = JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT, JSON.Feature.WRITE_NULL_PROPERTIES);

        List<Object> rows = new ArrayList<>();
        for (Evaluator.Outcome o : outcomes) {
            Map<String, Object> m = new LinkedHashMap<>();
            ManifestEntry e = o.getEntry();
            TestResult r = o.getResult();
            m.put("test", o.getTest());
            m.put("finding", e == null ? null : e.getFinding());
            m.put("kind", e == null ? null : e.getKind().name().toLowerCase());
            m.put("status", r == null ? "not-run" : r.getStatus().toJson());
            m.put("verdict", o.getVerdict().name());
            m.put("message", o.getMessage());
            m.put("failureText", r == null ? null : r.getFailureText());
            m.put("durationMs", r == null ? null : r.getDurationMs());
            if (e != null && e.getKind() == ManifestEntry.Kind.RATE) {
                m.put("observedRate", o.getRate() == null ? null : o.getRate().getRate());
                m.put("baselineRate", e.getBaselineRate());
            }
            rows.add(m);
        }
        writeFile(new File(reportDir, "results.json"), json.asString(rows));
        writeFile(new File(reportDir, "summary.txt"), summary(outcomes));
        writeFile(new File(reportDir, "scorecard.json"), json.asString(scorecard(outcomes, info)));
    }

    static Map<String, Object> scorecard(List<Evaluator.Outcome> outcomes, RunInfo info) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scorecardVersion", SCORECARD_VERSION);
        m.put("startedAt", info.startedAt);
        m.put("durationSeconds", Math.round(info.durationSeconds * 10.0) / 10.0);
        m.put("gitCommit", info.gitCommit);
        m.put("gitBranch", info.gitBranch);
        m.put("host", info.host);
        m.put("os", info.os);
        m.put("javaVersion", info.javaVersion);
        m.put("manifestSchemaVersion", Manifest.SCHEMA_VERSION);
        m.put("wifiProfileVersion", null);
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Map.Entry<Verdict, Integer> e : verdictCounts(outcomes).entrySet()) {
            counts.put(e.getKey().name(), e.getValue());
        }
        m.put("verdictCounts", counts);
        Map<String, Integer> open = new TreeMap<>();
        for (Evaluator.Outcome o : outcomes) {
            if (o.getEntry() != null) { open.merge(o.getEntry().getFinding(), 1, Integer::sum); }
        }
        m.put("knownIssuesOpen", open);
        m.put("blocking", Evaluator.isBlocking(outcomes));
        return m;
    }

    static Map<Verdict, Integer> verdictCounts(List<Evaluator.Outcome> outcomes) {
        Map<Verdict, Integer> counts = new LinkedHashMap<>();
        for (Verdict v : Verdict.values()) { counts.put(v, 0); }
        for (Evaluator.Outcome o : outcomes) { counts.merge(o.getVerdict(), 1, Integer::sum); }
        return counts;
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static void writeFile(File f, String content) throws IOException {
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
