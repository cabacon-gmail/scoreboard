package com.carolinarollergirls.scoreboard.reliability.capture;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.carolinarollergirls.scoreboard.reliability.harness.Trace;
import com.fasterxml.jackson.jr.ob.JSON;

/**
 * Writes what the proxy sees as a trace (Phase 2 design, section 3).
 * <p>
 * Events go to {@code events.partial.jsonl} as they happen, one flushed line each, so a crash loses nothing that was
 * written. The header can only be written once every device is known, so {@link #finish} (also run from a shutdown
 * hook) assembles {@code trace.crgtrace.jsonl} from the header and the events. {@link #assemble} does the same for
 * the directory of a proxy that died without finishing.
 * </p>
 * <p>
 * Session cookies are never written. Each connection's device is named after the page that opened it ({@code sbo},
 * {@code standard}, {@code sbo-2} for a second SBO open at the same time); a device that reconnects to the same
 * page continues as the same trace device. HTTP requests are tied to a
 * device by a hash of its cookie, salted with a value that is never written; the finished trace drops the hash.
 * </p>
 */
final class CaptureWriter {
    static final String EVENTS = "events.partial.jsonl";
    static final String DEVICES = "devices.json";
    static final String TRACE = "trace.crgtrace.jsonl";
    static final String SEED = "seed.json";
    static final String CAPTURE_INFO = "capture.json";

