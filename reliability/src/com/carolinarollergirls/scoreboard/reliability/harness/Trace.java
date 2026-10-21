package com.carolinarollergirls.scoreboard.reliability.harness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A trace: a header plus timed events. The single input format of the harness (Phase 2 design, section 3).
 * <p>
 * Scenario code and the {@link Bout} DSL build traces in memory; {@link TraceReader} loads them from
 * {@code .crgtrace.jsonl} files, including (later) traces captured from real games.
 * </p>
 */
public final class Trace {
    public static final int VERSION = 1;

    /** Event types, and which of them act on a device. */
    public static final Set<String> EVENT_TYPES = Collections.unmodifiableSet(new HashSet<>(
        Arrays.asList("connect", "send", "disconnect", "http", "recv", "stall", "clock", "kill", "expect", "mark")));
    public static final Set<String> DEVICE_EVENTS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("connect", "send", "disconnect", "http", "recv", "stall")));
    /** Variables the replayer resolves itself when the header does not set them. */
    public static final Set<String> BUILT_IN_VARS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("GAME")));

    /** One event. {@code fields} holds every field of the line, including t, ev and dev. */
    public static final class Event {
        public Event(long t, String ev, String dev, Map<String, Object> fields, int line) {
            this.t = t;
            this.ev = ev;
            this.dev = dev;
            this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            this.line = line;
        }

        public Object get(String field) { return fields.get(field); }

        @Override
        public String toString() {
            return "line " + line + " (t=" + t + " " + ev + (dev != null ? " " + dev : "") + ")";
        }

        public final long t;
        public final String ev;
        public final String dev;
        public final Map<String, Object> fields;
        /** 1-based line number in the trace file (header is line 1); 0 when built in memory. */
        public final int line;
    }

    public Trace(Map<String, Object> header, List<Event> events) {
        this.header = Collections.unmodifiableMap(new LinkedHashMap<>(header));
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
    }

    public Map<String, Object> header() { return header; }
    public List<Event> events() { return events; }

    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> devices() {
        Object d = header.get("devices");
        return d instanceof Map ? (Map<String, Map<String, Object>>) d : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> vars() {
        Object v = header.get("vars");
        return v instanceof Map ? (Map<String, Object>) v : Collections.emptyMap();
    }

    private final Map<String, Object> header;
    private final List<Event> events;
}
