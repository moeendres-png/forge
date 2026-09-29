# Find // Finality — PB-07 / CARD_28 Source-Truth Record

Branch: `sol/finality-sourcetruth-pb07-20260929`
Base: Forge PR #5 head `e15f37d6b2b5c0ad682948f86f037e07b6aaded5` (tree `a1d4d4a8fe421e57b919e8e0bd9fda7d9deb0d3b`)
Date: 2026-09-29
Status: source truth corrected; PB-07 row 28 re-derived at this head. Global PB-07 closure still
NOT claimed (this record closes one row and retracts one false premise, nothing more).

## 1. Source locks consumed

| Repository | Lock | Tree |
|---|---|---|
| Forge `master` | `ef958ee91ac6c9ce0152189f2654bf6e05abf273` | — |
| Forge PR #5 (`wsr28/commander-legality-and-scoping-20260928`) | `e15f37d6b2b5c0ad682948f86f037e07b6aaded5` | `a1d4d4a8fe421e57b919e8e0bd9fda7d9deb0d3b` |
| Forge PR #6 (`sol/final-candidate-successor-20260929`) | `b31d21df23815d9ba28235df59ab7b7656881cc3` | `2d7d39fa23192788f968ac2a75322ab6bca8a3f3` |
| Forge PR #7 (`claude/optimistic-bohr-6asye6`) | `d71f5b865521…` | — |
| Pristine upstream Forge | `a37a865a53280dd8ad6fad3384d69611e8c5a42f` | `4471ff068dd23127fc5878bdffa0c0e6de8e6c28` |
| Commander Lab `main` | `878125ab3c75ee2af2358dbaa8d11282617c050e` | `d987b2edaa9aaaa505781fc41667468b1c5114c6` |
| Commander Lab PR #299 (`glm-max/pb09-pristine-forge-20260929`) | `dfed3f437c27…` | — |

XMage pin `b19596980f2734496ea1896504253e1bdd2756dd` was used as an independent implementation
witness (Lab PR #294 / #292 line), not as an authority over Oracle.

## 2. Oracle and rules authority

| Source | Statement |
|---|---|
| Oracle text, Find // Finality (Double Feature, `rvr` 245 reprint; `mom` original) | layout `split`, type line `Sorcery // Sorcery`, `keywords: []`; Find face "Return up to two target creature cards from your graveyard to your hand."; Finality face "You may put two +1/+1 counters on a creature you control. Then all creatures get -4/-4 until end of turn." **No Aftermath on either face.** |
| CR 108.1 | "Use the Oracle card reference when determining a card's wording." |
| CR 702.127a | "Aftermath is an ability found on some split cards… 'You may cast this half of this split card from your graveyard,' 'This half of this split card can't be cast from any zone other than a graveyard,' and 'If this spell was cast from a graveyard, exile it instead of putting it anywhere else any time it would leave the stack.'" |
| Oracle Aftermath corpus | 27 distinct cards print Aftermath (Amonkhet / Kaladesh / MH2 / SNC / March of the Machine). `Find // Finality` is a March of the Gathering Double Feature card and is not one of them. |

Consequence: Find // Finality has no Aftermath half. Both halves are castable from the hand; neither
half is castable from a graveyard; the Aftermath exile-on-resolve replacement does not apply.

## 3. Provenance of the false mutation

Commit `bc347e62255e61d950154824b427251fdabcf5f6` — "WS234: systemic Cleave identity plus Aftermath
script fix with actual-card behavior tests" (2026-09-15) — is present on Forge `master` (it reached
`ef958ee9` through the WSR20/WSR24 lineage) and on PRs #4, #5, #6 and #7. Its only card-data hunk is:

```diff
 Name:Finality
 ManaCost:4 B G
 Types:Sorcery
+K:Aftermath
 A:SP$ PutCounter | …
-Oracle:You may put two +1/+1 counters on a creature you control. Then all creatures get -4/-4 until end of turn.
+Oracle:Aftermath (Cast this spell only from your graveyard. Then exile it.)\nYou may put two +1/+1 counters on a creature you control. Then all creatures get -4/-4 until end of turn.
```

