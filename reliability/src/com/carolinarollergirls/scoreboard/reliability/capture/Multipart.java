package com.carolinarollergirls.scoreboard.reliability.capture;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits a {@code multipart/form-data} body into its parts. Enough for browser uploads; not a general parser. */
final class Multipart {
    static final class Part {
        Part(String name, String filename, byte[] content) {
            this.name = name;
            this.filename = filename;
            this.content = content;
        }

        final String name;
        /** Null for an ordinary form field. */
        final String filename;
        final byte[] content;
    }

    private Multipart() {}

    private static final Pattern BOUNDARY = Pattern.compile("boundary=\"?([^\";]+)\"?");
    private static final Pattern NAME = Pattern.compile("\\bname=\"([^\"]*)\"");
    private static final Pattern FILENAME = Pattern.compile("\\bfilename=\"([^\"]*)\"");

    static List<Part> parse(String contentType, byte[] body) {
        List<Part> parts = new ArrayList<>();
        Matcher bm = BOUNDARY.matcher(contentType);
        if (!bm.find()) { return parts; }
        byte[] delim = ("--" + bm.group(1)).getBytes(StandardCharsets.ISO_8859_1);
        int pos = indexOf(body, delim, 0);
        while (pos >= 0) {
            int start = pos + delim.length;
            if (start + 1 < body.length && body[start] == '-' && body[start + 1] == '-') { break; } // closing
            start += 2; // CRLF after the delimiter
            int headEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), start);
            if (headEnd < 0) { break; }
            String headers = new String(body, start, headEnd - start, StandardCharsets.UTF_8);
            int next = indexOf(body, delim, headEnd + 4);
            if (next < 0) { break; }
            int contentEnd = next - 2; // CRLF before the next delimiter
            byte[] content = Arrays.copyOfRange(body, headEnd + 4, Math.max(headEnd + 4, contentEnd));
            Matcher n = NAME.matcher(headers);
            Matcher f = FILENAME.matcher(headers);
            parts.add(new Part(n.find() ? n.group(1) : "", f.find() ? f.group(1) : null, content));
            pos = next;
        }
        return parts;
    }

    static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) { continue outer; }
            }
            return i;
        }
        return -1;
    }
}