    CaptureWriter(File dir, Map<String, Object> headerExtras, Redactor redactor) throws IOException {
        this.dir = dir;
        this.redactor = redactor;
        this.headerExtras = headerExtras;
        new File(dir, "files").mkdirs();
        // Kept so --assemble can rebuild the header after a crash.
        JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT).write(headerExtras, new File(dir, CAPTURE_INFO));
        events = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, EVENTS), true),
                                                           StandardCharsets.UTF_8));
        salt = Long.toHexString(new java.security.SecureRandom().nextLong());
        start = System.nanoTime();
    }

    /** A new WebSocket connection: returns its trace device name and records the connect. */
    synchronized String connect(String cookie, String source, String platform) throws IOException {
        // A device reconnecting (same cookie, same page) continues as the same trace device.
        String id = cookieId(cookie);
        for (Map.Entry<String, Map<String, Object>> e : devices.entrySet()) {
            Map<String, Object> d = e.getValue();
            if (id.equals(d.get("cookie")) && source.equals(d.get("source")) && !connected.contains(e.getKey())) {
                connected.add(e.getKey());
                event("connect", e.getKey(), new LinkedHashMap<>());
                return e.getKey();
            }
        }
        String base = baseName(source);
        int n = nameCounts.merge(base, 1, Integer::sum);
        String name = n == 1 ? base : base + "-" + n;
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("source", source);
        d.put("platform", platform == null ? "" : platform);
        d.put("cookie", id);
        devices.put(name, d);
        connected.add(name);
        writeDevices();
        event("connect", name, new LinkedHashMap<>());
        return name;
    }

    synchronized void send(String device, String text) throws IOException {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("msg", redactor == null ? text : redactor.message(text));
        event("send", device, f);
    }

    synchronized void disconnect(String device, boolean clean) throws IOException {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("how", clean ? "close" : "drop");
        connected.remove(device);
        event("disconnect", device, f);
    }

    /**
     * A state-changing HTTP request. The device is the most recent WebSocket device with the same cookie, or a new
     * HTTP-only device.
     */
    synchronized void http(String cookie, String method, String path, Map<String, String> form,
                           List<Map<String, Object>> files) throws IOException {
        String device = null;
        String id = cookieId(cookie);
        for (Map.Entry<String, Map<String, Object>> e : devices.entrySet()) {
            if (id.equals(e.getValue().get("cookie"))) { device = e.getKey(); }
        }
        if (device == null) {
            device = "http-" + (++httpDevices);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("source", "/");
            d.put("platform", "http");
            d.put("cookie", id);
            devices.put(device, d);
            writeDevices();
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("method", method);
        f.put("path", path);
        if (form != null && !form.isEmpty()) { f.put("form", form); }
        if (files != null && !files.isEmpty()) { f.put("files", files); }
        event("http", device, f);
    }

    synchronized void mark(String label) throws IOException {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("label", label);
        event("mark", null, f);
    }

    /** Saves an uploaded file's bytes; returns the path to put in the trace, relative to the trace. */
    synchronized String saveFile(String originalName, byte[] content) throws IOException {
        String safe = originalName == null ? "upload" : originalName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 60) { safe = safe.substring(safe.length() - 60); }
        String rel = "files/" + String.format("%04d", ++fileCount) + "-" + safe;
        Files.write(new File(dir, rel).toPath(), content);
        return rel;
    }

    private void event(String ev, String device, Map<String, Object> fields) throws IOException {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("t", (System.nanoTime() - start) / 1_000_000L);
        line.put("wall", System.currentTimeMillis());
        line.put("ev", ev);
        if (device != null) { line.put("dev", device); }
        line.putAll(fields);
        events.write(JSON.std.asString(line));
        events.write("\n");
        events.flush();
        count++;
    }

    int eventCount() { return count; }

    private void writeDevices() throws IOException {
        File tmp = new File(dir, DEVICES + ".tmp");
        JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT).write(devices, tmp);
        Files.move(tmp.toPath(), new File(dir, DEVICES).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Closes the event log and writes the finished trace. Safe to call twice. */
    synchronized void finish() throws IOException {
        if (finished) { return; }
        finished = true;
        events.close();
        assemble(dir, headerExtras);
    }

    /** Builds {@code trace.crgtrace.jsonl} from a capture directory's devices, seed and events. */
    @SuppressWarnings("unchecked")
    static void assemble(File dir, Map<String, Object> headerExtras) throws IOException {
        File info = new File(dir, CAPTURE_INFO);
        if (headerExtras.isEmpty() && info.isFile()) { headerExtras = JSON.std.mapFrom(info); }
        Map<String, Object> devs = new LinkedHashMap<>();
        File df = new File(dir, DEVICES);
        if (df.isFile()) {
            for (Map.Entry<String, Object> e : JSON.std.mapFrom(df).entrySet()) {
                Map<String, Object> d = new LinkedHashMap<>((Map<String, Object>) e.getValue());
                d.remove("cookie"); // only meaningful during capture
                devs.put(e.getKey(), d);
            }
        }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("trace", Trace.VERSION);
        header.put("crgVersion", headerExtras.getOrDefault("crgVersion", "unknown"));
        header.put("origin", "captured");
        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("autosave", SEED);
        header.put("seed", seed);
        header.put("devices", devs);
        header.put("vars", new LinkedHashMap<>());
        header.put("redacted", Boolean.TRUE.equals(headerExtras.get("redacted")));
        header.put("notes", headerExtras.getOrDefault("notes", "Captured by RecordingProxy"));
        List<String> lines = new ArrayList<>();
        lines.add(JSON.std.asString(header));
        File ef = new File(dir, EVENTS);
        if (ef.isFile()) {
            for (String l : Files.readAllLines(ef.toPath(), StandardCharsets.UTF_8)) {
                if (!l.trim().isEmpty()) { lines.add(l); }
            }
        }
        File tmp = new File(dir, TRACE + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (String l : lines) { w.write(l + "\n"); }
        }
        Files.move(tmp.toPath(), new File(dir, TRACE).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** {@code /nso/sbo?game=…} becomes {@code sbo}; {@code /views/standard} becomes {@code standard}. */
    static String baseName(String source) {
        String path = source == null ? "" : source;
        int q = path.indexOf('?');
        if (q >= 0) { path = path.substring(0, q); }
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i].replaceAll("[^A-Za-z0-9_-]", "");
            if (!p.isEmpty() && !p.equalsIgnoreCase("index.html")) { return p.toLowerCase(java.util.Locale.ROOT); }
        }
        return "index";
    }

    private String cookieId(String cookie) {
        if (cookie == null) { return "none"; }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((salt + cookie).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) { sb.append(String.format("%02x", h[i])); }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private final File dir;
    private final Map<String, Object> headerExtras;
    private final Writer events;
    private final String salt;
    private final long start;
    private final Map<String, Map<String, Object>> devices = new LinkedHashMap<>();
    private final Map<String, Integer> nameCounts = new HashMap<>();
    private final java.util.Set<String> connected = new java.util.HashSet<>();
    private final Redactor redactor;
    private int httpDevices;
    private int fileCount;
    private int count;
    private boolean finished;
}
