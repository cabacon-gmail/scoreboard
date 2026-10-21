package com.carolinarollergirls.scoreboard.reliability.harness;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.jr.ob.JSON;

/** Reads and validates {@code .crgtrace.jsonl} files. Every problem is reported with its line number. */
public final class TraceReader {
    static final Pattern VAR = Pattern.compile("\\$([A-Z][A-Z0-9_]*)");

    private TraceReader() {}

    public static Trace read(File file) throws IOException, TraceFormatException {
        return parse(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    public static Trace parse(List<String> lines) throws TraceFormatException {
        Map<String, Object> header = null;
        List<Trace.Event> events = new ArrayList<>();
        long lastT = 0;
        for (int i = 0; i < lines.size(); i++) {
            int lineNo = i + 1;
            String text = lines.get(i).trim();
            if (text.isEmpty()) { continue; }
            Object parsed;
            try {
                parsed = JSON.std.anyFrom(text);
            } catch (IOException e) {
                throw new TraceFormatException("line " + lineNo + ": not valid JSON: " + e.getMessage());
            }
            if (!(parsed instanceof Map)) {
                throw new TraceFormatException("line " + lineNo + ": must be a JSON object");
            }
            Map<String, Object> m = (Map<String, Object>) parsed;
            if (header == null) {
                header = m;
                Object v = header.get("trace");
                if (!(v instanceof Number) || ((Number) v).intValue() != Trace.VERSION) {
                    throw new TraceFormatException("line " + lineNo + ": header 'trace' must be " + Trace.VERSION +
                                                   ", found " + v);
                }
                continue;
            }
            Object t = m.get("t");
            if (!(t instanceof Number) || ((Number) t).longValue() < 0) {
                throw new TraceFormatException("line " + lineNo + ": 't' must be a non-negative number of ms");
            }
            long tv = ((Number) t).longValue();
            if (tv < lastT) {
                throw new TraceFormatException("line " + lineNo + ": 't' goes backwards (" + tv + " < " + lastT + ")");
            }
            lastT = tv;
            Object ev = m.get("ev");
            if (!(ev instanceof String) || !Trace.EVENT_TYPES.contains(ev)) {
                throw new TraceFormatException("line " + lineNo + ": unknown event type '" + ev + "'");
            }
            Object dev = m.get("dev");
            events.add(new Trace.Event(tv, (String) ev, dev instanceof String ? (String) dev : null, m, lineNo));
        }
        if (header == null) { throw new TraceFormatException("line 1: missing header"); }
        Trace trace = new Trace(header, events);
        validate(trace);
        return trace;
    }

    /** Checks devices and variables. Also used for traces built in memory. */
    public static void validate(Trace trace) throws TraceFormatException {
        for (Trace.Event e : trace.events()) {
            String where = e.line > 0 ? "line " + e.line : e.toString();
            if (Trace.DEVICE_EVENTS.contains(e.ev)) {
                if (e.dev == null) { throw new TraceFormatException(where + ": '" + e.ev + "' needs 'dev'"); }
                if (!trace.devices().containsKey(e.dev)) {
                    throw new TraceFormatException(where + ": unknown device '" + e.dev + "' (not in header devices)");
                }
            } else if (e.dev != null && !trace.devices().containsKey(e.dev)) {
                throw new TraceFormatException(where + ": unknown device '" + e.dev + "' (not in header devices)");
            }
            if ("send".equals(e.ev) && e.get("msg") == null) {
                throw new TraceFormatException(where + ": 'send' needs 'msg'");
            }
            if ("expect".equals(e.ev) && !(e.get("state") instanceof Map)) {
                throw new TraceFormatException(where + ": 'expect' needs a 'state' object");
            }
            checkVars(trace, e.fields, where);
        }
        for (Map<String, Object> d : trace.devices().values()) { checkVars(trace, d, "header devices"); }
    }

    private static void checkVars(Trace trace, Object value, String where) throws TraceFormatException {
        if (value instanceof String) {
            Matcher m = VAR.matcher((String) value);
            while (m.find()) {
                String name = m.group(1);
                if (!trace.vars().containsKey(name) && !Trace.BUILT_IN_VARS.contains(name)) {
                    throw new TraceFormatException(where + ": unknown variable $" + name);
                }
            }
        } else if (value instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                checkVars(trace, e.getKey(), where);
                checkVars(trace, e.getValue(), where);
            }
        } else if (value instanceof List) {
            for (Object o : (List<?>) value) { checkVars(trace, o, where); }
        }
    }
}
