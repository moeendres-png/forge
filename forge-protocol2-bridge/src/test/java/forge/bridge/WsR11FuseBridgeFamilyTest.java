package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * R11b FUSE bridge family: Wear // Tear (CARD_11) fused combined cast.
 *
 * <p>WS234 proved Wear-half destroy; Tear shares Destroy
 * (TECHNICALLY_CONFORMANT); Fuse combined was NOT_RUN. Three strict
 * bridge tests through engine-offered frames: fused {1}{W}{R} destroys
 * both an artifact and an enchantment (each half's target picked
 * explicitly), Wear-half alone destroys only the artifact, short-mana
 * fused attempt fails closed at payment decline. Tests pick only
 * engine-offered options; zones are accounted, never injected.</p>
 */
public class WsR11FuseBridgeFamilyTest {

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

    private static void fillLibraries(BridgeTestSupport.ConstructedGame constructed, int count) {
        for (int seat = 0; seat < constructed.game.getPlayers().size(); seat++) {
            for (int i = 0; i < count; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
    }

    private static boolean inZone(BridgeSession session, int seat, ZoneType zone, String name) {
        for (Card c : session.getGame().getPlayers().get(seat).getZone(zone).getCards()) {
            if (name.equals(c.getName())) {
                return true;
            }
        }
        return false;
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

    private static BridgeTestSupport.ConstructedGame fuseGame(String gameId, int plains) {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(gameId);
        BridgeTestSupport.addCard(constructed.game, 0, "Wear // Tear", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        for (int i = 0; i < plains; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 1, "Ornithopter", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Glorious Anthem", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(constructed.session, "p1", 12);
        return constructed;
    }

    @Test(timeOut = 300000)
    public void testFusedDestroysBothTypes() {
        final BridgeTestSupport.ConstructedGame constructed = fuseGame("wsr11-fuse-both", 2);
        final BridgeSession session = constructed.session;
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, 30);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Wear // Tear".equals(o.sourceCardName)
                        && o.label != null && o.label.contains("{1}{W}{R}"),
                "fused cast"));
        for (int i = 0; i < 30; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.TARGET_SELECTION && f.actorPlayerId.equals("p1")) {
                boolean picked = false;
                for (DecisionFrame.Option o : f.options) {
                    if (o.label != null && o.label.contains("Ornithopter")) {
                        submit(session, f, o);
                        picked = true;
                        break;
                    }
                }
                if (!picked) {
                    for (DecisionFrame.Option o : f.options) {
                        if (o.label != null && o.label.contains("Glorious Anthem")) {
                            submit(session, f, o);
                            picked = true;
                            break;
                        }
                    }
                }
                if (!picked) {
                    submit(session, f, f.options.get(0));
                }
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && (o.label.contains("No attack")
                                || o.label.contains("No block")),
                        "decline combat"));
            } else {
                break;
            }
            if (inZone(session, 1, ZoneType.Graveyard, "Ornithopter")
                    && inZone(session, 1, ZoneType.Graveyard, "Glorious Anthem")) {
                break;
            }
        }
        drain(session, 20);
        Assert.assertTrue(inZone(session, 1, ZoneType.Graveyard, "Ornithopter"),
                "fused Wear must destroy the artifact");
        Assert.assertTrue(inZone(session, 1, ZoneType.Graveyard, "Glorious Anthem"),
                "fused Tear must destroy the enchantment");
        Assert.assertTrue(inZone(session, 0, ZoneType.Graveyard, "Wear // Tear"),
                "fused spell must resolve natively to graveyard");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testWearHalfDestroysOnlyArtifact() {
        final BridgeTestSupport.ConstructedGame constructed = fuseGame("wsr11-wear-half", 1);
        final BridgeSession session = constructed.session;
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, 30);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Wear // Tear".equals(o.sourceCardName)
                        && o.label != null && o.label.contains("{1}{R}")
                        && !o.label.contains("{W}"),
                "Wear-half cast"));
        drain(session, 30);
        Assert.assertTrue(inZone(session, 1, ZoneType.Graveyard, "Ornithopter"),
                "Wear half must destroy the artifact");
        Assert.assertTrue(inZone(session, 1, ZoneType.Battlefield, "Glorious Anthem"),
                "enchantment must survive the Wear half");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testFusedShortManaFailClosed() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr11-fuse-closed");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Wear // Tear", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Ornithopter", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Glorious Anthem", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Wear // Tear".equals(o.sourceCardName)
                        && o.label != null && o.label.contains("{1}{W}{R}"),
                "fused cast attempt"));
        for (int i = 0; i < 15; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("Decline"),
                        "decline short payment"));
                break;
            }
            if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, f.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertTrue(inZone(session, 0, ZoneType.Hand, "Wear // Tear"),
                "unpayable fused cast must remain in hand after decline");
        Assert.assertTrue(inZone(session, 1, ZoneType.Battlefield, "Ornithopter"),
                "artifact must survive the failed cast");
        session.shutdown(5000);
    }
}
