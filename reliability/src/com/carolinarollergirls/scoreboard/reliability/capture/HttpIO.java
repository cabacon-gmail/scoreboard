package com.carolinarollergirls.scoreboard.reliability.capture;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Just enough HTTP/1.1 for a forwarding proxy: read a message head, find where its body ends (Content-Length,
 * chunked, or until close), and copy bytes through unchanged.
 */
final class HttpIO {
    private HttpIO() {}

    /** A request or response head: the start line and the header lines, as received. */
    static final class Head {
        Head(String startLine, List<String> headers) {
            this.startLine = startLine;
            this.headers = headers;
        }

        String header(String name) {
            for (String h : headers) {
                int c = h.indexOf(':');
                if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase(name)) { return h.substring(c + 1).trim(); }
            }
            return null;
        }

        void removeHeader(String name) {
            headers.removeIf(h -> {
                int c = h.indexOf(':');
                return c > 0 && h.substring(0, c).trim().equalsIgnoreCase(name);
            });
        }

        boolean headerContains(String name, String token) {
            String v = header(name);
            return v != null && v.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT));
        }

        /** Request method, or the HTTP version for a response. */
        String first() { return startLine.split(" ", 3)[0]; }

        /** Request target, or the status code for a response. */
        String second() {
            String[] p = startLine.split(" ", 3);
            return p.length > 1 ? p[1] : "";
        }

        int status() {
            try {
                return Integer.parseInt(second());
            } catch (NumberFormatException e) { return 0; }
        }

        byte[] bytes() {
            StringBuilder sb = new StringBuilder(startLine).append("\r\n");
            for (String h : headers) { sb.append(h).append("\r\n"); }
            sb.append("\r\n");
            return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        }

        final String startLine;
        final List<String> headers;
    }

    /** Reads a head; null on a clean EOF before the first byte. */
    static Head readHead(InputStream in) throws IOException {
        String start = readLine(in, true);
        if (start == null) { return null; }
        while (start.isEmpty()) { // tolerate stray CRLFs between messages
            start = readLine(in, true);
            if (start == null) { return null; }
        }
        List<String> headers = new ArrayList<>();
        for (String line = readLine(in, false); !line.isEmpty(); line = readLine(in, false)) { headers.add(line); }
        return new Head(start, headers);
    }

    private static String readLine(InputStream in, boolean eofOk) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                byte[] a = b.toByteArray();
                int len = a.length > 0 && a[a.length - 1] == '\r' ? a.length - 1 : a.length;
                return new String(a, 0, len, StandardCharsets.ISO_8859_1);
            }
            b.write(c);
            if (b.size() > 64 * 1024) { throw new IOException("header line too long"); }
        }
        if (eofOk && b.size() == 0) { return null; }
        throw new EOFException("connection closed in the middle of a header");
    }

    /**
     * Copies a message body from {@code in} to {@code out}, as framed by {@code head}. If {@code keep} is not null,
     * the decoded body is also collected there. Returns false if the body ends at connection close (so the
     * connection can't be reused).
     */
    static boolean copyBody(Head head, boolean isResponse, String requestMethod, InputStream in, OutputStream out,
                            ByteArrayOutputStream keep) throws IOException {
        if (isResponse) {
            int s = head.status();
            if ((s >= 100 && s < 200) || s == 204 || s == 304 || "HEAD".equalsIgnoreCase(requestMethod)) {
                return true;
            }
        }
        if (head.headerContains("Transfer-Encoding", "chunked")) {
            copyChunked(in, out, keep);
            return true;
        }
        String cl = head.header("Content-Length");
        if (cl != null) {
            copyN(in, out, Long.parseLong(cl.trim()), keep);
            return true;
        }
        if (!isResponse) { return true; } // a request without length has no body
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (keep != null) { keep.write(buf, 0, n); }
        }
        out.flush();
        return false;
    }

    private static void copyN(InputStream in, OutputStream out, long n, ByteArrayOutputStream keep) throws IOException {
        byte[] buf = new byte[16384];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r == -1) { throw new EOFException("body ended early"); }
            out.write(buf, 0, r);
            if (keep != null) { keep.write(buf, 0, r); }
            n -= r;
        }
        out.flush();
    }

    private static void copyChunked(InputStream in, OutputStream out, ByteArrayOutputStream keep) throws IOException {
        while (true) {
            String sizeLine = readLine(in, false);
            out.write((sizeLine + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            int semi = sizeLine.indexOf(';');
            long size = Long.parseLong((semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim(), 16);
            if (size == 0) {
                // trailers, then the blank line
                for (String t = readLine(in, false); ; t = readLine(in, false)) {
                    out.write((t + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    if (t.isEmpty()) { break; }
                }
                out.flush();
                return;
            }
            copyN(in, out, size, keep);
            String crlf = readLine(in, false);
            out.write((crlf + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        }
    }
}
