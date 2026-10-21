package com.carolinarollergirls.scoreboard.reliability.harness;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.jr.ob.JSON;

/** Builds WebSocket messages exactly as {@code html/json/WS.js} sends them, and compares JSON values. */
public final class Messages {
    private Messages() {}

    /** {@code WS.Set(key, value)}: {@code {"action":"Set","key":…,"value":…,"flag":""}}. */
    public static String set(String key, Object value) { return set(key, value, ""); }

    /** {@code WS.Set(key, value, flag)} with flag {@code "change"} or {@code "reset"}. */
    public static String set(String key, Object value, String flag) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", "Set");
        m.put("key", key);
        m.put("value", value);
        m.put("flag", flag);
        return write(m);
    }

    /** {@code WS.Register(paths)}: {@code {"action":"Register","paths":[…]}}. */
    public static String register(String... paths) { return register(Arrays.asList(paths)); }

    public static String register(List<String> paths) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", "Register");
        m.put("paths", new ArrayList<>(paths));
        return write(m);
    }

    static String write(Object o) {
        try {
            return JSON.std.with(JSON.Feature.WRITE_NULL_PROPERTIES).asString(o);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    /** Equality of two values as JSON sees them: numbers compare by value, everything else by equals. */
    public static boolean jsonEquals(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return Objects.equals(a, b);
    }
}
