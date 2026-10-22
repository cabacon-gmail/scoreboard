package com.carolinarollergirls.scoreboard.reliability.harness;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

/**
 * Executes a trace against a running server: the only code in the harness that talks to the server on a device's
 * behalf.
 * <p>
 * An observer device connects directly (bypassing proxies) and registers everything; it is the ground truth for
 * {@code expect} events and for oracles. Each trace device connects through its own {@link FaultProxy}.
 * </p>
 */
public final class Replayer {
    public enum Timing {
        /** Wait until each event's t. Needed whenever game clocks matter. */
        REALTIME,
        /** Send each event as soon as the previous one is done. */
        ASAP
    }

    public static final class Options {
        public Timing timing = Timing.REALTIME;
        public boolean stopAtFirstFailure = false;
        /** Directory that relative file names in {@code http} events resolve against. */
        public java.io.File baseDir = new java.io.File(".");
        /**
         * Leave devices and the observer connected after the last event, so oracles can check convergence. The
         * caller then closes them with {@link Result#close()} (closing the server does too).
         */
        public boolean keepConnected = false;
        /** Called after each event is executed, for checks that must happen mid-trace. Exceptions are failures. */
        public EventHook afterEvent;
        /** Steps the server's wall clock for {@code clock} events; null rejects them. */
        public ClockControl clock;
    }

    /** See {@link Options#afterEvent}. */
    public interface EventHook {
        void after(Trace.Event event, Result result) throws Exception;
    }

    /** The response to one {@code http} event. */
    public static final class HttpExchange {
        HttpExchange(Trace.Event event, DeviceClient.HttpResult response) {
            this.event = event;
            this.response = response;
        }

        public final Trace.Event event;
        public final DeviceClient.HttpResult response;
    }

    /** A failed expect, or an event that could not be executed. */
    public static final class Failure {
        Failure(Trace.Event event, String message) {
            this.event = event;
            this.message = message;
        }

        @Override
        public String toString() {
            return event + ": " + message;
        }

        public final Trace.Event event;
        public final String message;
    }

    public static final class Result {
        public final List<Failure> failures = new ArrayList<>();
        public final Map<String, DeviceClient> devices = new LinkedHashMap<>();
        public DeviceClient observer;
        public final List<HttpExchange> http = new ArrayList<>();
        public Map<String, Object> vars;

        /** The observer's final state. */
        public Map<String, Object> state() { return observer.state(); }

        public boolean ok() { return failures.isEmpty(); }

        /** Closes every device and the observer. */
        public void close() {
            for (DeviceClient d : devices.values()) { d.close(); }
            if (observer != null) { observer.close(); }
            for (FaultProxy p : proxies.values()) { p.close(); }
        }

        final Map<String, FaultProxy> proxies = new LinkedHashMap<>();
    }

    private Replayer(Trace trace, ServerLauncher server, Options options) {
        this.trace = trace;
        this.server = server;
        this.options = options;
    }

    /** Replays {@code trace} against {@code server} and returns what happened. Devices are closed afterwards. */
    public static Result replay(Trace trace, ServerLauncher server, Options options) throws IOException {
        return new Replayer(trace, server, options).run();
    }

    public static Result replay(Trace trace, ServerLauncher server) throws IOException {
        return replay(trace, server, new Options());
    }

    private Result run() throws IOException {
        Result result = new Result();
        try {
            connectObserver(result);
            result.vars = resolveVars(result.observer);
            long start = System.nanoTime();
            for (Trace.Event e : trace.events()) {
                if (options.timing == Timing.REALTIME) { sleepUntil(start + TimeUnit.MILLISECONDS.toNanos(e.t)); }
                try {
                    execute(e, result);
                    if (options.afterEvent != null) { options.afterEvent.after(e, result); }
                } catch (Exception ex) {
                    result.failures.add(new Failure(e, "could not execute: " + ex));
                }
                if (options.stopAtFirstFailure && !result.failures.isEmpty()) { break; }
            }
        } finally {
            result.proxies.putAll(proxies);
            if (!options.keepConnected) { result.close(); }
        }
        return result;
    }

