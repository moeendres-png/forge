# 04 — CR 107.3m Design (TECHNICALLY_CONFORMANT + DIRECTLY_VERIFIED behavior)

Evidence class: rule text EXTERNALLY_RULE_VALIDATED (Coordinator gate);
engine mapping TECHNICALLY_CONFORMANT; behavior DIRECTLY_VERIFIED (02).

CR 107.3m: an ETB triggered ability or replacement effect referring to X
uses the chosen X of the spell that became that object as it resolved,
although the permanent itself ordinarily has X=0. Current CR 107.3k
concerns X in activated-ability costs and must not be cited for ETB-X.

## Engine mapping (general, no card names)

- Chosen spell X lives on the resolving spell SA (`setXManaCostPaid` via
  the native announce decision) and is visible on the stack card through
  its `castSA` linkage (`Card.getXManaCostPaid`).
- ETB evaluation (`AbilityUtils.xCount`, xPaid branches for ChangesZone
  triggers and ETB-flagged replacements) reads `c.getXManaCostPaid()`
  unless `isUnlinkedFromCastSA` reports genuinely divergent original hosts
  (fail-closed to 0). Non-ETB xPaid queries still return 0 by construction.
- No production logic keys on Stonecoil Serpent, Doubling Season, Hardened
  Scales, Hangarback Walker, or any other card name (verified by grep in
  the change surface; the suite proves generality across two doublers
  pairs and two X creatures).
- The permanent is never assigned a rules-value X: X is observable only
  through the resolving-spell lineage during ETB evaluation. The negative
  control (non-cast entry, null cause) materializes 0 and dies via SBA,
  proving no global leak and fail-closed lineage.

## Production delta in this workstream (behavior-neutral)

1. `GameAction.changeZone`: removed the dead Stack->Battlefield
   cast-linkage block (R1) and replaced it with a note recording why no
   copy is needed there (`copied == c`; lineage via moveToStack plus
   `addAndUnfreeze`). Net -13/+7 lines, no executable behavior change
   (proven by R3 plus the full regression matrix in 07).
2. `AbilityUtils.xCount`: corrected the stale `107.3k` citation to
   `107.3m` on the live ETB-X branch (comment-only fix, as required by the
   Coordinator gate whenever the surface remains).

Explicitly NOT done: global permanent-X assignment, replacement-framework
bypass, counter precomputation/injection, controller/provider-owned
ordering, card-name special cases. A broader `castSA` overwrite at
resolution was considered and rejected: `castSA` consumers (payingMana,
last-state maps, TotalManaSpent, optional-keyword amounts, Gift/Teamwork/
Escape/Emerge flags) can distinguish the LKI copy from the resolving SA,
so identity surgery without a diagnosed defect would risk real regressions
on the hottest engine path.
