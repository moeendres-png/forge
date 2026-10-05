# Forge exact-SHA candidate qualification (D17)

Tracked by `moeendres-png/commander-playtest-lab#498`, roadmap
`moeendres-png/commander-playtest-lab#479`.

This directory and
`.github/workflows/forge-candidate-qualification.yml` implement a **trusted
exact-SHA candidate qualification** for Forge. It is deliberately *not* a
mergeability signal and *not* a Rules qualification.

## The property this gate guarantees

**Candidate-authored build artifacts cannot manufacture qualification credit.**

No Surefire report, TestNG result file, generated, copied, renamed or committed
XML produced anywhere inside the candidate workspace is read for any
qualification signal. Credit is derived from two trusted ledgers only:

| Ledger | Produced by | Why it is trusted |
| --- | --- | --- |
| `required-surface.json` | `TestNG -dryrun` over the **trusted comparison base** checkout | an observation of what the trusted lineage's own tests would execute; a candidate cannot shrink it |
| `execution-manifest.json` + `witness/*.witness.jsonl` | the external trusted parent process, after a contained child JVM exits with the exact trusted count/skip contract | the candidate JVM receives no receipt key, run ID or trusted evidence handle; only the parent writes credited receipts, bound to a parent-only run ID |

`PASS` therefore requires positive proof that the exact candidate SHA/TREE
executed the whole trusted required surface: every required class observed alive,
observed volume at or above the trusted denominator, no failures, and no
undeclared skip.

### Why the previous design was insufficient

The earlier D17 revision parsed the candidate's own
`target/surefire-reports/TEST-*.xml` and granted `PASS` from those counts after a
candidate-controlled Maven run exited successfully. A candidate could suppress
tests in its POM, return Maven success, and commit or lifecycle-generate green XML
with plausible counts. **Trusted Python parsing candidate-authored numbers is not
provenance.** That revision was rejected at review and replaced.

## Trust boundary

| Property | Mechanism |
| --- | --- |
| The candidate cannot define its own qualification | `pull_request_target` loads the executing definition from `master`. |
| The authority is provably trusted | `source_lock.py` requires the run to execute from `refs/heads/master` at its tip, and requires `master` to physically carry this directory and the workflow file. |
| Candidate identity is exact | Full lowercase 40-hex only; the fetched ref must resolve to exactly that commit; the TREE is re-proven inside the candidate workspace before any candidate code runs. |
| No synthetic-merge fallback | `refs/pull/<n>/merge` is not an acceptable fetch ref. No merge is computed and no mergeability field is read anywhere. |
| Execution is externally receipted, not candidate-reported | A receiptless trusted child counter validates exact per-class dispatch counts and failures/skips inside containment. The external trusted parent observes only the child OS exit and writes the credited receipt. Candidate reports are never consulted. |
| Candidate code never runs as the validator | Candidate Maven/plugin execution runs as `d17build`; hostile candidate production bytecode in the qualification JVM runs as the distinct `d17exec` account. Neither is a trusted identity. Qualification test bodies come only from the trusted comparison base; candidate-owned test bodies are never executed for credit. Both identities are reaped and included in final integrity checks. |
| Trusted bytecode and toolchains are out of reach | Before any candidate code runs, the receiptless child driver/counter and containment guard are compiled against the digest-pinned TestNG 7.10.2 closure. They are staged root-owned and read-only under `/var/lib/d17-trusted`, together with the JDK and Maven used by later trusted steps. |
| The executed test bytecode is trusted policy | Test sources are exported from the **trusted comparison-base commit**, never from the candidate role, and compiled by trusted code with `-proc:none` only **after** Candidate production/dependency bytes are admitted and frozen into runner-owned staging. Candidate `target/test-classes` is never executed for credit. The manifest binds `trusted_test_source_sha` to the comparison-base source lock. |
| Hostile bytecode is separated from receipt authority | The system classpath contains only the trusted receiptless child harness and digest-pinned TestNG. Trusted comparison-base tests and candidate production bytecode use separate child loaders. The Candidate loader has only the JDK platform loader as its real parent; the trusted dependency bridge is private. Candidate production may resolve **no TestNG class at all**. A mandatory Java-17/21 SecurityManager guard denies system/context-loader authority, witness/TestNG package access, access-check suppression, SecurityManager replacement, process/native execution, shutdown hooks, JMX authority, all procfs access, protected child-output mutation and process-global property mutation. Runtime class-definition permission is denied where the JDK requests it, and an exact real-bytecode control proves that any class successfully defined through `MethodHandles.Lookup#defineClass` remains in the hostile Candidate loader domain and therefore remains subject to the same sticky guard. The private trusted-dependency loader is also hostile-tainted for permission checks, preventing delayed dependency-only confused-deputy attacks. A hostile Candidate/Dependency frame wins even when a trusted TestNG frame is lower on the stack; a JDK-only asynchronous context with no explicit trusted driver/TestNG authority defaults to restricted, so configured deputies cannot shed the hostile stack. Mutable `Properties` views, filesystem link creation, all `sun.*`/`jdk.internal.*` package access, and all procfs operations are included in that boundary and have real hostile-route controls. Crucially, no receipt key/run ID/evidence handle exists in the candidate JVM; the external parent alone creates credited evidence. If containment is unavailable, verdict is UNKNOWN. |
| Candidate listeners cannot change what runs or counts | TestNG's default listeners are off, and its service-loader discovery sees only the trusted jars, so a candidate `META-INF/services/org.testng.ITestNGListener` is never loaded. |
| Integrity is verified, not assumed | `sandbox.py verify` re-derives every trusted file from Git, re-checks the evidence seals and checks that no candidate process is alive. `qualify.py` turns a violation into `FAIL` and a missing or foreign `INTEGRITY.json` into `UNKNOWN`. |
| Stale evidence cannot be reused | The evidence directory is deleted and recreated each run; every parent receipt is bound to a fresh parent-only run ID and the validator recomputes its sequence/count aggregates. Module receipts must bind to that same run ID. |
| Non-PASS is never green | `PASS` exits 0. `FAIL`, `PARTIAL`, `NOT_RUN`, `UNKNOWN` exit 1, 2, 3, 4. |
| Absurd identity claims are rejected | A lock that does not assert `candidate_code_executed_as_validator=false`, `verdict_read_from_candidate=false`, `synthetic_merge_consulted=false`, `pr_mergeability_consulted=false`, or whose comparison base claims a synthetic merge, is rejected on load. |

