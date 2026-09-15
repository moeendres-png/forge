# Admission Contract Interpretation (WS231, provider-neutral)

Source: `ADMISSION_STAGE_CONTRACT.json` (schema
`ws225-admission-stage-contract/1.0.0`) + `CANDIDATE_ADMISSION_BAR.json`,
consumed read-only from the WS226 Lab reference. No XMage-shaped APIs are
imposed on Forge; semantic capabilities only, with the
`forge-decision-protocol/1.0.0` mapping layer as the candidate's mapping.

- **Funnel rule.** Cheap terminal blockers stop expensive downstream work. A
  stage FAIL is terminal for the current pin; later stages are still evaluated
  diagnostically (full gap set) but cannot overturn the terminal verdict.
  No candidate reaches S5 spend while a terminal blocker stands at S0–S4.
- **S0.** PASS iff commit+tree+license(SPDX)+build-or-adapter identity are all
  recorded and verifiable. Missing identity ⇒ FAIL (terminal).
- **S1.** PASS iff sole-Rules-authority design AND legal-action surface
  inventory AND fail-closed unsupported-path design AND principal-scoped
  observation design are all present at CODE_DERIVED minimum. A PROVEN
  whole-boundary violation (prohibited defaults in the production-reachable
  path) ⇒ FAIL terminal. Absent inventory (never performed) ⇒ UNKNOWN
  (insufficient evidence, still terminal for the pin).
- **S2.** PASS iff 2P–5P constructible AND Commander/multiplayer semantics
  viable at CODE_DERIVED minimum. A PROVEN init block (e.g. Partner
  unconstructible) is recorded as an S3-relevant gap; S2 FAIL only if the
  cardinalities themselves are unconstructible. WS231 task proof bullets per
  cardinality: session constructible, all seats externally controlled,
  principal identity distinct, turn order structurally valid, **no
  fixed-four-seat provider assumption**, no immediate bridge/controller
  blocker, clean termination/cleanup.
- **S3.** PASS iff frozen SUPPORTED==29 with one dedicated behavior test
  inventoried per CARD_* fixture (DIRECTLY_VERIFIED census grade; execution
  deferred to S5) AND micro implemented==17 with zero blocked areas. Anything
  less ⇒ FAIL terminal (proven gap). Construction/parsing is not behavior;
  import/parser support alone is not behavior support.
- **S4.** PASS iff Rules RNG attribution design AND a bounded clean-process
  semantic-replay runtime proof (minimum one tape + independent clean-process
  replay, WS218-Tape-v1 shape) exist. Design without runtime proof ⇒ FAIL.
  Slot/in-process-only replay ⇒ FAIL.
- **S5.** Out of scope for WS231 (NOT_RUN). S5 PASS is the only promotion
  event into full qualification.
- **Evidence grades.** CODE_DERIVED is not runtime; CODE_DERIVED suffices for
  S0 records and S1/S2 feasibility designs, while S3 counts are census-grade
  and S4 needs bounded replay proof. Historical runtime evidence may be
  retained only under exact current-pin identity plus unchanged relevant
  paths. UNKNOWN is terminal for candidate admission just as FAIL is; reason
  codes distinguish missing evidence from proven defect.
- **Current standing vs successor.** WS226 FORGE_STANDING (AF04 FAIL on the
  stock remote path) is historical evidence computed from older pins. WS231
  does not inherit it blindly nor erase it: it re-evaluates S0–S4 against the
  WS227 successor and reports CURRENT_CPL_STANDING /
  POST_WS227_CANDIDATE_ASSESSMENT / FUTURE_LAB_RECOMPUTATION_REQUIRED.
