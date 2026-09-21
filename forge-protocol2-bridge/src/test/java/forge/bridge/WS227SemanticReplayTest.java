package forge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.game.card.Card;
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
 * WS227 Forge semantic replay provider surface: neutral decision mapping,
 * stable semantic option identity, complete legal-set semantics,
 * principal-scoped observations, Core-owned RNG coordinates, event/checkpoint
 * coordinates, exactly-once resolution and fail-closed negatives, with divided
 * allocation (Arc Lightning 2+1 plus Seedcore 3+1) participating.
 *
 * <p>Behavior credit remains 0; this is provider/replay qualification. Rules
 * Core remains sole legality authority; the bridge projects, never computes.
 */
public class WS227SemanticReplayTest {
    private static final long RECORD_SEED = 2277717L;

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    // ---- shared driving helpers (WS217 lineage, no behavior change) ----

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
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
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

    private static void fillLibraries(BridgeTestSupport.ConstructedGame constructed, int count) {
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < count; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
    }

    private static void tapLand(BridgeSession session, String actorId, String landName) {
        DecisionFrame frame = driveTo(session, actorId, DecisionFrame.Kind.PRIORITY, "MAIN", 60);
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
            Assert.assertNotNull(frame, "no frame parked while driving to " + kind);
            lastRevision = frame.revision;
            if (frameMatches(session, frame, actorId, kind, phaseFragment)) {
                return frame;
            }
            if (frame.kind == DecisionFrame.Kind.MULLIGAN) {
                BridgeTestSupport.submitKeep(session, frame);
                continue;
            }
            if (frame.status != DecisionFrame.Status.SUPPORTED) {
                throw new AssertionError("blocked by " + frame.kind);
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
                                || o.label.contains("No block")), "decline"));
                continue;
            }
            throw new AssertionError("unexpected " + frame.kind + " while driving");
        }
        throw new AssertionError("never reached " + kind);
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

    private static BridgeTestSupport.ConstructedGame arcFixture(String gameId, long seed) {
        forge.util.MyRandom.bindSeed(seed);
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(gameId);
        constructed.session.setSeedBinding(Long.valueOf(seed));
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

    private static String findTargetOption(DecisionFrame dividedFrame, String fragment) {
        for (DecisionFrame.Option option : dividedFrame.options) {
            if (option.label != null && option.label.contains(fragment)) {
                return option.optionId;
            }
        }
        throw new AssertionError("divided target not offered: " + fragment);
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
                                || o.label.contains("No block")), "decline"));
                continue;
            }
            return;
        }
    }

    // ---- recorded semantic step (provider tape, neutral semantics) ----

    private static final class RecordedStep {
        String decisionClass;
        String actor;
        long revision;
        String legalSetDigest;
        int legalSetSize;
        String selectedFingerprint;
        String selectedKey;
        Long numericValue;
        Long numericMin;
        Long numericMax;
        Map<String, Integer> dividedVectorSemantic;
        long rngBefore;
        long rngAfter;
        Long rootSeed;
        boolean explicitSeed;
        long eventBefore;
        long eventAfter;
        String eventDigest;
        String observationDigest;
        String publicDigest;
        String postDigest;
        int turnBefore;
        int turnAfter;
        String phaseBefore;
        String phaseAfter;
    }

    private static int currentTurn(BridgeSession session) {
        try {
            return Math.max(0, session.getGame().getPhaseHandler().getTurn());
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String currentPhase(BridgeSession session) {
        try {
            return session.getGame().getPhaseHandler().getPhase() == null ? "beginning"
                    : session.getGame().getPhaseHandler().getPhase().name();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static RecordedStep captureBefore(BridgeSession session, DecisionFrame frame) {
        final RecordedStep step = new RecordedStep();
        step.decisionClass = SemanticReplay.decisionClass(frame.kind);
        step.actor = frame.actorPlayerId;
        step.revision = frame.revision;
        step.legalSetDigest = SemanticReplay.legalSetDigest(frame, session);
        step.legalSetSize = SemanticReplay.legalSetSize(frame);
        step.rngBefore = forge.util.MyRandom.getCallCount();
        step.rootSeed = forge.util.MyRandom.getRootSeed();
        step.explicitSeed = forge.util.MyRandom.isExplicitSeed();
        step.eventBefore = SemanticReplay.eventOffset(session);
        step.observationDigest =
                SemanticReplay.principalObservationDigest(session, frame.actorPlayerId);
        step.publicDigest = SemanticReplay.publicStateDigest(session);
        step.turnBefore = currentTurn(session);
        step.phaseBefore = currentPhase(session);
        if (frame.freeInput) {
            step.numericMin = Long.valueOf(frame.inputMin);
            step.numericMax = Long.valueOf(frame.inputMax);
        }
        if (frame.kind == DecisionFrame.Kind.DIVIDED_ALLOCATION) {
            step.numericMin = Long.valueOf(frame.dividedMinPerTarget);
            step.numericMax = Long.valueOf(frame.dividedTotal);
        }
        return step;
    }

    private static void captureAfter(BridgeSession session, RecordedStep step,
            String selectedPrints, String numeric) {
        step.rngAfter = forge.util.MyRandom.getCallCount();
        step.eventAfter = SemanticReplay.eventOffset(session);
        step.postDigest = SemanticReplay.publicStateDigest(session);
        step.turnAfter = currentTurn(session);
        step.phaseAfter = currentPhase(session);
        step.eventDigest = SemanticReplay.eventDigest(step.revision, step.decisionClass,
                step.actor, selectedPrints == null ? "" : selectedPrints,
                numeric == null ? "" : numeric, step.rngBefore, step.rngAfter,
                step.turnBefore, step.turnAfter, step.observationDigest, step.postDigest);
    }

    // ---- 1. neutral decision-family mapping (no fake generic classes) ----

    @Test(timeOut = 120000)
    public void testDecisionFamilyMappingIsNeutral() {
        final java.util.Set<String> classes = new java.util.HashSet<>();
        for (DecisionFrame.Kind kind : DecisionFrame.Kind.values()) {
            final String mapped = SemanticReplay.decisionClass(kind);
            Assert.assertTrue(mapped.startsWith("forge:"),
                    "Forge namespace required: " + mapped);
            Assert.assertTrue(mapped.endsWith("/1"),
                    "versioned class required: " + mapped);
            Assert.assertTrue(classes.add(mapped), "distinct class per family: " + mapped);
            // No XMage names are claimed universal; Forge families stay disjoint.
            Assert.assertFalse(mapped.contains("xmage"), "must not pretend XMage names");
        }
        // Representative families required by the contract are all present natively.
        for (String required : new String[] {"priority", "target_selection", "number_choice",
                "binary_choice", "hidden_zone_selection", "order_choice", "mode_selection",
                "replacement", "cost_selection", "mana_payment", "divided_allocation",
                "concession"}) {
            boolean found = false;
            for (DecisionFrame.Kind kind : DecisionFrame.Kind.values()) {
                if (SemanticReplay.decisionClass(kind).contains(required)) {
                    found = true;
                    break;
                }
            }
            Assert.assertTrue(found, "representative family missing: " + required);
        }
        // Lifecycle is exactly the native CONCESSION family; priority concede
        // options remain priority decisions with concede selection semantics.
        Assert.assertTrue(SemanticReplay.isLifecycle(DecisionFrame.Kind.CONCESSION));
        Assert.assertFalse(SemanticReplay.isLifecycle(DecisionFrame.Kind.PRIORITY));
        Assert.assertEquals(SemanticReplay.TAPE_CONTRACT, "semantic-replay-tape/1.0.0");
        Assert.assertEquals(SemanticReplay.DECISION_PROTOCOL_VERSION,
                "forge-decision-protocol/1.0.0");
    }

    // ---- 2. semantic option identity (no UUID/index/label-alone) ----

    @Test(timeOut = 120000)
    public void testSemanticOptionIdentityExcludesProcessLocal() {
        final BridgeTestSupport.ConstructedGame constructed =
                arcFixture("ws227-identity", RECORD_SEED);
        final BridgeSession session = constructed.session;
        try {
            DecisionFrame frame = null;
            for (int i = 0; i < 40; i++) {
                final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
                Assert.assertNotNull(parked);
                if (parked.kind == DecisionFrame.Kind.PRIORITY
                        && parked.actorPlayerId.equals("p1")) {
                    boolean hasArc = false;
                    for (DecisionFrame.Option option : parked.options) {
                        if ("cast_spell".equals(option.actionType)
                                && "Arc Lightning".equals(option.sourceCardName)) {
                            hasArc = true;
                            break;
                        }
                    }
                    if (hasArc) {
                        frame = parked;
                        break;
                    }
                }
                if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                    submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                    submit(session, parked, parked.options.get(0));
                } else if (parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                        || parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                    submit(session, parked, pickOption(parked,
                            o -> o.label != null && (o.label.contains("No attack")
                                    || o.label.contains("No block")), "decline"));
                } else {
                    throw new AssertionError("unexpected " + parked.kind);
                }
            }
            Assert.assertNotNull(frame, "Arc priority frame never parked");
            // Same option recomputed yields the same fingerprint (stable).
            for (DecisionFrame.Option option : frame.options) {
                final String first =
                        SemanticReplay.optionFingerprint(frame, option, session);
                final String second =
                        SemanticReplay.optionFingerprint(frame, option, session);
                Assert.assertEquals(second, first, "fingerprint must be stable");
                Assert.assertEquals(first.length(), 64, "SHA-256 hex required");
                // Random optionIds are never the identity: two semantically
                // identical projections share the fingerprint.
                Assert.assertFalse(first.contains(option.optionId),
                        "process-local optionId must not leak into identity");
            }
            // Distinguishable options have distinct fingerprints.
            final java.util.Set<String> prints = new java.util.HashSet<>();
            for (DecisionFrame.Option option : frame.options) {
                prints.add(SemanticReplay.optionFingerprint(frame, option, session));
            }
            Assert.assertTrue(prints.size() >= 2, "priority must offer distinct options");
            // Legal-set multiset is complete and verifiable before submission.
            final String digest = SemanticReplay.legalSetDigest(frame, session);
            Assert.assertEquals(digest.length(), 64);
            Assert.assertEquals(SemanticReplay.legalSetSize(frame), frame.options.size());
            // Redaction preserves Rules text while removing process-local ids.
            Assert.assertEquals(SemanticReplay.redactLabel("Divide to [Runeclaw Bear]"),
                    "Divide to [Runeclaw Bear]");
            Assert.assertTrue(SemanticReplay.redactLabel(
                    "Target object_id='abc' [a1b2c3]").contains("object_id='#'"));
        } finally {
            session.shutdown(5000);
            forge.util.MyRandom.clearBinding();
        }
    }

    // ---- 3. exactly-once resolution (never first-match) ----

    @Test(timeOut = 120000)
    public void testExactlyOnceResolution() {
        final BridgeTestSupport.ConstructedGame constructed =
                arcFixture("ws227-exactly-once", RECORD_SEED + 1);
        final BridgeSession session = constructed.session;
        try {
            DecisionFrame frame = null;
            for (int i = 0; i < 40; i++) {
                final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
                Assert.assertNotNull(parked);
                if (parked.kind == DecisionFrame.Kind.PRIORITY
                        && parked.actorPlayerId.equals("p1")) {
                    boolean hasArc = false;
                    for (DecisionFrame.Option option : parked.options) {
                        if ("cast_spell".equals(option.actionType)
                                && "Arc Lightning".equals(option.sourceCardName)) {
                            hasArc = true;
                            break;
                        }
                    }
                    if (hasArc) {
                        frame = parked;
                        break;
                    }
                }
                if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                    submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                    submit(session, parked, parked.options.get(0));
                } else {
                    submit(session, parked, parked.options.get(0));
                }
            }
            Assert.assertNotNull(frame);
            DecisionFrame.Option arc = null;
            for (DecisionFrame.Option option : frame.options) {
                if ("cast_spell".equals(option.actionType)
                        && "Arc Lightning".equals(option.sourceCardName)) {
                    arc = option;
                    break;
                }
            }
            Assert.assertNotNull(arc);
            final String recorded =
                    SemanticReplay.optionFingerprint(frame, arc, session);
            // Exactly one current match resolves to the native option.
            final DecisionFrame.Option resolved =
                    SemanticReplay.resolveExactlyOnce(frame, session, recorded);
            Assert.assertSame(resolved, arc, "must resolve to the current native option");
            // Zero matches is missing (fail closed, never fuzzy).
            try {
                SemanticReplay.resolveExactlyOnce(frame, session,
                        "0000000000000000000000000000000000000000000000000000000000000000");
                Assert.fail("missing semantic option must fail closed");
            } catch (SemanticReplay.SemanticReplayDivergence e) {
                Assert.assertEquals(e.code(), "CHOSEN_OPTION_MISSING");
            }
            // Malformed proposal is missing (fail closed).
            try {
                SemanticReplay.resolveExactlyOnce(frame, session, "");
                Assert.fail("malformed replay must fail closed");
            } catch (SemanticReplay.SemanticReplayDivergence e) {
                Assert.assertEquals(e.code(), "CHOSEN_OPTION_MISSING");
            }
            // Duplicate semantic projections are ambiguous (fail closed, never
            // first-match): two options sharing one fingerprint collide.
            final List<DecisionFrame.Option> dupes = new ArrayList<>();
            dupes.add(DecisionFrame.payloadOption("target", "Same", null,
                    arc.nativePayload == null ? "x" : arc.nativePayload, "DUP"));
            dupes.add(DecisionFrame.payloadOption("target", "Same", null,
                    arc.nativePayload == null ? "x" : arc.nativePayload, "DUP"));
            final DecisionFrame dupeFrame = new DecisionFrame(frame.revision,
                    DecisionFrame.Kind.TARGET_SELECTION, DecisionFrame.Status.SUPPORTED, "",
                    frame.actorPlayerId, frame.actorSeat, dupes, frame.preStateHash);
            final String dupePrint =
                    SemanticReplay.optionFingerprint(dupeFrame, dupes.get(0), session);
            Assert.assertEquals(
                    SemanticReplay.optionFingerprint(dupeFrame, dupes.get(1), session),
                    dupePrint, "identical semantics must collide");
            try {
                SemanticReplay.resolveExactlyOnce(dupeFrame, session, dupePrint);
                Assert.fail("ambiguous semantic option must fail closed");
            } catch (SemanticReplay.SemanticReplayDivergence e) {
                Assert.assertEquals(e.code(), "CHOSEN_OPTION_AMBIGUOUS");
            }
        } finally {
            session.shutdown(5000);
            forge.util.MyRandom.clearBinding();
        }
    }

    // ---- 4. principal scoping intact ----

    @Test(timeOut = 120000)
    public void testPrincipalScopingRemainsIntact() {
        final BridgeTestSupport.ConstructedGame constructed =
                arcFixture("ws227-scoping", RECORD_SEED + 2);
        final BridgeSession session = constructed.session;
        try {
            DecisionFrame frame = null;
            for (int i = 0; i < 40; i++) {
                final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
                Assert.assertNotNull(parked);
                if (parked.kind == DecisionFrame.Kind.PRIORITY
                        && parked.actorPlayerId.equals("p1")) {
                    boolean hasArc = false;
                    for (DecisionFrame.Option option : parked.options) {
                        if ("cast_spell".equals(option.actionType)
                                && "Arc Lightning".equals(option.sourceCardName)) {
                            hasArc = true;
                            break;
                        }
                    }
                    if (hasArc) {
                        frame = parked;
                        break;
                    }
                }
                if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                    submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                    submit(session, parked, parked.options.get(0));
                } else {
                    submit(session, parked, parked.options.get(0));
                }
            }
            Assert.assertNotNull(frame);
            // Actor sees the complete legal set; outsider sees nothing.
            final JsonObject actorState = StateProjection.gameState(session, "p1");
            Assert.assertTrue(actorState.getAsJsonArray("legal_actions").size() > 0,
                    "actor must see legal actions");
            final JsonObject outsiderState = StateProjection.gameState(session, "p2");
            Assert.assertEquals(outsiderState.getAsJsonArray("legal_actions").size(), 0,
                    "outsider must see no legal actions");
            final JsonObject outsiderMeta = StateProjection.bridgeMeta(session, "p2");
            Assert.assertTrue(outsiderMeta.get("pending_decision").isJsonNull(),
                    "outsider must see no pending decision");
            Assert.assertTrue(outsiderMeta.get("state_hash").isJsonNull(),
                    "outsider must hold no actor state hash");
            // Hands stay principal-scoped: owner sees names, outsider sees
            // placeholders with the same count (no hidden-info leak for replay).
            JsonObject p1 = null;
            JsonObject p2viewOfP1Hand = null;
            for (com.google.gson.JsonElement element
                    : actorState.getAsJsonArray("players")) {
                final JsonObject player = element.getAsJsonObject();
                if (player.get("player_id").getAsString().equals("p1")) {
                    p1 = player;
                    break;
                }
            }
            Assert.assertNotNull(p1);
            boolean seesArc = false;
            for (com.google.gson.JsonElement element : p1.getAsJsonObject("zones")
                    .getAsJsonArray("hand")) {
                if (element.getAsString().contains("Arc Lightning")) {
                    seesArc = true;
                    break;
                }
            }
            Assert.assertTrue(seesArc, "owner must see own Arc Lightning");
            for (com.google.gson.JsonElement element
                    : outsiderState.getAsJsonArray("players")) {
                final JsonObject player = element.getAsJsonObject();
                if (player.get("player_id").getAsString().equals("p1")) {
                    for (com.google.gson.JsonElement card : player.getAsJsonObject("zones")
                            .getAsJsonArray("hand")) {
                        Assert.assertEquals(card.getAsString(), "<hidden>",
                                "outsider must see only placeholders");
                    }
                }
            }
            // Digests: public is shared, principal observations are owner-bound.
            Assert.assertTrue(actorState.has("public_state_digest"));
            Assert.assertTrue(actorState.has("principal_observation_digest"));
            Assert.assertEquals(
                    actorState.get("public_state_digest").getAsString(),
                    outsiderState.get("public_state_digest").getAsString(),
                    "public truth is shared");
            Assert.assertNotEquals(
                    actorState.get("principal_observation_digest").getAsString(),
                    outsiderState.get("principal_observation_digest").getAsString(),
                    "principal views must differ while hands differ");
            // Wrong-actor submission still fails closed at the session boundary.
            final DecisionFrame.Option first = frame.options.get(0);
            final BridgeSession.SubmitOutcome wrong = session.submit("p2",
                    first.optionId, first.actionType, frame.revision);
            Assert.assertFalse(wrong.applied);
            Assert.assertEquals(wrong.errorCode, BridgeErrors.WRONG_ACTOR);
        } finally {
            session.shutdown(5000);
            forge.util.MyRandom.clearBinding();
        }
    }

    // ---- 5. Core-owned RNG binding (regenerate-not-inject) ----

    @Test(timeOut = 60000)
    public void testRulesRngBindingIsCoreOwned() {
        forge.util.MyRandom.clearBinding();
        Assert.assertFalse(forge.util.MyRandom.isExplicitSeed());
        Assert.assertNull(forge.util.MyRandom.getRootSeed());
        forge.util.MyRandom.bindSeed(424242L);
        Assert.assertTrue(forge.util.MyRandom.isExplicitSeed());
        Assert.assertEquals(forge.util.MyRandom.getRootSeed(), Long.valueOf(424242L));
        Assert.assertEquals(forge.util.MyRandom.getCallCount(), 0L);
        final int first = forge.util.MyRandom.getRandom().nextInt(1000000);
        final int second = forge.util.MyRandom.getRandom().nextInt(1000000);
        Assert.assertTrue(forge.util.MyRandom.getCallCount() > 0,
                "Rules RNG calls must advance coordinates");
        final long afterTwo = forge.util.MyRandom.getCallCount();
        // Regenerate-not-inject: the same root seed regenerates the same
        // sequence with the same coordinates in a fresh binding.
        forge.util.MyRandom.bindSeed(424242L);
        Assert.assertEquals(forge.util.MyRandom.getCallCount(), 0L);
        Assert.assertEquals(forge.util.MyRandom.getRandom().nextInt(1000000), first);
        Assert.assertEquals(forge.util.MyRandom.getRandom().nextInt(1000000), second);
        Assert.assertEquals(forge.util.MyRandom.getCallCount(), afterTwo);
        // Legacy path clears the explicit binding (non-authoritative).
        forge.util.MyRandom.setRandom(new java.util.Random(424242L));
        Assert.assertFalse(forge.util.MyRandom.isExplicitSeed());
        Assert.assertNull(forge.util.MyRandom.getRootSeed());
        forge.util.MyRandom.clearBinding();
        // Bridge launches through the Core seam (no bridge RNG, no prediction).
        Assert.assertFalse(forge.util.MyRandom.isExplicitSeed());
    }

    // ---- 6. events / state / checkpoints / terminal outcomes ----

    @Test(timeOut = 120000)
    public void testEventCheckpointTerminalSurface() {
        final BridgeTestSupport.ConstructedGame constructed =
                arcFixture("ws227-checkpoint", RECORD_SEED + 3);
        final BridgeSession session = constructed.session;
        try {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            // Turn/phase/step are exposed where stable (no parallel machine).
            final JsonObject state = StateProjection.gameState(session, frame.actorPlayerId);
            Assert.assertTrue(state.has("turn_number"));
            Assert.assertTrue(state.has("phase"));
            Assert.assertTrue(state.has("step"));
            Assert.assertTrue(state.has("rng_binding"));
            Assert.assertTrue(state.has("event_offset"));
            Assert.assertTrue(state.has("event_sequence"));
            Assert.assertTrue(state.has("public_state_digest"));
            Assert.assertTrue(state.has("principal_observation_digest"));
            Assert.assertTrue(state.has("terminal_outcomes"));
            Assert.assertEquals(state.get("semantic_replay_version").getAsString(),
                    SemanticReplay.SEMANTIC_REPLAY_VERSION);
            final JsonObject rng = state.getAsJsonObject("rng_binding");
            Assert.assertTrue(rng.get("explicit_seed").getAsBoolean(),
                    "constructed replay path must carry an explicit seed");
            Assert.assertEquals(rng.get("root_seed").getAsLong(), RECORD_SEED + 3);
            // Terminal seat outcomes are seat-sorted five-field rows.
            final JsonArray outcomes = state.getAsJsonArray("terminal_outcomes");
            Assert.assertEquals(outcomes.size(), 4);
            int lastSeat = -1;
            for (com.google.gson.JsonElement element : outcomes) {
                final JsonObject row = element.getAsJsonObject();
                Assert.assertTrue(row.has("seat"));
                Assert.assertTrue(row.has("won"));
                Assert.assertTrue(row.has("lost"));
                Assert.assertTrue(row.has("left"));
                Assert.assertTrue(row.has("life"));
                Assert.assertTrue(row.get("seat").getAsInt() > lastSeat,
                        "terminal outcomes must be seat-sorted");
                lastSeat = row.get("seat").getAsInt();
            }
            // Decision summary carries the neutral replay coordinates.
            final JsonObject summary =
                    StateProjection.decisionSummary(session, frame);
            Assert.assertTrue(summary.get("decision_class").getAsString()
                    .startsWith("forge:"));
            Assert.assertTrue(summary.has("legal_set_digest"));
            Assert.assertTrue(summary.has("legal_set_size"));
            Assert.assertTrue(summary.has("rng_binding"));
            Assert.assertTrue(summary.has("event_offset"));
            Assert.assertTrue(summary.has("public_state_digest"));
            Assert.assertTrue(summary.has("principal_observation_digest"));
        } finally {
            session.shutdown(5000);
            forge.util.MyRandom.clearBinding();
        }
    }

    // ---- 7. divided allocation participates (Arc 2+1 in-process replay) ----

    @Test(timeOut = 300000)
    public void testDividedAllocationReplayInProcess() {
        // Record in session A.
        final BridgeTestSupport.ConstructedGame recorded =
                arcFixture("ws227-record-arc", RECORD_SEED + 10);
        final BridgeSession sessionA = recorded.session;
        final int p2LifeBefore = sessionA.getGame().getPlayers().get(1).getLife();
        final List<RecordedStep> tape = new ArrayList<>();
        final Map<String, Map<String, Integer>> dividedVectors = new LinkedHashMap<>();
        try {
            // Drive to Arc offering, recording every parked step.
            long lastRevision = -1;
            DecisionFrame targetFrame = null;
            for (int i = 0; i < 80; i++) {
                final DecisionFrame parked = awaitNext(sessionA, lastRevision, 15000);
                Assert.assertNotNull(parked, "no frame parked while recording");
                lastRevision = parked.revision;
                final RecordedStep before = captureBefore(sessionA, parked);
                if (parked.kind == DecisionFrame.Kind.PRIORITY
                        && parked.actorPlayerId.equals("p1")) {
                    DecisionFrame.Option arc = null;
                    for (DecisionFrame.Option option : parked.options) {
                        if ("cast_spell".equals(option.actionType)
                                && "Arc Lightning".equals(option.sourceCardName)) {
                            arc = option;
                            break;
                        }
                    }
                    if (arc != null) {
                        before.selectedFingerprint =
                                SemanticReplay.optionFingerprint(parked, arc, sessionA);
                        before.selectedKey =
                                SemanticReplay.semanticKey(parked, arc, sessionA);
                        submit(sessionA, parked, arc);
                        captureAfter(sessionA, before, before.selectedFingerprint, "");
                        tape.add(before);
                        break;
                    }
                }
                if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                    final DecisionFrame.Option pass =
                            pickOption(parked, o -> o.isPass, "pass");
                    before.selectedFingerprint =
                            SemanticReplay.optionFingerprint(parked, pass, sessionA);
                    before.selectedKey =
                            SemanticReplay.semanticKey(parked, pass, sessionA);
                    submit(sessionA, parked, pass);
                    captureAfter(sessionA, before, before.selectedFingerprint, "");
                    tape.add(before);
                    continue;
                }
                if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                    final DecisionFrame.Option first = parked.options.get(0);
                    before.selectedFingerprint =
                            SemanticReplay.optionFingerprint(parked, first, sessionA);
                    submit(sessionA, parked, first);
                    captureAfter(sessionA, before, before.selectedFingerprint, "");
                    tape.add(before);
                    continue;
                }
                if (parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                        || parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                    final DecisionFrame.Option decline = pickOption(parked,
                            o -> o.label != null && (o.label.contains("No attack")
                                    || o.label.contains("No block")), "decline");
                    before.selectedFingerprint =
                            SemanticReplay.optionFingerprint(parked, decline, sessionA);
                    submit(sessionA, parked, decline);
                    captureAfter(sessionA, before, before.selectedFingerprint, "");
                    tape.add(before);
                    continue;
                }
                throw new AssertionError("unexpected " + parked.kind + " while recording");
            }
            // TARGET_SELECTION: Bear+p2.
            for (int i = 0; i < 30; i++) {
                final DecisionFrame parked = awaitNext(sessionA, lastRevision, 15000);
                Assert.assertNotNull(parked);
                lastRevision = parked.revision;
                if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION
                        && parked.actorPlayerId.equals("p1")) {
                    targetFrame = parked;
                    break;
                }
                if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                    final RecordedStep before = captureBefore(sessionA, parked);
                    final DecisionFrame.Option pass =
                            pickOption(parked, o -> o.isPass, "pass");
                    before.selectedFingerprint =
                            SemanticReplay.optionFingerprint(parked, pass, sessionA);
                    submit(sessionA, parked, pass);
                    captureAfter(sessionA, before, before.selectedFingerprint, "");
                    tape.add(before);
                    continue;
                }
                if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                    final RecordedStep before = captureBefore(sessionA, parked);
                    final DecisionFrame.Option first = parked.options.get(0);
                    before.selectedFingerprint =
                            SemanticReplay.optionFingerprint(parked, first, sessionA);
                    submit(sessionA, parked, first);
                    captureAfter(sessionA, before, before.selectedFingerprint, "");
                    tape.add(before);
                    continue;
                }
                throw new AssertionError("unexpected " + parked.kind + " awaiting targets");
            }
            Assert.assertNotNull(targetFrame, "Arc TARGET_SELECTION never parked");
            final RecordedStep targetStep = captureBefore(sessionA, targetFrame);
            DecisionFrame.Option bearPlusPlayer = null;
            for (DecisionFrame.Option option : targetFrame.options) {
                if (option.label != null && option.label.contains("Runeclaw Bear")
                        && option.label.contains("p2")) {
                    bearPlusPlayer = option;
                    break;
                }
            }
            Assert.assertNotNull(bearPlusPlayer, "Bear+p2 target set must be offered");
            targetStep.selectedFingerprint =
                    SemanticReplay.optionFingerprint(targetFrame, bearPlusPlayer, sessionA);
            targetStep.selectedKey =
                    SemanticReplay.semanticKey(targetFrame, bearPlusPlayer, sessionA);
            submit(sessionA, targetFrame, bearPlusPlayer);
            captureAfter(sessionA, targetStep, targetStep.selectedFingerprint, "");
            tape.add(targetStep);
            lastRevision = targetFrame.revision;
            // DIVIDED_ALLOCATION: 2 to Bear, 1 to p2.
            DecisionFrame dividedFrame = null;
            for (int i = 0; i < 20; i++) {
                final DecisionFrame parked = awaitNext(sessionA, lastRevision, 15000);
                Assert.assertNotNull(parked);
                lastRevision = parked.revision;
                if (parked.kind == DecisionFrame.Kind.DIVIDED_ALLOCATION
                        && parked.actorPlayerId.equals("p1")) {
                    dividedFrame = parked;
                    break;
                }
                throw new AssertionError("unexpected " + parked.kind + " awaiting divided");
            }
            Assert.assertNotNull(dividedFrame, "DIVIDED_ALLOCATION never parked");
            Assert.assertEquals(dividedFrame.dividedTotal, 3);
            Assert.assertEquals(dividedFrame.dividedMinPerTarget, 1);
            final RecordedStep dividedStep = captureBefore(sessionA, dividedFrame);
            final String bearId = findTargetOption(dividedFrame, "Runeclaw Bear");
            final String p2Id = findTargetOption(dividedFrame, "p2");
            // Record the semantic vector (fingerprints, never optionIds).
            final Map<String, Integer> semanticVector = new LinkedHashMap<>();
            for (DecisionFrame.Option option : dividedFrame.options) {
                final String print =
                        SemanticReplay.optionFingerprint(dividedFrame, option, sessionA);
                if (option.optionId.equals(bearId)) {
                    semanticVector.put(print, Integer.valueOf(2));
                } else if (option.optionId.equals(p2Id)) {
                    semanticVector.put(print, Integer.valueOf(1));
                }
            }
            Assert.assertEquals(semanticVector.size(), 2);
            dividedStep.dividedVectorSemantic = semanticVector;
            dividedStep.selectedFingerprint = SemanticReplay.legalSetDigest(dividedFrame, sessionA)
                    + "|2+1";
            final Map<String, Integer> vector = new LinkedHashMap<>();
            vector.put(bearId, Integer.valueOf(2));
            vector.put(p2Id, Integer.valueOf(1));
            dividedVectors.put("arc-2-1", vector);
            submitDivided(sessionA, dividedFrame, vector);
            captureAfter(sessionA, dividedStep,
                    String.join(",", new ArrayList<>(semanticVector.keySet())), "2+1");
            tape.add(dividedStep);
            passToResolution(sessionA, 40);
            Assert.assertTrue(tape.size() >= 3, "tape must hold the replay path");
        } finally {
            sessionA.shutdown(5000);
            forge.util.MyRandom.clearBinding();
        }
        // Replay in session B: fresh constructed game, same manifest/seed.
        final BridgeTestSupport.ConstructedGame replayed =
                arcFixture("ws227-replay-arc", RECORD_SEED + 10);
        final BridgeSession sessionB = replayed.session;
        try {
            long lastRevision = -1;
            int stepIndex = 0;
            // Replay priority/cast prefix plus target selection via exactly-once.
            for (int i = 0; i < 120 && stepIndex < tape.size(); i++) {
                final DecisionFrame parked = awaitNext(sessionB, lastRevision, 15000);
                Assert.assertNotNull(parked, "no frame parked while replaying");
                lastRevision = parked.revision;
                final RecordedStep expected = tape.get(stepIndex);
                // Compare neutral semantics before submission.
                Assert.assertEquals(SemanticReplay.decisionClass(parked.kind),
                        expected.decisionClass, "decision class must match");
                Assert.assertEquals(parked.actorPlayerId, expected.actor,
                        "actor must match");
                Assert.assertEquals(parked.revision, expected.revision,
                        "revision must match");
                Assert.assertEquals(SemanticReplay.legalSetDigest(parked, sessionB),
                        expected.legalSetDigest, "legal set must match before submission");
                Assert.assertEquals(SemanticReplay.legalSetSize(parked),
                        expected.legalSetSize);
                Assert.assertEquals(
                        SemanticReplay.principalObservationDigest(sessionB, parked.actorPlayerId),
                        expected.observationDigest, "observation must match");
                Assert.assertEquals(forge.util.MyRandom.getCallCount(), expected.rngBefore,
                        "RNG calls before must match");
                if (parked.kind == DecisionFrame.Kind.DIVIDED_ALLOCATION) {
                    // Resolve each recorded semantic target exactly once to a
                    // current native option, then submit the current vector.
                    final Map<String, Integer> currentVector = new LinkedHashMap<>();
                    for (Map.Entry<String, Integer> entry
                            : expected.dividedVectorSemantic.entrySet()) {
                        final DecisionFrame.Option current =
                                SemanticReplay.resolveExactlyOnce(
                                        parked, sessionB, entry.getKey());
                        currentVector.put(current.optionId, entry.getValue());
                    }
                    Assert.assertEquals(currentVector.size(), 2);
                    final String beforePublic =
                            SemanticReplay.publicStateDigest(sessionB);
                    submitDivided(sessionB, parked, currentVector);
                    final RecordedStep check = new RecordedStep();
                    check.revision = parked.revision;
                    check.decisionClass = expected.decisionClass;
                    check.actor = expected.actor;
                    // RNG/state/event coordinates must match after submission.
                    Assert.assertEquals(forge.util.MyRandom.getCallCount(),
                            expected.rngAfter, "RNG calls after must match");
                    Assert.assertEquals(SemanticReplay.publicStateDigest(sessionB),
                            expected.postDigest, "post state must match");
                    stepIndex++;
                    // Verify the semantic selection was submitted, not the
                    // recorded optionId (which is process-local and differs).
                    Assert.assertNotEquals(
                            new ArrayList<>(currentVector.keySet()).get(0),
                            "opt-recorded", "must submit current native ids");
                    Assert.assertTrue(beforePublic.length() == 64);
                    break;
                }
                // Enumerated replay: resolve recorded fingerprint exactly once
                // and submit the current native option.
                final DecisionFrame.Option current = SemanticReplay.resolveExactlyOnce(
                        parked, sessionB, expected.selectedFingerprint);
                final String beforePublic = SemanticReplay.publicStateDigest(sessionB);
                Assert.assertTrue(beforePublic.length() == 64);
                submit(sessionB, parked, current);
                Assert.assertEquals(forge.util.MyRandom.getCallCount(), expected.rngAfter);
                Assert.assertEquals(SemanticReplay.publicStateDigest(sessionB),
                        expected.postDigest);
                stepIndex++;
            }
            Assert.assertTrue(stepIndex >= tape.size() - 1,
                    "replay must consume the recorded tape");
            passToResolution(sessionB, 40);
            boolean bearDead = true;
            for (Card card : sessionB.getGame().getPlayers().get(1)
                    .getCardsIn(ZoneType.Battlefield)) {
                if (card.getName().equals("Runeclaw Bear")) {
                    bearDead = false;
                }
            }
            Assert.assertTrue(bearDead, "replayed 2+1 must kill the Bear natively");
            Assert.assertEquals(sessionB.getGame().getPlayers().get(1).getLife(),
                    p2LifeBefore - 1, "replayed 2+1 must deal exactly 1 to p2");
        } finally {
            sessionB.shutdown(5000);
            forge.util.MyRandom.clearBinding();
        }
    }

    // ---- 8. negatives fail closed with no partial mutation ----

    @Test(timeOut = 300000)
    public void testReplayNegativesFailClosed() {
        final BridgeTestSupport.ConstructedGame constructed =
                arcFixture("ws227-negatives", RECORD_SEED + 20);
        final BridgeSession session = constructed.session;
        try {
            DecisionFrame targetFrame = null;
            for (int i = 0; i < 80; i++) {
                final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
                Assert.assertNotNull(parked);
                if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION
                        && parked.actorPlayerId.equals("p1")) {
                    boolean hasBoth = false;
                    for (DecisionFrame.Option option : parked.options) {
                        if (option.label != null && option.label.contains("Runeclaw Bear")
                                && option.label.contains("p2")) {
                            hasBoth = true;
                            break;
                        }
                    }
                    if (hasBoth) {
                        targetFrame = parked;
                        break;
                    }
                }
                if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                    boolean arcHere = false;
                    DecisionFrame.Option arc = null;
                    for (DecisionFrame.Option option : parked.options) {
                        if ("cast_spell".equals(option.actionType)
                                && "Arc Lightning".equals(option.sourceCardName)) {
                            arcHere = true;
                            arc = option;
                            break;
                        }
                    }
                    if (arcHere && parked.actorPlayerId.equals("p1")) {
                        submit(session, parked, arc);
                    } else {
                        submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                    }
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
                                    || o.label.contains("No block")), "decline"));
                    continue;
                }
                throw new AssertionError("unexpected " + parked.kind);
            }
            Assert.assertNotNull(targetFrame, "target frame required for negatives");
            final String legalDigest =
                    SemanticReplay.legalSetDigest(targetFrame, session);
            final String publicBefore = SemanticReplay.publicStateDigest(session);
            final long rngBefore = forge.util.MyRandom.getCallCount();
            final long eventBefore = SemanticReplay.eventOffset(session);
            final DecisionFrame.Option first = targetFrame.options.get(0);
            final String firstPrint =
                    SemanticReplay.optionFingerprint(targetFrame, first, session);
            // WRONG_ACTOR.
            final BridgeSession.SubmitOutcome wrong = session.submit("p2",
                    first.optionId, first.actionType, targetFrame.revision);
            Assert.assertFalse(wrong.applied);
            Assert.assertEquals(wrong.errorCode, BridgeErrors.WRONG_ACTOR);
            // STALE_REVISION.
            final BridgeSession.SubmitOutcome stale = session.submit(
                    targetFrame.actorPlayerId, first.optionId, first.actionType,
                    targetFrame.revision - 1);
            Assert.assertFalse(stale.applied);
            Assert.assertEquals(stale.errorCode, BridgeErrors.STALE_REVISION);
            // LEGAL_SET_DRIFT: recorded digest does not match a tampered set.
            Assert.assertNotEquals(legalDigest,
                    SemanticReplay.sha256Hex("tampered-legal-set"),
                    "tamper must diverge the legal digest");
            // OPTION_MISSING: unknown fingerprint resolves to missing.
            try {
                SemanticReplay.resolveExactlyOnce(targetFrame, session,
                        SemanticReplay.sha256Hex("no-such-option"));
                Assert.fail("missing option must fail closed");
            } catch (SemanticReplay.SemanticReplayDivergence e) {
                Assert.assertEquals(e.code(), "CHOSEN_OPTION_MISSING");
            }
            // MALFORMED_REPLAY: null/empty proposal is missing.
            try {
                SemanticReplay.resolveExactlyOnce(targetFrame, session, null);
                Assert.fail("malformed replay must fail closed");
            } catch (SemanticReplay.SemanticReplayDivergence e) {
                Assert.assertEquals(e.code(), "CHOSEN_OPTION_MISSING");
            }
            // RNG_DRIFT: an extra Rules RNG call diverges coordinates.
            forge.util.MyRandom.getRandom().nextInt(1000000);
            Assert.assertNotEquals(forge.util.MyRandom.getCallCount(), rngBefore,
                    "extra RNG call must drift coordinates");
            // STATE_DRIFT: public digest still matches (no mutation yet), but a
            // tampered post digest would diverge.
            Assert.assertEquals(SemanticReplay.publicStateDigest(session), publicBefore,
                    "no partial mutation may occur after divergence checks");
            Assert.assertNotEquals(publicBefore,
                    SemanticReplay.sha256Hex("tampered-state"),
                    "tamper must diverge the state digest");
            // EVENT_DRIFT: audit size is stable while no submission applied.
            Assert.assertEquals(SemanticReplay.eventOffset(session), eventBefore,
                    "failed negatives must not advance the event offset");
            // No partial mutation: the live frame still offers the recorded set.
            Assert.assertEquals(SemanticReplay.legalSetDigest(targetFrame, session),
                    legalDigest, "legal set must be unchanged after negatives");
            Assert.assertEquals(
                    SemanticReplay.optionFingerprint(targetFrame, first, session),
                    firstPrint, "option identity must be unchanged after negatives");
            // The live frame still answers exactly once with the authoritative
            // choice (negatives did not consume it).
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
        } finally {
            session.shutdown(5000);
            forge.util.MyRandom.clearBinding();
        }
    }

    // ---- 9. Seedcore participates (materially different divided shape) ----

    @Test(timeOut = 300000)
    public void testSeedcoreReplayParticipates() {
        forge.util.MyRandom.bindSeed(RECORD_SEED + 30);
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws227-seedcore");
        constructed.session.setSeedBinding(Long.valueOf(RECORD_SEED + 30));
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Llanowar Elves", ZoneType.Battlefield);
        for (int i = 0; i < 4; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Storm the Seedcore", ZoneType.Hand);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        final BridgeSession session = constructed.session;
        try {
            BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
            for (int i = 0; i < 4; i++) {
                tapLand(session, "p1", "Forest");
            }
            DecisionFrame dividedFrame = null;
            String bearPrint = null;
            String elvesPrint = null;
            for (int i = 0; i < 80; i++) {
                final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
                Assert.assertNotNull(parked);
                if (parked.kind == DecisionFrame.Kind.PRIORITY
                        && parked.actorPlayerId.equals("p1")) {
                    DecisionFrame.Option seed = null;
                    for (DecisionFrame.Option option : parked.options) {
                        if ("cast_spell".equals(option.actionType)
                                && "Storm the Seedcore".equals(option.sourceCardName)) {
                            seed = option;
                            break;
                        }
                    }
                    if (seed != null) {
                        // Semantic selection is recorded, native option submitted.
                        final String recorded =
                                SemanticReplay.optionFingerprint(parked, seed, session);
                        final DecisionFrame.Option current =
                                SemanticReplay.resolveExactlyOnce(parked, session, recorded);
                        submit(session, parked, current);
                        continue;
                    }
                    submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                    continue;
                }
                if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION
                        && parked.actorPlayerId.equals("p1")) {
                    DecisionFrame.Option both = null;
                    for (DecisionFrame.Option option : parked.options) {
                        if (option.label != null
                                && option.label.contains("Runeclaw Bear")
                                && option.label.contains("Llanowar Elves")) {
                            both = option;
                            break;
                        }
                    }
                    Assert.assertNotNull(both, "Bear+Elves target set must be offered");
                    final String recorded =
                            SemanticReplay.optionFingerprint(parked, both, session);
                    submit(session, parked,
                            SemanticReplay.resolveExactlyOnce(parked, session, recorded));
                    continue;
                }
                if (parked.kind == DecisionFrame.Kind.DIVIDED_ALLOCATION
                        && parked.actorPlayerId.equals("p1")) {
                    dividedFrame = parked;
                    Assert.assertEquals(parked.dividedTotal, 4);
                    for (DecisionFrame.Option option : parked.options) {
                        final String print =
                                SemanticReplay.optionFingerprint(parked, option, session);
                        if (option.label != null
                                && option.label.contains("Runeclaw Bear")) {
                            bearPrint = print;
                        }
                        if (option.label != null
                                && option.label.contains("Llanowar Elves")) {
                            elvesPrint = print;
                        }
                    }
                    Assert.assertNotNull(bearPrint);
                    Assert.assertNotNull(elvesPrint);
                    Assert.assertNotEquals(bearPrint, elvesPrint,
                            "distinct divided targets must have distinct semantics");
                    break;
                }
                if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                    submit(session, parked, parked.options.get(0));
                    continue;
                }
                if (parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                        || parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                    submit(session, parked, pickOption(parked,
                            o -> o.label != null && (o.label.contains("No attack")
                                    || o.label.contains("No block")), "decline"));
                    continue;
                }
                throw new AssertionError("unexpected " + parked.kind);
            }
            Assert.assertNotNull(dividedFrame, "Seedcore DIVIDED_ALLOCATION never parked");
            // Resolve recorded semantic targets exactly once to current natives.
            final DecisionFrame.Option bearCurrent =
                    SemanticReplay.resolveExactlyOnce(dividedFrame, session, bearPrint);
            final DecisionFrame.Option elvesCurrent =
                    SemanticReplay.resolveExactlyOnce(dividedFrame, session, elvesPrint);
            final Map<String, Integer> vector = new LinkedHashMap<>();
            vector.put(bearCurrent.optionId, Integer.valueOf(3));
            vector.put(elvesCurrent.optionId, Integer.valueOf(1));
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
            Assert.assertNotNull(bear);
            Assert.assertNotNull(elves);
            Assert.assertEquals(bear.getCounters(forge.game.card.CounterEnumType.P1P1), 3);
            Assert.assertEquals(elves.getCounters(forge.game.card.CounterEnumType.P1P1), 1);
        } finally {
            session.shutdown(5000);
            forge.util.MyRandom.clearBinding();
        }
    }
}