### Export integrity, loaded code and tool resolution (ported from C12, 2026-10-04)

- **The export equals the locked blobs.** `git archive` applies the tree's own `.gitattributes` (`export-subst`, `export-ignore`, `ident`, `eol`, filters). A candidate could therefore build, and have trusted code recompile, bytes that are not its committed blobs. `sandbox.export_commit` compares every exported path with `ls-tree`: the same set, the same mode, and the same blob id computed from the raw bytes. Any difference refuses the export, both in `prepare` and in the trusted recompile (`ExportIntegrity` controls).
- **Nothing world-writable remains on the root filesystem.** `--harden-world-writable` strips o+w from files as well as non-sticky directories (`find -xdev`, so other mounts are not walked). A re-check that finds an entry left, or that did not complete, fails `prepare`. Trusted Python in `prepare`, `execute` and `verify` runs as `/usr/bin/python3 -I -S -B`, so `site` is not imported and no `.pth` file runs (workflow contract control).
- **PATH.** A PATH directory is probed for shadowing (a new entry written into it, or the directory replaced through a writable ancestor), not recursively: hosted runners ship many world-writable tool-cache files no trusted step runs. Every tool a trusted step runs (`TRUSTED_TOOLS`) is probed at its real path. `prepare` resolves Maven first, then switches to `PATH=$D17_TRUSTED_PATH`, which is the PATH it probes.

### Build and launch admission (review 2026-10-04)

Before any hostile production bytecode runs, `trusted_execution.py` freezes and
classifies the launch inputs in `launch_classpath_admission`:

- **Trusted comparison-base tests** are exported from the locked comparison-base
  Git commit and compiled by trusted `javac -proc:none`. Candidate test source,
  candidate `target/test-classes`, TestNG service resources and candidate test
  reports carry no credit.
- **Candidate production bytecode** comes only from a build whose Maven authority
  matches the trusted comparison base. Any candidate change to `pom.xml`,
  nested POMs, `.mvn/**` or the Maven wrapper makes the qualification
  `UNKNOWN`. Maven runs offline against the pre-resolved, read-only trusted
  repository and candidate annotation processing is disabled. Installed Forge
  sibling artifacts that actually appear on a module's resolved classpath are
  replaced **in place** by the matching exact-candidate `target/classes`.
  The artifactId→module map is derived recursively from the trusted
  comparison-base reactor POM; unknown or missing sibling outputs fail closed.
- **Dependencies/plugins** are read from the trusted Maven repository. Candidate
  output cannot shadow a trusted dependency in the execution classloader; the
  dependency domain is logically parent-first but is **not** the Candidate
  loader's actual parent. Candidate production and trusted dependencies are
  admitted/frozen before trusted `javac` is allowed to parse them.
- **The JVM system classpath contains no candidate bytecode.** It contains only
  the trusted receiptless child harness and digest-pinned TestNG closure. Trusted tests and
  candidate production code are loaded through distinct child-loader domains.
  Trusted tests may use the narrow TestNG test API after bytecode admission;
  Candidate production bytecode is rejected for **any** `org.testng.*`
  reference and the runtime loader/package guard enforces the same rule for
  computed-name access.
