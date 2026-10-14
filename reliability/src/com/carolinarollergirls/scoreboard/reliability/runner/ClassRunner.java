package com.carolinarollergirls.scoreboard.reliability.runner;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import com.fasterxml.jackson.jr.ob.JSON;

/**
 * Runs one test class with JUnit and writes its results as JSON.
 * <p>
 * Normally started by {@link ReliabilityMain} in its own JVM:
 * {@code ClassRunner <className> <resultsFile>}. Results are rewritten after every test so that a timeout or
 * crash loses only the tests that had not finished.
 * </p>
 */
public final class ClassRunner {
    public static void main(String[] args) {
        int exit = 0;
        try {
            if (args.length != 2) {
                System.err.println("usage: ClassRunner <className> <resultsFile>");
                exit = 2;
            } else {
                run(args[0], new File(args[1]));
            }
        } catch (Throwable t) {
            // Results written so far are kept; the parent fills in the rest.
            System.err.println("ClassRunner failed: " + t);
            t.printStackTrace();
            exit = 3;
        } finally {
            System.out.flush();
            System.err.flush();
            // The product starts non-daemon threads; without an explicit exit the JVM would hang.
            System.exit(exit);
        }
    }

    /** Runs {@code className} in this JVM and returns one result per test method. */
    public static List<TestResult> run(String className, File resultsFile) throws Exception {
        Class<?> testClass = Class.forName(className);
        Recorder recorder = new Recorder(resultsFile);
        recorder.write();
        JUnitCore core = new JUnitCore();
        core.addListener(recorder);
        core.run(testClass);
        // Methods JUnit never reported (e.g. a @BeforeClass failure) get the class-level failure.
        for (String method : testMethodNames(testClass)) {
            String test = className + "#" + method;
            if (!recorder.results.containsKey(test)) {
                String text = recorder.classFailure != null ? recorder.classFailure : "NOT_RUN by JUnit";
                recorder.results.put(test, new TestResult(test, TestResult.Status.FAILED, text, 0L));
            }
        }
        recorder.write();
        return new ArrayList<>(recorder.results.values());
    }

    /** Public, non-static methods annotated with {@code @Test}, sorted by name. */
    public static List<String> testMethodNames(Class<?> testClass) {
        TreeSet<String> names = new TreeSet<>();
        for (Method m : testClass.getMethods()) {
            if (m.isAnnotationPresent(Test.class) && !Modifier.isStatic(m.getModifiers())) { names.add(m.getName()); }
        }
        return new ArrayList<>(names);
    }

    public static List<TestResult> readResults(File resultsFile) throws IOException {
        List<TestResult> list = new ArrayList<>();
        if (!resultsFile.isFile()) { return list; }
        Object parsed = JSON.std.anyFrom(resultsFile);
        if (parsed instanceof List) {
            for (Object o : (List<?>) parsed) {
                if (o instanceof Map) { list.add(TestResult.fromMap((Map<?, ?>) o)); }
            }
        }
        return list;
    }

    private static final class Recorder extends RunListener {
        Recorder(File resultsFile) { this.resultsFile = resultsFile; }

        @Override
        public void testStarted(Description d) {
            started = System.currentTimeMillis();
            currentFailure = null;
        }

        @Override
        public void testFailure(Failure f) {
            Description d = f.getDescription();
            String text = String.valueOf(f.getException());
            if (d.getMethodName() == null) {
                classFailure = text;
            } else {
                currentFailure = text;
            }
        }

        @Override
        public void testAssumptionFailure(Failure f) {
            Description d = f.getDescription();
            if (d.getMethodName() != null) { assumptionFailed = true; }
        }

        @Override
        public void testIgnored(Description d) throws Exception {
            if (d.getMethodName() == null) { return; }
            String test = d.getClassName() + "#" + d.getMethodName();
            results.put(test, new TestResult(test, TestResult.Status.IGNORED, null, 0L));
            write();
        }

        @Override
        public void testFinished(Description d) throws Exception {
            if (d.getMethodName() == null) { return; }
            String test = d.getClassName() + "#" + d.getMethodName();
            long duration = System.currentTimeMillis() - started;
            TestResult r;
            if (currentFailure != null) {
                r = new TestResult(test, TestResult.Status.FAILED, currentFailure, duration);
            } else if (assumptionFailed) {
                r = new TestResult(test, TestResult.Status.IGNORED, null, duration);
            } else {
                r = new TestResult(test, TestResult.Status.PASSED, null, duration);
            }
            results.put(test, r);
            currentFailure = null;
            assumptionFailed = false;
            write();
        }

        synchronized void write() throws IOException {
            if (resultsFile == null) { return; }
            List<Object> list = new ArrayList<>();
            for (TestResult r : results.values()) { list.add(r.toMap()); }
            String json =
                JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT, JSON.Feature.WRITE_NULL_PROPERTIES).asString(list);
            File parent = resultsFile.getAbsoluteFile().getParentFile();
            parent.mkdirs();
            File tmp = new File(parent, resultsFile.getName() + ".tmp");
            Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), resultsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        private final File resultsFile;
        final Map<String, TestResult> results = new LinkedHashMap<>();
        String classFailure;
        private String currentFailure;
        private boolean assumptionFailed;
        private long started;
    }
}
