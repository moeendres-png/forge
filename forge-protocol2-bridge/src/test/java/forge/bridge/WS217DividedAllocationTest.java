package forge.bridge;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.player.DividedAllocationDecision;
import forge.game.player.DividedAllocationSelection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * WS217 Forge authoritative divided-allocation decision surface.
 *
 * <p>Arc Lightning is the mandatory first actual-card regression: native cast,
 * Forge-generated legal targets, authoritative DIVIDED_ALLOCATION decision
 * externally visible, exact vector submit, native resolution proving each target
 * receives the chosen amount, no manual injection, no bridge legality.
 */
public class WS217DividedAllocationTest {
    private static final String CORE_SHA = "c4d67145a6f9902e031a11dde5c33c60f51ed08d";

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
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

    private static BridgeSession.SubmitOutcome submit(BridgeSession session, DecisionFrame frame,
            DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage
                        + " session=" + session.getStatus() + " fail=" + session.getFailReason());
        return outcome;
    }

    private static BridgeSession.SubmitOutcome submitDivided(BridgeSession session,
            DecisionFrame frame, Map<String, Integer> allocations) {
        final BridgeSession.SubmitOutcome outcome = session.submitDividedAllocation(
                frame.actorPlayerId, frame.revision, allocations);
        Assert.assertTrue(outcome.applied,
                "divided submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
        Assert.assertTrue(outcome.executionOk, "divided vector rejected natively: "
                + session.getLastExecutionError());
        return outcome;
    }

    private static DecisionFrame driveTo(BridgeSession session, String actorId,
            DecisionFrame.Kind kind, String phaseFragment, int budget) {
        long lastRevision = -1;
        final DecisionFrame current = session.getCurrentFrame();
        if (current != null) {
            lastRevision = current.revision;
            if (frameMatches(session, current, actorId, kind, phaseFragment)) {
                return current;
            }
        }
        for (int i = 0; i < budget; i++) {
            final DecisionFrame frame = awaitNext(session, lastRevision, 15000);
            Assert.assertNotNull(frame, "no frame parked while driving to " + kind + " for "
                    + actorId);
            lastRevision = frame.revision;
            if (frameMatches(session, frame, actorId, kind, phaseFragment)) {
                return frame;
            }
            if (frame.kind == DecisionFrame.Kind.MULLIGAN) {
                BridgeTestSupport.submitKeep(session, frame);
                continue;
            }
            if (frame.status != DecisionFrame.Status.SUPPORTED) {
                throw new AssertionError("blocked by " + frame.kind + " " + frame.status + " "
                        + frame.reason + " for " + frame.actorPlayerId + " while driving to "
                        + kind + " for " + actorId);
            }
            if (frame.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, frame, pickOption(frame, o -> o.isPass, "pass"));
                continue;
            }
            if (frame.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, frame, frame.options.get(0));
                continue;
            }
            if (frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, frame, pickOption(frame,
                        o -> o.label != null && (o.label.contains("No attack")
                                || o.label.contains("No block")),
                        "bystander decline"));
                continue;
            }
            throw new AssertionError("unexpected " + frame.kind + " for "
                    + frame.actorPlayerId + " while driving to " + kind + " for " + actorId);
        }
        throw new AssertionError("never reached " + kind + " for " + actorId);
    }

    private static boolean frameMatches(BridgeSession session, DecisionFrame frame, String actorId,
            DecisionFrame.Kind kind, String phaseFragment) {
        if (frame.kind != kind || !frame.actorPlayerId.equals(actorId)
                || frame.status != DecisionFrame.Status.SUPPORTED) {
            return false;
        }
        if (phaseFragment == null) {
            return true;
        }
        try {
            final String phase = session.getGame().getPhaseHandler().getPhase().name();
            return phase.contains(phaseFragment);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void tapLand(BridgeSession session, String actorId, String landName) {
        DecisionFrame frame = driveTo(session, actorId, DecisionFrame.Kind.PRIORITY, "MAIN",
                60);
        final DecisionFrame.Option tap = pickOption(frame,
                o -> "activate_ability".equals(o.actionType) && landName.equals(o.sourceCardName),
                landName + " tap");
        submit(session, frame, tap);
        for (int i = 0; i < 10; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "no frame parked after tap");
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.actorPlayerId.equals(actorId)) {
                return;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " after tap");
        }
        throw new AssertionError("priority never resumed after tap");
    }

    private static void fillLibraries(BridgeTestSupport.ConstructedGame constructed, int count) {
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < count; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
    }

    private static void assertFrameNegatives(BridgeSession session, DecisionFrame frame) {
        final DecisionFrame.Option first = frame.options.get(0);
        final String other = frame.actorPlayerId.equals("p1") ? "p2" : "p1";
        final BridgeSession.SubmitOutcome wrongActor = session.submit(other, first.optionId,
                first.actionType, frame.revision);
        Assert.assertFalse(wrongActor.applied);
        Assert.assertEquals(wrongActor.errorCode, BridgeErrors.WRONG_ACTOR);
        final BridgeSession.SubmitOutcome stale = session.submit(frame.actorPlayerId,
                first.optionId, first.actionType, frame.revision - 1);
        Assert.assertFalse(stale.applied);
        Assert.assertEquals(stale.errorCode, BridgeErrors.STALE_REVISION);
        final BridgeSession.SubmitOutcome unknown = session.submit(frame.actorPlayerId,
                "opt-does-not-exist", first.actionType, frame.revision);
        Assert.assertFalse(unknown.applied);
        Assert.assertEquals(unknown.errorCode, BridgeErrors.UNKNOWN_OPTION);
    }

    private static void assertDividedNegatives(BridgeSession session, DecisionFrame frame) {
        Assert.assertEquals(frame.kind, DecisionFrame.Kind.DIVIDED_ALLOCATION);
        final String other = frame.actorPlayerId.equals("p1") ? "p2" : "p1";
        final Map<String, Integer> probe = new LinkedHashMap<>();
        probe.put(frame.options.get(0).optionId, 1);
        final BridgeSession.SubmitOutcome wrongActor = session.submitDividedAllocation(other,
                frame.revision, probe);
        Assert.assertFalse(wrongActor.applied);
        Assert.assertEquals(wrongActor.errorCode, BridgeErrors.WRONG_ACTOR);
        final BridgeSession.SubmitOutcome stale = session.submitDividedAllocation(
                frame.actorPlayerId, frame.revision - 1, probe);
        Assert.assertFalse(stale.applied);
        Assert.assertEquals(stale.errorCode, BridgeErrors.STALE_REVISION);
        final Map<String, Integer> unknown = new LinkedHashMap<>();
        unknown.put("opt-does-not-exist", 1);
        final BridgeSession.SubmitOutcome unknownOutcome = session.submitDividedAllocation(
                frame.actorPlayerId, frame.revision, unknown);
        Assert.assertFalse(unknownOutcome.applied);
        Assert.assertEquals(unknownOutcome.errorCode, BridgeErrors.UNKNOWN_OPTION);
        final BridgeSession.SubmitOutcome malformedNull = session.submitDividedAllocation(
                frame.actorPlayerId, frame.revision, null);
        Assert.assertFalse(malformedNull.applied);
        Assert.assertEquals(malformedNull.errorCode, BridgeErrors.MALFORMED_REQUEST);
        final Map<String, Integer> malformedKey = new LinkedHashMap<>();
        malformedKey.put("", 1);
        final BridgeSession.SubmitOutcome malformedKeyOutcome = session.submitDividedAllocation(
                frame.actorPlayerId, frame.revision, malformedKey);
        Assert.assertFalse(malformedKeyOutcome.applied);
        // Single-option submits on divided frames stay malformed (vector required).
        final DecisionFrame.Option first = frame.options.get(0);
        final BridgeSession.SubmitOutcome singleOption = session.submit(frame.actorPlayerId,
                first.optionId, first.actionType, frame.revision);
        Assert.assertFalse(singleOption.applied);
        Assert.assertEquals(singleOption.errorCode, BridgeErrors.MALFORMED_REQUEST);
    }

    private static BridgeTestSupport.ConstructedGame arcFixture(String gameId) {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(gameId);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        for (int i = 0; i < 3; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Arc Lightning", ZoneType.Hand);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(constructed.session, "p1", 12);
        for (int i = 0; i < 3; i++) {
            tapLand(constructed.session, "p1", "Mountain");
        }
        return constructed;
    }

    private static DecisionFrame castArc(BridgeSession session) {
        // The priority frame parked before Arc arrived cannot offer it; pass around
        // the table so the engine re-parks with the divided spell enumerated.
        for (int i = 0; i < 40; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "no frame parked awaiting Arc offering");
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                if (parked.actorPlayerId.equals("p1")) {
                    DecisionFrame.Option arc = null;
                    for (DecisionFrame.Option option : parked.options) {
                        if ("cast_spell".equals(option.actionType)
                                && "Arc Lightning".equals(option.sourceCardName)) {
                            arc = option;
                            break;
                        }
                    }
                    if (arc != null) {
                        submit(session, parked, arc);
                        break;
                    }
                }
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, parked, pickOption(parked,
                        o -> o.label != null && (o.label.contains("No attack")
                                || o.label.contains("No block")),
                        "bystander decline"));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting Arc offering");
        }
        DecisionFrame targetFrame = null;
        for (int i = 0; i < 30; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "no frame parked awaiting Arc targets");
            if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
                targetFrame = parked;
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting Arc targets");
        }
        Assert.assertNotNull(targetFrame, "Arc TARGET_SELECTION never parked");
        return targetFrame;
    }

    private static Map<String, String> targetIdsByLabel(DecisionFrame dividedFrame) {
        final Map<String, String> byLabel = new LinkedHashMap<>();
        for (DecisionFrame.Option option : dividedFrame.options) {
            byLabel.put(option.label, option.optionId);
        }
        return byLabel;
    }

    private static String findTargetOption(DecisionFrame dividedFrame, String fragment) {
        for (DecisionFrame.Option option : dividedFrame.options) {
            if (option.label != null && option.label.contains(fragment)) {
                return option.optionId;
            }
        }
        throw new AssertionError("divided target not offered: " + fragment
                + " in " + dividedFrame.options.size() + " options");
    }

    private static void passToResolution(BridgeSession session, int budget) {
        for (int i = 0; i < budget; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "no frame parked while settling");
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, parked, pickOption(parked,
                        o -> o.label != null && (o.label.contains("No attack")
                                || o.label.contains("No block")),
                        "bystander decline"));
                continue;
            }
            return;
        }
    }

    // ---- ARC LIGHTNING: 2 to Bear, 1 to p2 ----

    @Test(timeOut = 300000)
    public void testArcLightningSplitTwoToBear() {
        final BridgeTestSupport.ConstructedGame constructed = arcFixture("ws217-arc-2-1");
        final BridgeSession session = constructed.session;
        final int p2LifeBefore = session.getGame().getPlayers().get(1).getLife();
        final DecisionFrame targetFrame = castArc(session);
        assertFrameNegatives(session, targetFrame);
        // Choose Bear plus opposing player (two distinct legal targets).
        DecisionFrame.Option bearPlusPlayer = null;
        for (DecisionFrame.Option option : targetFrame.options) {
            if (option.label != null && option.label.contains("Runeclaw Bear")
                    && option.label.contains("p2")) {
                bearPlusPlayer = option;
                break;
            }
        }
        Assert.assertNotNull(bearPlusPlayer, "Bear+p2 target set must be offered");
        submit(session, targetFrame, bearPlusPlayer);
        DecisionFrame dividedFrame = null;
        for (int i = 0; i < 20; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "no frame parked awaiting divided allocation");
            if (parked.kind == DecisionFrame.Kind.DIVIDED_ALLOCATION
                    && parked.actorPlayerId.equals("p1")) {
                dividedFrame = parked;
                break;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting divided");
        }
        Assert.assertNotNull(dividedFrame, "DIVIDED_ALLOCATION never parked for Arc");
        Assert.assertEquals(dividedFrame.dividedTotal, 3, "Core total must be 3");
        Assert.assertEquals(dividedFrame.dividedMinPerTarget, 1, "CR 601.2d minimum is 1");
        Assert.assertEquals(dividedFrame.options.size(), 2, "two authoritative targets");
        assertDividedNegatives(session, dividedFrame);
        // Principal scoping: outsider sees no options while p1 owns the vector.
        final com.google.gson.JsonArray outsiderActions =
                StateProjection.legalActions(session).size() >= 0
                        ? StateProjection.gameState(session, "p2").getAsJsonArray("legal_actions")
                        : null;
        Assert.assertNotNull(outsiderActions);
        Assert.assertEquals(outsiderActions.size(), 0, "outsider must see no divided options");
        final com.google.gson.JsonObject outsiderMeta = StateProjection.bridgeMeta(session, "p2");
        Assert.assertTrue(outsiderMeta.get("pending_decision").isJsonNull(),
                "outsider must see no pending divided decision");
        final String bearId = findTargetOption(dividedFrame, "Runeclaw Bear");
        final String p2Id = findTargetOption(dividedFrame, "p2");
        Assert.assertNotEquals(bearId, p2Id, "target identities must be distinct");
        final Map<String, Integer> vector = new LinkedHashMap<>();
        vector.put(bearId, 2);
        vector.put(p2Id, 1);
        submitDivided(session, dividedFrame, vector);
        passToResolution(session, 40);
        boolean bearDead = true;
        for (Card card : session.getGame().getPlayers().get(1)
                .getCardsIn(ZoneType.Battlefield)) {
            if (card.getName().equals("Runeclaw Bear")) {
                bearDead = false;
            }
        }
        Assert.assertTrue(bearDead, "Bear must die to 2 divided damage (2/2)");
        Assert.assertEquals(session.getGame().getPlayers().get(1).getLife(), p2LifeBefore - 1,
                "p2 must take exactly the chosen 1 damage");
        boolean arcInGraveyard = false;
        for (Card card : session.getGame().getPlayers().get(0)
                .getCardsIn(ZoneType.Graveyard)) {
            if (card.getName().equals("Arc Lightning")) {
                arcInGraveyard = true;
            }
        }
        Assert.assertTrue(arcInGraveyard, "Arc must resolve natively to graveyard");
        session.shutdown(5000);
    }

    // ---- ARC LIGHTNING: second distinct legal allocation (1 to Bear, 2 to p2) ----

    @Test(timeOut = 300000)
    public void testArcLightningSplitOneToBear() {
        final BridgeTestSupport.ConstructedGame constructed = arcFixture("ws217-arc-1-2");
        final BridgeSession session = constructed.session;
        final int p2LifeBefore = session.getGame().getPlayers().get(1).getLife();
        final DecisionFrame targetFrame = castArc(session);
        DecisionFrame.Option bearPlusPlayer = null;
        for (DecisionFrame.Option option : targetFrame.options) {
            if (option.label != null && option.label.contains("Runeclaw Bear")
                    && option.label.contains("p2")) {
                bearPlusPlayer = option;
                break;
            }
        }
        Assert.assertNotNull(bearPlusPlayer, "Bear+p2 target set must be offered");
        submit(session, targetFrame, bearPlusPlayer);
        DecisionFrame dividedFrame = null;
        for (int i = 0; i < 20; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "no frame parked awaiting divided allocation");
            if (parked.kind == DecisionFrame.Kind.DIVIDED_ALLOCATION
                    && parked.actorPlayerId.equals("p1")) {
                dividedFrame = parked;
                break;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting divided");
        }
        Assert.assertNotNull(dividedFrame, "DIVIDED_ALLOCATION never parked for Arc");
        final String bearId = findTargetOption(dividedFrame, "Runeclaw Bear");
        final String p2Id = findTargetOption(dividedFrame, "p2");
        final Map<String, Integer> vector = new LinkedHashMap<>();
        vector.put(bearId, 1);
        vector.put(p2Id, 2);
        submitDivided(session, dividedFrame, vector);
        passToResolution(session, 40);
        boolean bearAlive = false;
        for (Card card : session.getGame().getPlayers().get(1)
                .getCardsIn(ZoneType.Battlefield)) {
            if (card.getName().equals("Runeclaw Bear")) {
                bearAlive = true;
            }
        }
        Assert.assertTrue(bearAlive, "Bear must survive 1 divided damage (2/2)");
        Assert.assertEquals(session.getGame().getPlayers().get(1).getLife(), p2LifeBefore - 2,
                "p2 must take exactly the chosen 2 damage");
        session.shutdown(5000);
    }

    // ---- SECOND ACTUAL CARD: Storm the Seedcore counters 3+1 (materially different) ----

    @Test(timeOut = 300000)
    public void testElvenRiteCountersSplit() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws217-rite");
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Llanowar Elves", ZoneType.Battlefield);
        for (int i = 0; i < 4; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Storm the Seedcore", ZoneType.Hand);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (int i = 0; i < 4; i++) {
            tapLand(session, "p1", "Forest");
        }
        for (int i = 0; i < 40; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "no frame parked awaiting Seedcore offering");
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                if (parked.actorPlayerId.equals("p1")) {
                    DecisionFrame.Option rite = null;
                    for (DecisionFrame.Option option : parked.options) {
                        if ("cast_spell".equals(option.actionType)
                                && "Storm the Seedcore".equals(option.sourceCardName)) {
                            rite = option;
                            break;
                        }
                    }
                    if (rite != null) {
                        submit(session, parked, rite);
                        break;
                    }
                }
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting Seedcore offering");
        }
        DecisionFrame targetFrame = null;
        for (int i = 0; i < 30; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "no frame parked awaiting Seedcore targets");
            if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
                targetFrame = parked;
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting Seedcore targets");
        }
        Assert.assertNotNull(targetFrame, "Seedcore TARGET_SELECTION never parked");
        DecisionFrame.Option both = null;
        for (DecisionFrame.Option option : targetFrame.options) {
            if (option.label != null && option.label.contains("Runeclaw Bear")
                    && option.label.contains("Llanowar Elves")) {
                both = option;
                break;
            }
        }
        Assert.assertNotNull(both, "Bear+Elves target set must be offered");
        submit(session, targetFrame, both);
        DecisionFrame dividedFrame = null;
        for (int i = 0; i < 20; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "no frame parked awaiting Seedcore divided");
            if (parked.kind == DecisionFrame.Kind.DIVIDED_ALLOCATION
                    && parked.actorPlayerId.equals("p1")) {
                dividedFrame = parked;
                break;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting Seedcore divided");
        }
        Assert.assertNotNull(dividedFrame, "DIVIDED_ALLOCATION never parked for Seedcore");
        Assert.assertEquals(dividedFrame.dividedTotal, 4, "Core counters total must be 4");
        final String bearId = findTargetOption(dividedFrame, "Runeclaw Bear");
        final String elvesId = findTargetOption(dividedFrame, "Llanowar Elves");
        final Map<String, Integer> vector = new LinkedHashMap<>();
        vector.put(bearId, 3);
        vector.put(elvesId, 1);
        submitDivided(session, dividedFrame, vector);
        passToResolution(session, 40);
        Card bear = null;
        Card elves = null;
        for (Card card : session.getGame().getPlayers().get(0)
                .getCardsIn(ZoneType.Battlefield)) {
            if (card.getName().equals("Runeclaw Bear")) {
                bear = card;
            }
            if (card.getName().equals("Llanowar Elves")) {
                elves = card;
            }
        }
        Assert.assertNotNull(bear, "Bear must remain on battlefield");
        Assert.assertNotNull(elves, "Elves must remain on battlefield");
        Assert.assertEquals(bear.getCounters(forge.game.card.CounterEnumType.P1P1), 3,
                "Bear must receive exactly 3 counters natively");
        Assert.assertEquals(elves.getCounters(forge.game.card.CounterEnumType.P1P1), 1,
                "Elves must receive exactly 1 counter natively");
        session.shutdown(5000);
    }

    // ---- NATIVE VALIDATION REMAINS AUTHORITATIVE (no bridge legality) ----

    @Test(timeOut = 300000)
    public void testNativeDividedValidation() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws217-native-validation");
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Llanowar Elves", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        final Player p1 = session.getGame().getPlayers().get(0);
        final List<GameEntity> bears = new ArrayList<>();
        for (Card card : session.getGame().getPlayers().get(1)
                .getCardsIn(ZoneType.Battlefield)) {
            bears.add(card);
        }
        Assert.assertEquals(bears.size(), 2, "fixture must hold two creatures");
        BridgeTestSupport.addCard(constructed.game, 0, "Arc Lightning", ZoneType.Hand);
        Card arcCard = null;
        for (Card card : session.getGame().getPlayers().get(0)
                .getCardsIn(ZoneType.Hand)) {
            if (card.getName().equals("Arc Lightning")) {
                arcCard = card;
                break;
            }
        }
        Assert.assertNotNull(arcCard, "Arc must be in hand");
        SpellAbility ability = null;
        for (SpellAbility sa : arcCard.getAllPossibleAbilities(p1, true)) {
            if (sa.isSpell()) {
                ability = sa;
                break;
            }
        }
        Assert.assertNotNull(ability, "Arc ability must exist natively");
        // Total too low (2 vs 3) fails before mutation.
        final DividedAllocationDecision tooLow = new DividedAllocationDecision(ability, 3,
                bears.subList(0, 2), false);
        final Map<GameEntity, Integer> lowMap = new LinkedHashMap<>();
        lowMap.put(bears.get(0), 1);
        lowMap.put(bears.get(1), 1);
        try {
            tooLow.apply(new DividedAllocationSelection(lowMap));
            throw new AssertionError("total too low must fail natively");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("TOO_LOW")
                    || expected.getMessage().contains("TOTAL"));
        }
        // Total too high (4 vs 3) fails before mutation.
        final Map<GameEntity, Integer> highMap = new LinkedHashMap<>();
        highMap.put(bears.get(0), 2);
        highMap.put(bears.get(1), 2);
        try {
            tooLow.apply(new DividedAllocationSelection(highMap));
            throw new AssertionError("total too high must fail natively");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("TOO_HIGH")
                    || expected.getMessage().contains("TOTAL"));
        }
        // Below minimum (0 where 1 required, CR 601.2d) fails.
        final Map<GameEntity, Integer> zeroMap = new LinkedHashMap<>();
        zeroMap.put(bears.get(0), 3);
        zeroMap.put(bears.get(1), 0);
        try {
            tooLow.apply(new DividedAllocationSelection(zeroMap));
            throw new AssertionError("illegal zero must fail natively");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("MINIMUM")
                    || expected.getMessage().contains("BELOW"));
        }
        // Unknown target (not in the authoritative decision) fails.
        final Map<GameEntity, Integer> foreignMap = new LinkedHashMap<>();
        foreignMap.put(bears.get(0), 2);
        foreignMap.put(p1, 1);
        try {
            tooLow.apply(new DividedAllocationSelection(foreignMap));
            throw new AssertionError("foreign target must fail natively");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("ILLEGAL_TARGET"));
        }
        // Missing target (count mismatch) fails.
        final Map<GameEntity, Integer> missingMap = new LinkedHashMap<>();
        missingMap.put(bears.get(0), 3);
        try {
            tooLow.apply(new DividedAllocationSelection(missingMap));
            throw new AssertionError("missing target must fail natively");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("COUNT"));
        }
        // No mutation on any failure: divided values stay absent.
        Assert.assertNull(ability.getDividedValue(bears.get(0)),
                "failed validation must not mutate Rules state");
        Assert.assertNull(ability.getDividedValue(bears.get(1)),
                "failed validation must not mutate Rules state");
        // Forced progress needs no controller: single target takes the total.
        ability.clearTargets();
        ability.getTargets().add(bears.get(0));
        final List<GameEntity> single = new ArrayList<>();
        single.add(bears.get(0));
        final DividedAllocationDecision forced = new DividedAllocationDecision(ability, 3,
                single, false);
        forced.resolve(null);
        Assert.assertEquals(ability.getDividedValue(bears.get(0)), Integer.valueOf(3),
                "single-target forced allocation must apply natively");
        session.shutdown(5000);
    }

    // ---- STALE ALLOCATION CANNOT BE REPLAYED ----

    @Test(timeOut = 300000)
    public void testStaleDividedRevisionFailsClosed() {
        final BridgeTestSupport.ConstructedGame constructed = arcFixture("ws217-arc-stale");
        final BridgeSession session = constructed.session;
        final DecisionFrame targetFrame = castArc(session);
        DecisionFrame.Option bearPlusPlayer = null;
        for (DecisionFrame.Option option : targetFrame.options) {
            if (option.label != null && option.label.contains("Runeclaw Bear")
                    && option.label.contains("p2")) {
                bearPlusPlayer = option;
                break;
            }
        }
        Assert.assertNotNull(bearPlusPlayer);
        submit(session, targetFrame, bearPlusPlayer);
        DecisionFrame dividedFrame = null;
        for (int i = 0; i < 20; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.DIVIDED_ALLOCATION) {
                dividedFrame = parked;
                break;
            }
            throw new AssertionError("unexpected " + parked.kind);
        }
        Assert.assertNotNull(dividedFrame);
        final long liveRevision = dividedFrame.revision;
        final String bearId = findTargetOption(dividedFrame, "Runeclaw Bear");
        final String p2Id = findTargetOption(dividedFrame, "p2");
        final Map<String, Integer> vector = new LinkedHashMap<>();
        vector.put(bearId, 2);
        vector.put(p2Id, 1);
        // Stale revision fails before touching Rules state.
        final BridgeSession.SubmitOutcome stale = session.submitDividedAllocation("p1",
                liveRevision - 1, vector);
        Assert.assertFalse(stale.applied);
        Assert.assertEquals(stale.errorCode, BridgeErrors.STALE_REVISION);
        // Live revision still answers exactly once with the authoritative vector.
        submitDivided(session, dividedFrame, vector);
        // Replay of the same vector against the consumed revision fails closed.
        final BridgeSession.SubmitOutcome replay = session.submitDividedAllocation("p1",
                liveRevision, vector);
        Assert.assertFalse(replay.applied);
        passToResolution(session, 40);
        session.shutdown(5000);
    }
}
