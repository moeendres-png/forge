package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * R11c BOSEIJU bridge family: Boseiju Reaches Skyward (CARD_29) saga
 * chapters + transform.
 *
 * <p>WS234 proved saga entry; chapters + transform were NOT_RUN for lack
 * of a multi-turn harness. Three strict bridge tests: chapter I search
 * (explicit 2-Forest pick from the framed subsets), full three-turn saga
 * (chapter II grave-land move and chapter III exile-return-transformed
 * resolve through engine passes; single-candidate/auto branches need no
 * frames), Branch P/T tracking an extra land drop. Event-driven driving
 * (chapter frames / Branch presence), never turn-counted; land drops
 * suppressed so P/T stays exact. Tests pick only engine-offered options.</p>
 */
public class WsR11BoseijuBridgeFamilyTest {

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
        } else if (frame.kind == DecisionFrame.Kind.SEARCH_SELECTION) {
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

    private static void fillLibraries(BridgeTestSupport.ConstructedGame constructed,
            String card, int count) {
        for (int seat = 0; seat < constructed.game.getPlayers().size(); seat++) {
            for (int i = 0; i < count; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, card, ZoneType.Library);
            }
        }
    }

    private static int zoneCount(BridgeSession session, int seat, ZoneType zone) {
        return session.getGame().getPlayers().get(seat).getZone(zone).getCards().size();
    }

    private static int countZone(BridgeSession session, int seat, ZoneType zone, String name) {
        int n = 0;
        for (Card c : session.getGame().getPlayers().get(seat).getZone(zone).getCards()) {
            if (name.equals(c.getName())) {
                n++;
            }
        }
        return n;
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

    private static BridgeTestSupport.ConstructedGame sagaGame(String gameId) {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(gameId);
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 4; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Boseiju Reaches Skyward", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Graveyard);
        fillLibraries(constructed, "Forest", 5);
        fillLibraries(constructed, "Plains", 10);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        return constructed;
    }

    private static void castSaga(BridgeSession session) {
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, 30);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Boseiju Reaches Skyward".equals(o.sourceCardName),
                "Saga cast"));
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
            if (findBf(session, 0, "Boseiju Reaches Skyward") != null) {
                break;
            }
        }
        Assert.assertNotNull(findBf(session, 0, "Boseiju Reaches Skyward"),
                "saga must enter");
    }

    // Answer chapter-I search with an explicit 2-Forest subset.
    private static void answerChapterOne(BridgeSession session) {
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.SEARCH_SELECTION && f.actorPlayerId.equals("p1")) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && countSemicolons(o.label) == 2
                                && o.label.contains("Forest"),
                        "search 2 Forests"));
                return;
            }
            answerCommon(session, f);
        }
        throw new AssertionError("chapter-I search never parked");
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

    // Pass full rounds (declining everything) until the Branch is present.
    // Lore accretes only on the saga controller's own MAIN1s (CR 703.4f),
    // i.e. every 4th game turn in 4P: ETB 1 (turn 1) + turn 5 (2) +
    // turn 9 (3, transform). Budget covers 10+ game turns.
    private static boolean waitForBranch(BridgeSession session) {
        for (int i = 0; i < 400; i++) {
            if (findBf(session, 0, "Branch of Boseiju") != null) {
                return true;
            }
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                boolean picked = false;
                for (DecisionFrame.Option o : f.options) {
                    if (o.label != null && o.label.contains("Forest")) {
                        submit(session, f, o);
                        picked = true;
                        break;
                    }
                }
                if (!picked) {
                    submit(session, f, f.options.get(0));
                }
                continue;
            }
            answerCommon(session, f);
        }
        return false;
    }

    @Test(timeOut = 300000)
    public void testBoseijuChapterOneSearch() {
        final BridgeTestSupport.ConstructedGame constructed = sagaGame("wsr11-boseiju-ch1");
        final BridgeSession session = constructed.session;
        int handBefore = zoneCount(session, 0, ZoneType.Hand);
        int libBefore = zoneCount(session, 0, ZoneType.Library);
        castSaga(session);
        answerChapterOne(session);
        Assert.assertEquals(zoneCount(session, 0, ZoneType.Hand), handBefore - 1 + 2,
                "chapter I must put exactly 2 Forests into hand (net +1 after cast)");
        Assert.assertEquals(zoneCount(session, 0, ZoneType.Library), libBefore - 2,
                "library must lose exactly the 2 searched Forests");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testBoseijuFullSagaTransform() {
        final BridgeTestSupport.ConstructedGame constructed = sagaGame("wsr11-boseiju-full");
        final BridgeSession session = constructed.session;
        int handBefore = zoneCount(session, 0, ZoneType.Hand);
        int libBefore = zoneCount(session, 0, ZoneType.Library);
        castSaga(session);
        answerChapterOne(session);
        boolean branchArrived = waitForBranch(session);
        Assert.assertTrue(branchArrived, "Branch must arrive within the turn budget");
        Assert.assertNull(findBf(session, 0, "Boseiju Reaches Skyward"),
                "saga front must leave after chapter III");
        final Card branch = findBf(session, 0, "Branch of Boseiju");
        Assert.assertNotNull(branch, "Branch must enter transformed");
        Assert.assertEquals(branch.getNetPower(), 4, "Branch must be 4 power (4 lands)");
        Assert.assertEquals(branch.getNetToughness(), 4, "Branch must be 4 toughness (4 lands)");
        Assert.assertEquals(countZone(session, 0, ZoneType.Graveyard, "Forest"), 0,
                "chapter II must move the grave Forest to the library");
        Assert.assertTrue(zoneCount(session, 0, ZoneType.Hand) >= handBefore + 1,
                "hand must hold cast+search+draws (net >= +1)");
        Assert.assertTrue(zoneCount(session, 0, ZoneType.Library) <= libBefore - 3,
                "library must lose search-2 plus draws");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testBranchPowerTracksExtraLand() {
        final BridgeTestSupport.ConstructedGame constructed = sagaGame("wsr11-boseiju-track");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Hand);
        castSaga(session);
        answerChapterOne(session);
        waitForBranch(session);
        final Card branch = findBf(session, 0, "Branch of Boseiju");
        Assert.assertNotNull(branch, "Branch must enter transformed");
        Assert.assertEquals(branch.getNetPower(), 4, "Branch starts 4 power (4 lands)");
        // Play the extra Forest from hand through the offered play_land option.
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p1") && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                boolean hasLand = false;
                for (DecisionFrame.Option o : f.options) {
                    if ("play_land".equals(o.actionType)
                            && "Forest".equals(o.sourceCardName)) {
                        hasLand = true;
                    }
                }
                if (hasLand) {
                    submit(session, f, pickOption(f,
                            o -> "play_land".equals(o.actionType)
                                    && "Forest".equals(o.sourceCardName),
                            "play Forest"));
                    break;
                }
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
                continue;
            }
            answerCommon(session, f);
        }
        Assert.assertEquals(branch.getNetPower(), 5, "Branch must grow to 5 power (5 lands)");
        Assert.assertEquals(branch.getNetToughness(), 5, "Branch must grow to 5 toughness");
        session.shutdown(5000);
    }
}
