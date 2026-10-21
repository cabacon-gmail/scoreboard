package com.carolinarollergirls.scoreboard.reliability.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A per-device TCP proxy between a simulated device and the server.
 * <p>
 * Phase 2 supports pass-through, {@link #cut()} (close both sides cleanly, without a WebSocket close frame) and
 * {@link #drop()} (reset both sides). Phase 3 adds latency, jitter, stalls and half-open connections behind this
 * same class, which is why every device goes through a proxy from the start.
 * </p>
 */
public final class FaultProxy implements AutoCloseable {
    public FaultProxy(String name, int targetPort) throws IOException {
        this.name = name;
        this.targetPort = targetPort;
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread accept = new Thread(this::acceptLoop, "FaultProxy-" + name + "-accept");
        accept.setDaemon(true);
        accept.start();
    }

    public int port() { return server.getLocalPort(); }

    /** Closes every current connection with a normal TCP close (FIN). New connections are still accepted. */
    public void cut() { closeAll(false); }

    /** Resets every current connection (RST), as when Wi-Fi drops. New connections are still accepted. */
    public void drop() { closeAll(true); }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException e) {
            // ignore
        }
        closeAll(true);
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket client = server.accept();
                Socket upstream = new Socket();
                upstream.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort), 5000);
                client.setTcpNoDelay(true);
                upstream.setTcpNoDelay(true);
                Pair p = new Pair(client, upstream);
                pairs.add(p);
                pump(client, upstream, p, "up");
                pump(upstream, client, p, "down");
            } catch (IOException e) {
                if (closed) { return; }
            }
        }
    }

    private void pump(Socket from, Socket to, Pair p, String dir) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[16384];
            try (InputStream in = from.getInputStream()) {
                OutputStream out = to.getOutputStream();
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException e) {
                // connection closed
            } finally { p.close(false); }
        }, "FaultProxy-" + name + "-" + dir);
        t.setDaemon(true);
        t.start();
    }

    private void closeAll(boolean reset) {
        for (Pair p : pairs) { p.close(reset); }
        pairs.clear();
    }

    private static final class Pair {
        Pair(Socket a, Socket b) {
            this.a = a;
            this.b = b;
        }

        synchronized void close(boolean reset) {
            for (Socket s : new Socket[] {a, b}) {
                try {
                    if (reset && !s.isClosed()) { s.setSoLinger(true, 0); }
                    s.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }

        final Socket a;
        final Socket b;
    }

    private final String name;
    private final int targetPort;
    private final ServerSocket server;
    private final List<Pair> pairs = new CopyOnWriteArrayList<>();
    private volatile boolean closed;
}