The same commit added the three false Aftermath trials to
`forge-gui-desktop/src/test/java/forge/gamesimulationtests/ws234/Ws234CleaveAftermathTest.java`
(`testFindIsHandCastableFinalityIsAftermath`, `testFinalityOnlyCastableFromGraveyard`,
`testFinalityExilesOnResolve`) and wrote
`forge-protocol2-bridge/ws234-s3/AFTERMATH_VERDICT.json` with
`"verdict": "FULL (SUPPORTED) for Find // Finality"`.

Note the script edit was self-consistent (it rewrote the Oracle line too), so a script-internal
consistency check could not have detected it. Detecting it required external Oracle authority, which
is recorded in §2 and is the reason the card-database invariant test added here is explicitly
described as a one-sided-divergence detector, not as a substitute for Oracle review.

### Pristine upstream vs fork vs XMage

| Lineage | `find_finality.txt` Finality half |
|---|---|
| Pristine upstream `a37a865a` | no `K:Aftermath`; Oracle without the Aftermath prefix |
| Fork at PR #5 (pre-correction) | `K:Aftermath`; Oracle with the Aftermath prefix (false) |
| Fork at this branch | byte-identical to pristine upstream (verified with `git diff a37a865a` → empty) |
| XMage `b1959698` (independent) | plain split card; Finality castable from hand; neither half from a graveyard |

### Aftermath card-set cross-check

`grep -l "K:Aftermath"` over `forge-gui/res/cardsfolder` yields 27 scripts on this branch, and the
27 file names map one-to-one onto the 27 Oracle Aftermath card names
(`Appeal to Authority, Claim // Fame, Commit // Memory, Consign to Oblivion, Cut // Ribbons,
Destined // Lead, Driven // Despair, Dusk // Dawn, Failure // Comply, Farm // Market, Grind // Dust,
Heaven // Earth, Indulge in Excess, Insult // Injury, Leave // Chance, Mouth Feed, Never // Return,
Onward // Victory, Prepare // Fight, Rags // Riches, Reason // Believe, Reduce // Rubble,
Refuse to Cooperate, Road // Ruin, Spring // Mind, Start // Finish, Struggle // Survive`).
`Find // Finality` is absent. Before the correction the fork had 28 scripts, i.e. exactly one false
Aftermath, and it was this card.

## 4. Correction applied

Production surface (1 file):

* `forge-gui/res/cardsfolder/f/find_finality.txt` — `K:Aftermath` removed, Oracle line restored.

No Rules-Core Java change was made or needed. The Aftermath keyword support in the engine
(`Spell.Aftermath` stack gate, `CardFactoryUtil` RightSplit graveyard zone, the exile replacement,
`isAftermath()`) is correct and is exercised by genuine Aftermath cards; it is untouched.

### Rules-Core mutation from Forge PR #6 — disposition: NOT REUSED

PR #6 commits `49bcaed6517` (Card.getAllPossibleAbilities split-half enumeration), `6ea3d95357c`
(DeepseekAftermathDiscoveryTest), `6f70e32e810` and `b31d21df238` exist only to make the FALSE
Aftermath half enumerable and castable. With the false requirement removed:

* no PB-07 row needs the mutation;
* `Ws234CleaveAftermathTest` (the genuine-Aftermath control) does not need it;
* `AftermathKeywordOracleConsistencyTest` does not need it;
* the PB-07 register does not need it.

It is therefore not carried into this branch, and it is explicitly not retained on the strength of
its own previously green tests. This branch is based on PR #5, which is `49bcaed65`'s parent, so the
decision is structural rather than a revert. PR #6 remains open and unmodified for coordinator
adjudication of supersession.

## 5. Correction of dependent tests and evidence

