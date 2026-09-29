# Forge unified qualification successor: terminal handoff

Claude Opus 5.5 (Claude Code), 2026-09-29. Exclusive Forge workstream.
`PRODUCTION_PROVIDER = NOT_SELECTED` · `ARCHITECTURE_FREEZE = NOT_CLAIMED`

## Source Lock
- **Successor branch:** `claude/forge-unified-successor-20260929`, moeendres-png/forge PR #11 (draft, base = PR #6 branch, **not for master**).
- **Tested code head:** `32c9216cf02607377881396cd689b8cc8f90ca2c`, tree `0474d35388248e228b2e292ea1db4271635b71ba`. This handoff commit adds documentation only.
- **Ancestry:** begins at the exact PR #6 head `b31d21df23815d9ba28235df59ab7b7656881cc3`.
- **Donors:**
  - PR #7 `0fb74efee7b27be27f0500069bfcf72f64b54ae9`
  - PR #9 `a975467c3e7ec0f6b67cb8122f0ebf00718d87ad`
  - common base PR #5 `e15f37d6b2b5c0ad682948f86f037e07b6aaded5`
- **Rules:** CR 2026-09-25 (sha256 `8d860e45…`). Oracle via Scryfall, 2026-09-29.

## Work Completed
1. **Commit and path adjudication.**
   - #6 = #5 + 4 commits: `Card.java` alternate-split-state enumeration, `DeepseekAftermathDiscoveryTest`, the PB-07 probe.
   - #7 = #5 + 17 commits (`forge-protocol2-bridge` main and tests only).
   - They are **file-disjoint**.
2. **Merged #7** into the #6 base (normal merge, no conflicts).
3. **Rules adjudication of #6.**
   - Oracle: Find // Finality is an ordinary split card, and Finality has **no** Aftermath (Lab finding F-11: Lab commit `bc347e62` added `K:Aftermath`).
   - #6's tests bound its fix to Finality as an aftermath card.
   - Experiment: the same assertions bound to **Cut // Ribbons** (a genuine aftermath card) **fail 3/6 without #6's `Card.java` hunk and pass 6/6 with it**. The systemic fix is real, and only its evidence premise was wrong.
4. **Merged #9** (Sol's PB-07 source-truth correction). The single conflict (`WsR24Pb07MechanicProbesTest`) was resolved to #9's Oracle-correct version. #7 never touched that file.
5. **Re-bound** `DeepseekAftermathDiscoveryTest` from Finality to Cut // Ribbons. The assertions are unchanged; this is in commit `32c9216c` with the #9 merge.

## Changes (successor vs PR #6 head)
- All 17 #7 bridge commits:
  - combat declarations (engine-valid only, multi-block, wide boards);
  - optional/additional costs, unless costs (mana and non-mana);
  - tap-N-type and crew costs; ninjutsu, energy, reveal, exert and mill costs;
  - emerge/offering; fail-closed unframed costs;
  - scry, surveil, type, vote, piles and single choices;
  - multiplayer trigger ordering and >9-target scale; distinguishable players and objects;
  - object_refs that respect visibility.
- #9: `find_finality.txt` restored to Oracle, `AftermathKeywordOracleConsistencyTest`, `Ws234CleaveAftermathTest`, the Oracle-correct CARD_28 probe, and PB-07 evidence records.
- `DeepseekAftermathDiscoveryTest` → Cut // Ribbons.
- No card-name hacks. Legality stays in the Rules Core; the bridge offers engine-enumerated options only.

## Impact Adjudication
- **Rules Core delta vs the #5 lineage:**
  - `Card.getAllPossibleAbilities` now enumerates the alternate split half; zone legality is left to the card's own restrictions (#6);
  - the Find // Finality script is corrected (#9).
- **Bridge delta:** #7, file-disjoint and unchanged.

## Tests
| Scope | Command | Result |
|---|---|---|
| Focused Aftermath (#6 re-bound, #9) | `mvn -o -pl forge-gui-desktop -am test -Dtest=DeepseekAftermathDiscoveryTest,Ws234CleaveAftermathTest,AftermathKeywordOracleConsistencyTest` | 6/6, 5/5, 2/2 |
| Discriminating experiment | same Aftermath assertions with `Card.java` hunk reverted | Ribbons 3/6 fail (fix proven necessary) |
| Complete `forge-protocol2-bridge` suite (all #7 decision-surface tests, #9 PB-07 probe) | `mvn -o -pl forge-protocol2-bridge -am test` | **347 run, 0 failures, 0 errors, 0 skipped** |
| Rules-Core game simulations | `xvfb-run -a mvn -o -pl forge-gui-desktop -am test` | **458 run, 0 failures, 0 errors, 6 skipped** |
| Checkstyle | `mvn -o validate -pl forge-core,forge-game,forge-ai,forge-gui,forge-gui-desktop,forge-protocol2-bridge` | pass |
| Exact-head GitHub workflows | Test build (Java 17/21), iOS compatibility gate | on PR #11 |

## PASS / FAIL / UNKNOWN
- **PASS:** all local scopes above.
- **UNKNOWN:** the exact-head GitHub workflows until they complete; modules outside the tested reactor (mobile, android, ios) are covered only by CI.

## Evidence invalidated by the new combined identity
- Any evidence bound to the PR #6 head, the PR #7 head, or #5-lineage heads is **HISTORICAL_ONLY** for its own identity. It is not credited to this successor without re-execution. Everything listed under Tests above was re-executed.
- **INVALIDATED:** PB-07 / CARD_28 evidence that asserted a graveyard (aftermath) cast of Finality, i.e. #6's probe and the WS234-era premise. It is contrary to Oracle, and #9 already marks the WS231/WS234/WS236 records superseded.
- Forge qualification rows touching split-card enumeration, or decision surfaces changed by #7, require re-execution before any credit on this identity.

## Remaining Blockers
- **Coordinator decision:** accept #9's inclusion (this successor goes beyond the literal "#6 + #7" scope for Rules correctness) or require the pure #6 + #7 identity. The latter would keep tests that contradict Oracle.
- The Lab Forge pin / PB-09 adoption of this successor is a separate Lab workstream. The Lab is not changed here.
- PR #10 (Sol: #7@`e623140d` + #9) is superseded in content by this successor. That's the Coordinator's call.

## Exact Next Action
1. Confirm the exact-head CI on PR #11.
2. The Coordinator adjudicates the #9 inclusion.
3. If accepted, a Lab workstream re-pins the Forge candidate to `32c9216c` (or this handoff's descendant) and re-executes the Forge qualification rows.
