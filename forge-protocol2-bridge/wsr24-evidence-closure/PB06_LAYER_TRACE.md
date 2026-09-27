# PB-06 Layer Trace — Forge side (WSR24 evidence closure)

Date: 2026-09-27. Branch `wsr24/forge-candidate-evidence-closure-20260927`.
Engine candidate `ef958ee91ac6c9ce0152189f2654bf6e05abf273` /
tree `fc3387bf37aab19d780b2939a235309ed32b0492`.
Bridge/harness lineage `18bba95a4528f6ab5910633f1f87f603b8c4ddf8`.

Fail-before baseline (pre-existing, unmodified):
- `forge-protocol2-bridge/wsr20-full107/HIDDEN_INFO_RESULTS.json`:
  HIDDEN_05 UNKNOWN, HIDDEN_06 UNKNOWN, HIDDEN_08 NOT_RUN_BLOCKED,
  HIDDEN_11 UNKNOWN (unasserted), HIDDEN_12 NOT_RUN_BLOCKED.
- WSR22 `HIDDEN_INFO_FORGE.json` freshly classifies all five seams as
  "not reachable on the generic Protocol-2 surface"; PB-06 UNKNOWN_IMPACT
  with rows HIDDEN_05/06/11/08/12 + WS05-CMD-MULL-2.
- Principal-scoped ordinary zone observation (own hand visible, opponent
  hands `<hidden>`, library hidden, public redacted) is exercised live and
  green (BridgeEngineTest#testHiddenInformationAdversary,
  WsR15HiddenInfoFamilyTest, WSR22 principal_observations). That is the
  ceiling of the old evidence — NOT PB-06 closure.

Production-reachable decision/observation surface (the only proof surface):
- `BridgeEngine.get_game_state` → `StateProjection.gameState(session, observer)`
  + `StateProjection.bridgeMeta(session, observer)`.
- `BridgeEngine.get_legal_actions` → actor-only; non-actor gets WRONG_ACTOR
  (actor id only, no card data).
- `StateProjection.legalActions` / `decisionSummary` are actor-scoped by the
  caller; `bridgeMeta` exposes revision/pending_decision/state_hash ONLY to
  the frame actor (others get -1/null).
- Audit log (`cards_revealed` etc.) records counts/zones only, never names;
  it is not exposed with private content over RPC.

Engine truth consulted (read-only, no invention):
- `CardView.canBeShownTo` (zone gate) + `canFaceDownBeShownTo` (face gate);
  exile face-down visible only with mayLook grant; hand visible only to
  controller; library hidden to all; mindSlaveMaster branch lets a
  controlling player see through the controlled player's eyes.
- `Card.addMayLookFaceDownExile` / `addMayLookAt` / `addMayLookTemp` (+
  `updateMayLook` → `PlayerMayLook` view flag) are the engine-owned grants.
  `ChangeZoneEffect` (ExileFaceDown path) does exactly
  `turnFaceDown(true)` + `addMayLookFaceDownExile(activator)`.
- `ExternalPlayerController.chooseCardsToDiscardFrom` labels show names only
  for `visibleToChooser` (engine-supplied set), else `<hidden>`; all such
  frames are actor-scoped end to end (HIDDEN_ZONE_SELECTION).
- `arrangeForScry` frames every bottom-subset as GENERIC_SELECTION with
  engine-derived names, actor-scoped.
- Mulligan: `mulliganKeepHand` parks a binary MULLIGAN frame (Keep/Ship,
  no card content in labels); `tuckCardsViaMulligan` throws
  BridgeUnsupportedDecision (London tuck fails closed, no private selection
  transported).

## Per-row trace

### HIDDEN_05 — face-down exile actor-specific permission persists
- Owner principal: the granted looker (exiling activator); outsiders +
  public must not see.
- Public: card is in exile, face-down status public; identity private.
- Private: true card identity, granted to exactly the engine's mayLook set.
- Historical seam: permission grant existed in engine but no per-scenario
  projection proof on any production surface.
- Origin: engine state (mayLookFaceDownExile) → view flag → bridge
  `shownName` (both gates). Bridge already reads engine truth literally.
- Generic Protocol-2 lack: no scenario drives a face-down exile + grant,
  so nothing to observe. Underlying behavior correct, unproven.
- Classification: HARNESS_GAP. Expect REACHABLE_AND_CORRECT once a
  deterministic scenario (face-down exile + grant, multi-principal reads,
  persistence across game progress) runs green. No engine defect alleged.

### HIDDEN_06 — face-down exile knowledge invalidates correctly
- Owner principal: former looker after the card leaves exile; all
  principals after a move to a public zone.
- Public/private: after Exile→Graveyard (face-up), identity is public to
  all; the exile channel must go stale-empty (no ghost), and the old grant
  must not manufacture visibility anywhere the gates forbid.
