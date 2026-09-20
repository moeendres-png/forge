# R9b Root Cause — Retarget (Bolt Bend/CARD_22, Flare/CARD_13)

Verdict: `AI_DEFECT` (sim-seam controller capability) + `TEST_GAP`;
engine Rules Core + bridge production: `NO_DEFECT` (one test-design
correction owned by the workstream, see below).

## Fail-before chain (DIRECTLY_VERIFIED)

1. Sim-seam: opponent Bolt would not cast (harness: no mana — repaired).
2. Sim-seam: Bend/Flare would not cast in response. Root cause:
   `ChangeTargetsAi.checkApiLogic` returns CantPlayAi for non-magnet
   ChangeTargets ("The AI can't otherwise play this ability"), and
   `PlayerControllerAi.chooseNewTargetsFor` returns null ("AI currently
   can't do this"). The engine resolution path (ChangeTargetsEffect +
   canTargetSpellAbility stack-proxy authority) is sound; only the sim AI
   choice seams are missing. Preserved as AI_DEFECT; the Protocol-2 bridge
   (external option picking) is the authoritative seam.
3. Bridge probe: full chain reachable (cast_spell offers, TARGET_SELECTION
   for cast (multi) with single-candidate engine auto-bind, MANA_PAYMENT,
   `retarget` TARGET_SELECTION at resolution, lifecycle intact).
4. Test-design corrections (mine, rules-correct): priority-round
   choreography (a responder acts before a full pass round resolves the
   stack; caster-retains-priority enables back-to-back casts); targeted
   mana answers (Forest for G); alt-cost route picking (sac vs mana
   variants enumerated distinctly); COST_SELECTION sac answers.
5. Rules correction (mine): Bend with power>=4 costs {R}, not 0
   (CR 117.7a generic-only reduction of {3}{R}). The "free" premise was
   wrong; engine + bridge correct (R paid, overpay tolerated, decline
   aborts). Flare fail-closed redesigned to decline-path (engine
   over-offers unpayable casts; fail-closed at payment, TD02 class).

## Disposition

CARD_22 ChangeTargets: SUPPORTED (framed choice, auto-bind, resolution
retarget, damage accounting). CARD_22 ReduceCost: SUPPORTED (R paid;
full-3R impossible with the 2-source pool). CARD_13 sac-alt-cost:
SUPPORTED (COST_SELECTION + copy 6 damage). Fail-closed paths:
SUPPORTED. Sim-seam retarget stays NOT_RUN (AI_DEFECT preserved, bridge
is the seam).
