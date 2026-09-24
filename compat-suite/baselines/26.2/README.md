# 26.2 baseline

First differential run of the suite, kept as the reference point for later runs (26.3 port, compat fixes).

- Command: `./gradlew -p compat-suite runSuite -Pcompat.targets=paper,lecithin,lecithin-dispatch-off -Pcompat.runId=20260924-baseline-26.2`
- Date: 2026-09-24, Windows 11, JDK 25.0.1.
- Suite code: the commit that added this directory (`run.json` shows `70856ea637`, the base it was run on,
  because the suite files were not committed yet at run time).
- Targets: Paper 26.2 build 118 (reference), Lecithin CI pre-release `26.2-70856ea`, and the same Lecithin
  jar with `compat-config.caller-context-dispatch=false`.
- Result: reference contract 70/70; `lecithin` 46 PASS / 24 KNOWN_FAIL / 0 REGRESSION;
  `lecithin-dispatch-off` 10 PASS / 45 EXPECTED_FOLIA_DIFFERENCE / 15 KNOWN_FAIL.

`results.json` is the machine-readable record, `summary.md` the differential summary. The KNOWN_FAIL root
causes are in `../../expectations.json`.