| Surface | Before | After |
|---|---|---|
| `Ws234CleaveAftermathTest.testFindIsHandCastableFinalityIsAftermath` | asserted `finalitySa.isAftermath()` true and a Graveyard zone restriction | replaced by `testFindFinalityBothHalvesArePlainSplitSpells`: neither half is Aftermath, neither is graveyard-gated, and the split card's combined characteristics are 8 mana value and black-green |
| `Ws234CleaveAftermathTest.testFinalityOnlyCastableFromGraveyard` | asserted Finality is NOT castable from hand and IS castable from the graveyard | replaced by `testFinalityIsHandCastableAndGraveyardCastRefused`: both halves are offered as distinct hand casts (mana value 2 and 6) and the graveyard object exposes no Aftermath ability; `Commit // Memory` is the positive control that genuine Aftermath halves remain graveyard-gated |
| `Ws234CleaveAftermathTest.testFinalityExilesOnResolve` | asserted a graveyard cast resolves into exile | replaced by `testFinalityResolvesFromHandAndReturnsToGraveyard`: hand cast resolves, own 4/4 survives +2 counters and -4/-4, opponent 2/2 dies, card ends in the graveyard and NOT in exile |
| `WsR24Pb07MechanicProbesTest.testFindAndAftermath` | asserted the Aftermath survey finds nothing and that the engine enumerates zero abilities for the graveyard card | replaced by `testFindFromHandAndGraveyardHalfRefused` (Find runtime retained; negative control added: no graveyard cast of either half may be offered, and the graveyard object exposes no ability) |
| — | — | added `WsR24Pb07MechanicProbesTest.testFinalityFromHandResolvesAsymmetricPump`: the CARD_28 frozen decision (`cast_split_half` / half `Finality`) executed with external decisions only |
| — | — | added `AftermathKeywordOracleConsistencyTest` (card-database invariants) |

## 6. Decision integrity for the new CARD_28 runtime

The frozen Lab fixture is `commander-playtest-lab/qualification/ws47/SEMANTIC_FIXTURE_MATERIALIZATION_v1_0_5.json`
record `CARD_28` (index 97), whose `decision_script` and `expected_events` are:

* `semantic_value = {action: cast_split_half, half: Finality, object: obj:card_28-subject}` with
  `on_multiple_match: FAIL_CLOSED`, `on_zero_match: FAIL_CLOSED`;
* `action_cost_state[0].explicit_payment_sources = [obj:card_28-mana-p1-0 … obj:card_28-mana-p1-5]`,
  `minimum_mana_or_equivalent: 6`;
* `required_events = [cast_split_half:Finality, put_+1/+1_counters:2, continuous_-4/-4_all_creatures, state_based_actions]`.

The frozen fixture never scripts a graveyard cast. The harness implements exactly the four permitted
dispositions:

1. **Half selection** — fixture-scripted exact choice. The offered `cast_spell` option is matched on
   the engine's own half identity (`SpellAbility.getCardState().getName()`), not on option order,
   label position, action id or card name. Exactly one match is required; zero or two fails closed.
2. **Mana payment** — fixture-scripted exact choice. Only the six fixture payment sources exist on
   the battlefield; any offered tap source outside that set fails closed as un-scripted, and the
   selection is the first still-untapped scripted source the engine actually offers, so offered
   legality is proven before execution. A mana-frame payload kind the harness does not understand is
   a hard failure, never a guess.
3. **Counter target** — fixture-scripted exact choice. The optional `+1/+1` target frame is matched on
   the fixture's own creature (`Serra Angel`) and must expose exactly one such option. The test
   additionally proves the counters landed: the own 4/4 survives -4/-4 only if it received +2/++2, so
   a "decline" answer cannot satisfy the fixture.
4. **Ambiguity** — fails closed. There is no `first()`, no `sorted()[0]`, no card-name branch and no
   "any legal payment is equivalent" assumption in the new probe path.

## 7. PB-07 disposition

| | Before | After |
|---|---|---|
| `Find // Finality` row status | `RUNTIME_QUALIFIED_FRONT_PLUS_ENGINE_GAP` | `RUNTIME_QUALIFIED` |
| `summary.ENGINE_GAP_documented` | 1 | 0 |
| `summary.RUNTIME_QUALIFIED` | 28 | 29 |
| Denominator | 29 (Lab `ACTUAL_CARD_DOMAIN_v1.json#regression_corpus_29`) | 29, unchanged |
| `global_verdict` | PARTIAL, one documented engine gap | PARTIAL, no engine gap; row 28 re-derived |