- `qualify.py` fails or returns `UNKNOWN` on rejected/unverified launch input,
  failed trusted test compilation, missing admission records, build-definition
  divergence or absent trusted Maven authority.

### Bounded influence of candidate-controlled inputs

The candidate controls the production source/content being qualified. It does
**not** control the Maven/plugin definition used for a qualifying run, the
dependency/plugin repository, the qualification test bodies, TestNG authority,
the witness, evidence store or verdict rule. A candidate that changes Maven build
authority is deliberately non-qualifiable by D17 until that trusted definition is
reviewed on the default branch.

## Separately reported evidence classes

| Class | Status in the evidence | Credit |
| --- | --- | --- |
| `exact_candidate_qualification` | `PASS` / `FAIL` / `PARTIAL` / `NOT_RUN` / `UNKNOWN` | the only class this gate asserts |
| `pr_mergeability` | `NOT_APPLICABLE` | none — not an input to the verdict |
| `synthetic_merge_evidence` | `NEVER_COMPUTED` | none |
| `rules_qualification_evidence` | `NOT_CLAIMED` | none — a green CI qualification is not a Magic Rules qualification |

## Verdict derivation

Signals are evaluated strongest-negative-first:

The emitted signal set binds, at minimum: exact candidate SHA/TREE; comparison-base
denominator and test-source authority; trusted Maven/build definition; non-empty
required surface; trusted child completion; parent-receipt presence/parsing/digest;
per-class/invocation floors; failures and skip attribution; external receipt authority; distinct untrusted
build/execution OS identities; trusted-state integrity; mandatory hostile-bytecode
containment; trusted test compilation; launch-classpath admission; and explicit
whole-reactor coverage debt. Verdict precedence is fail-closed: infrastructure
ambiguity is `UNKNOWN`, missing execution is `NOT_RUN`, known incomplete
coverage is `PARTIAL`, and only a complete trusted surface can be `PASS`.

## Whole-reactor coverage

`QUALIFY_EXPECTED_MODULES` declares the modules that must report, and
`QUALIFY_OUT_OF_BAND_CLASSES` declares test classes outside the qualified
surface.

All three test-bearing reactor modules must report, but **666 is no longer
described as full Forge test coverage**. It was only a historical
TestNG-discoverable denominator; D23 already changes the executable source set,
and D24/#528 proved additional source tests were silently absent.

D17 now derives coverage in two independent ways from the trusted comparison
base:

1. a **generic source-level TestNG inventory** finds every class with a real
   TestNG `@Test` annotation (comments/strings/text blocks are stripped) that is
   absent from the trusted executable surface; and
2. the canonical D20 `.github/ci/known-not-run.json`
   (`forge.known-not-run/2`) supplies named framework blockers and the
   source-hash-bound intentionally-disabled category. D17 reads this file from
   the committed comparison-base Git blob and exposes baseline hash/execution
   drift rather than trusting a working-tree copy.

Method-level disabled TestNG obligations are inventoried independently as well:
CR104.3f remains D22-owned, and D24 leaves two CardDbPerformance benchmark
methods disabled. Any generic NOT_RUN obligation, D20 baseline debt/drift or
explicitly disabled method keeps `whole_reactor_coverage_complete=false`; an
otherwise valid execution is `PARTIAL`, never PASS.

Only a skip in a declared out-of-band class is excluded. A skip in any other
class is `PARTIAL`. `skipped_total`, `observed_skipped_classes`,
`declared_out_of_band_classes` and `undeclared_skipped_classes` are always
reported, so the exclusion list is an auditable scope statement rather than a
silent suppression. Adding a class is a visible change to the trusted definition
on `master`.

This gate qualifies the Java 21 leg only. The Java 17 leg remains covered by the
`test-build.yaml` matrix; JDK battery scope belongs to D21/#502.

## Controls

```
python3 .github/qualification/qualification_selftest.py
python3 .github/qualification/qualification_mutation_check.py
D17_REQUIRE_TOOLCHAIN=1 python3 -m unittest qualification_runtime_controls   # from .github/qualification
```

`forge-candidate-qualification-selftest.yml` runs all three on every pull
request that changes the gate.

`qualification_runtime_controls.py` runs the real receiptless child driver on the Java 17/21 selftest matrix and
pinned TestNG. It executes hostile
**candidate production bytecode** in fresh JVMs for each authority route:
system/context-loader access, computed forbidden-TestNG access,
property replacement, classloader creation, process exit/creation, native
loading, protected-output writes, witness reflection and a racing
property-mutator thread. Every SecurityManager/loader-authority route must trip
the sticky containment state; merely making the test fail is insufficient. The
Candidate-parent-loader control is structural instead: it proves the only parent
visible to Candidate code is the platform loader and cannot resolve TestNG.
Separate controls prove an honest candidate call works, trusted dependencies
cannot be shadowed, full-reactor Candidate outputs are used, trusted tests compile
only against frozen inputs, and neither untrusted UID can overwrite
validator/denominator/evidence paths.

