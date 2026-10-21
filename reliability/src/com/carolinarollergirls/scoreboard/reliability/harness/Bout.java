package com.carolinarollergirls.scoreboard.reliability.harness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DSL for synthetic traces. Each call appends the event a real screen would produce, at the current time.
 *
 * <pre>
 * Trace t = Bout.create("two devices, one whistle")
 *               .device("sbo", "/nso/sbo?game=$GAME")
 *               .device("jt", "/nso/jt?game=$GAME")
 *               .at(0).on("sbo").connect().register("ScoreBoard.Game($GAME)")
 *               .at(1000).on("sbo").startJam()
 *               .after(500).expect(2000, "ScoreBoard.Game($GAME).InJam", true)
 *               .build();
 * </pre>
 */
public final class Bout {
    private Bout(String notes) {
        header.put("trace", Trace.VERSION);
        header.put("origin", "synthetic");
        header.put("devices", devices);
        header.put("vars", vars);
        header.put("redacted", false);
        header.put("notes", notes);
    }

    public static Bout create(String notes) { return new Bout(notes); }

    /** Declares a device with the page it claims to be (as WS.js sends in {@code source}). */
    public Bout device(String name, String source) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("source", source);
        d.put("platform", "trace");
        devices.put(name, d);
        return this;
    }

    /** Declares every device of {@code crew}. Use {@link #connectCrew} to connect them. */
    public Bout crew(CrewProfile crew) {
        this.crew = crew;
        for (CrewProfile.Device d : crew.devices().values()) { device(d.name, d.source); }
        return this;
    }

    /**
     * Connects every crew device, {@code spacingMs} apart, each registering the paths its real screen registers.
     * Leaves the current time after the last one.
     */
    public Bout connectCrew(long spacingMs) {
        if (crew == null) { throw new IllegalStateException("call crew(profile) first"); }
        boolean first = true;
        for (CrewProfile.Device d : crew.devices().values()) {
            if (!first) { after(spacingMs); }
            first = false;
            on(d.name).connect().send(Messages.register(d.paths));
        }
        return this;
    }

    public Bout var(String name, String value) {
        vars.put(name, value);
        return this;
    }

    /** Autosave-format seed file, relative to the trace file (or absolute). */
    public Bout seed(String autosavePath) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("autosave", autosavePath);
        header.put("seed", s);
        return this;
    }

    /**
     * Sets the current time (ms since the trace started). Going back is allowed, so a test can add events to a
     * finished bout (a stall, a kill); {@link #build} orders events by time, keeping call order for equal times.
     */
    public Bout at(long t) {
        if (t < 0) { throw new IllegalArgumentException("negative time: " + t); }
        now = t;
        return this;
    }

    public Bout after(long ms) { return at(now + ms); }

    /** Selects the device the following device events act on. */
    public Bout on(String device) {
        if (!devices.containsKey(device)) { throw new IllegalArgumentException("unknown device " + device); }
        current = device;
        return this;
    }

    public Bout connect() { return dev("connect", map()); }

    public Bout disconnect() { return dev("disconnect", map("how", "close")); }

    /** Drops the connection without a close frame, as when Wi-Fi goes away. */
    public Bout drop() { return dev("disconnect", map("how", "drop")); }

    public Bout register(String... paths) { return send(Messages.register(Arrays.asList(paths))); }

    public Bout set(String key, Object value) { return send(Messages.set(key, value)); }

    /** Sends a message verbatim. */
    public Bout send(String msg) { return dev("send", map("msg", msg)); }

    /** Stops reading for {@code ms}, so the server's sends to this device back up. */
    public Bout stall(long ms) { return dev("stall", map("ms", ms)); }

    /** {@code WS.Set(key, value, flag)}, e.g. flag {@code "change"} for the +1 / -1 buttons. */
    public Bout set(String key, Object value, String flag) { return send(Messages.set(key, value, flag)); }

    /** An HTTP request with this device's cookie. {@code files}: maps with field, name and text/base64/from. */
    public Bout http(String method, String path, Map<String, Object> form, List<Map<String, Object>> files) {
        Map<String, Object> f = map("method", method, "path", path);
        if (form != null && !form.isEmpty()) { f.put("form", form); }
        if (files != null && !files.isEmpty()) { f.put("files", files); }
        return dev("http", f);
    }

    /** One file part for {@link #http}, with inline text content. */
    public static Map<String, Object> textFile(String field, String name, String text) {
        return map("field", field, "name", name, "text", text);
    }

    /** One file part for {@link #http}, with binary content. */
    public static Map<String, Object> binaryFile(String field, String name, byte[] content) {
        return map("field", field, "name", name, "base64", java.util.Base64.getEncoder().encodeToString(content));
    }

    /** One file part for {@link #http}, read from {@code path} (relative to the trace's directory) at replay. */
    public static Map<String, Object> fileFrom(String field, String name, String path) {
        return map("field", field, "name", name, "from", path);
    }

    /** Steps the server's wall clock by {@code ms} (whole seconds; negative steps back). Needs a ClockControl. */
    public Bout clock(long ms) {
        events.add(new Trace.Event(now, "clock", null, map("step", ms), 0));
        return this;
    }

    // Buttons, as the SBO and JT screens send them.
    public Bout startJam() { return set("ScoreBoard.Game($GAME).StartJam", true); }
    /** The "Stop Jam / End Timeout / Lineup" button. */
    public Bout stopJam() { return set("ScoreBoard.Game($GAME).StopJam", true); }
    public Bout timeout() { return set("ScoreBoard.Game($GAME).Timeout", true); }
    public Bout officialTimeout() { return set("ScoreBoard.Game($GAME).OfficialTimeout", true); }
    /** Assigns the running timeout to team 1 or 2 (or starts one), as the team Timeout button does. */
    public Bout teamTimeout(int team) { return set("ScoreBoard.Game($GAME).Team(" + team + ").Timeout", true); }
    /** The SK screen's "Trip Pts +1" button: a relative change. */
    public Bout tripScoreUp(int team) { return set("ScoreBoard.Game($GAME).Team(" + team + ").TripScore", 1, "change"); }
    public Bout clockUndo() { return set("ScoreBoard.Game($GAME).ClockUndo", true); }

    /** Within {@code withinMs}, the observer's state must have {@code key} = {@code value} (null = absent). */
    public Bout expect(long withinMs, String key, Object value) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put(key, value);
        return expect(withinMs, state);
    }

    public Bout expect(long withinMs, Map<String, Object> state) {
        events.add(new Trace.Event(now, "expect", null, map("within", withinMs, "state", state), 0));
        return this;
    }

    public Bout mark(String label) {
        events.add(new Trace.Event(now, "mark", null, map("label", label), 0));
        return this;
    }

    /** Kills the server ("kill" or "term") and optionally restarts it. */
    public Bout kill(String signal, boolean restart) {
        events.add(new Trace.Event(now, "kill", null, map("signal", signal, "restart", restart), 0));
        return this;
    }

    public Trace build() {
        List<Trace.Event> ordered = new ArrayList<>(events);
        ordered.sort((a, b) -> Long.compare(a.t, b.t)); // stable: equal times keep call order
        // Number events as they will appear in the written file (the header is line 1), so failures name the line.
        for (int i = 0; i < ordered.size(); i++) {
            Trace.Event e = ordered.get(i);
            ordered.set(i, new Trace.Event(e.t, e.ev, e.dev, e.fields, i + 2));
        }
        Trace t = new Trace(header, ordered);
        try {
            TraceReader.validate(t);
        } catch (TraceFormatException e) { throw new IllegalStateException(e.getMessage(), e); }
        return t;
    }

    private Bout dev(String ev, Map<String, Object> fields) {
        if (current == null) { throw new IllegalStateException("call on(device) first"); }
        events.add(new Trace.Event(now, ev, current, fields, 0));
        return this;
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) { m.put((String) kv[i], kv[i + 1]); }
        return m;
    }

    private final Map<String, Object> header = new LinkedHashMap<>();
    private final Map<String, Object> devices = new LinkedHashMap<>();
    private final Map<String, Object> vars = new LinkedHashMap<>();
    private final List<Trace.Event> events = new ArrayList<>();
    private long now;
    private String current;
    private CrewProfile crew;
}