The denominator was **not** changed. 28/29 → 29/29 reflects executed evidence at this head, not a
redefinition of the corpus.

## 8. Historical evidence invalidated

| Artifact | Claim invalidated | Disposition |
|---|---|---|
| `wsr24-evidence-closure/PB07_EVIDENCE.json` (PR #4/#5/#6 copies) | `Find // Finality = RUNTIME_QUALIFIED_FRONT_PLUS_ENGINE_GAP`, `ENGINE_GAP_documented: 1` | RETRACTED; replaced on this branch with the re-derived row and an explicit `supersedes` pointer |
| `ws234-s3/AFTERMATH_VERDICT.json` | `"verdict": "FULL (SUPPORTED) for Find // Finality"`, `"fix": "…K:Aftermath + Oracle Aftermath prefix"` | RETRACTED as a description of this card; the underlying Aftermath engine support it also described remains sound and is still covered |
| `ws234-s3/S3_ACTUAL_CARD_CENSUS.json`, `ws231-admission/S3_ACTUAL_CARD_CENSUS.json` | any `Find // Finality` verdict derived from the Aftermath path | re-derived on this branch; historical copies preserved |
| `ws234-s3/FINAL_HANDOFF.md`, `ws234-s3/VALIDATION.md`, `ws231-admission/FINAL_HANDOFF.md`, `ws231-admission/VALIDATION.md`, `ws231-admission/S3_SYSTEMIC_GAP_MAP.json`, `ws234-s3/S3_SYSTEMIC_GAP_MAP.json`, `ws234-s3/S3_DEDICATED_TEST_INVENTORY.json`, `ws236-s1/DRAIN_INVENTORY.json` | narrative describing the Aftermath "engine gap" on this card | preserved verbatim as history, marked superseded by `SUPERSEDED_FIND_FINALITY_SOURCE_TRUTH_20260929.md` in the same directories |
| Forge PR #6 `Card.getAllPossibleAbilities` split-half enumeration + `DeepseekAftermathDiscoveryTest` | the premise that the mutation was needed | not reused; PR left untouched for coordinator adjudication |
| Commander Lab `docs/pre_freeze_completion_20260927/PROVIDER_READINESS_PACKET_20260928.md` §6.4 and §7 blocker row | "28 runtime-qualified with one documented engine gap, `Find // Finality` (the Aftermath back-half legal ability discovery)" and "Aftermath `Find // Finality` … NON_BLOCKING_CAPABILITY_GAP" | **NOT MUTATED — ownership conflict.** That file is an active edit surface of Lab PR #289 (`sbmax/final-pre-freeze-20260928`), so the correction is handed off rather than pushed |

## 9. Rules-Core impact

`forge-game` and `forge-core` production Java are unchanged on this branch relative to PR #5, so no
inherited evidence depends on a changed Rules-Core identity. The two production-relevant changes are
(a) one card script and (b) test-only classes. Cards whose behavior depends on the engine's Aftermath
implementation are unaffected: the keyword path, the RightSplit graveyard zone gate and the exile
replacement were not touched, and the genuine-Aftermath control (`Commit // Memory`, plus the
card-database invariant over all 27 Aftermath scripts) passes at this head.

## 10. Ownership and non-interference

* Written only on the new branch `sol/finality-sourcetruth-pb07-20260929` in the isolated worktree
  `/home/moeen/code/forge-finality-sourcetruth-20260929`.
* Forge `master`, PR #4, #5, #6, #7 and every donor branch are unmodified.
* Commander Lab `main`, #284, #289, #294 and #299 are unmodified.
* No branch, PR or artifact was deleted or closed. `PRODUCTION_PROVIDER` remains NOT SELECTED and
  `ARCHITECTURE_FREEZE` remains NOT CLAIMED.
