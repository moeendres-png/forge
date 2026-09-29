package forge.bridge;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Multiplayer priority and player elimination (CR 117.3d, 104.3, 800.4a)
 * with actual cards in 4- and 5-player Commander: a spell resolves only
 * after every player in turn order passed; a player at 0 life loses while
 * the game continues; their permanents leave the game (800.4a); they get no
 * further decisions; and turn order skips them.
 */
public class MultiplayerEliminationTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    private static DecisionFrame.Option pick(DecisionFrame frame,
            Predicate<DecisionFrame.Option> test, String what) {
        for (DecisionFrame.Option option : frame.options) {
            if (test.test(option)) {
                return option;
            }
        }
        throw new AssertionError(what + " not offered; kind=" + frame.kind + " actor="
                + frame.actorPlayerId + " reason=" + frame.reason);
    }

    private static void submit(BridgeSession session, DecisionFrame frame,
            DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
    }

    private void eliminate(int players) {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("mp-elim-" + players, players);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Shock", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 2, "Grizzly Bears", ZoneType.Battlefield);
        for (int seat = 0; seat < players; seat++) {
            for (int i = 0; i < 10; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        final List<Player> ps = session.getGame().getPlayers();
        final Player p3 = ps.get(2);
        p3.loseLife(38, false, false, null);
        Assert.assertEquals(p3.getLife(), 2);

        submit(session, frame, pick(frame, o -> "activate_ability".equals(o.actionType)
                && "Mountain".equals(o.sourceCardName), "Mountain"));
        frame = BridgeTestSupport.awaitFrame(session, 15000);
        submit(session, frame, pick(frame, o -> "cast_spell".equals(o.actionType)
                && "Shock".equals(o.sourceCardName), "cast Shock"));
        final List<String> passesWhileOnStack = new ArrayList<>();
        for (int i = 0; i < 30 && p3.getLife() > 0 && !p3.hasLost(); i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, pick(f, o -> o.label != null && o.label.contains("p3"), "p3"));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                if (!session.getGame().getStack().isEmpty()) {
                    passesWhileOnStack.add(f.actorPlayerId);
                }
                submit(session, f, pick(f, o -> o.isPass, "pass"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.reason);
            }
        }
        final List<String> expectedOrder = new ArrayList<>();
        for (int seat = 0; seat < players; seat++) {
            expectedOrder.add("p" + (seat + 1));
        }
        Assert.assertEquals(passesWhileOnStack, expectedOrder,
                "Shock resolves only after every player passed in turn order");

        // Drive on: the next decision checks state-based actions (CR 704.5a).
        final List<String> activeAfter = new ArrayList<>();
        String lastActive = "p1";
        for (int i = 0; i < 400 && activeAfter.size() < players - 1; i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            Assert.assertNotEquals(f.actorPlayerId, "p3",
                    "an eliminated player is never asked again: " + f.kind);
            final String active = session.playerIdOf(
                    session.getGame().getPhaseHandler().getPlayerTurn());
            if (!active.equals(lastActive)) {
                activeAfter.add(active);
                lastActive = active;
            }
            if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pick(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS) {
                submit(session, f, pick(f, o -> "No attacks".equals(o.label), "no attacks"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " for " + f.actorPlayerId + " "
                        + f.reason);
            }
        }
        Assert.assertTrue(p3.hasLost(), "p3 lost at 0 life");
        Assert.assertFalse(session.getGame().isGameOver(), "the game continues");
        for (Card card : session.getGame().getCardsIn(ZoneType.Battlefield)) {
            Assert.assertNotEquals(card.getOwner(), p3, "p3's permanents left the game: "
                    + card.getName());
        }
        final List<String> expectedTurns = new ArrayList<>();
        for (int seat = 1; seat < players; seat++) {
            if (seat != 2) {
                expectedTurns.add("p" + (seat + 1));
            }
        }
        expectedTurns.add("p1");
        Assert.assertEquals(activeAfter, expectedTurns, "turn order skips the eliminated p3");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void fourPlayersContinueAfterAnElimination() {
        eliminate(4);
    }

    @Test(timeOut = 300000)
    public void fivePlayersContinueAfterAnElimination() {
        eliminate(5);
    }

    // ---- "Any player may ..." (Book Burning) in 4P: players decide in turn
    // order starting with the caster; one payer suffices.

    private void bookBurning(String payer) {
        final int players = 4;
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("mp-book-" + payer, players);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Book Burning", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        for (int seat = 0; seat < players; seat++) {
            for (int i = 0; i < 10; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        final List<Player> ps = session.getGame().getPlayers();
        final int p2Library = ps.get(1).getCardsIn(ZoneType.Library).size();
        for (int i = 0; i < 2; i++) {
            submit(session, frame, pick(frame, o -> "activate_ability".equals(o.actionType)
                    && "Mountain".equals(o.sourceCardName), "Mountain"));
            frame = BridgeTestSupport.awaitFrame(session, 15000);
        }
        submit(session, frame, pick(frame, o -> "cast_spell".equals(o.actionType)
                && "Book Burning".equals(o.sourceCardName), "cast Book Burning"));
        final List<String> asked = new ArrayList<>();
        boolean resolved = false;
        for (int i = 0; i < 40 && !resolved; i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            Assert.assertEquals(f.status, DecisionFrame.Status.SUPPORTED,
                    "blocked: " + f.kind + " " + f.reason);
            if (f.options.stream().anyMatch(o -> "pay_to_prevent".equals(o.actionType))) {
                asked.add(f.actorPlayerId);
                final boolean pay = payer.equals(f.actorPlayerId);
                submit(session, f, pick(f, o -> Boolean.valueOf(pay).equals(o.confirmValue),
                        pay ? "take 6" : "decline"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, pick(f, o -> o.label != null && o.label.contains("p2"), "p2"));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                resolved = !asked.isEmpty() && session.getGame().getStack().isEmpty();
                if (!resolved) {
                    submit(session, f, pick(f, o -> o.isPass, "pass"));
                }
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.reason);
            }
        }
        Assert.assertTrue(resolved, "Book Burning resolved");
        // The caster decides first, then the others in turn order. Whether
        // players after the first payer must still be asked is not pinned
        // here (Forge asks everyone; the official ruling was not reachable
        // from this environment): only the prefix up to the payer is asserted.
        final List<String> turnOrder = List.of("p1", "p2", "p3", "p4");
        final int decided = payer.isEmpty() ? 4 : turnOrder.indexOf(payer) + 1;
        Assert.assertTrue(asked.size() >= decided, "asked " + asked);
        Assert.assertEquals(asked.subList(0, decided), turnOrder.subList(0, decided),
                "the caster first, then each player in turn order");
        for (int seat = 0; seat < players; seat++) {
            final String id = "p" + (seat + 1);
            Assert.assertEquals(ps.get(seat).getLife(), id.equals(payer) ? 34 : 40,
                    id + " life");
        }
        Assert.assertEquals(ps.get(1).getCardsIn(ZoneType.Library).size(),
                payer.isEmpty() ? p2Library - 6 : p2Library,
                payer.isEmpty() ? "nobody paid: p2 mills six" : "someone paid: no mill");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void bookBurningAThirdPlayerMayTakeTheDamage() {
        bookBurning("p3");
    }

    @Test(timeOut = 300000)
    public void bookBurningMillsWhenNoPlayerPays() {
        bookBurning("");
    }

    // ---- More than four simultaneous triggers (CR 603.3b) in 4P: Wrath of
    // God under Blood Artist puts six triggers under p1's control at once.

    @Test(timeOut = 300000)
    public void sixSimultaneousBloodArtistTriggersAreOrderedAndResolve() {
        final int players = 4;
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("mp-many-triggers", players);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Wrath of God", ZoneType.Hand);
        for (int i = 0; i < 4; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Blood Artist", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Grizzly Bears", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Grizzly Bears", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Grizzly Bears", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 2, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 3, "Llanowar Elves", ZoneType.Battlefield);
        for (int seat = 0; seat < players; seat++) {
            for (int i = 0; i < 10; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (int i = 0; i < 4; i++) {
            submit(session, frame, pick(frame, o -> "activate_ability".equals(o.actionType)
                    && "Plains".equals(o.sourceCardName), "Plains"));
            frame = BridgeTestSupport.awaitFrame(session, 15000);
        }
        submit(session, frame, pick(frame, o -> "cast_spell".equals(o.actionType)
                && "Wrath of God".equals(o.sourceCardName), "cast Wrath of God"));
        final List<Player> ps = session.getGame().getPlayers();
        int orderFrames = 0;
        int targeted = 0;
        boolean wiped = false;
        for (int i = 0; i < 120; i++) {
            if (wiped && targeted == 6 && session.getGame().getStack().isEmpty()) {
                break;
            }
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            Assert.assertEquals(f.status, DecisionFrame.Status.SUPPORTED,
                    "blocked: " + f.kind + " " + f.reason);
            if (f.kind == DecisionFrame.Kind.TRIGGER_ORDER) {
                Assert.assertEquals(f.actorPlayerId, "p1", "p1 controls every Blood Artist trigger");
                Assert.assertEquals(f.options.size(), 6 - orderFrames,
                        "one option per trigger not yet placed");
                orderFrames++;
                submit(session, f, f.options.get(f.options.size() - 1));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                wiped = true;
                // Spread the drain: two triggers each at p2, p3, p4.
                final String victim = "p" + (2 + targeted / 2);
                targeted++;
                submit(session, f, pick(f, o -> o.label != null && o.label.contains(victim),
                        victim));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                if (session.getGame().getCardsIn(ZoneType.Battlefield).stream()
                        .noneMatch(c -> c.isCreature())) {
                    wiped = true;
                }
                submit(session, f, pick(f, o -> o.isPass, "pass"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.reason);
            }
        }
        Assert.assertEquals(orderFrames, 5, "six triggers ordered in five picks");
        Assert.assertEquals(targeted, 6, "every trigger was targeted");
        Assert.assertEquals(ps.get(0).getLife(), 46, "p1 gained 6");
        for (int seat = 1; seat < players; seat++) {
            Assert.assertEquals(ps.get(seat).getLife(), 38, "p" + (seat + 1) + " lost 2");
        }
        session.shutdown(5000);
    }
}