    private void connectObserver(Result result) throws IOException {
        result.observer = new DeviceClient("observer", server.port(), "/reliability/observer", "observer");
        result.observer.connect(server.port());
        result.observer.register("ScoreBoard");
    }

    private Map<String, Object> resolveVars(DeviceClient observer) throws IOException {
        Map<String, Object> vars = new LinkedHashMap<>(trace.vars());
        if (!vars.containsKey("GAME")) {
            if (!observer.await(s -> s.get("ScoreBoard.CurrentGame.Game") instanceof String, 10_000)) {
                throw new IOException("setup: server reported no current game within 10 s");
            }
            vars.put("GAME", observer.get("ScoreBoard.CurrentGame.Game"));
        }
        return vars;
    }

    @SuppressWarnings("unchecked")
    private void execute(Trace.Event e, Result result) throws IOException {
        switch (e.ev) {
        case "connect": {
            DeviceClient d = result.devices.get(e.dev);
            if (d == null) {
                Map<String, Object> spec = trace.devices().get(e.dev);
                String source = subst(String.valueOf(spec.getOrDefault("source", "")), result.vars);
                String platform = String.valueOf(spec.getOrDefault("platform", "trace"));
                d = new DeviceClient(e.dev, server.port(), source, platform);
                result.devices.put(e.dev, d);
            }
            FaultProxy p = proxies.get(e.dev);
            if (p == null) {
                p = new FaultProxy(e.dev, server.port());
                proxies.put(e.dev, p);
            }
            d.connect(p.port());
            break;
        }
        case "send": {
            Object msg = e.get("msg");
            String text = msg instanceof String ? (String) msg : Messages.write(msg);
            device(e, result).send(subst(text, result.vars));
            break;
        }
        case "disconnect":
            if ("drop".equals(e.get("how"))) {
                proxies.get(e.dev).drop();
            } else {
                device(e, result).disconnect();
            }
            break;
        case "stall": {
            DeviceClient d = device(e, result);
            long ms = ((Number) e.get("ms")).longValue();
            d.pauseReading();
            Thread resume = new Thread(() -> {
                sleep(ms);
                d.resumeReading();
            }, "stall-" + e.dev);
            resume.setDaemon(true);
            resume.start();
            break;
        }
        case "kill": {
            ServerLauncher.Signal sig =
                "term".equals(e.get("signal")) ? ServerLauncher.Signal.TERM : ServerLauncher.Signal.KILL;
            server.kill(sig);
            if (Boolean.TRUE.equals(e.get("restart"))) {
                server.restart();
                result.observer.close();
                connectObserver(result);
            }
            break;
        }
        case "http": {
            DeviceClient d = deviceForHttp(e, result);
            String method = String.valueOf(e.get("method"));
            String path = subst(String.valueOf(e.get("path")), result.vars);
            Map<String, String> form = new LinkedHashMap<>();
            if (e.get("form") instanceof Map) {
                for (Map.Entry<String, Object> f : ((Map<String, Object>) e.get("form")).entrySet()) {
                    form.put(f.getKey(), subst(String.valueOf(f.getValue()), result.vars));
                }
            }
            List<DeviceClient.Upload> files = new ArrayList<>();
            if (e.get("files") instanceof List) {
                for (Object o : (List<Object>) e.get("files")) { files.add(upload((Map<String, Object>) o)); }
            }
            result.http.add(new HttpExchange(e, d.http(method, path, form, files)));
            break;
        }
        case "clock": {
            if (options.clock == null || !options.clock.available()) {
                throw new IOException("a 'clock' event needs Options.clock (ClockControl) to be available");
            }
            options.clock.step(((Number) e.get("step")).longValue());
            break;
        }
        case "expect": expect(e, (Map<String, Object>) e.get("state"), result); break;
        case "recv":
        case "mark": break; // informational
        default: throw new IOException("event type '" + e.ev + "' is not supported by this replayer yet");
        }
    }

