package com.carolinarollergirls.scoreboard.reliability.harness;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Steps the server's wall clock without touching its monotonic clock (finding F1; libfaketime spike).
 * <ul>
 * <li><b>Linux and macOS:</b> libfaketime, preloaded into the server process only. Steps are written to a timestamp
 * file, atomically. Found via {@code -Dreliability.libfaketime=<path>} or the usual install locations.</li>
 * <li><b>Windows:</b> the real system clock, stepped with {@code Set-Date}, only when
 * {@code -Dreliability.allowClockChanges=true} (needs an elevated shell). Every step is undone on {@link #close}.</li>
 * </ul>
 * When neither is available, {@link #detect} returns a control whose {@link #available()} is false and whose
 * {@link #reason()} says why, so the test can be SKIPPED.
 */
public final class ClockControl implements AutoCloseable {
    public enum Mode { FAKETIME, SYSTEM_CLOCK, NONE }

    private ClockControl(Mode mode, File library, String reason) {
        this.mode = mode;
        this.library = library;
        this.reason = reason;
    }

    public static ClockControl detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            if (Boolean.getBoolean("reliability.allowClockChanges")) {
                return new ClockControl(Mode.SYSTEM_CLOCK, null, "Windows system clock (opt-in)");
            }
            return new ClockControl(Mode.NONE, null,
                                    "Windows: stepping the system clock needs -Dreliability.allowClockChanges=true "
                                        + "and an elevated shell");
        }
        boolean mac = os.contains("mac");
        List<String> candidates = new ArrayList<>();
        String configured = System.getProperty("reliability.libfaketime");
        if (configured != null && !configured.isEmpty()) {
            candidates.add(configured);
        } else if (mac) {
            candidates.addAll(Arrays.asList("/opt/homebrew/lib/faketime/libfaketimeMT.1.dylib",
                                            "/usr/local/lib/faketime/libfaketimeMT.1.dylib"));
        } else {
            candidates.addAll(Arrays.asList("/usr/lib/x86_64-linux-gnu/faketime/libfaketimeMT.so.1",
                                            "/usr/lib/aarch64-linux-gnu/faketime/libfaketimeMT.so.1",
                                            "/usr/lib/faketime/libfaketimeMT.so.1",
                                            "/usr/lib64/faketime/libfaketimeMT.so.1",
                                            "/usr/local/lib/faketime/libfaketimeMT.so.1"));
        }
        for (String c : candidates) {
            File f = new File(c);
            if (f.isFile()) { return new ClockControl(Mode.FAKETIME, f, "libfaketime " + f); }
        }
        return new ClockControl(Mode.NONE, null,
                                "libfaketime (multi-threaded build) not found; install it or set "
                                    + "-Dreliability.libfaketime=<path to libfaketimeMT>; looked in " + candidates);
    }

    public boolean available() { return mode != Mode.NONE; }
    public Mode mode() { return mode; }
    public String reason() { return reason; }

    /**
     * Prepares {@code options} so the server starts under this control. Call before {@link ServerLauncher#start}.
     * {@code dir} is the server directory; the timestamp file lives next to it.
     */
    public void configure(ServerLauncher.Options options, File dir) throws IOException {
        if (mode != Mode.FAKETIME) { return; }
        timestampFile = new File(dir.getAbsoluteFile().getParentFile(), dir.getName() + "-faketime.rc");
        writeOffset();
        Map<String, String> env = options.environment == null ? new LinkedHashMap<>() : options.environment;
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        if (mac) {
            env.put("DYLD_INSERT_LIBRARIES", library.getPath());
            env.put("DYLD_FORCE_FLAT_NAMESPACE", "1");
        } else {
            env.put("LD_PRELOAD", library.getPath());
        }
        env.put("FAKETIME_TIMESTAMP_FILE", timestampFile.getPath());
        env.put("FAKETIME_NO_CACHE", "1");
        env.put("FAKETIME_DONT_FAKE_MONOTONIC", "1");
        options.environment = env;
    }

    /** Steps the server's wall clock by {@code ms} (positive = forward). Whole seconds only. */
    public void step(long ms) throws IOException {
        if (ms % 1000 != 0) { throw new IllegalArgumentException("clock steps must be whole seconds: " + ms); }
        switch (mode) {
        case FAKETIME:
            offsetMs += ms;
            writeOffset();
            break;
        case SYSTEM_CLOCK:
            if (!w32timeStopped) {
                runQuietly("net", "stop", "w32time");
                w32timeStopped = true;
            }
            setDate(ms);
            offsetMs += ms;
            break;
        default: throw new IllegalStateException("no clock control: " + reason);
        }
    }

    /** Total offset applied so far. */
    public long offsetMs() { return offsetMs; }

    /** Undoes every system-clock step (Windows) and restarts time sync. Nothing to undo for libfaketime. */
    @Override
    public void close() {
        if (mode == Mode.SYSTEM_CLOCK) {
            try {
                if (offsetMs != 0) { setDate(-offsetMs); }
                offsetMs = 0;
            } catch (IOException e) { System.err.println("ClockControl: could not undo clock step: " + e); }
            if (w32timeStopped) {
                runQuietly("net", "start", "w32time");
                runQuietly("w32tm", "/resync");
            }
        }
    }

    private void writeOffset() throws IOException {
        long s = offsetMs / 1000;
        String text = (s >= 0 ? "+" : "") + s + "s\n";
        File tmp = new File(timestampFile.getPath() + ".tmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.US_ASCII));
        Files.move(tmp.toPath(), timestampFile.toPath(), StandardCopyOption.REPLACE_EXISTING,
                   StandardCopyOption.ATOMIC_MOVE);
    }

    private static void setDate(long ms) throws IOException {
        int code = run("powershell", "-NoProfile", "-Command",
                       "Set-Date -Adjust ([TimeSpan]::FromMilliseconds(" + ms + ")) | Out-Null");
        if (code != 0) { throw new IOException("Set-Date failed with exit code " + code + " (is the shell elevated?)"); }
    }

    private static int run(String... cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        try {
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static void runQuietly(String... cmd) {
        try {
            run(cmd);
        } catch (IOException e) { System.err.println("ClockControl: " + String.join(" ", cmd) + ": " + e); }
    }

    private final Mode mode;
    private final File library;
    private final String reason;
    private File timestampFile;
    private long offsetMs;
    private boolean w32timeStopped;
}
