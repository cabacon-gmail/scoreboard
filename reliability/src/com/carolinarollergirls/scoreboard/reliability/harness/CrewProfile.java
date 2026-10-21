package com.carolinarollergirls.scoreboard.reliability.harness;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.jr.ob.JSON;

/**
 * A crew: which devices it has, the page each one claims to be, and the paths each one registers. Loaded from
 * {@code reliability/crews/<profile>.json}, which {@code reliability/tools/harvest-crew.js} generates from the real
 * screens (Phase 2 design, section 6).
 */
public final class CrewProfile {
    /** One device of the crew. */
    public static final class Device {
        Device(String name, String source, String role, List<String> paths) {
            this.name = name;
            this.source = source;
            this.role = role;
            this.paths = Collections.unmodifiableList(paths);
        }

        public final String name;
        public final String source;
        public final String role;
        public final List<String> paths;
    }

    private CrewProfile(String name, Map<String, Device> devices) {
        this.name = name;
        this.devices = Collections.unmodifiableMap(devices);
    }

    /** Loads {@code reliability/crews/<profile>.json} from the repository. */
    public static CrewProfile load(File repoDir, String profile) throws IOException {
        return read(new File(repoDir, "reliability/crews/" + profile + ".json"));
    }

    @SuppressWarnings("unchecked")
    public static CrewProfile read(File file) throws IOException {
        Map<String, Object> root = JSON.std.mapFrom(file);
        Object devs = root.get("devices");
        if (!(devs instanceof Map)) { throw new IOException(file + ": no 'devices' object"); }
        Map<String, Device> devices = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) devs).entrySet()) {
            Map<String, Object> d = (Map<String, Object>) e.getValue();
            List<String> paths = new ArrayList<>();
            if (d.get("paths") instanceof List) {
                for (Object p : (List<Object>) d.get("paths")) { paths.add(String.valueOf(p)); }
            }
            if (paths.isEmpty()) { throw new IOException(file + ": device '" + e.getKey() + "' registers no paths"); }
            devices.put(e.getKey(), new Device(e.getKey(), String.valueOf(d.get("source")),
                                               String.valueOf(d.getOrDefault("role", "")), paths));
        }
        String name = String.valueOf(root.getOrDefault("profile", file.getName()));
        return new CrewProfile(name, devices);
    }

    public String name() { return name; }
    public Map<String, Device> devices() { return devices; }

    public Device device(String name) {
        Device d = devices.get(name);
        if (d == null) { throw new IllegalArgumentException("crew '" + this.name + "' has no device '" + name + "'"); }
        return d;
    }

    private final String name;
    private final Map<String, Device> devices;
}