    private void expect(Trace.Event e, Map<String, Object> raw, Result result) {
        DeviceClient who = e.dev != null ? result.devices.get(e.dev) : result.observer;
        long within = e.get("within") instanceof Number ? ((Number) e.get("within")).longValue() : 0L;
        Map<String, Object> want = new LinkedHashMap<>();
        for (Map.Entry<String, Object> w : raw.entrySet()) {
            Object v = w.getValue() instanceof String ? subst((String) w.getValue(), result.vars) : w.getValue();
            want.put(subst(w.getKey(), result.vars), v);
        }
        boolean ok = who.await(s -> matches(s, want), within);
        if (!ok) {
            Map<String, Object> actual = who.state();
            StringBuilder sb = new StringBuilder("expect failed after " + within + " ms:");
            for (Map.Entry<String, Object> w : want.entrySet()) {
                Object a = actual.get(w.getKey());
                if (!Messages.jsonEquals(a, w.getValue())) {
                    sb.append(" ")
                        .append(w.getKey())
                        .append(" expected ")
                        .append(w.getValue())
                        .append(" but was ")
                        .append(a)
                        .append(";");
                }
            }
            result.failures.add(new Failure(e, sb.toString()));
        }
    }

    private static boolean matches(Map<String, Object> s, Map<String, Object> want) {
        for (Map.Entry<String, Object> w : want.entrySet()) {
            if (!Messages.jsonEquals(s.get(w.getKey()), w.getValue())) { return false; }
        }
        return true;
    }

    /** HTTP needs only a cookie, so a device may make requests before it opens a WebSocket. */
    private DeviceClient deviceForHttp(Trace.Event e, Result result) {
        DeviceClient d = result.devices.get(e.dev);
        if (d == null) {
            Map<String, Object> spec = trace.devices().get(e.dev);
            d = new DeviceClient(e.dev, server.port(), subst(String.valueOf(spec.getOrDefault("source", "")), result.vars),
                                 String.valueOf(spec.getOrDefault("platform", "trace")));
            result.devices.put(e.dev, d);
        }
        return d;
    }

    /** A file part: {@code {"field","name"}} plus one of {@code text}, {@code base64} or {@code from} (a path). */
    private DeviceClient.Upload upload(Map<String, Object> f) throws IOException {
        String field = String.valueOf(f.getOrDefault("field", "file"));
        String name = String.valueOf(f.get("name"));
        byte[] content;
        if (f.get("text") != null) {
            content = String.valueOf(f.get("text")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } else if (f.get("base64") != null) {
            content = java.util.Base64.getDecoder().decode(String.valueOf(f.get("base64")));
        } else if (f.get("from") != null) {
            java.io.File src = new java.io.File(String.valueOf(f.get("from")));
            if (!src.isAbsolute()) { src = new java.io.File(options.baseDir, src.getPath()); }
            content = java.nio.file.Files.readAllBytes(src.toPath());
        } else {
            throw new IOException("file part '" + name + "' needs text, base64 or from");
        }
        return new DeviceClient.Upload(field, name, content);
    }

    private DeviceClient device(Trace.Event e, Result result) throws IOException {
        DeviceClient d = result.devices.get(e.dev);
        if (d == null) { throw new IOException("device '" + e.dev + "' has not connected yet"); }
        return d;
    }

    static String subst(String s, Map<String, Object> vars) {
        Matcher m = TraceReader.VAR.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            Object v = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group(0) : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static void sleepUntil(long nanoDeadline) {
        long left = nanoDeadline - System.nanoTime();
        if (left > 0) { sleep(TimeUnit.NANOSECONDS.toMillis(left) + 1); }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private final Trace trace;
    private final ServerLauncher server;
    private final Options options;
    private final Map<String, FaultProxy> proxies = new LinkedHashMap<>();
}
