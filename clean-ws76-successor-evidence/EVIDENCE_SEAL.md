# Evidence Seal — Clean WS76 Successor + H01 Rules-Oracle Remediation

## Source Identity

- Repository: moeendres-png/forge
- Worktree: /tmp/ws62-forge-src-a9a95db (detached HEAD workflow on shared clean-base checkout)
- Branch: ws77/forge-clean-ws76-successor-20260912
- Audit base (Source Lock): a9a95db6662c2d28814390a9c0c2f986e39aa8b4
- Successor commit: aa5c00aa32dfd40e213f223f8fd400c43daabb24
- Parent of successor: a9a95db6662c2d28814390a9c0c2f986e39aa8b4 (direct, G1)
- Reference only: 2a49cef3c4078577d3b47cc0d3139c9f93a2bc7f (production diff ported; tests ported byte-identical)
- NOT in lineage: 22e7f17befee8fcce0684fa6d006f29afdb5c280 (WS67, excluded by authority decision)

## Production Changes (aa5c00aa vs a9a95db)

1. forge-game/src/main/java/forge/game/GameAction.java
   - Removed `import forge.util.collect.FCollectionView;`
   - `checkGameOverCondition`: `FCollectionView<Player> allPlayers = game.getPlayers()`
     -> `List<Player> allPlayers = Lists.newArrayList(game.getPlayers())` with CR 104.3a comment.
   - Byte-identical semantics to the 2a49cef hunk.
2. forge-game/src/main/java/forge/game/phase/PhaseHandler.java
   - CLEANUP `autoPassCancel` sweep iterates `Lists.newArrayList(game.getPlayers())` with CR 104.3a comment.
   - Byte-identical semantics to the 2a49cef hunk.
3. ReplacementHandler.java: UNCHANGED vs a9a95db (G2). Verified: no `enteringOwnReplacements`
   / `getEntryCandidateReplacements` symbols; `git diff a9a95db HEAD --stat` lists only the
   two files above plus two new test files.

## Tests Added

- forge-gui-desktop/src/test/java/forge/gamesimulationtests/h01/H01CloneHumilityTest.java (new, 274 lines)
  - A HUMILITY_FIRST: copyChoiceCalls == 0, confirmReplacementCalls == 0, Bear count stays 1,
    no cloned flag, Clone enters as itself 1/1 under Humility, then Clone in graveyard 0/0 after
    Humility destroyed (SBA 704.5f). Terminal 1/1 NOT used as oracle.
  - B CLONE_FIRST: choice occurs, 2/2 copy; Humility -> 1/1 copy persists; Humility leaves -> 2/2 Bear copy.
  - C NO_HUMILITY: choice occurs, 2/2 Bear copy.
  - Engine-direct actual cards, native Island mana payment; only copy selection scripted.
- forge-gui-desktop/src/test/java/forge/gamesimulationtests/ws76/Ws76ImmediateConcessionTest.java (new, 263 lines)
  - Byte-identical port of the 2a49cef reference test (5 tests).

## Fresh Runtime Results (clean-base worktree, offline Maven, JDK 21)

All runs: `mvn -o -pl forge-gui-desktop -am test -Dtest=<selection> -Dsurefire.failIfNoSpecifiedTests=false`

1. H01 family: Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 — PASS (G3/G4/G5)
2. WS76 battery: 5/5 PASS (G6/G7/G8/G9; 4P run asserts native 800.4 owned-leaves + control-returns-to-owner;
   coherence run asserts turn advance + departed never consulted)
3. Combined H01+WS76: Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 — PASS
4. Regression battery (ws59 A04/C01/G04 + ReplacementHandlerTest + H01 + WS76):
   Tests run: 18, Failures: 0, Errors: 0, Skipped: 0 — PASS (G10)
5. Checkstyle (`mvn -o -pl forge-gui-desktop -am validate` with cache-file override):
   0 violations in all in-scope modules (parent/core/game/ai/gui/gui-desktop) — PASS (G10 gate part).
   Note: repo-wide `mvn -o validate` cannot complete in this container because /tmp (tmpfs, 100% used
   by unrelated workstream checkouts) rejects the checkstyle cache write on the unrelated forge-lda
   module ("Unable to persist cache file. No space left on device"). Environment-infra block, not a
   violation; no violation was reported for any module that ran.

No historical output imported: every PASS above is fresh runtime on the clean candidate.

## Gate Verdicts

- G1 clean ancestry from a9a95db: PASS (`HEAD~1 == a9a95db`)
- G2 WS67 ReplacementHandler delta absent: PASS
- G3 H01 Humility-first no-copy: PASS (fresh)
- G4 H01 Clone-first contrast: PASS (fresh)
- G5 H01 no-Humility control: PASS (fresh)
- G6 immediate concession 2P: PASS (fresh)
- G7 immediate concession 4P: PASS (fresh)
- G8 immediate concession 5P: PASS (fresh)
- G9 native leave-game coherence: PASS (fresh)
- G10 relevant regression suite: PASS (18/18 + checkstyle 0 violations in scope)
- G11 no card-name production hacks: PASS (production diff has no card names)
- G12 no provider deferral/semantic fallback: PASS (no provider files touched; engine-direct tests only)

UNKNOWN: none. NOT_RUN: Full107 (per contract), Architecture Freeze (not claimed).

## Forbidden-Shortcut Attestation

No WS67 port, no card-name special cases, no provider deferral, no fabricated copy choices
(HUMILITY_FIRST asserts zero callbacks), no weakened assertions, no fallback selection,
no Full107/Architecture-Freeze claims, no repin, no direct push.

## Handoff Fields

- CLEAN_WS76_SUCCESSOR=aa5c00aa32dfd40e213f223f8fd400c43daabb24
- H01_RULES_ORACLE=PASS
- WS67_REPLACEMENT_PATCH_PRESENT=NO
- IMMEDIATE_CONCESSION=PASS
- SUCCESSOR_ACCEPTED=NO (Coordinator adjudication pending)
- BEHAVIOR_CREDIT=0/107
- FULL107=NOT_RUN
- ARCHITECTURE_FREEZE=NOT_CLAIMED
- PRODUCTION_PROVIDER=NOT_SELECTED
