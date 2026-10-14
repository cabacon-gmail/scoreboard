package com.carolinarollergirls.scoreboard.reliability.runner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Entry point of the reliability suite: runs every {@code *ReliabilityTest} class, compares the results with the
 * expected-failures manifest and writes a report.
 * <p>
 * Exit codes: 0 = no blocking verdicts, 1 = at least one blocking verdict, 2 = infrastructure error.
 * </p>
 */
public final class ReliabilityMain {
    public static final int EXIT_OK = 0;
    public static final int EXIT_BLOCKING = 1;
    public static final int EXIT_INFRA = 2;

    static final class Options {
        File manifest;
        File testClasses;
        File reportDir;
        File workDir;
        File repoDir = new File(".");
        String classpath;
        int classTimeoutSeconds = 600;
        double rateScale = 1.0;
        Pattern only;
        boolean inProcess;
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(parse(args));
        } catch (UsageException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.err.println(USAGE);
            code = EXIT_INFRA;
        } catch (ManifestException e) {
            System.err.println("ERROR: " + e.getMessage());
            code = EXIT_INFRA;
        } catch (Throwable t) {
            System.err.println("ERROR: reliability runner failed: " + t);
            t.printStackTrace();
            code = EXIT_INFRA;
        }
        System.out.flush();
        System.exit(code);
    }

    static int run(Options o) throws Exception {
        long start = System.currentTimeMillis();
        Manifest manifest = Manifest.load(o.manifest);

        List<String> classes = discover(o.testClasses);
        if (o.only != null) {
            List<String> kept = new ArrayList<>();
            for (String c : classes) {
                if (o.only.matcher(c).find()) { kept.add(c); }
            }
            classes = kept;
        }
        if (classes.isEmpty()) {
            System.err.println("ERROR: no *ReliabilityTest classes found under " + o.testClasses.getPath() +
                               (o.only != null ? " matching --only " + o.only.pattern() : ""));
            return EXIT_INFRA;
        }

        deleteRecursively(o.reportDir.toPath());
        File logDir = new File(o.reportDir, "logs");
        logDir.mkdirs();
        o.workDir.mkdirs();

        List<TestResult> results = new ArrayList<>();
        for (String className : classes) {
            long classStart = System.currentTimeMillis();
            System.out.println("Running " + className + " ...");
            List<TestResult> classResults = runClass(o, className, logDir);
            results.addAll(classResults);
            System.out.println(String.format(Locale.ROOT, "  %d test(s) in %.1fs", classResults.size(),
                                             (System.currentTimeMillis() - classStart) / 1000.0));
        }

        List<Evaluator.Outcome> outcomes = Evaluator.evaluate(results, manifest, classes, o.only != null);
        Report.RunInfo info = runInfo(o.repoDir, start);
        Report.write(o.reportDir, outcomes, info);
        System.out.println();
        System.out.print(Report.summary(outcomes));
        System.out.println("Report: " + o.reportDir.getPath());
        return Evaluator.isBlocking(outcomes) ? EXIT_BLOCKING : EXIT_OK;
    }

    /** Runs one class in its own JVM (or in this one with --in-process) and fills in missing results. */
    static List<TestResult> runClass(Options o, String className, File logDir) throws IOException {
        File classWork = new File(o.workDir, className);
        prepareWorkDir(classWork, o.repoDir);
        File partial = new File(classWork, "results.partial.json");
        File log = new File(logDir, className + ".log");

        String missingText;
        if (o.inProcess) {
            missingText = null;
            try {
                ClassRunner.run(className, partial);
            } catch (Throwable t) { missingText = "IN_PROCESS_ERROR " + t; }
        } else {
            String java = new File(new File(System.getProperty("java.home"), "bin"), "java").getPath();
            List<String> cmd = new ArrayList<>();
            cmd.add(java);
            cmd.add("-cp");
            cmd.add(o.classpath);
            cmd.add("-Dreliability.workDir=" + classWork.getAbsolutePath());
            cmd.add("-Dreliability.repoDir=" + o.repoDir.getAbsolutePath());
            cmd.add("-Dreliability.rateScale=" + o.rateScale);
            cmd.add(ClassRunner.class.getName());
            cmd.add(className);
            cmd.add(partial.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(classWork);
            pb.redirectErrorStream(true);
            pb.redirectOutput(log);
            Process p = pb.start();
            boolean finished;
            try {
                finished = p.waitFor(o.classTimeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finished = false;
            }
            if (!finished) {
                p.destroyForcibly();
                try {
                    p.waitFor(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                missingText = "TIMEOUT after " + o.classTimeoutSeconds + "s";
                System.out.println("  timed out after " + o.classTimeoutSeconds + "s");
            } else if (p.exitValue() != 0) {
                missingText = "CHILD_CRASH exit=" + p.exitValue();
                System.out.println("  child JVM exited with " + p.exitValue() + "; see " + log.getPath());
            } else {
                missingText = "CHILD_CRASH exit=0 (no result recorded)";
            }
        }

        List<TestResult> recorded = ClassRunner.readResults(partial);
        Set<String> have = new HashSet<>();
        for (TestResult r : recorded) { have.add(r.getTest()); }
        List<TestResult> all = new ArrayList<>(recorded);
        List<String> methods;
        try {
            methods =
                ClassRunner.testMethodNames(Class.forName(className, false, ReliabilityMain.class.getClassLoader()));
        } catch (Throwable t) {
            methods = Collections.emptyList();
            if (recorded.isEmpty()) {
                all.add(
                    new TestResult(className + "#classLoad", TestResult.Status.FAILED, "CLASS_LOAD_ERROR " + t, 0L));
            }
        }
        for (String m : methods) {
            String test = className + "#" + m;
            if (!have.contains(test)) {
                all.add(
                    new TestResult(test, TestResult.Status.FAILED, missingText != null ? missingText : "NOT_RUN", 0L));
            }
        }
        return all;
    }

    /** Creates a fresh work directory holding a copy of the repo's config/ (without config/autosave/). */
    static void prepareWorkDir(File classWork, File repoDir) throws IOException {
        deleteRecursively(classWork.toPath());
        classWork.mkdirs();
        final Path srcConfig = new File(repoDir, "config").toPath();
        if (!Files.isDirectory(srcConfig)) { return; }
        final Path dstConfig = classWork.toPath().resolve("config");
        final Path autosave = srcConfig.resolve("autosave");
        Files.walkFileTree(srcConfig, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.equals(autosave)) { return FileVisitResult.SKIP_SUBTREE; }
                Files.createDirectories(dstConfig.resolve(srcConfig.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dstConfig.resolve(srcConfig.relativize(file).toString()),
                           StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static List<String> discover(File testClasses) throws IOException {
        if (!testClasses.isDirectory()) { throw new IOException("Test classes directory not found: " + testClasses); }
        final Path root = testClasses.toPath();
        List<String> names = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths
                .filter(p -> {
                    String n = p.getFileName().toString();
                    return n.endsWith("ReliabilityTest.class") && !n.contains("$");
                })
                .forEach(p -> {
                    String rel = root.relativize(p).toString();
                    rel = rel.substring(0, rel.length() - ".class".length());
                    names.add(rel.replace(File.separatorChar, '.'));
                });
        }
        Collections.sort(names);
        return names;
    }

    static Report.RunInfo runInfo(File repoDir, long start) {
        Report.RunInfo info = new Report.RunInfo();
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        info.startedAt = iso.format(new Date(start));
        info.durationSeconds = (System.currentTimeMillis() - start) / 1000.0;
        info.gitCommit = git(repoDir, "rev-parse", "HEAD");
        info.gitBranch = git(repoDir, "rev-parse", "--abbrev-ref", "HEAD");
        try {
            info.host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            String env = System.getenv("COMPUTERNAME");
            info.host = env != null ? env : System.getenv("HOSTNAME");
        }
        info.os = System.getProperty("os.name") + " " + System.getProperty("os.version") + " " +
                  System.getProperty("os.arch");
        info.javaVersion = System.getProperty("java.version");
        return info;
    }

    /** @return the trimmed output of {@code git <args>} in {@code repoDir}, or null if git is unavailable. */
    static String git(File repoDir, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            Collections.addAll(cmd, args);
            Process p = new ProcessBuilder(cmd).directory(repoDir).redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); }
            }
            if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) { return null; }
            String s = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) { return null; }
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) { return; }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static final class UsageException extends Exception {
        UsageException(String message) { super(message); }

        private static final long serialVersionUID = 1L;
    }

    static Options parse(String[] args) throws UsageException {
        Options o = new Options();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--in-process".equals(a)) {
                o.inProcess = true;
                continue;
            }
            if (i + 1 >= args.length) { throw new UsageException("missing value for " + a); }
            String v = args[++i];
            switch (a) {
            case "--manifest": o.manifest = new File(v); break;
            case "--test-classes": o.testClasses = new File(v); break;
            case "--report-dir": o.reportDir = new File(v); break;
            case "--work-dir": o.workDir = new File(v); break;
            case "--repo-dir": o.repoDir = new File(v); break;
            case "--classpath": o.classpath = v; break;
            case "--class-timeout": o.classTimeoutSeconds = parseInt(a, v); break;
            case "--rate-scale": o.rateScale = parseDouble(a, v); break;
            case "--only":
                if (!v.isEmpty()) {
                    try {
                        o.only = Pattern.compile(v);
                    } catch (PatternSyntaxException e) {
                        throw new UsageException("--only is not a valid regex: " + e.getDescription());
                    }
                }
                break;
            default: throw new UsageException("unknown option " + a);
            }
        }
        if (o.manifest == null) { throw new UsageException("--manifest is required"); }
        if (o.testClasses == null) { throw new UsageException("--test-classes is required"); }
        if (o.reportDir == null) { throw new UsageException("--report-dir is required"); }
        if (o.workDir == null) { throw new UsageException("--work-dir is required"); }
        if (o.classpath == null && !o.inProcess) { throw new UsageException("--classpath is required"); }
        if (o.classTimeoutSeconds <= 0) { throw new UsageException("--class-timeout must be positive"); }
        if (!(o.rateScale > 0)) { throw new UsageException("--rate-scale must be positive"); }
        return o;
    }

    private static int parseInt(String option, String v) throws UsageException {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) { throw new UsageException(option + " must be an integer, found " + v); }
    }

    private static double parseDouble(String option, String v) throws UsageException {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) { throw new UsageException(option + " must be a number, found " + v); }
    }

    static final String USAGE =
        "usage: ReliabilityMain --manifest <file> --test-classes <dir> --report-dir <dir> --work-dir <dir>\n"
        + "         --classpath <cp> [--repo-dir <dir>] [--class-timeout <seconds>] [--rate-scale <n>]\n"
        + "         [--only <regex>] [--in-process]";
}
