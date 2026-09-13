# WS93 20-Kind Decision Census (from BLOCKER_CENSUS.json + adjudication)

Authority: WS90 `FIRST_WAVE_DECISION_REQUIREMENTS_CORRECTED.json` (20 kinds).
Evidence codes: P = RUNTIME_VERIFIED via real dispatch (this session);
C = CODE_DERIVED from pinned bridge source; C+P = both.

| # | kind | carriers | status | evidence |
|---|---|---|---|---|
| 1 | pass | all 15 | SUPPORTED | P (pass option + existing 87/87 baseline, not re-run) |
| 2 | cast | 10 slots | PARTIAL (targetless+X-free+zero-mana+simple-cost only) | C |
| 3 | activate | A03 | PARTIAL (same priority gate) | C |
| 4 | mana source | A03,A04,C01 | PARTIAL (fixed-output only) | C |
| 5 | X | A04,C03 | BLOCKED (X_VALUE) | C |
| 6 | mana payment | 11 slots | BLOCKED unless provably zero | C |
| 7 | targets | 7 slots | BLOCKED | C+P (select_targets=unknown_message) |
| 8 | alternate cost | C01 | BLOCKED | C |
| 9 | hidden-zone selection | C01,F01 | BLOCKED | C |
| 10 | modes | D06 | BLOCKED | C+P (choose_modes=unknown_message) |
| 11 | replacement ordering | A04 | BLOCKED | C |
| 12 | trigger ordering | B01 | BLOCKED | C+P (order_triggers=unknown_message) |
| 13 | attackers | E01,E02,G03,J02 | BLOCKED | C |
| 14 | defender per attacker | E01,E02,G03,J02 | BLOCKED (same declareAttackers callback) | C |
| 15 | blockers | E02,G03 | BLOCKED | C |
| 16 | combat damage assignment | E02 | BLOCKED | C |
| 17 | search | F01 | BLOCKED | C |
| 18 | Commander movement | G02 | BLOCKED (choice path; counts/damage observation visible) | C |
| 19 | concession | G04 | BLOCKED at protocol | P (concede=unknown_message; engine seam NOT_REACHED) |
| 20 | copy choices | H01 | BLOCKED | C |

Result: 1 SUPPORTED, 3 PARTIAL, 16 BLOCKED (first-blocker per slot in BLOCKER_CENSUS.json).
NOT_REACHED is not PASS. No WS65 credit imported. Behavior scoring NOT_RUN.
