package com.carolinarollergirls.scoreboard.reliability.harness;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Replays one trace file against a fresh packaged server and reports what happened:
 *
 * <pre>
 *   ant -f reliability/build.xml replay -Dtrace=reliability/captures/&lt;dir&gt;/trace.crgtrace.jsonl
 * </pre>
 *
 * The server starts from the trace's seed (relative to the trace file). Exit code 0 if every event executed and
 * every {@code expect} held, 1 otherwise.
 */
public final class ReplayMain {
    private ReplayMain() {}

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ReplayMain <repo dir> <trace file>");
            System.exit(2);
        }
        File repo = new File(args[0]).getAbsoluteFile();
        File traceFile = new File(args[1]).getAbsoluteFile();
        Trace trace = TraceReader.read(traceFile);
        File base = traceFile.getParentFile();
        ServerLauncher.Options so = new ServerLauncher.Options();
        Object seed = trace.header().get("seed");
        if (seed instanceof Map && ((Map<String, Object>) seed).get("autosave") != null) {
            File s = new File(String.valueOf(((Map<String, Object>) seed).get("autosave")));
            so.seed = s.isAbsolute() ? s : new File(base, s.getPath());
        }
        File dir = new File(repo, "reliability/build/replay/" + System.currentTimeMillis());
        Replayer.Options ro = new Replayer.Options();
        ro.baseDir = base;
        ro.keepConnected = true;
        long last = trace.events().isEmpty() ? 0 : trace.events().get(trace.events().size() - 1).t;
        System.out.println("Replaying " + trace.events().size() + " events from " + trace.devices().size() +
                           " devices, about " + (last / 1000) + " s, seed " + so.seed);
        int exit;
        try (ServerLauncher server = ServerLauncher.start(repo, dir, so)) {
            Replayer.Result r = Replayer.replay(trace, server, ro);
            List<String> conv = Oracles.convergence(r, 5000);
            String game = String.valueOf(r.vars.get("GAME"));
            System.out.println("Final: team 1 " + r.state().get("ScoreBoard.CurrentGame.Team(1).Score") + ", team 2 " +
                               r.state().get("ScoreBoard.CurrentGame.Team(2).Score") + ", period " +
                               r.state().get("ScoreBoard.CurrentGame.CurrentPeriodNumber") + ", jam " +
                               r.state().get("ScoreBoard.CurrentGame.Clock(Jam).Number"));
            for (Replayer.Failure f : r.failures) { System.out.println("FAILED " + f); }
            for (String c : conv) { System.out.println("NOT CONVERGED " + c); }
            for (String i : Oracles.invariants(r.state(), game)) { System.out.println("INVARIANT " + i); }
            r.close();
            exit = r.ok() ? 0 : 1;
            System.out.println(exit == 0 ? "REPLAY: OK" : "REPLAY: FAILED");
            System.out.println("Server directory: " + dir);
        }
        System.exit(exit);
    }
}
