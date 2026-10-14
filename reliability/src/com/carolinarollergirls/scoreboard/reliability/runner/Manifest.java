package com.carolinarollergirls.scoreboard.reliability.runner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.jr.ob.JSON;

/**
 * The expected-failures manifest ({@code reliability/expected-failures.json}).
 * <p>
 * Loading validates the whole file; any problem is reported as a {@link ManifestException} naming the entry.
 * </p>
 */
public final class Manifest {
    public static final int SCHEMA_VERSION = 1;

    private static final Set<String> TOP_LEVEL_FIELDS =
        new HashSet<>(Arrays.asList("schemaVersion", "expectedFailures"));
    private static final Set<String> ENTRY_FIELDS =
        new HashSet<>(Arrays.asList("test", "finding", "kind", "messagePattern", "baselineRate", "notes"));
    private static final Pattern TEST_ID =
        Pattern.compile("^[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*#[A-Za-z_$][\\w$]*$");
    private static final Pattern FINDING_ID = Pattern.compile("^F[0-9]+$");

    private Manifest(List<ManifestEntry> entries) {
        this.entries = Collections.unmodifiableList(entries);
        Map<String, ManifestEntry> byTest = new LinkedHashMap<>();
        for (ManifestEntry e : entries) { byTest.put(e.getTest(), e); }
        this.byTest = Collections.unmodifiableMap(byTest);
    }

    public List<ManifestEntry> getEntries() { return entries; }

    /** @return the entry for {@code test}, or null when the test is not listed. */
    public ManifestEntry get(String test) { return byTest.get(test); }

    public static Manifest load(File file) throws ManifestException {
        if (!file.isFile()) { throw new ManifestException("Manifest file not found: " + file.getPath()); }
        Object parsed;
        try {
            parsed = JSON.std.anyFrom(file);
        } catch (Exception e) {
            throw new ManifestException("Manifest is not valid JSON (" + file.getPath() + "): " + e.getMessage(), e);
        }
        return fromJson(parsed);
    }

    public static Manifest fromJson(Object parsed) throws ManifestException {
        if (!(parsed instanceof Map)) { throw new ManifestException("Manifest must be a JSON object"); }
        Map<?, ?> root = (Map<?, ?>) parsed;
        for (Object key : root.keySet()) {
            if (!TOP_LEVEL_FIELDS.contains(key)) {
                throw new ManifestException("Manifest has unknown top-level field '" + key + "'");
            }
        }
        Object version = root.get("schemaVersion");
        if (!(version instanceof Number) || ((Number) version).intValue() != SCHEMA_VERSION ||
            ((Number) version).doubleValue() != SCHEMA_VERSION) {
            throw new ManifestException("Manifest schemaVersion must be " + SCHEMA_VERSION + ", found " + version);
        }
        Object list = root.get("expectedFailures");
        if (!(list instanceof List)) {
            throw new ManifestException("Manifest field 'expectedFailures' must be a list");
        }

        List<ManifestEntry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (Object item : (List<?>) list) {
            ManifestEntry e = parseEntry(item, index);
            if (!seen.add(e.getTest())) {
                throw new ManifestException(where(index, e.getTest()) + "duplicate entry for this test");
            }
            entries.add(e);
            index++;
        }
        return new Manifest(entries);
    }

    private static ManifestEntry parseEntry(Object item, int index) throws ManifestException {
        if (!(item instanceof Map)) { throw new ManifestException(where(index, null) + "must be a JSON object"); }
        Map<?, ?> m = (Map<?, ?>) item;
        String test = m.get("test") instanceof String ? (String) m.get("test") : null;
        for (Object key : m.keySet()) {
            if (!ENTRY_FIELDS.contains(key)) {
                throw new ManifestException(where(index, test) + "unknown field '" + key + "'");
            }
        }
        if (test == null) { throw new ManifestException(where(index, null) + "missing required field 'test'"); }
        if (!TEST_ID.matcher(test).matches()) {
            throw new ManifestException(where(index, test) + "'test' must be fully.qualified.ClassName#methodName");
        }

        String finding = requireString(m, "finding", index, test);
        if (!FINDING_ID.matcher(finding).matches()) {
            throw new ManifestException(where(index, test) + "'finding' must match F<number>, found '" + finding + "'");
        }

        String kindText = requireString(m, "kind", index, test);
        ManifestEntry.Kind kind;
        if ("single".equals(kindText)) {
            kind = ManifestEntry.Kind.SINGLE;
        } else if ("rate".equals(kindText)) {
            kind = ManifestEntry.Kind.RATE;
        } else {
            throw new ManifestException(where(index, test) + "'kind' must be 'single' or 'rate', found '" + kindText +
                                        "'");
        }

        Pattern pattern = null;
        Double baseline = null;
        if (kind == ManifestEntry.Kind.SINGLE) {
            String regex = requireString(m, "messagePattern", index, test);
            try {
                pattern = Pattern.compile(regex);
            } catch (PatternSyntaxException pse) {
                throw new ManifestException(where(index, test) +
                                            "'messagePattern' is not a valid regex: " + pse.getDescription());
            }
            if (m.containsKey("baselineRate")) {
                throw new ManifestException(where(index, test) + "'baselineRate' is only allowed for kind 'rate'");
            }
        } else {
            Object rate = m.get("baselineRate");
            if (!(rate instanceof Number)) {
                throw new ManifestException(where(index, test) + "missing required numeric field 'baselineRate'");
            }
            baseline = ((Number) rate).doubleValue();
            if (!(baseline > 0.0 && baseline <= 1.0)) {
                throw new ManifestException(where(index, test) + "'baselineRate' must be in (0, 1], found " + baseline);
            }
            if (m.containsKey("messagePattern")) {
                throw new ManifestException(where(index, test) + ("'messagePattern' is only allowed for kind " +
                                                                  "'single'"));
            }
        }

        Object notes = m.get("notes");
        if (notes != null && !(notes instanceof String)) {
            throw new ManifestException(where(index, test) + "'notes' must be a string");
        }
        return new ManifestEntry(test, finding, kind, pattern, baseline, (String) notes);
    }

    private static String requireString(Map<?, ?> m, String field, int index, String test) throws ManifestException {
        Object v = m.get(field);
        if (!(v instanceof String) || ((String) v).isEmpty()) {
            throw new ManifestException(where(index, test) + "missing required field '" + field + "'");
        }
        return (String) v;
    }

    private static String where(int index, String test) {
        return "Manifest entry #" + (index + 1) + (test != null ? " (" + test + ")" : "") + ": ";
    }

    private final List<ManifestEntry> entries;
    private final Map<String, ManifestEntry> byTest;
}
