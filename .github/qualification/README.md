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
| `execution-manifest.json` + `witness/*.witness.jsonl` | the trusted orchestrator launching each module's required classes itself, with a listener compiled from this trusted branch and placed **first** on the classpath | records what those JVMs actually dispatched, bound to a per-run nonce and a gap-free sequence |

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
| Execution is witnessed, not reported | A trusted listener, compiled here and placed first on the classpath, records every dispatch. Candidate reports are never consulted. |
| Stale evidence cannot be reused | The evidence directory is deleted and recreated each run; every witness ledger is bound to a fresh nonce and a gap-free sequence, and its summary is recomputed from its records. |
| Non-PASS is never green | `PASS` exits 0. `FAIL`, `PARTIAL`, `NOT_RUN`, `UNKNOWN` exit 1, 2, 3, 4. |
| Absurd identity claims are rejected | A lock that does not assert `candidate_code_executed_as_validator=false`, `verdict_read_from_candidate=false`, `synthetic_merge_consulted=false`, `pr_mergeability_consulted=false`, or whose comparison base claims a synthetic merge, is rejected on load. |

### Bounded influence of candidate-controlled inputs

The candidate's POMs, sources, `mvn test-compile` and
`dependency:build-classpath` are candidate-controlled because the code under
qualification must be compiled. Their influence is bounded and **fail-closed by
construction**: each can only *reduce* the set of required methods observed as
dispatched. None can increase it, and none is read as evidence. If any is
subverted, the run records fewer invocations than the trusted denominator
requires, and the verdict is not `PASS`. Subverting the witness itself also only
reduces observations.

## Separately reported evidence classes

| Class | Status in the evidence | Credit |
| --- | --- | --- |
| `exact_candidate_qualification` | `PASS` / `FAIL` / `PARTIAL` / `NOT_RUN` / `UNKNOWN` | the only class this gate asserts |
| `pr_mergeability` | `NOT_APPLICABLE` | none — not an input to the verdict |
| `synthetic_merge_evidence` | `NEVER_COMPUTED` | none |
| `rules_qualification_evidence` | `NOT_CLAIMED` | none — a green CI qualification is not a Magic Rules qualification |

## Verdict derivation

Signals are evaluated strongest-negative-first:

1. `candidate_identity_bound` — the execution manifest's SHA **and** TREE equal the locked candidate.
2. `required_surface_bound_to_comparison_base` — the denominator came from the locked comparison base.
3. `trusted_launches_succeeded` — every trusted launch exited 0.
4. `no_failed_cases` — the witness observed no failures.
5. `required_surface_non_empty` — a denominator was actually enumerated.
6. `witness_ledgers_present` — a ledger exists for every required module. Absent ⇒ `NOT_RUN`.
7. `witness_ledgers_parsed` — every present ledger is authentic. Present but malformed ⇒ `UNKNOWN`.
8. `required_classes_executed` — every baseline required class was observed executing.
9. `required_invocations_met` — observed volume meets the trusted denominator.
10. `no_undeclared_skips` — no skipped case outside the declared out-of-band surface.

## Whole-reactor coverage

`QUALIFY_EXPECTED_MODULES` declares the modules that must report, and
`QUALIFY_OUT_OF_BAND_CLASSES` declares test classes outside the qualified
surface.

The surface is intentionally **the whole reactor**, not a subset. The three
modules below are the only ones with test sources in the default reactor, and
all three must report. The trusted denominator is measured, not assumed: on Forge
master `b95c07b3436bf582274ea458520ca30aa379d6e7` the dry run enumerates
**666 tests** — `forge-game` 3, `forge-gui-desktop` 450,
`forge-protocol2-bridge` 213 — matching the existing `test-build.yaml` gate
exactly. An earlier revision used `-pl forge-gui-desktop -am`, which silently
dropped `forge-protocol2-bridge`'s 213 tests (32 % of the suite); that is
recorded as a mistake and is now prevented by a mutation control on the include
patterns.

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
```

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
* **H** an honest exact candidate reaches `PASS`.

`qualification_mutation_check.py` mutates the product code once per safety
property in a scratch copy and requires the control suite to go red. A surviving
mutation exits nonzero. Without it a green control suite would prove nothing; it
is what makes the red controls non-vacuous.

Both are stdlib-only. Forge has no Python test infrastructure, so `unittest` is
used deliberately rather than adding a dependency.

## Residual limitations

Stated as part of the evidence, not hidden.

* **No in-JVM channel is unforgeable against adversarial candidate test code.**
  Candidate tests run in the same JVM as the trusted listener, so a candidate
  could in principle append witness records directly. That is a strictly harder
  threat than forging build artifacts, requires a deliberate act in the
  candidate's own test code (visible in its diff), and is not claimed as
  prevented. What *is* prevented is everything in the enumerated attack list:
  build/plugin/lifecycle suppression, Maven success with no execution, forged
  or copied or committed reports, and manipulated report counts.
* **It cannot prove a candidate-owned test method is semantically strong.** That
  is review, not CI.
* **It is not a required status check.** It is informational until a separate
  decision promotes it.
* **Candidate definition divergence is reported, not enforced.** Enforcing it
  would duplicate lab CI-02's required-check mutation detection (#483).

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