- Historical seam: invalidation path never exercised per-scenario.
- Origin: engine zone contents → same `shownName` gates. Bridge exposes
  truth; harness never moved a face-down exiled card across zones under
  observation.
- Classification: HARNESS_GAP. Expect REACHABLE_AND_CORRECT.

### HIDDEN_08 — look reaches only specified audience
- Owner principal: the looking actor (decision frame actor); audience is
  exactly actor + engine-declared visible set; outsiders + public excluded.
- Public: fact that a look decision is pending (actor id) — already public.
- Private: looked-at identities (option labels / visibleToChooser names).
- Historical seam: no look-audience scenario on the generic surface.
- Origin: engine callbacks (`chooseCardsToDiscardFrom` visibleToChooser,
  scry top-N) → actor-scoped DecisionFrame → actor-scoped legal_actions.
  Bridge transports engine truth (labels already redact non-entitled names
  to `<hidden>`); non-actors receive empty actions + WRONG_ACTOR.
- Real-card vehicle: Thoughtseize-family discard-from-hand
  (HIDDEN_ZONE_SELECTION) distinct from the HIDDEN_07 Thoughtseize
  instance (different card, full 4-seat + public + RPC matrix), i.e. the
  shared look-decision channel, not a card hack.
- Classification: HARNESS_GAP. Expect REACHABLE_AND_CORRECT.

### HIDDEN_11 — shuffle invalidates order knowledge
- Owner principal: every seat + public (order is hidden to all).
- Public: library_size (count) only.
- Private: library order and identities (never exposed positionally).
- Historical seam: invalidation unasserted.
- Origin: bridge never projects library contents (empty array always);
  engine `Zone.shuffle()` reorders engine-side only. No order channel
  exists to invalidate — the guarantee is absence of exposure before,
  across, and after a real shuffle.
- Classification: HARNESS_GAP. Expect REACHABLE_AND_CORRECT via a
  look-then-shuffle scenario (scry learns top, Evolving Wilds search +
  shuffle, all-principal reads show no order at every step).

### HIDDEN_12 — controlled-player authority receives legally visible information
- Owner principal: the controlling player (mindSlaveMaster) + the
  controlled actor's decision frames; outsiders + public excluded.
- Public: control fact itself is engine event data, not card content.
- Private: controlled player's hand contents.
- Historical seam: no controlled-player scenario anywhere.
- Origin: engine control registry (`Player.addController` →
  `PlayerView.updateMindSlaveMaster`) → `CardView` mindSlaveMaster
  branches. BUT bridge `handZone` redacts by pure identity
  (`observer.equals(player)`) and never consults the engine's control
  truth — a candidate BRIDGE_GAP (over-redaction: controller entitled by
  engine gates receives `<hidden>`; NOT a leak).
- Classification: BRIDGE_GAP (to be confirmed by fail-before test:
  controller observation of controlled hand currently redacted). After a
  narrow engine-truth fix (honor controlling-player in handZone only),
  expect REACHABLE_AND_CORRECT, with revocation proven after control ends.
  If the fail-before test unexpectedly passes, reclassify HARNESS_GAP.

### WS05-CMD-MULL-2 — two-player Commander mulligan
- Owner principals: p1 and p2 in turn as mulligan actors; each hand
  private to its owner throughout the authoritative sequence
  (STARTING_PLAYER → MULLIGAN p1 → MULLIGAN p2 → PRIORITY).
- Public: decision actor ids + revisions (public coordination), life/
  zones counts.
- Private: opening-hand contents, keep/ship intent before submission.
- Historical seam: mulligan exercised only at 4P keep-all in WSR22; 2P
  principal-scoped mulligan privacy never proven per-scenario; London tuck
  path exists as fail-closed (throws) but unbound to this row.
- Origin: engine mulligan callbacks → binary MULLIGAN frames (content-free
  labels) → actor-scoped observations. Bridge transports; harness never
  ran the 2P sequence with per-frame principal reads.
- Classification: HARNESS_GAP. Expect REACHABLE_AND_CORRECT via a real
  2P Protocol-2 game driving the full authoritative sequence with
  per-frame actor/non-actor/public reads + RPC binding + tuck fail-closed
  binding.

## What would change a classification
- Any test showing a non-actor/public observation containing a private
  identity, option id, or decision label → REACHABLE_AND_LEAKS (engine or
  bridge defect by location of the read) and the row stays open until a
  systemic fix + regression.
- Any scenario the engine cannot reach without inventing Rules
  (card-name hacks, second Rules engine in bridge, AI/default decisions) →
  CURRENTLY_UNREACHABLE with the exact missing seam named; never
  re-labeled PASS.
- UNKNOWN is not PASS and is carried as BLOCKED in the evidence register.
