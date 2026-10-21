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

    /** Sets the current time (ms since the trace started). */
    public Bout at(long t) {
        if (t < now) { throw new IllegalArgumentException("time goes backwards: " + t + " < " + now); }
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

    // Buttons, as the SBO and JT screens send them.
    public Bout startJam() { return set("ScoreBoard.Game($GAME).StartJam", true); }
    /** The "Stop Jam / End Timeout / Lineup" button. */
    public Bout stopJam() { return set("ScoreBoard.Game($GAME).StopJam", true); }
    public Bout timeout() { return set("ScoreBoard.Game($GAME).Timeout", true); }
    public Bout officialTimeout() { return set("ScoreBoard.Game($GAME).OfficialTimeout", true); }
    /** Assigns the running timeout to team 1 or 2 (or starts one), as the team Timeout button does. */
    public Bout teamTimeout(int team) { return set("ScoreBoard.Game($GAME).Team(" + team + ").Timeout", true); }
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
        Trace t = new Trace(header, events);
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

    private static Map<String, Object> map(Object... kv) {
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
}
