# PB-07 Source-Truth Successor Integration Record

Branch: `sol/pb07-sourcetruth-successor-20260929`
Base: PR #7 `claude/optimistic-bohr-6asye6` at `e623140ded42afde40afab9f829b5279de3186ad`
Date: 2026-09-29
Status: integration applied; validation in progress (see test section).

## 1. Source locks consumed

| Role | Identity |
|---|---|
| Successor base (PR #7 HEAD at snapshot) | `e623140ded42afde40afab9f829b5279de3186ad` |
| PR #7 common ancestor with the donor line | PR #5 `e15f37d6b2b5c0ad682948f86f037e07b6aaded5` |
| PB-07 source-truth donor (PR #9 HEAD) | `a975467c3e7ec0f6b67cb8122f0ebf00718d87ad` |
| Minimal duplicate remediation (PR #8 HEAD) | `4f6e3c766a7d031fdbfc4dfa64d352586f9abccd` |
| Obsolete Aftermath-enumerability candidate (PR #6 HEAD) | `b31d21df23815d9ba28235df59ab7b7656881cc3` |
| Forge master | `ef958ee91ac6c9ce0152189f2654bf6e05abf273` |
| Pristine upstream Forge (reference/control identity only) | `a37a865a53280dd8ad6fad3384d69611e8c5a42f` |

The assignment's expected PR #7 HEAD (`eb3aacad5342a0f103fba40d639a8bc12d8ad61b`) was stale:
the branch had advanced two commits (`eb3aacad` → `ffde1b9e` → `e623140d`) on the same lineage
by the same campaign. The successor snapshots the freshly verified HEAD `e623140d`; the drift
is recorded, not hidden.

## 2. Ownership

- No Foundry state file claims PR #7 or the decision-surface files; no local worktree holds the
  #7 branch. PR #7 itself is never mutated by this workstream.
- The #7 writer pushed during this session (commits up to minutes before the snapshot). There is
  no same-file contention: the #7 delta and the #9 donor delta are file-disjoint (see §3), so no
  demonstrated ownership conflict exists. The moving base is handled by exact-SHA snapshot
  semantics; any later #7 push is a newer base the coordinator can rebase onto.

## 3. Path/commit impact inspection (before the transplant)

PR #7 vs PR #5 touches only `forge-protocol2-bridge` (14 files: `BridgeCostDecisionMaker.java`,
`BridgeEngine.java`, `ExternalPlayerController.java` plus 11 new decision-surface test files,
including `MultiplayerCombatTest.java` and `MultiplayerEliminationTest.java` at the snapshot head).

PR #9 vs PR #5 touches: `find_finality.txt`, `Ws234CleaveAftermathTest.java`,
`AftermathKeywordOracleConsistencyTest.java` (new), `WsR24Pb07MechanicProbesTest.java`,
`wsr24-evidence-closure/PB07_EVIDENCE.json`, `wsr24-evidence-closure/WORKSTREAM_STATE.md`,
`wsr24-evidence-closure/FIND_FINALITY_SOURCE_TRUTH_20260929.md` (new), 3 supersession pointers.

**Intersection: empty.** No file is changed by both sides, so no wholesale merge was performed:
the five PR #9 commits were cherry-picked verbatim (new SHAs `165c72951`, `9e98979e7`,
`792607c88c`, `60a69dcd6`, `b172f0e4a`) with zero conflicts.

## 4. Behavioral impact analysis (the only real risk)

The donor probes call into production bridge code that PR #7 changed. Checked on the merged tree:

- `DecisionFrame.java`: untouched by #7. Every `Option` field the probes use
  (`actionType`, `label`, `sourceCardName`, `nativeBinding`, `nativePayload`,
  `payloadKind`, `confirmValue`, `optionId`, `isPass`) is intact.
- `BridgeTestSupport.java`: untouched by #7 (all #7 test files are new).
- `BridgeEngine.java`: notes-string only.
- `ExternalPlayerController.java` (+915/−142): all changes are broader offering
  (optional-cost `COST_SELECTION` frames, unless-cost framing, combat declarations,
  surveil/scry/single-choice framing). The three mana payload kinds the card script's
  `payScriptedBasicLands` understands (`SPELL_ABILITY` tap-source inside a `MANA_PAYMENT`
  frame, `MANA` pool units, `MANA_COMBO` allocations) still exist with the same semantics;
  the two new `SPELL_ABILITY` sites are under `COST_SELECTION`/`COPY_CHOICE`, which the
  helper never sees (it asserts the `MANA_PAYMENT` kind first and hard-fails on any
  unrecognized mana payload kind).
- `SpellAbility.getCardState()` (used by `splitHalfName`) is Rules-Core API; the Rules Core
  is untouched by both #7 and the donor.
- Rules-Core Java diff on this branch vs the #5 line: **zero files** outside test/resource
  surfaces except #7's own `forge-protocol2-bridge` production files. No `forge-game` /
  `forge-core` mutation exists anywhere in this lineage.

Remaining risk is therefore purely behavioral and is covered by re-execution (§6).

## 5. Hard semantic gates (status on the successor head)

1. Find // Finality is not Aftermath — proven by `testFindFinalityBothHalvesArePlainSplitSpells`
   and the card-database invariant (7/7 engine tests green).
2. Both halves have correct ordinary split-card semantics — hand casts at CMC 2 and 6, combined
   characteristics (8 total, black-green), GUR #225 split-card frame facts in the record.
3. The fork's Aftermath population is exactly the Oracle set — both invariant tests iterate the
   whole card database; no card-name special case anywhere in the new code.
4. CARD_28 executes the frozen Lab fixture — `testFinalityFromHandResolvesAsymmetricPump` runs
   the `cast_split_half:Finality` decision, the six fixture payment sources, and the fixture
   counter target, all through engine-offered options with fail-closed uniqueness.
5. The PB-07 count is backed by executed tests — 17/17 probes + 7/7 engine tests at this head;
   full suites below.
6. No PR #7 decision-surface regression — full bridge suite re-run below; #7's own 13 test
   classes execute inside it.
7. No Rules-Core Java mutation — structural fact, verified by diff (see §4).
8. Unsupported/ambiguous paths fail closed — by construction (exact-match helpers, unrecognized
   payload kinds are hard failures) and by the suite (any unexpected `UNSUPPORTED`/extra frame
   fails loudly, never silently).

## 6. Test evidence on the exact successor head

| Suite | Command | Result |
|---|---|---|
| Focused engine (`Ws234CleaveAftermathTest`, `AftermathKeywordOracleConsistencyTest`) | `mvn -B -pl forge-gui-desktop -am test -Dtest='Ws234CleaveAftermathTest,AftermathKeywordOracleConsistencyTest'` | **7 run, 0 failures, 0 errors, 0 skipped** |
| Focused CARD_28 probes | `mvn -B -pl forge-protocol2-bridge -am test -Dtest='WsR24Pb07MechanicProbesTest#testFindFromHandAndGraveyardHalfRefused+testFinalityFromHandResolvesAsymmetricPump'` | **2 run, 0 failures, 0 errors, 0 skipped** |
| Full `forge-protocol2-bridge` | `mvn -B -pl forge-protocol2-bridge -am test` | **338 run, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS** (302 donor-era + 36 new #7 decision-surface tests; #7's own 13 test classes execute inside it) |
| Full `forge-gui-desktop` | `mvn -B -pl forge-gui-desktop -am test` | **452 run, 0 failures, 0 errors, 6 skipped — BUILD SUCCESS** (the 6 skips are all `NetworkPlayIntegrationTest` network-environment skips, identical to the donor-head run; all 7 Aftermath/Cleave/Finality cases executed and passed, verified in `TEST-TestSuite.xml`) |
| Checkstyle | runs inside every `mvn` invocation above | **0 violations** in every module |

The PB07 register (`PB07_EVIDENCE.json` 1.1.0, 29/0/0/0) was authored for the #5-based donor head.
It stands re-verified on this successor head by the full `forge-protocol2-bridge` result above
(338/338, including the 17/17 `WsR24Pb07MechanicProbesTest` probes and all #7 decision-surface
classes). This paragraph is that re-verification, recorded additively; the donor row itself is
untouched.

## 7. PR dispositions (to be posted once the successor is validated)

- PR #8: duplicate minimal source-truth remediation (same byte-identical card fix, no register).
- PR #9: donor/evidence predecessor, now carried by the successor.
- PR #6: superseded with respect to the false Find // Finality Aftermath requirement.
- None of #6/#8/#9 is closed, merged, or mutated by this workstream. No branch is deleted.
  This successor is not merged to `master`.

## 8. PB-09 identity boundary

- Pristine upstream `a37a865a` = reference/control identity, untouched.
- This successor = the actual modified Forge candidate identity.
- No pristine execution credit transfers to the modified candidate merely because the corrected
  `find_finality.txt` is byte-identical to pristine for this one card. PB-09 runtime
  comparison/requalification is a separate later workstream and is not started here.

## 9. Governance

`PRODUCTION_PROVIDER = NOT SELECTED`. `ARCHITECTURE_FREEZE = NOT CLAIMED`.
