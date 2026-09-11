# 03 — Root Cause (CODE_DERIVED + DIRECTLY_VERIFIED experiments)

Evidence class: CODE_DERIVED (source-structure proofs) + DIRECTLY_VERIFIED
(ablation and pre-WS59 engine runs).

## R1. The WS59 Stack->Battlefield "equals-gate" block is dead code

`GameAction.changeZone`: `toBattlefield` is assigned once and never
reassigned. The WS59 cast-linkage block sat inside the `else` branch of
`if (toBattlefield || ...)`, while its own condition required
`toBattlefield` — a contradiction, so it could never execute for a real
battlefield entry (for which `copied == c`, no `copyCard`). Temporary
firing diagnostics across 8+ instrumented natural-cast runs: zero firings.
Its model comment ("battlefield copy") is false and its law citation
(107.3k) is stale.

## R2. Actual CR 107.3m lineage path (why the pipeline is green)

1. `PlaySpellAbility.playAbility`: `moveToStack` sets the stack copy's
   `castSA` to the casting SA; X is announced onto that same SA object via
   the native `announceRequirements` decision; payment records X-by-color.
2. `MagicStack.addAndUnfreeze`: refreshes the stack card's `castSA` with an
   LKI copy of the X-bearing SA (clone preserves X, same id, shared
   CardState, hence same original-host reference).
3. Resolution (`PermanentEffect` -> `moveToPlay` -> `changeZone`,
   `copied == c`): the `Moved` replacement evaluates the card's own
   `etbCounter` with `c.getXManaCostPaid() == 3` and
   `isUnlinkedFromCastSA == false`; the populated CounterMap routes through
   `AddCounter`, where Doubling Season + Hardened Scales contest via the
   existing `chooseSingleReplacementEffect` ordering. 3 -> 7|8.

## R3. Ablation proof (DIRECTLY_VERIFIED)

With both WS59 A04 `GameAction` blocks disabled (`if (false && ...)`),
recompiled and re-run: natural exact + base 2/2 green; direct
`Ws59A04ReplacementOrderingTest` 3/3 green. The natural path never depended
on WS59, and the direct path does not depend on those blocks either
(consistent with WS59's own note that the table remap is "No-op in the
exact fixture").

## R4. Pre-WS59 engine proof (DIRECTLY_VERIFIED)

All six WS59 production files reverted to `HEAD~1` (coherent pre-WS59
engine; G04/C01 test files temporarily set aside for compilation): the
HEAD direct A04 test passes 3/3. The direct path was already green before
WS59; WS59's "was 0" claim does not reproduce for this path.

## R5. Consequence for the WS62 shape

Per 01, the WS62 run requires an empty `Moved` list. Per R2-R4, no native
path in this repository produces that: the entering card's own `Moved`
replacement always lists (even singleton) and always invokes the
controller. Object-identity suspects were tested, not assumed
(`c.equals(cause.getHostCard())` holds; chosen X reaches the stack ability;
payment records on the stack card; `isUnlinkedFromCastSA` is false; cast
lineage survives copying). The remaining un-replicated components are the
CPL vertical-provider engine-side input handling and the exact 29-turn
history, both outside this repository. Provider synthesis count is 0 and
provider repair is forbidden, so the WS62 shape is dispositioned as
NOT_REPRODUCED Forge-side with a provider-side investigation required —
not as a Forge pipeline defect.
