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
| `RateTest.run(iterations, body)` | Race-dependent scenarios that report a failure rate. Use distinct objects per iteration: non-capturing lambdas are a single shared instance. |

Format Java sources with the repository's `.clang-format`.
