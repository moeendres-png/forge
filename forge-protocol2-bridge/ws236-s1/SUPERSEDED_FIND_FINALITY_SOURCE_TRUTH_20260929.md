# SUPERSEDED IN PART — Find // Finality Aftermath premise

The artifacts in this directory are preserved verbatim as history. One claim in them is **retracted**:

> `Find // Finality` has an Aftermath half, is castable from the graveyard, and is exiled on resolution.

That claim came from Forge commit `bc347e62255e61d950154824b427251fdabcf5f6`, which added
`K:Aftermath` to the Finality half of `forge-gui/res/cardsfolder/f/find_finality.txt` and rewrote
that half's Oracle line to match. The real card prints no Aftermath keyword on either face, so under
CR 108.1 (Oracle text governs wording) and CR 702.127a (Aftermath is a keyword ability found on some
split cards) the card has no graveyard half at all.

Authoritative record, including the Oracle text, the rules citations, the provenance chain, the
pristine-upstream/fork/XMage comparison, the impact matrix and the re-derived PB-07 row 28:

`forge-protocol2-bridge/wsr24-evidence-closure/FIND_FINALITY_SOURCE_TRUTH_20260929.md`

on branch `sol/finality-sourcetruth-pb07-20260929`.

## What is NOT retracted

The engine's Aftermath support described in these artifacts is sound and is unchanged: the
`Spell.Aftermath` stack gate, the `CardFactoryUtil` RightSplit graveyard zone, the exile-on-leave
replacement, `SpellAbility.isAftermath()`, and the systemic Cleave identity added by the same commit
(`AbilityFactory` / `AlternativeCost.Cleave` / `SpellAbility.isCleave()`). The 27 genuine Aftermath
cards are unaffected. Only the claim about *this one card* is false.

## How to read these artifacts

Read them as history. Do not re-derive a PASS, a capability gap, or a PB-07 row from them. In
particular `RUNTIME_QUALIFIED_FRONT_PLUS_ENGINE_GAP`, `ENGINE_GAP_documented: 1` and any 28/29
aggregate that counted this card as an engine gap are retracted.
