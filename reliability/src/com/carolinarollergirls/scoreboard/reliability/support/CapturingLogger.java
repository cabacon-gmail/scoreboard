package com.carolinarollergirls.scoreboard.reliability.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.carolinarollergirls.scoreboard.utils.Logger;

/**
 * A product {@link Logger} that keeps every line it receives and also prints it to stdout (and so to the test
 * class's log).
 * <p>
 * Only {@code Main} installs a product logger, and {@code Logger.printStackTrace} throws a NullPointerException
 * without one. Tests that reach any error-logging path therefore need a logger installed; creating a
 * {@code CapturingLogger} installs it as the product's logger.
 * </p>
 */
public final class CapturingLogger extends Logger {
    public CapturingLogger() { setLogger(this); }

    /** Installs a CapturingLogger unless the product already has a logger. Used by the runner for every class. */
    public static void installIfAbsent() {
        if (Logger.getInstance() == null) { new CapturingLogger(); }
    }

    /** The logger installed by the most recent {@code new CapturingLogger()}, or null. */
    public static CapturingLogger current() {
        Logger l = Logger.getInstance();
        return l instanceof CapturingLogger ? (CapturingLogger) l : null;
    }

    @Override
    public void log(String msg) {
        lines.add(msg);
        System.out.println(msg);
    }

    /** A snapshot of every line logged so far. */
    public List<String> lines() {
        synchronized (lines) { return new ArrayList<>(lines); }
    }

    /** True if any logged line contains {@code text}. */
    public boolean contains(String text) {
        for (String l : lines()) {
            if (l.contains(text)) { return true; }
        }
        return false;
    }

    public void clear() { lines.clear(); }

    private final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
}
