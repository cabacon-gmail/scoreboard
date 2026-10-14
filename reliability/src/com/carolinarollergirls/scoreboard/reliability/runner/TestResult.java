package com.carolinarollergirls.scoreboard.reliability.runner;

import java.util.LinkedHashMap;
import java.util.Map;

/** The raw outcome of one test method, as recorded by {@link ClassRunner}. */
public final class TestResult {
    public enum Status {
        PASSED("passed"),
        FAILED("failed"),
        IGNORED("ignored");

        Status(String json) { this.json = json; }

        public String toJson() { return json; }

        public static Status fromJson(String s) {
            for (Status st : values()) {
                if (st.json.equals(s)) { return st; }
            }
            throw new IllegalArgumentException("Unknown test status '" + s + "'");
        }

        private final String json;
    }

    public TestResult(String test, Status status, String failureText, long durationMs) {
        this.test = test;
        this.status = status;
        this.failureText = failureText;
        this.durationMs = durationMs;
    }

    /** {@code fully.qualified.ClassName#methodName} */
    public String getTest() { return test; }
    public Status getStatus() { return status; }
    /** {@code Throwable.toString()} of the failure, or null when the test did not fail. */
    public String getFailureText() { return failureText; }
    public long getDurationMs() { return durationMs; }

    public String getClassName() { return test.substring(0, test.indexOf('#')); }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("test", test);
        m.put("status", status.toJson());
        m.put("failureText", failureText);
        m.put("durationMs", durationMs);
        return m;
    }

    public static TestResult fromMap(Map<?, ?> m) {
        Object duration = m.get("durationMs");
        return new TestResult((String) m.get("test"), Status.fromJson((String) m.get("status")),
                              (String) m.get("failureText"),
                              duration instanceof Number ? ((Number) duration).longValue() : 0L);
    }

    private final String test;
    private final Status status;
    private final String failureText;
    private final long durationMs;
}
