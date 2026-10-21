package com.carolinarollergirls.scoreboard.reliability.harness;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.jr.ob.JSON;

/**
 * Reusable checks an NSO would recognise (Phase 2 design, section 5). Each returns a list of problems; empty means
 * the check held. Problems are worded for assertion messages.
 */
public final class Oracles {
    private Oracles() {}

    /**
     * Convergence: within {@code withinMs}, every connected device's mirror equals the observer's state for the keys
     * its registered paths cover (WS.* keys excepted). Returns one line per device that never converged.
     */
    public static List<String> convergence(Replayer.Result r, long withinMs) {
        List<String> problems = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(withinMs);
        for (DeviceClient d : r.devices.values()) {
            if (!d.isConnected()) { continue; }
            String last;
            do {
                last = diff(r.observer.state(), d.state(), d.registeredPaths());
                if (last == null) { break; }
                sleep(50);
            } while (System.nanoTime() < deadline);
            if (last != null) { problems.add(d.name() + " did not converge within " + withinMs + " ms: " + last); }
        }
        return problems;
    }

    /** Null if equal on the covered keys, else a short description of the first few differences. */
    static String diff(Map<String, Object> truth, Map<String, Object> mirror, RegisteredPaths paths) {
        TreeSet<String> keys = new TreeSet<>();
        for (String k : truth.keySet()) {
            if (paths.covers(k)) { keys.add(k); }
        }
        for (String k : mirror.keySet()) {
            if (!k.startsWith("WS.") && paths.covers(k)) { keys.add(k); }
        }
        int count = 0;
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            Object a = truth.get(k), b = mirror.get(k);
            if (!Messages.jsonEquals(a, b)) {
                if (count < 3) { sb.append(count > 0 ? "; " : "").append(k).append(" is ").append(b).append(", observer has ").append(a); }
                count++;
            }
        }
        return count == 0 ? null : count + " keys differ (" + sb + ")";
    }

    private static final Pattern JAM_SCORE =
        Pattern.compile("^ScoreBoard\\.Game\\(([^)]*)\\)\\.Period\\((\\d+)\\)\\.Jam\\((\\d+)\\)\\.TeamJam\\(([12])\\)\\.JamScore$");
    private static final Pattern JAM_NUMBER =
        Pattern.compile("^ScoreBoard\\.Game\\(([^)]*)\\)\\.Period\\((\\d+)\\)\\.Jam\\((\\d+)\\)\\.Id$");
    private static final Pattern TIMEOUT_RUNNING =
        Pattern.compile("^ScoreBoard\\.Game\\(([^)]*)\\)\\.Period\\((\\d+)\\)\\.Timeout\\(([^)]*)\\)\\.Running$");

    /**
     * Game invariants for {@code game} in {@code state}: each team's score equals the sum of its jam scores; jam
     * numbers in each period run 1..n without gaps; at most one timeout is running.
     */
    public static List<String> invariants(Map<String, Object> state, String game) {
        List<String> problems = new ArrayList<>();
        long[] jamSums = new long[3];
        Map<Integer, TreeSet<Integer>> jams = new TreeMap<>();
        int runningTimeouts = 0;
        for (Map.Entry<String, Object> e : state.entrySet()) {
            Matcher m = JAM_SCORE.matcher(e.getKey());
            if (m.matches() && m.group(1).equals(game) && e.getValue() instanceof Number) {
                jamSums[Integer.parseInt(m.group(4))] += ((Number) e.getValue()).longValue();
            }
            m = JAM_NUMBER.matcher(e.getKey());
            if (m.matches() && m.group(1).equals(game)) {
                jams.computeIfAbsent(Integer.parseInt(m.group(2)), k -> new TreeSet<>()).add(Integer.parseInt(m.group(3)));
            }
            m = TIMEOUT_RUNNING.matcher(e.getKey());
            if (m.matches() && m.group(1).equals(game) && Boolean.TRUE.equals(e.getValue())) { runningTimeouts++; }
        }
        for (int t = 1; t <= 2; t++) {
            Object score = state.get("ScoreBoard.Game(" + game + ").Team(" + t + ").Score");
            if (!(score instanceof Number) || ((Number) score).longValue() != jamSums[t]) {
                problems.add("team " + t + " score is " + score + " but its jam scores add up to " + jamSums[t]);
            }
        }
        for (Map.Entry<Integer, TreeSet<Integer>> p : jams.entrySet()) {
            if (p.getKey() == 0) { continue; }
            TreeSet<Integer> nums = new TreeSet<>(p.getValue());
            nums.remove(0);
            if (!nums.isEmpty() && (nums.first() != 1 || nums.last() != nums.size())) {
                problems.add("period " + p.getKey() + " jam numbers are not 1.." + nums.size() + ": " + nums);
            }
        }
        if (runningTimeouts > 1) { problems.add(runningTimeouts + " timeouts are running at once"); }
        return problems;
    }

    /** Press-to-visible latencies of score presses, in ms: one sample per (press, device that shows the score). */
    public static final class Latency {
        Latency(List<Long> samples, int missed) {
            this.samples = Collections.unmodifiableList(samples);
            this.missed = missed;
        }

        public long percentile(double p) {
            if (samples.isEmpty()) { return -1; }
            List<Long> s = new ArrayList<>(samples);
            Collections.sort(s);
            int idx = (int) Math.ceil(p / 100.0 * s.size()) - 1;
            return s.get(Math.max(0, Math.min(s.size() - 1, idx)));
        }

        @Override
        public String toString() {
            return "n=" + samples.size() + " p50=" + percentile(50) + "ms p95=" + percentile(95) + "ms max=" +
                percentile(100) + "ms missed=" + missed;
        }

        public final List<Long> samples;
        /** Presses whose new score never reached a device that shows it. */
        public final int missed;
    }

    /**
     * For every trip-score press any device sent, the time until each other device that registered the team score
     * received the score the observer settled on after that press.
     */
    public static Latency scorePressLatency(Replayer.Result r) {
        return scorePressLatency(r, java.util.Collections.emptySet());
    }

    /** As {@link #scorePressLatency(Replayer.Result)}, leaving out the devices named in {@code exclude}. */
    public static Latency scorePressLatency(Replayer.Result r, java.util.Set<String> exclude) {
        String game = String.valueOf(r.vars.get("GAME"));
        List<long[]> presses = new ArrayList<>(); // {nanos, team}
        for (DeviceClient d : r.devices.values()) {
            for (DeviceClient.Frame f : d.frames()) {
                if (!f.outgoing || !f.text.contains("TripScore")) { continue; }
                Map<String, Object> msg = parse(f.text);
                Object key = msg == null ? null : msg.get("key");
                if (key instanceof String && ((String) key).endsWith(".TripScore") && "Set".equals(msg.get("action"))) {
                    Matcher m = Pattern.compile("\\.Team\\(([12])\\)\\.TripScore$").matcher((String) key);
                    if (m.find()) { presses.add(new long[] { f.nanos, Integer.parseInt(m.group(1)) }); }
                }
            }
        }
        presses.sort((a, b) -> Long.compare(a[0], b[0]));
        List<Long> samples = new ArrayList<>();
        int missed = 0;
        for (long[] press : presses) {
            String gameKey = "ScoreBoard.Game(" + game + ").Team(" + press[1] + ").Score";
            String curKey = "ScoreBoard.CurrentGame.Team(" + press[1] + ").Score";
            Object target = firstValueAfter(r.observer, gameKey, press[0], null);
            if (target == null) { continue; } // the press changed nothing visible (e.g. guarded)
            for (DeviceClient d : r.devices.values()) {
                if (exclude.contains(d.name())) { continue; }
                String key = d.registeredPaths().covers(curKey) ? curKey
                           : d.registeredPaths().covers(gameKey) ? gameKey : null;
                if (key == null) { continue; }
                Long at = firstTimeWithValue(d, key, press[0], target);
                if (at == null) {
                    missed++;
                } else {
                    samples.add(TimeUnit.NANOSECONDS.toMillis(at - press[0]));
                }
            }
        }
        return new Latency(samples, missed);
    }

    private static Object firstValueAfter(DeviceClient d, String key, long nanos, Object unused) {
        for (DeviceClient.Frame f : d.frames()) {
            if (f.outgoing || f.nanos < nanos || !f.text.contains(key)) { continue; }
            Map<String, Object> st = state(f.text);
            if (st != null && st.containsKey(key)) { return st.get(key); }
        }
        return null;
    }

    private static Long firstTimeWithValue(DeviceClient d, String key, long nanos, Object value) {
        for (DeviceClient.Frame f : d.frames()) {
            if (f.outgoing || f.nanos < nanos || !f.text.contains(key)) { continue; }
            Map<String, Object> st = state(f.text);
            if (st != null && Messages.jsonEquals(st.get(key), value)) { return f.nanos; }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> state(String text) {
        Map<String, Object> m = parse(text);
        return m != null && m.get("state") instanceof Map ? (Map<String, Object>) m.get("state") : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String text) {
        try {
            Object o = JSON.std.anyFrom(text);
            return o instanceof Map ? (Map<String, Object>) o : null;
        } catch (IOException e) { return null; }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
