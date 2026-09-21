# WS236 Discriminator Plan

## Question

For Veyran / Harmonic You-filtered SpellCast-family: does the observed
failure live in ENGINE, HARNESS, BRIDGE, SCENARIO_SETUP, OBSERVATION, or
remain UNKNOWN?

## Design: A/B with target-free spell + family controls

Spell: Divination (2U sorcery, no targets) on both seams — removes the
Lightning Bolt target-selection confound from the WS234 sim probes while
keeping identical trigger/filter semantics (`Instant,Sorcery` ⊇ Sorcery;
Prowess `nonCreature` ⊇ Sorcery).

- Seam A (sim): `Ws236F4SpellcastDiscriminatorTest`
  (`forge-gui-desktop/.../gamesimulationtests/ws236/`): `bf()` placement,
  `castFromHand` engine cast, lifecycle prints, drain, power/toughness
  assertions.
- Seam B (bridge): `WS236F4BridgeTest`
  (`forge-protocol2-bridge/.../forge/bridge/`): constructed game, 3 Islands,
  Divination from hand through the bridge decision boundary, real priority
  flow, power/toughness assertions.

## Lifecycle observation boundaries (in order)

1. Active trigger collection: `getActiveTrigger(mode, runParams)` with real
   runParams (pre-cast prediction and post-cast with the actual stack SA).
2. Trigger registration: trigger/static counts on host, host zone,
   controller identity.
3. Dispatch evidence: stack size/top/activator post-cast;
   `hasSimultaneousStackEntries()` (queued vs never-dispatched);
   `getActivationsThisTurn()` (dispatcher reached, incl. doubling count).
4. Suppression flags: `isTriggerSuppressed(SpellCast/SpellCastOrCopy)`.
5. Ordering: `TRIGGER_ORDER` frame options on seam B.
6. Resolution outcome: power/toughness deltas, spell resolution
   (Divination in graveyard, cards drawn).

## Controls

- Clever Lumimancer: identical SpellCastOrCopy/You magecraft shape with
  explicit `Defined$ Self` — isolates card-script cause from family cause.
- Harmonic Prowess: engine-generated SpellCast/You keyword path.
- Veyran-doubles-other (Lumimancer +4/+4) and Harmonic-doubles-other
  (Lumimancer +4/+4): prove each card's Panharmonicon static over another
  permanent's trigger, closing doubling beyond self-triggers.
- Kaervek strict-under-legacy-drain (diagnostic, since removed from the
  shipped file; observations sealed in DISCRIMINATOR_OBSERVATIONS.json):
  tests whether Opponent-filtered SpellCast genuinely resolves in seam A
  without the ordering correction.

## Rules

- No manual trigger injection, no manual outcome injection, no card-name
  hacks, no second Rules engine. The Forge Rules Core generates every
  trigger and outcome; harnesses only create the legal starting scenario,
  drive priority/passes, and answer engine-parked ordering frames from
  engine-offered options.
- First failing boundary localizes causality; terminal UNKNOWN stays
  UNKNOWN if evidence requires it.

Status: **DISCRIMINATOR_EXECUTED = PASS** (see DISCRIMINATOR_OBSERVATIONS.json
and ACTUAL_CARD_RESULTS.json).
