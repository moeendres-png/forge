package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.function.Predicate;

/**
 * Scry and surveil (CR 701.22a / 701.25a): the rest go back on top "in any
 * order". Scry kept the library's order (the pilot could not reorder), and
 * surveil was not represented at all (any surveil card halted the pilot).
 * The kept cards are now ordered by the pilot one position at a time.
 */
public class LibraryArrangementTest {

    private static final String[] LIBRARY = {"Grizzly Bears", "Craw Wurm", "Runeclaw Bear",
        "Raging Goblin", "Elvish Mystic", "Llanowar Elves", "Fervent Paincaster",
        "Longtusk Cub", "Palace Guard", "Boggart Brute"};

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

    /**
     * Casts {@code spell} and answers its two-card look. With {@code swap},
     * nothing goes away and the two cards go back in reversed order; without,
     * the top card goes away and the second is kept. Returns the two
     * looked-at names, topmost first, as they were before the spell.
     */
    private static List<String> castAndArrange(BridgeSession session, String spell,
            String land, int lands, String arrangeAction, boolean swap) {
        DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        final List<String> top = topNames(session, 2);
        final List<String> away = swap ? List.of() : List.of(top.get(0));
        final List<String> topOrder = swap ? List.of(top.get(1), top.get(0))
                : List.of(top.get(1));
        for (int i = 0; i < lands; i++) {
            submit(session, frame, pick(frame, o -> "activate_ability".equals(o.actionType)
                    && land.equals(o.sourceCardName), land + " tap"));
            frame = BridgeTestSupport.awaitFrame(session, 15000);
        }
        submit(session, frame, pick(frame, o -> "cast_spell".equals(o.actionType)
                && spell.equals(o.sourceCardName), "cast " + spell));
        boolean arranged = false;
        int ordered = 0;
        for (int i = 0; i < 30 && (!arranged || !session.getGame().getStack().isEmpty()); i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            Assert.assertEquals(f.status, DecisionFrame.Status.SUPPORTED,
                    "blocked: " + f.kind + " " + f.reason);
            if (has(f, arrangeAction)) {
                arranged = true;
                submit(session, f, pick(f, o -> awayMatches(o.label, away), "away " + away));
            } else if (has(f, "library_top_order")) {
                final String next = topOrder.get(ordered++);
                submit(session, f, pick(f, o -> o.label.endsWith(": " + next), "top " + next));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pick(f, o -> o.isPass, "pass"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.reason);
            }
        }
        Assert.assertTrue(arranged, "the look was the pilot's choice");
        Assert.assertEquals(ordered, Math.max(0, topOrder.size() - 1),
                "one order frame per position but the last");
        return top;
    }

    private static boolean awayMatches(String label, List<String> away) {
        final int open = label.indexOf('[');
        final int close = label.indexOf(']');
        final String listed = label.substring(open + 1, close);
        final StringBuilder expected = new StringBuilder();
        for (String name : away) {
            expected.append(name).append(';');
        }
        return listed.equals(expected.toString());
    }