The verdict controls reject candidate/foreign receipt authority, rewritten or
digest-mismatched receipts, replayed parent run IDs, candidate code run as
`root` or `runner`, identity collapse, integrity violations and missing
integrity. The child command is asserted to contain no receipt nonce/key/ledger
handle; the obsolete in-JVM signing listener is not compiled or present.

`qualification_selftest.py` builds real Git repositories in temporary
directories and asserts the **specific** non-`PASS` class for every red control,
so a control cannot pass for the wrong reason. Beyond the identity, authority,
lock-validation, definition-divergence, witness-ledger-integrity,
verdict-derivation, CLI and workflow-contract controls, it carries the mandatory
adversarial set:

* **A** suppression + zero execution + forged green XML in every module ⇒ never `PASS`;
* **B** Maven success + zero required execution + forged expected counts ⇒ never `PASS`;
* **C** forged reports in `forge-game`, `forge-gui-desktop` and `forge-protocol2-bridge` ⇒ never `PASS`;
* **D** copied / renamed reports at the expected path ⇒ no credit, verdict unchanged;
* **E** observed real failure with forged green reports ⇒ `FAIL`;
* **F** candidate-fabricated run report, manifest or verdict document ⇒ no credit;
* **G** required class suppressed, or volume cut below the trusted denominator ⇒ never `PASS`;
* **H** an honest **fully covered synthetic fixture** reaches `PASS`. Current
  Forge's **coverage classification** remains `PARTIAL` while explicit disabled
  debt exists; an actual current candidate may still be `FAIL` for ordinary
  test failures, which takes precedence over coverage PARTIAL.

`qualification_mutation_check.py` mutates the product code once per safety
property in a scratch copy and requires the control suite to go red. A surviving
mutation exits nonzero. Without it a green control suite would prove nothing; it
is what makes the red controls non-vacuous.

Both are stdlib-only. Forge has no Python test infrastructure, so `unittest` is
used deliberately rather than adding a dependency.

## Containment and remaining limits

* **REQUIRE_CONTAINMENT is enforced, not waived.** Candidate production bytecode
  does not share the system loader with the witness. The mandatory containment
  guard is installed before trusted-base tests initialize candidate code.
  Candidate attempts to obtain the system loader, suppress access checks,
  replace the SecurityManager/properties object, execute processes, load native
  code, mutate protected child output or TestNG global knobs are sticky
  violations. The child turns any containment violation into a non-credit OS
  exit; only the external parent can create a credited receipt.
* **Runtime support is bounded.** This design deliberately uses the JDK 17/21
  SecurityManager compatibility mechanism. A runtime where that mechanism is
  unavailable is `UNKNOWN`, not an implicit downgrade to the older shared-JVM
  mitigation model. D17 does not claim future-JDK containment from this design.
* **D24 execution restoration is consumed, not reimplemented.** The formerly
  silent PowerMock-derived concrete classes are now part of the executable
  denominator through the trusted Surefire include policy. D17 separately
  retains the remaining disabled-class/method debt (including CR104.3f and two
  CardDbPerformance benchmarks) as non-PASS coverage.
* **Dependency changes are UNKNOWN.** A candidate-added/upgraded dependency that
  is absent from the trusted Maven repository is not admitted.
* **Frozen classpath and immutable runtime data.** Candidate production output
  and trusted dependency jars are admitted and frozen into runner-owned staging
  **before trusted test compilation**, then staged root-owned/read-only before
  the hostile-bytecode JVM starts. Launch working-directory data comes from a
  separately verified exact-candidate Git export, not from the writable build
  tree. Build/plugin code and runtime candidate bytecode use different OS UIDs.
* **No authentication secret exists in the candidate JVM.** The child has no
  receipt key, parent run ID or trusted evidence handle. It can only return a
  bounded OS status. Credited receipt files are created by the external parent
  in the trusted evidence directory and are then digest-bound and integrity
  sealed.

## Scope boundary with D20/#501

D20/#501 owns general persisted JUnit/timing artifact provenance, receipts,
retention and artifact stores. D17 implements only the minimum trusted
execution provenance needed to stop candidate-authored build artifacts from
manufacturing `PASS`. This directory persists a witness ledger and a
required-surface ledger; it does not build a general artifact store, and it does
not take over D20's scope.

Not in scope for D17: superseded-head concurrency (D18/#499), JUnit/timing
artifact provenance (D20/#501), JDK battery reduction (D21/#502), CR 104.3f
disabled Rules test remediation (D22/#503), and the first-option ratchet
(D23/#504).