package com.carolinarollergirls.scoreboard.reliability.capture;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.jr.ob.JSON;

/**
 * A recording proxy for real games: devices talk to it on the scoreboard's usual port, it forwards everything
 * unchanged to CRG on a local-only port, and it writes what the devices sent as a trace the harness can replay.
 *
 * <pre>
 *   java -jar lib/crg-scoreboard.jar --port=8001 --host=127.0.0.1
 *   ant -f reliability/build.xml record                       (listens on 8000, forwards to 127.0.0.1:8001)
 *   ... play the game; Ctrl-C to stop. The trace is in reliability/captures/&lt;date-time&gt;/trace.crgtrace.jsonl
 * </pre>
 *
 * What it records: the starting state (from {@code /SaveJSON}, without device records), every WebSocket connect,
 * message and disconnect, and state-changing HTTP requests (POSTs, with uploaded files). What it changes: it removes
 * {@code Sec-WebSocket-Extensions} from upgrade requests, so frames are not compressed and can be read, and it
 * rewrites the list {@code /urls} returns, so the start page's links and QR codes point at the proxy. Everything else passes
 * through byte for byte. Session cookies are never written.
 */
public final class RecordingProxy {
    public static void main(String[] args) throws Exception {
        Map<String, String> o = new LinkedHashMap<>();
        o.put("--listen", "8000");
        o.put("--bind", "0.0.0.0");
        o.put("--target", "127.0.0.1:8001");
        o.put("--out", "");
        o.put("--assemble", "");
        o.put("--redact", "true");
        o.put("--crg", "");
        for (String a : args) {
            int eq = a.indexOf('=');
            if (eq < 0 || !o.containsKey(a.substring(0, eq))) {
                System.err.println("usage: java -jar crg-recorder.jar [--crg=lib/crg-scoreboard.jar] [--listen=8000] "
                                   + "[--bind=0.0.0.0] [--target=127.0.0.1:8001] [--out=<dir>] [--redact=true|false]\n"
                                   + "       java -jar crg-recorder.jar --assemble=<capture dir>");
                System.exit(2);
            }
            o.put(a.substring(0, eq), a.substring(eq + 1));
        }
        if (!o.get("--assemble").isEmpty()) {
            CaptureWriter.assemble(new File(o.get("--assemble")), Collections.emptyMap());
            System.out.println("wrote " + new File(o.get("--assemble"), CaptureWriter.TRACE));
            return;
        }
        String out = o.get("--out");
        if (out.isEmpty()) { out = "captures/" + new SimpleDateFormat("yyyy-MM-dd_HHmmss").format(new Date()); }
        String[] target = o.get("--target").split(":");
        Process crg = null;
        if (!o.get("--crg").isEmpty()) { crg = startCrg(new File(o.get("--crg")), target[0], Integer.parseInt(target[1])); }
        RecordingProxy p = new RecordingProxy(o.get("--bind"), Integer.parseInt(o.get("--listen")), target[0],
                                              Integer.parseInt(target[1]), new File(out));
        p.redact = Boolean.parseBoolean(o.get("--redact"));
        final Process child = crg;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shuttingDown = true;
            p.stop();
            if (child != null) { stopCrg(child); }
        }, "RecordingProxy-finish"));
        p.start();
        if (child != null) {
            // If CRG stops, nothing can be recorded and devices can't reach it: say so and stop too.
            Thread watch = new Thread(() -> {
                try {
                    int code = child.waitFor();
                    if (!shuttingDown) {
                        System.err.println("CRG exited (code " + code + "); stopping the recorder.");
                        System.exit(1);
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "RecordingProxy-crg-watch");
            watch.setDaemon(true);
            watch.start();
        }
        System.out.println("Recording to " + new File(out).getAbsolutePath() +
                           (p.redact ? " (names redacted)" : " (names NOT redacted)"));
        System.out.println("Open the scoreboard on other devices at:");
        for (String u : p.urls()) { System.out.println("    " + u); }
        System.out.println("Press Ctrl-C to stop; the trace is written then.");
        Thread.currentThread().join();
    }

    /**
     * Starts CRG from its jar, as its own launcher does but on a port only this computer can reach, and waits until
     * it answers. Its output goes to {@code crg-output.log} next to the jar's folder.
     */
    static Process startCrg(File jar, String host, int port) throws IOException, InterruptedException {
        File jarFile = jar.getAbsoluteFile();
        if (!jarFile.isFile()) { throw new IOException("CRG jar not found: " + jarFile); }
        File crgDir = jarFile.getParentFile().getParentFile(); // <crg>/lib/crg-scoreboard.jar
        String java = new File(new File(System.getProperty("java.home"), "bin"), "java").getPath();
        ProcessBuilder pb = new ProcessBuilder(java, "-jar", jarFile.getPath(), "--port=" + port, "--host=" + host)
                                .directory(crgDir)
                                .redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.appendTo(new File(crgDir, "crg-output.log")));
        Process p = pb.start();
        System.out.println("Starting CRG from " + crgDir + " ...");
        for (int i = 0; i < 300; i++) {
            if (!p.isAlive()) { throw new IOException("CRG exited during start-up; see " + new File(crgDir, "crg-output.log")); }
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + "/urls/").openConnection();
                c.setConnectTimeout(500);
                if (c.getResponseCode() == 200) { return p; }
            } catch (IOException e) { /* not up yet */ }
            Thread.sleep(200);
        }
        p.destroyForcibly();
        throw new IOException("CRG did not start within 60 s; see " + new File(crgDir, "crg-output.log"));
    }

    /** Asks CRG to stop the way closing its window does, then waits for it. */
    static void stopCrg(Process p) {
        p.destroy();
        try {
            if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public RecordingProxy(String bind, int listenPort, String targetHost, int targetPort, File dir) {
        this.bind = bind;
        this.listenPort = listenPort;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.dir = dir;
    }

    /** Takes the starting state from CRG, opens the listening port and starts accepting. */
    public void start() throws IOException {
        dir.mkdirs();
        redactor = redact ? new Redactor() : null;
        Map<String, Object> extras = saveSeed();
        writer = new CaptureWriter(dir, extras, redactor);
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(bind, listenPort));
        listenPort = server.getLocalPort();
        Thread t = new Thread(this::acceptLoop, "RecordingProxy-accept");
        t.setDaemon(true);
        t.start();
    }

    public int port() { return listenPort; }
    public File dir() { return dir; }
    public File trace() { return new File(dir, CaptureWriter.TRACE); }

    /** Stops accepting, closes every connection, and writes the trace. */
    public synchronized void stop() {
        if (stopped) { return; }
        stopped = true;
        try {
            server.close();
        } catch (IOException e) { /* closing anyway */ }
        synchronized (open) {
            for (Socket s : open) { closeQuietly(s); }
        }
        try {
            writer.finish();
            System.out.println("Wrote " + trace() + " (" + writer.eventCount() + " events)");
        } catch (IOException e) { System.err.println("RecordingProxy: could not write the trace: " + e); }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket c = server.accept();
                c.setTcpNoDelay(true);
                track(c);
                Thread t = new Thread(() -> handle(c), "RecordingProxy-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!server.isClosed()) { System.err.println("RecordingProxy: accept failed: " + e); }
            }
        }
    }

    /** One client connection: a sequence of HTTP requests, possibly ending in a WebSocket upgrade. */
    private void handle(Socket client) {
        Socket upstream = null;
        InputStream uin = null;
        OutputStream uout = null;
        try {
            InputStream cin = new BufferedInputStream(client.getInputStream());
            OutputStream cout = client.getOutputStream();
            while (true) {
                HttpIO.Head req = HttpIO.readHead(cin);
                if (req == null) { return; }
                String path = req.second();
                String cookie = crgCookie(req.header("Cookie"));
                boolean urlsRequest = "GET".equals(req.first()) &&
                                      (path.equals("/urls") || path.startsWith("/urls/") || path.startsWith("/urls?"));
                if (upstream == null || upstream.isClosed()) {
                    upstream = new Socket(targetHost, targetPort);
                    upstream.setTcpNoDelay(true);
                    track(upstream);
                    uin = new BufferedInputStream(upstream.getInputStream());
                    uout = upstream.getOutputStream();
                }
                if (req.headerContains("Upgrade", "websocket")) {
                    webSocket(req, cookie, client, cin, cout, upstream, uin, uout);
                    return;
                }
                boolean record = "POST".equals(req.first());
                ByteArrayOutputStream body = record ? new ByteArrayOutputStream() : null;
                uout.write(req.bytes());
                HttpIO.copyBody(req, false, null, cin, uout, body);
                uout.flush();
                if (record) { recordHttp(req, cookie, body.toByteArray()); }
                HttpIO.Head resp = HttpIO.readHead(uin);
                if (resp == null) { throw new EOFException("CRG closed the connection without answering"); }
                if (urlsRequest && resp.status() == 200) {
                    // Keep CRG's headers (it may set the session cookie here) but list the proxy's addresses.
                    boolean reusable = HttpIO.copyBody(resp, true, req.first(), uin, new ByteArrayOutputStream(), null);
                    byte[] list = (String.join("\n", urls()) + "\n").getBytes(StandardCharsets.UTF_8);
                    resp.removeHeader("Transfer-Encoding");
                    resp.removeHeader("Content-Length");
                    resp.headers.add("Content-Length: " + list.length);
                    cout.write(resp.bytes());
                    cout.write(list);
                    cout.flush();
                    if (!reusable) { return; }
                    continue;
                }
                cout.write(resp.bytes());
                boolean reusable = HttpIO.copyBody(resp, true, req.first(), uin, cout, null);
                cout.flush();
                if (!reusable || resp.headerContains("Connection", "close") ||
                    req.headerContains("Connection", "close")) {
                    return;
                }
            }
        } catch (IOException e) {
            // A device went away or CRG closed the connection: nothing to record beyond the disconnect.
        } finally {
            closeQuietly(client);
            if (upstream != null) { closeQuietly(upstream); }
        }
    }

    /** Forwards the upgrade, then pumps both directions; device-to-server text messages are recorded. */
    private void webSocket(HttpIO.Head req, String cookie, Socket client, InputStream cin, OutputStream cout,
                           Socket upstream, InputStream uin, OutputStream uout) throws IOException {
        req.removeHeader("Sec-WebSocket-Extensions"); // no permessage-deflate: frames stay readable
        uout.write(req.bytes());
        uout.flush();
        HttpIO.Head resp = HttpIO.readHead(uin);
        if (resp == null) { throw new EOFException("CRG closed the connection during the upgrade"); }
        cout.write(resp.bytes());
        cout.flush();
        if (resp.status() != 101) {
            HttpIO.copyBody(resp, true, req.first(), uin, cout, null);
            return;
        }
        Map<String, String> q = query(req.second());
        String device = writer.connect(cookie, q.getOrDefault("source", ""), q.get("platform"));
        Thread down = new Thread(() -> {
            byte[] buf = new byte[16384];
            int n;
            try {
                while ((n = uin.read(buf)) != -1) {
                    cout.write(buf, 0, n);
                    cout.flush();
                }
            } catch (IOException e) { /* either side closed */ } finally {
                closeQuietly(client);
                closeQuietly(upstream);
            }
        }, "RecordingProxy-ws-down");
        down.setDaemon(true);
        down.start();
        boolean closeFrame = false;
        try {
            closeFrame = pumpFrames(device, cin, uout);
        } finally {
            closeQuietly(client);
            closeQuietly(upstream);
            writer.disconnect(device, closeFrame);
        }
    }

    /**
     * Forwards device-to-server WebSocket frames unchanged and records each complete text message. Returns true if
     * the device sent a close frame (a clean disconnect) before the connection ended.
     */
    private boolean pumpFrames(String device, InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        boolean inText = false;
        boolean closeSeen = false;
        try {
            while (true) {
                int b0 = in.read();
                if (b0 == -1) { return closeSeen; }
                int b1 = readByte(in);
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                raw.write(b0);
                raw.write(b1);
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    int a = readByte(in), c = readByte(in);
                    raw.write(a);
                    raw.write(c);
                    len = (a << 8) | c;
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        int x = readByte(in);
                        raw.write(x);
                        len = (len << 8) | x;
                    }
                }
                byte[] mask = new byte[4];
                if (masked) {
                    for (int i = 0; i < 4; i++) {
                        mask[i] = (byte) readByte(in);
                        raw.write(mask[i]);
                    }
                }
                if (len > 16 * 1024 * 1024) { throw new IOException("frame too large: " + len); }
                byte[] payload = new byte[(int) len];
                int got = 0;
                while (got < len) {
                    int r = in.read(payload, got, (int) len - got);
                    if (r == -1) { throw new EOFException("frame cut short"); }
                    got += r;
                }
                raw.write(payload);
                out.write(raw.toByteArray());
                out.flush();
                if (masked) {
                    for (int i = 0; i < payload.length; i++) { payload[i] ^= mask[i % 4]; }
                }
                if (opcode == 0x1 || (opcode == 0x0 && inText)) {
                    message.write(payload);
                    inText = !fin;
                    if (fin) {
                        writer.send(device, new String(message.toByteArray(), StandardCharsets.UTF_8));
                        message.reset();
                    }
                } else if (opcode == 0x8) {
                    closeSeen = true;
                }
            }
        } catch (IOException e) { return closeSeen; }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) { throw new EOFException(); }
        return b;
    }

    /** Records a POST: multipart parts become form fields and saved files; a urlencoded body joins the query. */
    private void recordHttp(HttpIO.Head req, String cookie, byte[] body) throws IOException {
        String path = req.second();
        String type = req.header("Content-Type");
        Map<String, String> form = new LinkedHashMap<>();
        List<Map<String, Object>> files = new ArrayList<>();
        if (type != null && type.toLowerCase().startsWith("multipart/form-data")) {
            for (Multipart.Part part : Multipart.parse(type, body)) {
                if (part.filename == null) {
                    form.put(part.name, new String(part.content, StandardCharsets.UTF_8));
                } else {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("field", part.name);
                    f.put("name", part.filename);
                    byte[] content = part.content;
                    if (redactor != null && path.toLowerCase().startsWith("/load/")) {
                        content = redactUpload(path, content);
                        if (content == null) {
                            content = new byte[0];
                            f.put("redacted", "content not kept: names can't be removed from this kind of file");
                        }
                    }
                    f.put("from", writer.saveFile(part.filename, content));
                    files.add(f);
                }
            }
        } else if (body.length > 0 && type != null &&
                   type.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
            String qs = new String(body, StandardCharsets.UTF_8);
            path = path + (path.contains("?") ? "&" : "?") + qs;
        }
        writer.http(cookie, req.first(), path, form, files);
    }

    /** A loaded JSON game has its names redacted; other loads (spreadsheets) can't be, so return null. */
    @SuppressWarnings("unchecked")
    private byte[] redactUpload(String path, byte[] content) {
        if (!path.toLowerCase().startsWith("/load/json")) { return null; }
        try {
            Map<String, Object> root = new LinkedHashMap<>(JSON.std.mapFrom(content));
            if (root.get("state") instanceof Map) {
                root.put("state", redactor.state((Map<String, Object>) root.get("state")));
            }
            return JSON.std.asBytes(root);
        } catch (IOException e) { return null; }
    }

    /** The addresses devices can use, as CRG's own /urls lists them, but on the proxy's port. */
    public List<String> urls() {
        TreeSet<String> urls = new TreeSet<>();
        try {
            if ("0.0.0.0".equals(bind) || "::".equals(bind)) {
                for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    for (InetAddress a : Collections.list(iface.getInetAddresses())) {
                        if (!a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                            urls.add(new java.net.URI("http", null, a.getHostAddress(), listenPort, "/", null, null)
                                         .toString());
                        }
                    }
                }
            } else {
                urls.add(new java.net.URI("http", null, bind, listenPort, "/", null, null).toString());
            }
        } catch (Exception e) { /* list what we have */ }
        return new ArrayList<>(urls);
    }

    /** Fetches the starting state from CRG and writes it as the seed, without device and client records. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> saveSeed() throws IOException {
        URL u = new URL("http://" + targetHost + ":" + targetPort + "/SaveJSON");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(30000);
        Map<String, Object> root;
        try (InputStream in = c.getInputStream()) {
            root = JSON.std.mapFrom(in);
        } catch (IOException e) {
            throw new IOException("could not read the starting state from CRG at " + u +
                                  "; start CRG first (e.g. --port=" + targetPort + " --host=127.0.0.1)", e);
        }
        Map<String, Object> state = (Map<String, Object>) root.get("state");
        TreeMap<String, Object> kept = new TreeMap<>();
        for (Map.Entry<String, Object> e : state.entrySet()) {
            if (!e.getKey().startsWith("ScoreBoard.Clients.")) { kept.put(e.getKey(), e.getValue()); }
        }
        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("state", redactor != null ? redactor.state(kept) : kept);
        JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT).write(seed, new File(dir, CaptureWriter.SEED));
        Map<String, Object> extras = new LinkedHashMap<>();
        StringBuilder version = new StringBuilder();
        for (Map.Entry<String, Object> e : kept.entrySet()) {
            if (e.getKey().startsWith("ScoreBoard.Version(")) {
                version.append(version.length() > 0 ? " " : "").append(e.getKey().substring(19).replace(")", "="))
                    .append(e.getValue());
            }
        }
        extras.put("crgVersion", version.length() > 0 ? version.toString() : "unknown");
        extras.put("redacted", redactor != null);
        extras.put("notes", "Captured by RecordingProxy from " + targetHost + ":" + targetPort + " starting " +
                                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        return extras;
    }

    static Map<String, String> query(String target) {
        Map<String, String> q = new LinkedHashMap<>();
        int i = target.indexOf('?');
        if (i < 0) { return q; }
        for (String kv : target.substring(i + 1).split("&")) {
            int eq = kv.indexOf('=');
            try {
                if (eq > 0) {
                    q.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"), URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                }
            } catch (Exception e) { /* skip malformed */ }
        }
        return q;
    }

    static String crgCookie(String header) {
        if (header == null) { return null; }
        for (String c : header.split(";")) {
            String t = c.trim();
            if (t.startsWith("CRG_SCOREBOARD=")) { return t.substring("CRG_SCOREBOARD=".length()); }
        }
        return null;
    }

    private void track(Socket s) {
        synchronized (open) {
            open.removeIf(Socket::isClosed);
            open.add(s);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException e) { /* already closed */ }
    }

    private final String bind;
    private int listenPort;
    private final String targetHost;
    private final int targetPort;
    private final File dir;
    private final List<Socket> open = new ArrayList<>();
    private ServerSocket server;
    private CaptureWriter writer;
    private static volatile boolean shuttingDown;
    /** Redact names (the default). Set before {@link #start}. */
    public boolean redact = true;
    private Redactor redactor;
    private boolean stopped;
}
