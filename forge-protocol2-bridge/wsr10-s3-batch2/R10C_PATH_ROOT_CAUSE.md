# R10c Root Cause — Path scry (CARD_27)

Verdict: `TEST_GAP` (sim seam closes the branch) + `BRIDGE_DEFECT`
(filed with reproduction, not repaired in R10).

## Sim closure (DIRECTLY_VERIFIED, 4/4)

- Scry trigger fires on shared-type Path spend (queued simultaneous +
  ordered, resolved via engine AI discretion) and stays silent for
  non-sharing types and non-Path mana; enters-tapped retained.
- Type sharing verified directly (sharesCreatureTypeWith true, incl.
  command-zone commanders); commander-zone evaluation sound.

## Bridge defects (reproduced, out of R10 repair scope)

1. **Path tap production silent-drop**: tapping Path of Ancestry through
   a bridge tap_mana_source submit consumes the tap, parks no
   COLOR_CHOICE (not even mono-R direct), adds zero pool mana over 5s of
   polling, raises no error; game continues. Repro:
   WsR10PathMonoProbeTest shape (removed after sealing this note):
   Kediss commander + Path + Monitor, tap Path, pool stays 0.
2. **Scry arrangement unrepresented**: `arrangeForScry` throws
   unsupported (explicit). Even with working production, the choice
   could not be framed without a new DecisionFrame surface (production
   feature scope + full bridge requal — a bridge workstream, not S3).

## Disposition

CARD_27 mana+scry: SUPPORTED via sim (mechanism proven end-to-end).
Bridge seam for this branch: UNSUPPORTED (defects above, never PASS by
assumption).
