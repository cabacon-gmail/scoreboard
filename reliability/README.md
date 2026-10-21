# CRG ScoreBoard reliability suite

Tests that expose known reliability problems in the scoreboard and stay in place to prove when fixes land. Design: [Phase 0 — Foundations](https://github.com/cabacon-gmail/crg-reliability-plan/blob/main/docs/phase0-foundations.md); overall plan: [crg-reliability-plan](https://github.com/cabacon-gmail/crg-reliability-plan).

Everything here is self-contained. The root `build.xml`, `src/`, `tests/` and the rest of the repo are unchanged, and `ant test` never compiles or runs this suite.

## Setting up a test machine

You need Git, a JDK (11–21; the code compiles to Java 8) and Apache Ant 1.10+. Check all three from a new terminal:

```
git --version
java -version
ant -version
```

**Windows** (PowerShell):

1. `winget install --id Git.Git -e` and `winget install --id EclipseAdoptium.Temurin.21.JDK -e`.
2. Download `apache-ant-1.10.15-bin.zip` from https://ant.apache.org/bindownload.cgi and extract it to `C:\tools\apache-ant-1.10.15`.
3. Add Ant to your user environment, then open a new PowerShell window:

   ```powershell
   [Environment]::SetEnvironmentVariable("ANT_HOME", "C:\tools\apache-ant-1.10.15", "User")
   $p = [Environment]::GetEnvironmentVariable("Path", "User")
   [Environment]::SetEnvironmentVariable("Path", "$p;C:\tools\apache-ant-1.10.15\bin", "User")
   ```

4. If `ant -version` complains about `JAVA_HOME`, set `JAVA_HOME` (User scope) to the JDK folder under `C:\Program Files\Eclipse Adoptium\`.

**macOS**: with [Homebrew](https://brew.sh), run `brew install git ant openjdk@21`, then follow brew's note on linking `openjdk@21` so `java` is on your PATH.

**Linux** (Debian/Ubuntu): `sudo apt install git ant ant-optional junit4 openjdk-21-jdk`.

Clone into a folder that is **not synced by OneDrive, iCloud or Dropbox**. Sync clients lock files mid-run and cause failures that have nothing to do with the tests. Your home folder is usually safe.

```
git clone -b reliability https://github.com/cabacon-gmail/scoreboard.git crg-scoreboard
cd crg-scoreboard
```

## Running it

From the repository root:

```
ant -f reliability/build.xml            # build the product, compile the suite, run it
ant -f reliability/build.xml clean      # delete reliability/build/
```

Useful properties (`-Dname=value`):

| Property | Default | Meaning |
| --- | --- | --- |
| `only` | empty | Regex; run only test classes whose name matches it. |
| `class.timeout.seconds` | `600` | Kill a test class's JVM after this long. |
| `rate.scale` | `1.0` | Multiply the iterations of rate (race) tests. |
| `manifest.file` | `reliability/expected-failures.json` | Use a different manifest. |

The build succeeds when no test has a blocking verdict, and fails otherwise. Results are in `reliability/build/report/`:

- `summary.txt`: the table that is also printed to the console.
- `results.json`: one object per test with its verdict and failure text.
- `scorecard.json`: run-level counts, for later trend tracking.
- `logs/<ClassName>.log`: the output of each test class's JVM.

Each test class runs in its own JVM, with its own working directory under `reliability/build/work/<ClassName>/`. That directory starts with a copy of the repo's `config/` (without `config/autosave/`), and anything the product writes lands there, never in the repo.

## Verifying the suite itself

```
ant -f reliability/build.xml verify                                  # everything, including the root 'ant test'
ant -f reliability/build.xml verify -Dverify.skip.roottests=true     # skip the slow root 'ant test'
```

`verify` checks that the runner and the pilot behave as the Phase 0 design requires, one check per item:

| Check | What it proves |
| --- | --- |
| M1 | The real suite exits 0; every manifest entry is known; the canary and smoke tests behave. |
| M2 | All report files and scorecard fields are present. |
| M3 | Tests write only into their own work dirs, never the repo. |
| M4–M7 | `NEW_FAILURE`, `UNEXPECTED_PASS`, `WRONG_FAILURE` and `STALE_ENTRY` each fail the run. |
| M8 | `--only` runs just the matching classes. |
| M9 | Invalid manifests are rejected before any test runs. |
| M10 | Class timeouts work. |
| M11 | Rate verdicts work. |
| M12 | The root `ant test` still passes. |
| M13 | No file outside `reliability/` differs from `origin/dev`. Pass `-Dverify.base.ref=<ref>` to compare against another ref. |
| M14 | Every manifest entry fails for its own reason. Unlisted, each test is `NEW_FAILURE` with text matching its `messagePattern`, and no pattern is so broad it would match a timeout, crash or generic exception. |
| M15 | Every manifest entry's `notes` declares its trigger (see below). |
| P1 | The F7 pilot fails for the right reason when unlisted. |
| P3 | A simulated fix flips F7 to `UNEXPECTED_PASS`. |

It never edits tracked files. Every scenario uses temporary manifests and the fixture tests in `selfcheck/fixtures/`, which are never part of the real suite. P3 compiles a patched copy of `GameImpl` into a scratch directory. Everything it writes goes under `reliability/build/verify/`. Read `verify-summary.txt` there first; each scenario has its own report and `runner-output.txt`.

A check is skipped rather than failed when it can't run. That happens for M12 with `-Dverify.skip.roottests=true`, for M13 when the base ref isn't fetched, and for P3 if `GameImpl.java` has changed so the simulated fix no longer applies.

## Protocol harness (Phase 2)

Tests under `reliability/tests/.../reliability/` in `protocol/`, `bout/`, `clock/` and `crash/` drive the **packaged server** (`lib/crg-scoreboard.jar`) the way a crew's devices do, over `/WS/`. Design: [Phase 2](https://github.com/cabacon-gmail/crg-reliability-plan/blob/main/docs/phase2-protocol-harness.md).

- **Everything is a trace.** A test builds a trace with the `Bout` DSL, or reads a `.crgtrace.jsonl` file, and `Replayer` plays it. Tests save their trace in their work dir (e.g. `reliability/build/work/<Class>/f6-pilot.crgtrace.jsonl`), so any run can be replayed exactly.
- **`ServerLauncher`** starts the jar in a fresh directory on a free port, with an optional seed autosave. Stopping always kills the process tree, also from a shutdown hook.
- **`DeviceClient`** is one device: its own session cookie, a WebSocket, and a mirror of the state it subscribed to. Every device connects through its own **`FaultProxy`**. Phase 2 uses only pass-through, cut and drop.
- **The observer** registers everything and connects directly. It is the ground truth for `expect` lines and for oracles.

```java
Trace t = Bout.create("what this scenario is")
    .device("sbo", "/nso/sbo?game=$GAME")
    .at(0).on("sbo").connect().register("ScoreBoard.Game($GAME)")
    .at(1500).on("sbo").startJam()
    .after(100).expect(2000, "ScoreBoard.Game($GAME).InJam", true)
    .build();
try (ServerLauncher server = ServerLauncher.start(ServerLauncher.repoDirFromRunner(), new File("server"))) {
    Replayer.Result r = Replayer.replay(t, server);
    // r.failures, r.state(), r.devices, r.vars.get("GAME")
}
```

`$GAME` is the current game's ID, resolved at replay. Wrap DSL chains in `// clang-format off` / `// clang-format on` so they read as a timeline.

### Crews, seeds and the reference bout

- **Crew profiles** (`reliability/crews/<profile>.json`) list each device of a crew, the page it claims to be, and the exact paths its real screen registers. `Bout.crew(CrewProfile.load(repo, "default")).connectCrew(100)` connects them all. The `default` profile is the plan's nine-device crew (SBO, JT, two SKs, PT, LT, PBT, main display, overlay).
- **Harvesting** regenerates a profile from the real screens. Edit `crews/<profile>.screens.json`, then run `node reliability/tools/harvest-crew.js <profile>`. It needs Node and Playwright with Chromium (`npm install --no-save playwright && npx playwright install chromium`), but only for this tool: the tests never need a browser. Re-run it when screens change, and commit the result.
- **Seeds** (`reliability/seeds/*.json`) are autosave-format starting states. `two-teams-short-periods.json` has two 15-skater teams and a ruleset with 2-minute periods, 30-second jams and a 1-minute intermission. Regenerate with `ant -f reliability/build.xml seeds`.
- **`ReferenceBout`** is a whole two-period game for a crew on that seed, in about 5 1/2 minutes. `ReferenceBoutReliabilityTest` plays it with no faults; its press-to-visible latency lands in `reliability/build/work/<Class>/reference-bout-latency.json`. F12 and F15 build on it.
- **`Oracles`**: `convergence` (each device's mirror equals the observer for the paths it registered), `invariants` (score equals the sum of jam scores, jam numbers contiguous, at most one timeout running) and `scorePressLatency`.

### HTTP, clock steps and test options

- `Bout.http(method, path, form, files)` makes a request with the device's cookie, for example an upload. The server only knows a device once its page has opened the WebSocket, so `connect()` first. Responses are in `Result.http`.
- `Bout.clock(ms)` steps the server's wall clock, through `ClockControl`: libfaketime on Linux and macOS, or, on Windows, the real system clock when you opt in (below).
- Options for individual tests are passed on the ant command line as `-Dreliability.<name>=…` and reach every test JVM:

| Option | Used by | Meaning |
| --- | --- | --- |
| `reliability.statsbook` | F2 | Path to a blank WFTDA StatsBook `.xlsx`. Without it, F2 is `SKIPPED`. |
| `reliability.libfaketime` | F1 | Path to `libfaketimeMT.so.1` (Linux) or `libfaketimeMT.1.dylib` (macOS), if not in the usual place. Install with `sudo apt install faketime` or `brew install libfaketime`. Without it, F1 is `SKIPPED`. |
| `reliability.allowClockChanges` | F1 | Windows only: `true` lets F1 step the real system clock by 5 minutes and back. Run from an elevated shell; the Windows Time service is stopped during the test and restarted afterwards. |
| `reliability.seed` | F12 | Seed for the random kill times. |

The bout tests are slow: the reference bout takes about 6 minutes, F15 about 12, F12 about 3. They are marked `@Slow` (in `support`). A full run, and `verify`, take about 25 minutes more because of them. For a quick loop, leave them out:

```
ant -f reliability/build.xml reliability -Dquick=true
ant -f reliability/build.xml verify -Dverify.quick=true
```

Run the full `verify` before a PR.

## How expected failures work

Every test asserts the correct behaviour, so a test for an open issue fails. `expected-failures.json` lists those tests:

```json
{
  "test": "fully.qualified.ClassName#methodName",
  "finding": "F7",
  "kind": "single",
  "messagePattern": "a regex found in the failure's toString()",
  "notes": "Trigger: natural (…) or Trigger: injected (…), then anything else"
}
```

`notes` must start with **`Trigger: natural`** or **`Trigger: injected`**, and `verify` (M15) enforces this:

- **Natural**: the test reaches the bug through a realistic sequence of crew actions and conditions, for example two devices pressing Stop within 400 ms.
- **Injected**: the test plants a fault to open the window the bug lives in, for example a listener that throws during an undo. An injected test proves the code is fragile, not how often the bug happens at a real game.

Say briefly in parentheses what the trigger is.

Rate tests use `"kind": "rate"` and `"baselineRate"` (the failure rate when the entry was added) instead of `messagePattern`.

| Verdict | Meaning | Fails the build |
| --- | --- | --- |
| `PASS` | Not listed; passed. | No |
| `KNOWN` | Listed; failed with the expected message. | No |
| `KNOWN_RATE` | Rate test; failed at or below 1.5× its baseline rate. | No |
| `RATE_REGRESSION` | Rate test; failed above 1.5× its baseline rate. | No (warning) |
| `SKIPPED` | `@Ignore`d. | No (warning) |
| `NEW_FAILURE` | Not listed; failed. | Yes |
| `WRONG_FAILURE` | Listed; failed for a different reason. | Yes |
| `UNEXPECTED_PASS` | Listed; passed. The issue looks fixed: remove its entry. | Yes |
| `STALE_ENTRY` | Listed, but no such test ran. | Yes |

An invalid manifest stops the run before any test, with a message naming the entry (runner exit code 2).

`CanaryReliabilityTest` is permanent: its planted failure is listed under `F0` and must stay `KNOWN`. Its planted pass must stay `PASS`.

## Adding a test

1. Write a class whose name ends in `ReliabilityTest` under `reliability/tests/`. Use the product's own package when you need package-private access.
2. Make it assert the behaviour a crew needs. Start the assertion message with the finding ID, for example `"F7: Start Jam had no effect …"`.
3. Run with `-Donly=<YourClass>`. Confirm the verdict is `NEW_FAILURE`, and that the failure text describes the finding.
4. Add its entry to `expected-failures.json`, with a `messagePattern` specific to that finding and a `Trigger:` note. Run `ant -f reliability/build.xml verify`. M14 confirms the test fails for its own reason; M15 checks the note.
5. When a fix lands, the test reports `UNEXPECTED_PASS`; delete its manifest entry in the same change.

Simulated fixes like the F7 pilot's P3 are optional. Add one only when a fix is a small, obvious patch, because it is tied to the current text of the product source.

Helpers in `com.carolinarollergirls.scoreboard.reliability.support`:

| Helper | Use it to |
| --- | --- |
| `ProductFixture.start()` | Start the product the way `Main` does: global clock stopped, quick-clock detection off, `Version` loaded, and the JSON listener attached (without it, autosaves are empty). Then use `.sb()` and `.game()`. |
| `ProductFixture.newScoreBoard()`, `finishStartup(sb)` | Create a second scoreboard, for example to restore an autosave into. |
| `CapturingLogger` | Capture what the product logs (`new CapturingLogger()`, then `contains(...)`). The runner installs one for every class anyway, because the product's `Logger.printStackTrace` crashes without a logger. |
| `Platform.assumeWindows(why)` | Run a test only on Windows. Elsewhere it is reported as `SKIPPED`, which never fails a run, and `verify` accepts a skipped entry for that OS. |
| `@Slow("about N minutes")` | Mark a class that takes more than about a minute, so `-Dquick=true` can leave it out. |
| `RateTest.run(iterations, body)` | Race-dependent scenarios that report a failure rate. Use distinct objects per iteration: non-capturing lambdas are a single shared instance. |

Format Java sources with the repository's `.clang-format`.
