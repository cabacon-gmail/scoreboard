package com.carolinarollergirls.scoreboard.reliability.harness;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes traces as JSON Lines: the header, then one event per line with t, ev and dev first. */
public final class TraceWriter {
    private TraceWriter() {}

    public static List<String> lines(Trace trace) {
        List<String> out = new ArrayList<>();
        out.add(Messages.write(trace.header()));
        for (Trace.Event e : trace.events()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", e.t);
            m.put("ev", e.ev);
            if (e.dev != null) { m.put("dev", e.dev); }
            for (Map.Entry<String, Object> f : e.fields.entrySet()) {
                if (!m.containsKey(f.getKey())) { m.put(f.getKey(), f.getValue()); }
            }
            out.add(Messages.write(m)); // keeps nulls: in an expect, null means 'absent'
        }
        return out;
    }

    public static void write(Trace trace, File file) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null) { parent.mkdirs(); }
        Files.write(file.toPath(), lines(trace), StandardCharsets.UTF_8);
    }
}
