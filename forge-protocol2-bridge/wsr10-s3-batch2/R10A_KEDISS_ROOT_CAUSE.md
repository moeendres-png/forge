# R10a Root Cause — Kediss multiplayer trigger (CARD_04)

Verdict: `TEST_GAP` (no defect anywhere). 3/4 green first run; the fourth
needed choreography repair (mine).

- Engine: DamageDone commander-combat trigger, DamageAll to each other
  opponent, controller scoping (own commanders only), zone scoping
  (battlefield only) all native (DIRECTLY_VERIFIED, 4 tests).
- Harness: bridge 4P constructed game + combat frames + Fervor haste for
  same-turn attacks sufficed. Repair (mine): never drain through the
  attacker's combat (declines it) — tight post-cast flow attacks at the
  FIRST declare-attackers frame.
- Commander identity via addCommander + command-zone cast proven
  (reused pattern).

## Disposition

CARD_04 redirect branch: SUPPORTED. Commander-damage multiplayer
accounting exact (p2/p3/p4 each -1 as applicable).
