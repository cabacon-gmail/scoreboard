package com.carolinarollergirls.scoreboard.reliability.harness;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Starts the packaged scoreboard ({@code lib/crg-scoreboard.jar}) as its own process in a fresh directory.
 * <p>
 * The directory gets a copy of the repo's {@code config/} (without {@code autosave/}); the server creates whatever
 * else it needs there. An optional seed autosave is installed as the newest autosave so the product loads it the
 * normal way. Stopping always kills the whole process tree, with a timeout, and never relies on the server exiting
 * cleanly; a JVM shutdown hook does the same if the test JVM dies first.
 * </p>
 */
public final class ServerLauncher implements AutoCloseable {
    public enum Signal { KILL, TERM }

    public static final class Options {
        /** Autosave-format file installed as config/autosave/scoreboard-0-secs-ago.json, or null for a fresh server. */
        public File seed;
        /** Extra environment variables for the server process (e.g. LD_PRELOAD for libfaketime). */
        public Map<String, String> environment;
        /** Extra JVM options for the server, e.g. -Xmx256m. */
        public List<String> jvmOptions = new ArrayList<>();
        public long startTimeoutMs = 60_000L;
    }

    private ServerLauncher(File repoDir, File dir, Options options) {
        this.repoDir = repoDir;
        this.dir = dir;
        this.options = options;
    }

    /**
     * Starts a server in {@code dir} (created; must not already contain a server). {@code repoDir} is the repository
     * root holding {@code lib/crg-scoreboard.jar} and {@code config/}.
     */
    public static ServerLauncher start(File repoDir, File dir, Options options) throws IOException {
        ServerLauncher l = new ServerLauncher(repoDir.getAbsoluteFile(), dir.getAbsoluteFile(), options);
        l.prepare();
        l.port = freePort();
        l.launch();
        return l;
    }

    public static ServerLauncher start(File repoDir, File dir) throws IOException {
        return start(repoDir, dir, new Options());
    }

    /** The repository root, from the runner's {@code reliability.repoDir} system property. */
    public static File repoDirFromRunner() {
        String r = System.getProperty("reliability.repoDir");
        if (r == null) {
            throw new IllegalStateException("reliability.repoDir is not set; run through ReliabilityMain");
        }
        return new File(r);
    }

    public int port() { return port; }
    public File dir() { return dir; }
    public File log() { return new File(dir, "server.log"); }
    public long pid() { return process.pid(); }
    public boolean isAlive() { return process != null && process.isAlive(); }
    public String baseUrl() { return "http://127.0.0.1:" + port; }

    /** Kills the server ({@code KILL} = hard kill, {@code TERM} = polite) and waits for the whole tree to exit. */
    public void kill(Signal signal) {
        if (process == null) { return; }
        List<ProcessHandle> tree = new ArrayList<>();
        process.descendants().forEach(tree::add);
        if (signal == Signal.KILL) {
            process.destroyForcibly();
        } else {
            process.destroy();
        }
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly().waitFor(15, TimeUnit.SECONDS); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        for (ProcessHandle h : tree) { h.destroyForcibly(); }
    }

    /** Starts the server again on the same directory and port, keeping its autosaves. */
    public void restart() throws IOException {
        if (isAlive()) { kill(Signal.TERM); }
        launch();
    }

    /** Stops the server; safe to call repeatedly. */
    @Override
    public void close() {
        kill(Signal.KILL);
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // JVM already shutting down, or hook not registered.
        }
    }

    private void prepare() throws IOException {
        File jar = new File(repoDir, "lib/crg-scoreboard.jar");
        if (!jar.isFile()) { throw new IOException("setup: " + jar + " not found; build the product first"); }
        if (new File(dir, "config").exists()) { throw new IOException("setup: " + dir + " already holds a server"); }
        dir.mkdirs();
        copyTree(new File(repoDir, "config").toPath(), new File(dir, "config").toPath(), "autosave");
        if (options.seed != null) {
            File autosave = new File(dir, "config/autosave");
            autosave.mkdirs();
            Files.copy(options.seed.toPath(), new File(autosave, "scoreboard-0-secs-ago.json").toPath(),
                       StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void launch() throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(new File(System.getProperty("java.home"), "bin"), "java").getPath());
        cmd.addAll(options.jvmOptions);
        cmd.add("-jar");
        cmd.add(new File(repoDir, "lib/crg-scoreboard.jar").getPath());
        cmd.add("--port=" + port);
        cmd.add("--host=127.0.0.1");
        cmd.add("--import=");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log()));
        if (options.environment != null) { pb.environment().putAll(options.environment); }
        process = pb.start();
        if (hook == null) {
            hook = new Thread(this::close, "ServerLauncher-cleanup");
            Runtime.getRuntime().addShutdownHook(hook);
        }
        long deadline = System.currentTimeMillis() + options.startTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("setup: server exited with " + process.exitValue() + " during start-up; see " +
                                      log());
            }
            if (answers()) { return; }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        kill(Signal.KILL);
        throw new IOException("setup: server did not answer on port " + port + " within " + options.startTimeoutMs +
                              " ms; see " + log());
    }

    private boolean answers() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(baseUrl() + "/urls/").openConnection();
            c.setConnectTimeout(500);
            c.setReadTimeout(2000);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (IOException e) { return false; }
    }

    /** The tail of the server log, for failure messages. */
    public String logTail(int lines) {
        try {
            List<String> all = Files.readAllLines(log().toPath(), StandardCharsets.UTF_8);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) { return "(no log: " + e + ")"; }
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    static void copyTree(Path src, Path dst, String skipChild) throws IOException {
        if (!Files.isDirectory(src)) { return; }
        Path skip = src.resolve(skipChild);
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                if (d.equals(skip)) { return FileVisitResult.SKIP_SUBTREE; }
                Files.createDirectories(dst.resolve(src.relativize(d).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.copy(f, dst.resolve(src.relativize(f).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private final File repoDir;
    private final File dir;
    private final Options options;
    private int port;
    private Process process;
    private Thread hook;
}
