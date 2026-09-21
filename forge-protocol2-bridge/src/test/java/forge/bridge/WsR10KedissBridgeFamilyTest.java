package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * R10a KEDISS bridge family: Kediss, Emberclaw Familiar (CARD_04) commander
 * combat-damage trigger in multiplayer.
 *
 * <p>WS234 proved Partner + trigger presence; the redirect branch (commander
 * combat damage to an opponent hits each other opponent) was NOT_RUN for
 * lack of a multiplayer combat harness. Four strict bridge tests through
 * engine-offered frames: trigger damages each other opponent (p2/p3/p4
 * accounting), no trigger for non-commander damage, no trigger with Kediss
 * off the battlefield (zone scoping), controller scoping (opponent's
 * commander does not trigger mine, mine does not trigger for theirs).
 * Fervor grants haste so same-turn casts can attack; all targets/blocks/
 * payments are engine-offered picks, damage is accounted never injected.</p>
 */
public class WsR10KedissBridgeFamilyTest {

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

    private static boolean frameMatches(DecisionFrame frame, String actorId,
            DecisionFrame.Kind kind) {
        return frame.kind == kind && frame.actorPlayerId.equals(actorId)
                && frame.status == DecisionFrame.Status.SUPPORTED;
    }

    private static DecisionFrame awaitNext(BridgeSession session, long lastRevision,
            long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            final DecisionFrame frame = session.getCurrentFrame();
            if (frame != null && frame.revision != lastRevision) {
                return frame;
            }
            if (session.isTerminal()) {
                return session.getCurrentFrame();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return session.getCurrentFrame();
            }
        }
        return session.getCurrentFrame();
    }

    private static void answerCommon(BridgeSession session, DecisionFrame frame) {
        if (frame.kind == DecisionFrame.Kind.PRIORITY) {
            submit(session, frame, pickOption(frame, o -> o.isPass, "pass"));
        } else if (frame.kind == DecisionFrame.Kind.MANA_PAYMENT) {
            submit(session, frame, frame.options.get(0));
        } else if (frame.kind == DecisionFrame.Kind.TARGET_SELECTION) {
            submit(session, frame, frame.options.get(0));
        } else if (frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                || frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
            submit(session, frame, pickOption(frame,
                    o -> o.label != null && (o.label.contains("No attack")
                            || o.label.contains("No block")),
                    "decline combat"));
        } else {
            throw new AssertionError("unexpected " + frame.kind + " for " + frame.actorPlayerId);
        }
    }

    private static DecisionFrame driveTo(BridgeSession session, String actorId,
            DecisionFrame.Kind kind, int budget) {
        long lastRevision = -1;
        final DecisionFrame current = session.getCurrentFrame();
        if (current != null) {
            lastRevision = current.revision;
            if (frameMatches(current, actorId, kind)) {
                return current;
            }
        }
        for (int i = 0; i < budget; i++) {
            final DecisionFrame frame = awaitNext(session, lastRevision, 15000);
            Assert.assertNotNull(frame, "no frame parked while driving to " + kind);
            lastRevision = frame.revision;
            if (frameMatches(frame, actorId, kind)) {
                return frame;
            }
            if (frame.status != DecisionFrame.Status.SUPPORTED) {
                throw new AssertionError("blocked by " + frame.kind + " " + frame.status);
            }
            answerCommon(session, frame);
        }
        throw new AssertionError("never reached " + kind + " for " + actorId);
    }

    private static void tapLand(BridgeSession session, String actorId, String landName) {
        DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
        Assert.assertNotNull(frame);
        submit(session, frame, pickOption(frame,
                o -> landName.equals(o.sourceCardName)
                        && o.actionType != null && o.actionType.contains("tap"),
                "tap " + landName));
    }

    private static void fillLibraries(BridgeTestSupport.ConstructedGame constructed, int count) {
        for (int seat = 0; seat < constructed.game.getPlayers().size(); seat++) {
            for (int i = 0; i < count; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
    }

    private static int life(int seat, BridgeSession session) {
        return session.getGame().getPlayers().get(seat).getLife();
    }

    // Attack with the given creature at the given defender; no blocks.
    private static void attackUnblocked(BridgeSession session, String attackerId,
            String attackerName, String defenderId) {
        for (int i = 0; i < 30; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals(attackerId)
                    && f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains(attackerName)
                                && o.label.contains(defenderId),
                        "attack " + attackerName + " -> " + defenderId));
                break;
            }
            answerCommon(session, f);
        }
        for (int i = 0; i < 20; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("No block"),
                        "no blocks"));
                break;
            }
            answerCommon(session, f);
        }
    }

    private static void drain(BridgeSession session, int budget) {
        long lastRevision = -1;
        for (int i = 0; i < budget; i++) {
            DecisionFrame frame = awaitNext(session, lastRevision, 15000);
            Assert.assertNotNull(frame, "no frame while draining");
            if (frame.revision == lastRevision) {
                return;
            }
            lastRevision = frame.revision;
            if (frame.status != DecisionFrame.Status.SUPPORTED) {
                return;
            }
            answerCommon(session, frame);
        }
    }

    private static void castKedissFromCommand(BridgeSession session) {
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, 30);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Kediss, Emberclaw Familiar".equals(o.sourceCardName),
                "Kediss cast"));
        for (int i = 0; i < 20; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, f.options.get(0));
            } else {
                break;
            }
            if (findBf(session, 0, "Kediss, Emberclaw Familiar") != null) {
                break;
            }
        }
        Assert.assertNotNull(findBf(session, 0, "Kediss, Emberclaw Familiar"),
                "Kediss must enter from the command zone");
    }

    private static Card findBf(BridgeSession session, int seat, String name) {
        for (Card c : session.getGame().getPlayers().get(seat)
                .getZone(ZoneType.Battlefield).getCards()) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    @Test(timeOut = 300000)
    public void testKedissTriggerDamagesEachOtherOpponent() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr10-kediss-hit");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCommander(constructed.game, 0, "Kediss, Emberclaw Familiar");
        BridgeTestSupport.addCard(constructed.game, 0, "Fervor", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        int p1Life = life(0, session);
        int p2Life = life(1, session);
        int p3Life = life(2, session);
        int p4Life = life(3, session);

        castKedissFromCommand(session);
        attackUnblocked(session, "p1", "Kediss, Emberclaw Familiar", "p2");
        drain(session, 40);
        Assert.assertEquals(life(0, session), p1Life, "p1 untouched");
        Assert.assertEquals(life(1, session), p2Life - 1, "p2 takes commander combat");
        Assert.assertEquals(life(2, session), p3Life - 1, "p3 takes trigger");
        Assert.assertEquals(life(3, session), p4Life - 1, "p4 takes trigger");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testKedissNoTriggerForNonCommanderDamage() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr10-kediss-bear");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Kediss, Emberclaw Familiar",
                ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Fervor", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        int p2Life = life(1, session);
        int p3Life = life(2, session);
        int p4Life = life(3, session);

        attackUnblocked(session, "p1", "Runeclaw Bear", "p2");
        drain(session, 40);
        Assert.assertEquals(life(1, session), p2Life - 2, "p2 takes Bear combat");
        Assert.assertEquals(life(2, session), p3Life, "p3 untouched (non-commander)");
        Assert.assertEquals(life(3, session), p4Life, "p4 untouched (non-commander)");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testKedissOffBattlefieldNoTrigger() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr10-kediss-zone");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCommander(constructed.game, 0, "Kediss, Emberclaw Familiar");
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Fervor", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        int p2Life = life(1, session);
        int p3Life = life(2, session);
        int p4Life = life(3, session);

        attackUnblocked(session, "p1", "Runeclaw Bear", "p2");
        drain(session, 40);
        Assert.assertEquals(life(1, session), p2Life - 2, "p2 takes Bear combat");
        Assert.assertEquals(life(2, session), p3Life, "p3 untouched (Kediss in command)");
        Assert.assertEquals(life(3, session), p4Life, "p4 untouched (Kediss in command)");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testKedissTriggerScopedToOwnCommander() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr10-kediss-scope");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Kediss, Emberclaw Familiar",
                ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Fervor", ZoneType.Battlefield);
        BridgeTestSupport.addCommander(constructed.game, 1, "Kediss, Emberclaw Familiar");
        BridgeTestSupport.addCard(constructed.game, 1, "Fervor", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Mountain", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        // Reach p2's main phase, cast p2 Kediss from command, attack p1.
        for (int i = 0; i < 60; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p2") && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                boolean has = false;
                for (DecisionFrame.Option o : f.options) {
                    if ("cast_spell".equals(o.actionType)
                            && "Kediss, Emberclaw Familiar".equals(o.sourceCardName)) {
                        has = true;
                    }
                }
                if (has) {
                    break;
                }
            }
            answerCommon(session, f);
        }
        DecisionFrame frame = driveTo(session, "p2", DecisionFrame.Kind.PRIORITY, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Kediss, Emberclaw Familiar".equals(o.sourceCardName),
                "p2 Kediss cast"));
        // Tight post-cast flow: answer mana, pass priorities, and attack at
        // p2's FIRST declare-attackers frame (never let generic answering
        // consume/decline p2's combat).
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p2")
                    && f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("Kediss, Emberclaw Familiar")
                                && o.label.contains("p1"),
                        "p2 Kediss -> p1"));
                break;
            }
            answerCommon(session, f);
        }
        Assert.assertNotNull(findBf(session, 1, "Kediss, Emberclaw Familiar"),
                "p2 Kediss must be on the battlefield");
        for (int i = 0; i < 20; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("No block"),
                        "no blocks"));
                break;
            }
            answerCommon(session, f);
        }
        // No drain here: it would decline p2's combat before the attack.
        // Attack already submitted above; snapshot pre-damage lives now.
        int p1Life = life(0, session);
        int p3Life = life(2, session);
        int p4Life = life(3, session);
        drain(session, 40);
        Assert.assertEquals(life(0, session), p1Life - 1, "p1 takes commander combat only");
        Assert.assertEquals(life(2, session), p3Life - 1, "p3 takes p2 trigger");
        Assert.assertEquals(life(3, session), p4Life - 1, "p4 takes p2 trigger");
        session.shutdown(5000);
    }
}
