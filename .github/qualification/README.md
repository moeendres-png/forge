# Forge exact-SHA candidate qualification (D17)

Tracked by `moeendres-png/commander-playtest-lab#498`, roadmap
`moeendres-png/commander-playtest-lab#479`.

This directory and
`.github/workflows/forge-candidate-qualification.yml` implement a **trusted
exact-SHA candidate qualification** for Forge. It is deliberately *not* a
mergeability signal and *not* a Rules qualification.

## Trust boundary

| Property | Mechanism |
| --- | --- |
| The candidate cannot define its own qualification | The workflow trigger is `pull_request_target`, so the executing definition is loaded from the trusted default branch `master`. |
| The authority is provably trusted | `source_lock.py` requires the run to execute from `refs/heads/master` at its tip, and requires `master` to physically carry both this directory and the workflow file. A detached HEAD, another branch, or a default branch without the definition is a hard failure. |
| Candidate identity is exact | The candidate must be a full lowercase 40-hex commit. The ref fetched must resolve to exactly that commit. Abbreviated, uppercase, ref-shaped, absent or mismatched identities are hard failures. |
| No synthetic-merge fallback | `refs/pull/<n>/merge` — GitHub's synthetic merge — is not an acceptable fetch ref. No merge is computed and no mergeability field is read anywhere. |
| No fabricated results | The verdict is derived by the trusted verifier from the trusted source lock, the trusted run report, and candidate test reports. Candidate reports contribute executed/failed/skipped counts and skipped-class attribution only. Verdict-shaped attributes found in them (`verdict`, `status`, `result`, `outcome`, `passed`, `success`) are discarded and listed in the evidence. |
| Non-PASS is never green | `PASS` exits 0. `FAIL`, `PARTIAL`, `NOT_RUN` and `UNKNOWN` exit 1, 2, 3 and 4 respectively, so no consumer can read an unproven qualification as a passing check. |
| Absurd identity claims are rejected | A lock document that does not assert `candidate_code_executed_as_validator=false`, `verdict_read_from_candidate=false`, `synthetic_merge_consulted=false`, `pr_mergeability_consulted=false`, or whose comparison base claims a synthetic merge, is rejected on load. |

The candidate working tree is materialized as a **separate repository** (own
HEAD, index, config and refs) so that candidate code never replaces the trusted
workspace. Its HEAD and TREE are re-proven from inside that workspace against
the locked identities before any candidate code runs.

## Separately reported evidence classes

The evidence document keeps these four classes apart. None is ever promoted into
another.

| Class | Status in the evidence | Credit |
| --- | --- | --- |
| `exact_candidate_qualification` | `PASS` / `FAIL` / `PARTIAL` / `NOT_RUN` / `UNKNOWN` | the only class this gate asserts |
| `pr_mergeability` | `NOT_APPLICABLE` | none — not an input to the verdict |
| `synthetic_merge_evidence` | `NEVER_COMPUTED` | none |
| `rules_qualification_evidence` | `NOT_CLAIMED` | none — a green CI qualification is not a Magic Rules qualification |

## Verdict derivation

Signals are evaluated strongest-negative-first:

1. `candidate_identity_bound` — the run report's SHA **and** TREE equal the locked candidate.
2. `trusted_steps_succeeded` — every trusted step exited 0.
3. `run_completed`.
4. `no_failed_cases` — zero failures and zero errors in the candidate evidence.
5. `reports_present` — at least one report was discovered.
6. `reports_parsed` — no unparseable report.
7. `tests_executed` — at least one executed test case.
8. `expected_surface_reported` — every declared module reported.
9. `no_undeclared_skips` — no skipped case inside the qualified surface.

| Verdict | Meaning |
| --- | --- |
| `FAIL` | Something is proven wrong or a trusted step failed. |
| `PARTIAL` | The surface reported incompletely, or coverage inside it was skipped. |
| `NOT_RUN` | No candidate test report exists; execution is unproven. |
| `UNKNOWN` | Evidence exists but is malformed, ambiguous, or contains no executed case. |
| `PASS` | The exact candidate SHA/TREE executed the full bounded surface without failure. |

## Bounded surface

`QUALIFY_EXPECTED_MODULES` in the workflow declares the modules that must
report, and `QUALIFY_OUT_OF_BAND_CLASSES` declares test classes outside the
qualified surface.

The surface is intentionally identical to the existing `test-build.yaml` gate:
`xvfb-run -a mvn -U -B clean test` over the whole reactor. Measured on Forge
master `b95c07b3436bf582274ea458520ca30aa379d6e7` with Java 21 that is **666
tests** in **17:04 min** — `forge-game` 3, `forge-gui-desktop` 450 and
`forge-protocol2-bridge` 213. Those three are the only modules with test sources
in the default reactor, and each must report or the qualification is `PARTIAL`.
Qualifying a narrower subset would silently drop 213 existing tests from the
candidate's qualification, so the surface is not narrowed.

Only a skip in a declared out-of-band class is excluded. A skip in any other
class is `PARTIAL`, and a skipped count that cannot be attributed to a class is
always `PARTIAL` (`<unattributable>`), so an exemption cannot be laundered
through an opaque report. `skipped_total`, `observed_skipped_classes`,
`declared_out_of_band_classes` and `undeclared_skipped_classes` are always
reported, so the exclusion list is an auditable scope statement rather than a
silent suppression. Adding a class to that list is a change to the trusted
definition on `master` and is therefore visible in review.

This gate qualifies the Java 21 leg only. The Java 17 leg remains covered by the
`test-build.yaml` matrix; JDK battery scope belongs to D21/#502.

## Controls

```
python3 .github/qualification/qualification_selftest.py
python3 .github/qualification/qualification_mutation_check.py
```

`qualification_selftest.py` builds real Git repositories in temporary
directories and asserts the **specific** non-`PASS` class for every red
control, so a control cannot pass for the wrong reason. It covers identity
mismatch, abbreviated identity, synthetic-merge refusal, off-default-branch
execution, detached HEAD, unrelated event base, lock-document trust
overstatement, candidate edits to the definition, malformed and absent
evidence, missing modules, undeclared skips, unattributable skips,
candidate-fabricated verdicts, and mergeability/synthetic-merge claims.

`qualification_mutation_check.py` mutates the product code once per safety
property in a scratch copy and requires the control suite to go red. A
surviving mutation exits nonzero. Without it, a green control suite would prove
nothing; it is what makes the red controls non-vacuous.

Both are stdlib-only. Forge has no Python test infrastructure, so `unittest` is
used deliberately rather than adding a dependency.

## Scope

This gate is **informational**. It is not a required status check, and making
it required is a separate governance decision. It asserts no Rules correctness
credit and is not evidence for any Magic Rules qualification claim.

Not in scope for D17: superseded-head concurrency (D18/#499), JUnit/timing
artifact provenance (D20/#501), JDK battery reduction (D21/#502), CR 104.3f
disabled Rules test remediation (D22/#503), and the first-option ratchet
(D23/#504).