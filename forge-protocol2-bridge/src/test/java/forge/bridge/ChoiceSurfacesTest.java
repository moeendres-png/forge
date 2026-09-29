package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * Engine-supplied single choices that were not represented at all, so the
 * card halted the pilot: protection type (Mother of Runes), a chosen creature
 * type (Cavern of Souls) and voting (Council's Judgment).
 */
public class ChoiceSurfacesTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    private static DecisionFrame.Option pick(DecisionFrame frame,
            Predicate<DecisionFrame.Option> test, String what) {
        final StringBuilder seen = new StringBuilder();
        for (DecisionFrame.Option option : frame.options) {
            if (test.test(option)) {
                return option;
            }
            seen.append('[').append(option.actionType).append('|').append(option.label).append(']');
        }
        throw new AssertionError(what + " not offered; kind=" + frame.kind + " status="
                + frame.status + " reason=" + frame.reason + " options=" + seen);
    }

    private static void submit(BridgeSession session, DecisionFrame frame,
            DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
    }

    private static boolean has(DecisionFrame frame, String actionType) {
        return frame.options.stream().anyMatch(o -> actionType.equals(o.actionType));
    }

    private static BridgeTestSupport.ConstructedGame game(String id) {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(id, 2);
        for (int seat = 0; seat < 2; seat++) {
            for (int i = 0; i < 7; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        return constructed;
    }

    private static Card find(BridgeSession session, int seat, String name, ZoneType zone) {
        for (Card card : session.getGame().getPlayers().get(seat).getCardsIn(zone)) {
            if (card.getName().equals(name)) {
                return card;
            }
        }
        return null;
    }

    @Test(timeOut = 300000)
    public void motherOfRunesGrantsTheChosenProtection() {
        final BridgeTestSupport.ConstructedGame constructed = game("protection-mother");
        final BridgeSession session = constructed.session;
        final Card mother = BridgeTestSupport.addCard(constructed.game, 0, "Mother of Runes",
                ZoneType.Battlefield);
        final Card bears = BridgeTestSupport.addCard(constructed.game, 0, "Grizzly Bears",
                ZoneType.Battlefield);
        BridgeTestSupport.launchConstructed(constructed);
        mother.setSickness(false);
        final DecisionFrame main = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        submit(session, main, pick(main, o -> "activate_ability".equals(o.actionType)
                && "Mother of Runes".equals(o.sourceCardName), "Mother of Runes"));
        boolean chosen = false;
        for (int i = 0; i < 20 && (!chosen || !session.getGame().getStack().isEmpty()); i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            Assert.assertEquals(f.status, DecisionFrame.Status.SUPPORTED,
                    "blocked: " + f.kind + " " + f.reason);
            if (has(f, "protection_type")) {
                Assert.assertEquals(f.options.size(), 5, "one option per color");
                chosen = true;
                submit(session, f, pick(f, o -> o.label.equals("Protection from red"), "red"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, pick(f, o -> o.label != null
                        && o.label.contains("Grizzly Bears"), "target the Bears"));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pick(f, o -> o.isPass, "pass"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.reason);
            }
        }
        Assert.assertTrue(chosen, "the protection was the pilot's choice");
        Assert.assertTrue(bears.hasKeyword("Protection from red"), "Bears protected from red");
        Assert.assertFalse(bears.hasKeyword("Protection from blue"), "and only from red");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void cavernOfSoulsRemembersTheChosenType() {
        final BridgeTestSupport.ConstructedGame constructed = game("type-cavern");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Cavern of Souls", ZoneType.Hand);
        BridgeTestSupport.launchConstructed(constructed);
        final DecisionFrame main = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        submit(session, main, pick(main, o -> "play_land".equals(o.actionType)
                && "Cavern of Souls".equals(o.sourceCardName), "play Cavern"));
        final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
        Assert.assertNotNull(f, "no frame");
        Assert.assertEquals(f.status, DecisionFrame.Status.SUPPORTED,
                "blocked: " + f.kind + " " + f.reason);
        Assert.assertTrue(has(f, "choose_type"), "type choice framed: " + f.kind);
        Assert.assertTrue(f.options.size() > 100, "every creature type is offered");
        submit(session, f, pick(f, o -> o.label.endsWith("[Elf]"), "Elf"));
        BridgeTestSupport.awaitFrame(session, 15000);
        final Card cavern = find(session, 0, "Cavern of Souls", ZoneType.Battlefield);
        Assert.assertNotNull(cavern, "Cavern entered");
        Assert.assertEquals(cavern.getChosenType(), "Elf");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void councilsJudgmentExilesTheVotedPermanent() {
        final BridgeTestSupport.ConstructedGame constructed = game("vote-judgment");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Council's Judgment", ZoneType.Hand);
        for (int i = 0; i < 3; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 1, "Craw Wurm", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Grizzly Bears", ZoneType.Battlefield);
        BridgeTestSupport.launchConstructed(constructed);
        DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (int i = 0; i < 3; i++) {
            submit(session, frame, pick(frame, o -> "activate_ability".equals(o.actionType)
                    && "Plains".equals(o.sourceCardName), "Plains tap"));
            frame = BridgeTestSupport.awaitFrame(session, 15000);
        }
        submit(session, frame, pick(frame, o -> "cast_spell".equals(o.actionType)
                && "Council's Judgment".equals(o.sourceCardName), "cast Council's Judgment"));
        int votes = 0;
        for (int i = 0; i < 30 && (votes < 2 || !session.getGame().getStack().isEmpty()); i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            Assert.assertEquals(f.status, DecisionFrame.Status.SUPPORTED,
                    "blocked: " + f.kind + " " + f.reason);
            if (has(f, "vote")) {
                votes++;
                Assert.assertEquals(f.options.size(), 2, "both of p2's nonland permanents");
                submit(session, f, pick(f, o -> o.label.contains("Craw Wurm"), "vote Wurm"));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pick(f, o -> o.isPass, "pass"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.reason);
            }
        }
        Assert.assertEquals(votes, 2, "each player voted");
        Assert.assertNotNull(find(session, 1, "Craw Wurm", ZoneType.Exile), "Wurm exiled");
        Assert.assertNotNull(find(session, 1, "Grizzly Bears", ZoneType.Battlefield), "Bears stay");
        session.shutdown(5000);
    }
}
