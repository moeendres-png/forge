package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * R9c FINALE-X10 bridge family: Finale of Revelation (CARD_15) X&gt;=10
 * branch closure.
 *
 * <p>WS234 proved X=2 draw; X&gt;=10 (shuffle/draw-10/untap-5/no-max/exile)
 * was NOT_RUN. Strict bridge tests: X_ANNOUNCE free-input 10, 12-mana
 * payment, graveyard-shuffle, draw-10, five-land untap, self-exile, plus an
 * 11-mana fail-closed negative. Design note: the bridge caps card-selection
 * framing at 128 combinations, so the mana base uses 5 Islands + 7 Mox
 * Sapphires (nonland mana) to keep the untap candidate set at C(5,0..5)=32;
 * twelve tapped Islands would be 1586 combinations and fail closed by
 * design (sealed probe evidence). Tests pick only engine-offered options;
 * the 5-Island untap subset is unique (C(5,5)=1).</p>
 */
public class WsR9FinaleX10BridgeFamilyTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    private static DecisionFrame.Option pickOption(DecisionFrame frame,
            Predicate<DecisionFrame.Option> test, String what) {
        final StringBuilder seen = new StringBuilder();
        for (DecisionFrame.Option option : frame.options) {
            if (test.test(option)) {
                return option;
            }
            seen.append('[').append(option.actionType).append('|')
                    .append(option.label).append(']');
        }
        throw new AssertionError(
                what + " not offered; kind=" + frame.kind + " options=" + seen);
    }

    private static void submit(BridgeSession session, DecisionFrame frame,
            DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
    }

    private static void submitValue(BridgeSession session, DecisionFrame frame, long value) {
        Assert.assertTrue(frame.freeInput, "not a free-input frame");
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                DecisionFrame.FREE_INPUT_ID, frame.options.get(0).actionType, frame.revision,
                Long.valueOf(value));
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
    }

    private static void fillLibraries(BridgeTestSupport.ConstructedGame constructed, int count) {
        for (int seat = 0; seat < constructed.game.getPlayers().size(); seat++) {
            for (int i = 0; i < count; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
    }

    private static int zoneCount(BridgeSession session, int seat, ZoneType zone) {
        return session.getGame().getPlayers().get(seat).getZone(zone).getCards().size();
    }

    private static int countUntapped(BridgeSession session, String name) {
        int n = 0;
        for (Card c : session.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (name.equals(c.getName()) && !c.isTapped()) {
                n++;
            }
        }
        return n;
    }

    private static boolean inZone(BridgeSession session, int seat, ZoneType zone, String name) {
        for (Card c : session.getGame().getPlayers().get(seat).getZone(zone).getCards()) {
            if (name.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    // Drive to p1 priority with Finale offered (passing others, paying first
    // mana, first targets, declining combat).
    private static DecisionFrame driveToFinale(BridgeSession session) {
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p1") && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                boolean has = false;
                for (DecisionFrame.Option o : f.options) {
                    if ("cast_spell".equals(o.actionType)
                            && "Finale of Revelation".equals(o.sourceCardName)) {
                        has = true;
                    }
                }
                if (has) {
                    return f;
                }
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
                continue;
            }
            if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && (o.label.contains("No attack")
                                || o.label.contains("No block")),
                        "decline combat"));
            } else {
                throw new AssertionError("unexpected " + f.kind);
            }
        }
        throw new AssertionError("never reached Finale priority");
    }

    private static int countSemicolons(String label) {
        int n = 0;
        for (int i = 0; i < label.length(); i++) {
            if (label.charAt(i) == ';') {
                n++;
            }
        }
        return n;
    }

    @Test(timeOut = 300000)
    public void testFinaleX10FullBranch() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr9-finale-x10");
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        }
        for (int i = 0; i < 7; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mox Sapphire", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Finale of Revelation", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Grizzly Bears", ZoneType.Graveyard);
        BridgeTestSupport.addCard(constructed.game, 0, "Grizzly Bears", ZoneType.Graveyard);
        BridgeTestSupport.addCard(constructed.game, 0, "Shock", ZoneType.Graveyard);
        fillLibraries(constructed, 20);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        int handBefore = zoneCount(session, 0, ZoneType.Hand);
        int libBefore = zoneCount(session, 0, ZoneType.Library);

        DecisionFrame frame = driveToFinale(session);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Finale of Revelation".equals(o.sourceCardName),
                "Finale cast"));
        boolean announced = false;
        boolean untapAnswered = false;
        for (int i = 0; i < 80; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.X_ANNOUNCE && f.actorPlayerId.equals("p1")) {
                submitValue(session, f, 10);
                announced = true;
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.GENERIC_SELECTION
                    && f.actorPlayerId.equals("p1")) {
                // Untap-up-to-5: the 5-Island subset is unique (C(5,5)=1).
                submit(session, f, pickOption(f,
                        o -> o.label != null && countSemicolons(o.label) == 5
                                && o.label.contains("Island"),
                        "untap all five Islands"));
                untapAnswered = true;
            } else if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && (o.label.contains("No attack")
                                || o.label.contains("No block")),
                        "decline combat"));
            } else {
                break;
            }
            if (untapAnswered && inZone(session, 0, ZoneType.Exile, "Finale of Revelation")) {
                break;
            }
        }
        Assert.assertTrue(announced, "X=10 must be announced");
        Assert.assertTrue(untapAnswered, "untap selection must be framed and answered");
        Assert.assertTrue(inZone(session, 0, ZoneType.Exile, "Finale of Revelation"),
                "X>=10 Finale must exile itself (not graveyard)");
        Assert.assertFalse(inZone(session, 0, ZoneType.Graveyard, "Grizzly Bears"),
                "graveyard must be shuffled into library");
        Assert.assertFalse(inZone(session, 0, ZoneType.Graveyard, "Shock"),
                "graveyard must be shuffled into library");
        Assert.assertEquals(zoneCount(session, 0, ZoneType.Hand), handBefore - 1 + 10,
                "must draw exactly 10 (net +9 after cast)");
        Assert.assertEquals(zoneCount(session, 0, ZoneType.Library), libBefore + 3 - 10,
                "library must gain 3 shuffled and lose 10 drawn");
        Assert.assertEquals(countUntapped(session, "Island"), 5,
                "all five Islands must be untapped");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testFinaleX10NeedsTwelveManaFailClosed() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr9-finale-closed");
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        }
        for (int i = 0; i < 6; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mox Sapphire", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Finale of Revelation", ZoneType.Hand);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        DecisionFrame frame = driveToFinale(session);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Finale of Revelation".equals(o.sourceCardName),
                "Finale cast attempt"));
        // X=10 announced but only 11 mana available: decline at payment.
        for (int i = 0; i < 30; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.X_ANNOUNCE && f.actorPlayerId.equals("p1")) {
                submitValue(session, f, 10);
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("Decline"),
                        "decline short payment"));
                break;
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, f.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertTrue(inZone(session, 0, ZoneType.Hand, "Finale of Revelation"),
                "unpayable X=10 Finale must remain in hand after decline");
        session.shutdown(5000);
    }
}
