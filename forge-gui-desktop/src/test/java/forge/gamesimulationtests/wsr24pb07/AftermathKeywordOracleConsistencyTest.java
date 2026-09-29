package forge.gamesimulationtests.wsr24pb07;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

import forge.ai.simulation.SimulationTest;
import forge.card.CardRules;
import forge.card.CardSplitType;
import forge.card.CardStateName;
import forge.card.ICardFace;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.item.PaperCard;
import forge.model.FModel;

/**
 * PB-07 source truth: the engine's Aftermath keyword state must be derivable
 * from each card half's own Oracle text, and no card may gain a keyword the
 * printed card does not carry.
 *
 * <p>Defect class this guards: a card-script edit that adds {@code K:Aftermath}
 * (or any keyword line) to a half rewrites engine behavior, so a script that
 * disagrees with the card's Oracle text silently manufactures a rules mechanic
 * that no printed card has. The PB-07 / CARD_28 investigation found exactly that
 * on Find // Finality: Forge commit {@code bc347e62255e61d950154824b427251fdabcf5f6}
 * added {@code K:Aftermath} to the Finality half and rewrote its Oracle line to
 * match, so a script-internal check alone could not have caught it — Oracle
 * authority is external. What this test proves is the invariant that remains
 * mechanically checkable inside the Rules Core: the keyword a script grants must
 * be visible in that half's Oracle text, and the engine's runtime Aftermath flag
 * must agree with the script.
 *
 * <p>No card name selects an expected answer here. Every assertion is a property
 * of the whole card database, so adding a new Aftermath card needs no test edit
 * and reintroducing a false one fails without a card-name special case.
 */
public class AftermathKeywordOracleConsistencyTest extends SimulationTest {

    /** The exact Aftermath reminder wording printed on the card (CR 702.127a). */
    private static final String AFTERMATH_REMINDER =
            "Aftermath (Cast this spell only from your graveyard. Then exile it.)";

    private static boolean scriptGrantsAftermath(CardRules rules, ICardFace face) {
        return rules != null && face != null && rules.hasStartOfKeyword("Aftermath", face);
    }

    private static String halfName(ICardFace face, String fallback) {
        return face != null && face.getName() != null ? face.getName() : fallback;
    }

    /**
     * Every half whose script declares the Aftermath keyword must also print the
     * Aftermath reminder in its Oracle text, and vice versa. This is the
     * one-sided-divergence detector: it catches a script that grants or drops
     * Aftermath without also correcting (or preserving) the printed wording.
     */
    @Test(timeOut = 600000)
    public void everyAftermathScriptHalfPrintsTheAftermathReminder() {
        final List<String> violations = new ArrayList<>();
        final List<String> aftermathCards = new ArrayList<>();
        String previous = "";
        for (PaperCard pc : FModel.getMagicDb().getCommonCards().getAllCards()) {
            // One version per card name; alternate printings share the script.
            if (pc.getName().equals(previous)) {
                continue;
            }
            previous = pc.getName();
            final CardRules rules = pc.getRules();
            if (rules == null || rules.getSplitType() != CardSplitType.Split
                    || rules.getOtherPart() == null) {
                continue;
            }
            final ICardFace other = rules.getOtherPart();
            final boolean scripted = scriptGrantsAftermath(rules, other);
            final String oracle = other.getOracleText() == null ? "" : other.getOracleText();
            final boolean printed = oracle.startsWith(AFTERMATH_REMINDER);
            if (scripted != printed) {
                violations.add(pc.getName() + " // " + halfName(other, "?")
                        + " scriptKeyword=" + scripted + " oracleReminder=" + printed);
            }
            if (scripted) {
                aftermathCards.add(pc.getName());
            }
        }
        assertTrue(violations.isEmpty(),
                "every Aftermath script half must print the Aftermath reminder in its "
                        + "Oracle text and vice versa; divergences: " + violations);
        assertFalse(aftermathCards.isEmpty(),
                "sanity: the card database must contain genuine Aftermath cards, otherwise "
                        + "this control is vacuous");
    }

    /**
     * Runtime cross-check: for every split card, the engine's per-half Aftermath
     * flag must equal the script keyword of that same half. This is what the
     * Find // Finality defect violated at the Rules-Core level: the engine built
     * a graveyard-only SpellAbility out of a half whose printed text grants no
     * such ability.
     */
    @Test(timeOut = 600000)
    public void engineAftermathFlagMatchesTheScriptedKeyword() {
        final Game game = initAndCreateGame();
        final Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        final List<String> violations = new ArrayList<>();
        final List<String> runtimeAftermath = new ArrayList<>();
        String previous = "";
        for (PaperCard pc : FModel.getMagicDb().getCommonCards().getAllCards()) {
            if (pc.getName().equals(previous)) {
                continue;
            }
            previous = pc.getName();
            final CardRules rules = pc.getRules();
            if (rules == null || rules.getSplitType() != CardSplitType.Split
                    || rules.getOtherPart() == null) {
                continue;
            }
            final boolean scripted = scriptGrantsAftermath(rules, rules.getOtherPart());
            final Card card = createCard(pc.getName(), p1);
            if (card == null || !card.hasState(CardStateName.RightSplit)) {
                continue;
            }
            final SpellAbility sa = card.getState(CardStateName.RightSplit).getFirstSpellAbility();
            if (sa == null) {
                continue;
            }
            sa.setActivatingPlayer(p1);
            if (sa.isAftermath() != scripted) {
                violations.add(pc.getName() + " scriptKeyword=" + scripted
                        + " engineIsAftermath=" + sa.isAftermath());
            }
            if (sa.isAftermath()) {
                runtimeAftermath.add(pc.getName());
            }
        }
        assertTrue(violations.isEmpty(),
                "the engine's Aftermath flag must equal the half's scripted keyword for "
                        + "every split card; divergences: " + violations);
        assertFalse(runtimeAftermath.isEmpty(),
                "sanity: genuine Aftermath halves must still be flagged by the engine");
    }
}