    private static BridgeTestSupport.ConstructedGame game(String id, String spell, String land,
            int lands) {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(id, 2);
        BridgeTestSupport.addCard(constructed.game, 0, spell, ZoneType.Hand);
        for (int i = 0; i < lands; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, land, ZoneType.Battlefield);
        }
        for (String name : LIBRARY) {
            BridgeTestSupport.addCard(constructed.game, 0, name, ZoneType.Library);
        }
        for (int i = 0; i < 7; i++) {
            BridgeTestSupport.addCard(constructed.game, 1, "Plains", ZoneType.Library);
        }
        BridgeTestSupport.launchConstructed(constructed);
        return constructed;
    }

    private static List<String> topNames(BridgeSession session, int n) {
        return session.getGame().getPlayers().get(0).getCardsIn(ZoneType.Library, n).stream()
                .map(Card::getName).toList();
    }

    private static boolean inHand(BridgeSession session, String name) {
        return session.getGame().getPlayers().get(0).getCardsIn(ZoneType.Hand).stream()
                .anyMatch(c -> c.getName().equals(name));
    }

    @Test(timeOut = 300000)
    public void preordainKeepsBothInThePilotsOrder() {
        final BridgeTestSupport.ConstructedGame constructed = game("scry-order", "Preordain",
                "Island", 1);
        final BridgeSession session = constructed.session;
        // Reverse the library's order: the second card is drawn.
        final List<String> top = castAndArrange(session, "Preordain", "Island", 1,
                "scry_arrange", true);
        Assert.assertTrue(inHand(session, top.get(1)), "drew the card the pilot put on top");
        Assert.assertEquals(topNames(session, 1), List.of(top.get(0)), "the other is next");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void curateSurveilsOneAwayAndDrawsTheOther() {
        final BridgeTestSupport.ConstructedGame constructed = game("surveil-one", "Curate",
                "Island", 2);
        final BridgeSession session = constructed.session;
        final List<String> top = castAndArrange(session, "Curate", "Island", 2,
                "surveil_arrange", false);
        Assert.assertTrue(session.getGame().getPlayers().get(0).getCardsIn(ZoneType.Graveyard)
                .stream().anyMatch(c -> c.getName().equals(top.get(0))), "surveilled away");
        Assert.assertTrue(inHand(session, top.get(1)), "drew the kept card");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void curateKeepsBothInThePilotsOrder() {
        final BridgeTestSupport.ConstructedGame constructed = game("surveil-order", "Curate",
                "Island", 2);
        final BridgeSession session = constructed.session;
        final List<String> top = castAndArrange(session, "Curate", "Island", 2,
                "surveil_arrange", true);
        Assert.assertTrue(inHand(session, top.get(1)), "drew the card the pilot put on top");
        Assert.assertEquals(topNames(session, 1), List.of(top.get(0)), "the other is next");
        session.shutdown(5000);
    }

    // ---- Six cards to the bottom in any order (Collected Company): the order
    // was all-or-nothing up to five cards; six or more ended the session.

    @Test(timeOut = 300000)
    public void collectedCompanyPutsSixCardsOnTheBottomInThePilotsOrder() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("zone-order-six", 2);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Collected Company", ZoneType.Hand);
        for (int i = 0; i < 4; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        }
        final String[] lands = {"Plains", "Island", "Swamp", "Mountain", "Wastes", "Forest",
            "Command Tower", "Evolving Wilds"};
        for (String land : lands) {
            BridgeTestSupport.addCard(constructed.game, 0, land, ZoneType.Library);
        }
        for (int i = 0; i < 7; i++) {
            BridgeTestSupport.addCard(constructed.game, 1, "Plains", ZoneType.Library);
        }
        BridgeTestSupport.launchConstructed(constructed);
        DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        final List<String> top6 = topNames(session, 6);
        for (int i = 0; i < 4; i++) {
            submit(session, frame, pick(frame, o -> "activate_ability".equals(o.actionType)
                    && "Forest".equals(o.sourceCardName), "Forest"));
            frame = BridgeTestSupport.awaitFrame(session, 15000);
        }
        submit(session, frame, pick(frame, o -> "cast_spell".equals(o.actionType)
                && "Collected Company".equals(o.sourceCardName), "cast Collected Company"));
        // Choose the reverse of the looked-at order, position by position.
        final List<String> wanted = new java.util.ArrayList<>(top6);
        java.util.Collections.reverse(wanted);
        int placed = 0;
        for (int i = 0; i < 40 && (placed < 5 || !session.getGame().getStack().isEmpty()); i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            Assert.assertEquals(f.status, DecisionFrame.Status.SUPPORTED,
                    "blocked: " + f.kind + " " + f.reason);
            if (has(f, "order_zone_next")) {
                Assert.assertEquals(f.options.size(), 6 - placed);
                final String next = wanted.get(placed++);
                submit(session, f, pick(f, o -> o.label.endsWith(": " + next)
                        && o.label.contains("1 = closest to the top"), "position " + placed));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pick(f, o -> o.isPass, "pass"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.reason + " "
                        + f.options.stream().map(o -> o.actionType + "|" + o.label).toList());
            }
        }
        Assert.assertEquals(placed, 5, "six cards ordered in five picks");
        final List<String> library = session.getGame().getPlayers().get(0)
                .getCardsIn(ZoneType.Library).stream().map(Card::getName).toList();
        final List<String> bottom6 = library.subList(library.size() - 6, library.size());
        // Dig moves the rest one by one to the bottom, so position 1 ends up
        // closest to the top of that bottom block, as its label says.
        Assert.assertEquals(bottom6, wanted, "the chosen order is kept, position 1 topmost");
        session.shutdown(5000);
    }
}
