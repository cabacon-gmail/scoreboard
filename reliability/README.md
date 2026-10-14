# CRG ScoreBoard reliability suite

Tests that expose known reliability problems in the scoreboard and stay in place to prove when fixes land. Design: [Phase 0 — Foundations](https://github.com/cabacon-gmail/crg-reliability-plan/blob/main/docs/phase0-foundations.md); overall plan: [crg-reliability-plan](https://github.com/cabacon-gmail/crg-reliability-plan).

Everything here is self-contained. The root `build.xml`, `src/`, `tests/` and the rest of the repo are unchanged, and `ant test` never compiles or runs this suite.

## Running it

Prerequisites: a JDK (11–21; the code compiles to Java 8), Apache Ant 1.10+, and git.

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

## How expected failures work

Every test asserts the correct behaviour, so a test for an open issue fails. `expected-failures.json` lists those tests:

```json
{
  "test": "fully.qualified.ClassName#methodName",
  "finding": "F7",
  "kind": "single",
  "messagePattern": "a regex found in the failure's toString()",
  "notes": "optional"
}
```

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
2. Make it assert the behaviour a crew needs. Put the finding ID at the start of the assertion message, for example `"F7: Start Jam had no effect …"`.
3. Run with `-Donly=<YourClass>`. Confirm the verdict is `NEW_FAILURE`, and that its failure text matches the finding.
4. Add its entry to `expected-failures.json`. Run the full suite and confirm `KNOWN`.
5. When a fix lands, the test reports `UNEXPECTED_PASS`; delete its manifest entry in the same change.

For race-dependent scenarios, use `com.carolinarollergirls.scoreboard.reliability.support.RateTest.run(iterations, body)`.

Format Java sources with the repository's `.clang-format`.
