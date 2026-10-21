package com.carolinarollergirls.scoreboard.reliability.harness;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.fasterxml.jackson.jr.ob.JSON;

/**
 * One simulated device: its own session cookie (device identity) and a WebSocket to {@code /WS/}, optionally through
 * a {@link FaultProxy}.
 * <p>
 * It mirrors the server's state the way {@code WS.js} does (a {@code null} value deletes the key) and timestamps every
 * frame in and out on the monotonic clock. A device keeps its cookie across reconnects, like a browser.
 * </p>
 */
public final class DeviceClient implements AutoCloseable {
    /** One frame, with the monotonic time it was sent or fully received. */
    public static final class Frame {
        Frame(long nanos, boolean outgoing, String text) {
            this.nanos = nanos;
            this.outgoing = outgoing;
            this.text = text;
        }

        public final long nanos;
        public final boolean outgoing;
        public final String text;
    }

    public DeviceClient(String name, int serverPort, String source, String platform) {
        this.name = name;
        this.serverPort = serverPort;
        this.source = source;
        this.platform = platform;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public String name() { return name; }

    /** Connects to {@code /WS/} on {@code port} (the server's, or a proxy's). Gets a cookie first if it has none. */
    public synchronized void connect(int port) throws IOException {
        if (cookie == null) { cookie = fetchCookie(); }
        String url = "ws://127.0.0.1:" + port + "/WS/?source=" + enc(source) + "&platform=" + enc(platform);
        try {
            ws = http.newWebSocketBuilder()
                     .header("Cookie", cookie)
                     .connectTimeout(Duration.ofSeconds(10))
                     .buildAsync(URI.create(url), new Listener())
                     .get(15, TimeUnit.SECONDS);
        } catch (Exception e) { throw new IOException(name + ": WebSocket connect failed: " + e, e); }
        connected = true;
    }

    /** Sends one text frame exactly as given. */
    public void send(String text) throws IOException {
        WebSocket w = ws;
        if (w == null || !connected) { throw new IOException(name + ": not connected"); }
        frames.add(new Frame(System.nanoTime(), true, text));
        try {
            w.sendText(text, true).get(10, TimeUnit.SECONDS);
        } catch (Exception e) { throw new IOException(name + ": send failed: " + e, e); }
    }

    /** Sends {@code {"action":"Set","key":key,"value":value,"flag":""}}, as WS.js does. */
    public void set(String key, Object value) throws IOException { send(Messages.set(key, value)); }

    public void register(String... paths) throws IOException { send(Messages.register(paths)); }

    /** Closes cleanly with a WebSocket close frame. */
    public void disconnect() {
        WebSocket w = ws;
        connected = false;
        if (w != null) {
            try {
                w.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
            } catch (Exception e) { w.abort(); }
        }
    }

    /** Stops reading frames, so the server's sends to this device back up. */
    public void pauseReading() { paused = true; }

    public void resumeReading() {
        paused = false;
        WebSocket w = ws;
        if (w != null) { w.request(1); }
    }

    public boolean isConnected() { return connected; }

    /** A copy of the mirrored state. */
    public Map<String, Object> state() {
        synchronized (state) { return new LinkedHashMap<>(state); }
    }

    public Object get(String key) {
        synchronized (state) { return state.get(key); }
    }

    /** Waits until {@code condition} holds for the mirrored state; returns false on timeout. */
    public boolean await(Predicate<Map<String, Object>> condition, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (state) {
            while (!condition.test(state)) {
                long left = deadline - System.nanoTime();
                if (left <= 0) { return false; }
                try {
                    state.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /** Waits until {@code key} equals {@code value} (compared as JSON values). */
    public boolean awaitValue(String key, Object value, long timeoutMs) {
        return await(s -> Messages.jsonEquals(s.get(key), value), timeoutMs);
    }

    public List<Frame> frames() {
        synchronized (frames) { return new ArrayList<>(frames); }
    }

    /** Messages the server sent that were not state updates (authorization errors, errors). */
    public List<String> serverErrors() {
        synchronized (errors) { return new ArrayList<>(errors); }
    }

    @Override
    public void close() {
        disconnect();
        WebSocket w = ws;
        if (w != null) { w.abort(); }
    }

    private String fetchCookie() throws IOException {
        // Any request creates the session; /urls/ works without the html/ tree.
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/urls/"))
                              .timeout(Duration.ofSeconds(10))
                              .build();
        try {
            HttpResponse<Void> r = http.send(req, HttpResponse.BodyHandlers.discarding());
            for (String c : r.headers().allValues("set-cookie")) {
                if (c.startsWith("CRG_SCOREBOARD=")) { return c.split(";", 2)[0]; }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(name + ": interrupted while fetching a session cookie");
        }
        throw new IOException(name + ": server set no CRG_SCOREBOARD cookie");
    }

    @SuppressWarnings("unchecked")
    private void apply(String text) {
        Object parsed;
        try {
            parsed = JSON.std.anyFrom(text);
        } catch (IOException e) { return; }
        if (!(parsed instanceof Map)) { return; }
        Map<String, Object> msg = (Map<String, Object>) parsed;
        if (msg.containsKey("authorization") || msg.containsKey("error")) {
            synchronized (errors) { errors.add(text); }
        }
        Object st = msg.get("state");
        if (st instanceof Map) {
            synchronized (state) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) st).entrySet()) {
                    if (e.getValue() == null) {
                        state.remove(e.getKey());
                    } else {
                        state.put(e.getKey(), e.getValue());
                    }
                }
                state.notifyAll();
            }
        }
    }

    private final class Listener implements WebSocket.Listener {
        @Override
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                frames.add(new Frame(System.nanoTime(), false, text));
                apply(text);
            }
            if (!paused) { w.request(1); }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket w, int status, String reason) {
            connected = false;
            return null;
        }

        @Override
        public void onError(WebSocket w, Throwable error) {
            connected = false;
        }

        private final StringBuilder partial = new StringBuilder();
    }

    private static String enc(String s) { return URLEncoder.encode(Objects.toString(s, ""), StandardCharsets.UTF_8); }

    private final String name;
    private final int serverPort;
    private final String source;
    private final String platform;
    private final HttpClient http;
    private final Map<String, Object> state = new LinkedHashMap<>();
    private final List<Frame> frames = Collections.synchronizedList(new ArrayList<>());
    private final List<String> errors = new ArrayList<>();
    private volatile WebSocket ws;
    private volatile boolean connected;
    private volatile boolean paused;
    private String cookie;
}
