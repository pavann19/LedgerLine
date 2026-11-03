# Java static analysis

The `Java production defect analysis` CI job builds both services and runs
SpotBugs Maven plugin **4.9.8.3** against production bytecode. It fails on every
medium- or high-confidence finding in `CORRECTNESS` and `MT_CORRECTNESS`, and on
an analyzer failure. There are no class exclusions or accepted-finding baselines.
The scope targets defects such as definite null dereferences, invalid equality
comparisons and unsafe synchronization. It is not a security audit or a proof of
transaction correctness; integration and invariant tests remain separate gates.

With JDK 21 and Python 3 available, run from the repository root:

```sh
./mvnw -Pstatic-analysis -Dmaven.test.skip=true verify --batch-mode
python3 scripts/test_static_analysis_gate.py
```

In PowerShell, quote `'-Dmaven.test.skip=true'`. The first command skips test
execution deliberately; normal CI runs the complete test suite in another job.
Reports are in each service's `target/spotbugsXml.xml` and retained as CI artifacts.

The control script copies the actual plugin configuration from the production
profile into a disposable Maven project. It first requires null-safe code to
pass, then requires an unconditional null dereference to fail with an
`NP_ALWAYS_NULL` finding in the XML report. A compiler or dependency failure does
not satisfy the negative control. Temporary sources and reports are removed when
the script exits; no application service, database or Kafka broker is started.

Rule selection and confidence thresholds are deliberate scope boundaries, not a
claim that all possible SpotBugs checks run. To expand the gate, review new
findings individually and fix defects rather than hiding classes or creating an
unreviewed baseline.

References: [plugin release](https://github.com/spotbugs/spotbugs-maven-plugin/releases/tag/spotbugs-maven-plugin-4.9.8.3),
[check goal](https://spotbugs.github.io/spotbugs-maven-plugin/check-mojo.html),
[violation checking](https://spotbugs.github.io/spotbugs-maven-plugin/examples/violationChecking.html).
