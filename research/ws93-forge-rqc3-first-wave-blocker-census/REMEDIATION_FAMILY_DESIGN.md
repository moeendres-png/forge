# WS93 Systemic Remediation Family Design (no implementation, no prod changes)

Ordered by first-blocker frequency across the 15 slots. Each family is a design
direction only; no capability is claimed and no code was changed.

1. **F-TARGET (covers A03, C01, D06, G02, I01 + J02-trigger)**: externally
   represent target selection as principal-scoped legal-target enumeration +
   revision-bound submission, reusing the priority-frame binding pattern. Must
   prove target-legality comes from Rules Core, not request-derived legality.
2. **F-MANA (covers B01, F01, H01 + A04/C03-payment)**: externalize mana-source
   choice + mana payment as explicit decisions (no weighted auto-payment).
   Hardest: payment-route enumeration without strategic leakage; hidden mana
   legality must stay public-correct.
3. **F-X (covers A04, C03)**: X announcement as a bounded numeric decision with
   engine-owned range legality (0..available-mana semantics owned by Rules Core).
4. **F-COMBAT (covers E01, E02, G03, J02-attack)**: declare-attackers (with
   per-attacker defender + Propaganda-style tax hooks), declare-blockers, and
   702.19b-compliant damage division as separate revision-bound frames.
5. **F-MODAL (covers D06)**: mode-subset offer (all 31 nonempty subsets for
   Casualties) + per-mode typed targets; legality from Rules Core.
6. **F-STACK-ORDER (covers B01-trigger, A04-replacement)**: APNAP + controller
   ordering and replacement-effect choice as explicit order frames.
7. **F-COST-FORK (covers C01)**: normal-vs-alternate cost fork with hidden-zone
   pitch selection (principal-scoped; exile public after).
8. **F-ZONE-SEARCH (covers F01)**: library search with fail-to-find semantics +
   shuffle owned by engine.
9. **F-COMMANDER-MOVE (covers G02)**: graveyard-vs-command-zone movement choice
   as an explicit frame; recast tax read from native Commander identity.
10. **F-CONCEDE (covers G04)**: expose the existing engine-native concession
    (WS59/WS76) through a revision-bound protocol message; currently the only
    family whose engine side already exists.
11. **F-COPY (covers H01)**: copy-choice offer/absence gating (CLONE_FIRST +
    NO_HUMILITY offer+take; HUMILITY_FIRST must-not-occur).

Sequencing note: F-TARGET and F-MANA unblock the most slots and are
prerequisites for almost every other family. F-CONCEDE is the cheapest
(bridge-only exposure of an existing engine seam) and could qualify G04 alone.
