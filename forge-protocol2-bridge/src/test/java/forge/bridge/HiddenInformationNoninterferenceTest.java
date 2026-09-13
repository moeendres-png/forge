package forge.bridge;

import com.google.gson.JsonObject;
import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Collections;

/**
 * WS82 principal noninterference + state-identity tests.
 *
 * <p>Pairs A-C fix the principal-visible authoritative observation and vary only
 * hidden state: the ENTIRE principal-visible envelope (game state + bridge
 * metadata incl. digest) must be identical. Pair D (visible change) must move
 * the digest. Derivability, canonical-form, failure-semantics, internal-audit
 * order and revision-binding tests complete the identity taxonomy.
 *
 * <p>Real engine, real cards, unlaunched constructed games except the final
 * launched binding test; only DecisionFrame parking is bypassed via reflection
 * (projection + digest paths fully real).
 */
public class HiddenInformationNoninterferenceTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    // ---- helpers (real engine objects, real projection; only frame parking bypassed) ----

    private static BridgeTestSupport.ConstructedGame newGame(String gameId) {
        return BridgeTestSupport.buildConstructedGame(gameId);
    }

    private static DecisionFrame parkTestFrame(BridgeSession session, String actorId, long revision) {
        final DecisionFrame frame = new DecisionFrame(revision, DecisionFrame.Kind.PRIORITY,
                DecisionFrame.Status.SUPPORTED, "", actorId,
                session.seatOf(session.playerById(actorId)),
                Collections.singletonList(DecisionFrame.passOption()), "test-pre");
        try {
            final java.lang.reflect.Field field =
                    BridgeSession.class.getDeclaredField("currentFrame");
            field.setAccessible(true);
            field.set(session, frame);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return frame;
    }

    private static String projectionOf(BridgeSession session, String observer) {
        return StateProjection.gameState(session, observer).toString();
    }

    private static JsonObject metaOf(BridgeSession session, String observer) {
        return StateProjection.bridgeMeta(session, observer);
    }

    private static String hashOf(JsonObject meta) {
        return meta.get("state_hash").isJsonNull()
                ? null : meta.get("state_hash").getAsString();
    }

    /** Meta comparison excluding the digest under test. */
    private static String metaMinusHash(JsonObject meta) {
        final JsonObject copy = meta.deepCopy();
        copy.remove("state_hash");
        return copy.toString();
    }

    private static void swapCard(BridgeTestSupport.ConstructedGame cg, int seat, ZoneType zone,
            String removeName, String addName) {
        final forge.game.player.Player owner = cg.game.getPlayers().get(seat);
        Card victim = null;
        for (Card c : owner.getCardsIn(zone)) {
            if (c.getName().equals(removeName)) {
                victim = c;
                break;
            }
        }
        Assert.assertNotNull(victim, "expected to find " + removeName + " in " + zone);
        owner.getZone(zone).remove(victim);
        BridgeTestSupport.addCard(cg.game, seat, addName, zone);
    }

    private static void seedLibraries(BridgeTestSupport.ConstructedGame cg, String card, int count) {
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < count; i++) {
                BridgeTestSupport.addCard(cg.game, seat, card, ZoneType.Library);
            }
        }
    }

    // ---- Pair A: opponent hand identity ----

    @Test(timeOut = 120000)
    public void testOpponentHandIdentityNoninterference() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws82-pre-a");
        final BridgeSession session = cg.session;
        BridgeTestSupport.addCard(cg.game, 0, "Memnite", ZoneType.Hand);
        BridgeTestSupport.addCard(cg.game, 0, "Plains", ZoneType.Hand);
        BridgeTestSupport.addCard(cg.game, 1, "Serra Angel", ZoneType.Hand);
        BridgeTestSupport.addCard(cg.game, 1, "Plains", ZoneType.Hand);
        BridgeTestSupport.addCard(cg.game, 0, "Plains", ZoneType.Battlefield);
        seedLibraries(cg, "Plains", 5);
        parkTestFrame(session, "p1", 7);

        final String projBefore = projectionOf(session, "p1");
        final JsonObject metaBefore = metaOf(session, "p1");
        Assert.assertTrue(projBefore.contains("<hidden>"), projBefore);
        Assert.assertFalse(projBefore.contains("Serra Angel"), projBefore);
        final String hashBefore = hashOf(metaBefore);
        Assert.assertNotNull(hashBefore);

        // Same count, different hidden identities.
        swapCard(cg, 1, ZoneType.Hand, "Serra Angel", "Grizzly Bears");

        final String projAfter = projectionOf(session, "p1");
        final JsonObject metaAfter = metaOf(session, "p1");
        Assert.assertEquals(projAfter, projBefore, "projection must redact opponent identities");
        Assert.assertEquals(metaMinusHash(metaAfter), metaMinusHash(metaBefore));
        Assert.assertEquals(hashOf(metaAfter), hashBefore,
                "PRIVACY: opponent hand identity changed the actor-visible digest");
        session.shutdown(1000);
    }

    // ---- Pair B: hidden library identity ----

    @Test(timeOut = 120000)
    public void testHiddenLibraryIdentityNoninterference() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws82-pre-b");
        final BridgeSession session = cg.session;
        BridgeTestSupport.addCard(cg.game, 0, "Memnite", ZoneType.Hand);
        BridgeTestSupport.addCard(cg.game, 1, "Serra Angel", ZoneType.Hand);
        seedLibraries(cg, "Plains", 5);
        parkTestFrame(session, "p1", 7);

        final String projBefore = projectionOf(session, "p1");
        final String hashBefore = hashOf(metaOf(session, "p1"));
        Assert.assertNotNull(hashBefore);

        // Same size, different hidden identities in p2's library.
        final forge.game.player.Player p2 = cg.game.getPlayers().get(1);
        for (Card c : new java.util.ArrayList<>(p2.getCardsIn(ZoneType.Library))) {
            p2.getZone(ZoneType.Library).remove(c);
        }
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(cg.game, 1, "Island", ZoneType.Library);
        }

        final String projAfter = projectionOf(session, "p1");
        Assert.assertEquals(projAfter, projBefore, "libraries are always hidden");
        Assert.assertEquals(hashOf(metaOf(session, "p1")), hashBefore,
                "PRIVACY: hidden library identity changed the actor-visible digest");
        session.shutdown(1000);
    }

    // ---- Pair C: unauthorized face-down identity ----

    @Test(timeOut = 120000)
    public void testUnauthorizedFaceDownNoninterference() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws82-pre-c");
        final BridgeSession session = cg.session;
        final Card morph = BridgeTestSupport.addCard(cg.game, 0, "Grizzly Bears",
                ZoneType.Battlefield);
        Assert.assertTrue(morph.turnFaceDown(true));
        BridgeTestSupport.addCard(cg.game, 0, "Plains", ZoneType.Battlefield);
        seedLibraries(cg, "Plains", 5);
        // Observer p2 is unauthorized (no may-look grant); actor p2 for digest scope.
        parkTestFrame(session, "p2", 7);

        final String projBefore = projectionOf(session, "p2");
        Assert.assertTrue(projBefore.contains("<face-down>"), projBefore);
        Assert.assertFalse(projBefore.contains("Grizzly Bears"), projBefore);
        Assert.assertTrue(projBefore.contains("Plains"), projBefore);
        final String hashBefore = hashOf(metaOf(session, "p2"));
        Assert.assertNotNull(hashBefore);

        // Same authorized marker, different underlying true card. Preserve zone
        // order (battlefield order is public observation): reinsert at index 0.
        cg.game.getPlayers().get(0).getZone(ZoneType.Battlefield).remove(morph);
        final Card morph2 = BridgeTestSupport.addCard(cg.game, 0, "Squire",
                ZoneType.Battlefield);
        Assert.assertTrue(morph2.turnFaceDown(true));
        cg.game.getPlayers().get(0).getZone(ZoneType.Battlefield).reorder(morph2, 0);

        final String projAfter = projectionOf(session, "p2");
        Assert.assertEquals(projAfter, projBefore, "unauthorized face-down must stay redacted");
        Assert.assertEquals(hashOf(metaOf(session, "p2")), hashBefore,
                "PRIVACY: unauthorized face-down identity changed the actor-visible digest");
        session.shutdown(1000);
    }

    // ---- Pair D: public change control (must change digest; passes pre- and post-fix) ----

    @Test(timeOut = 120000)
    public void testVisibleBattlefieldChangeSensitivity() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws82-pre-d");
        final BridgeSession session = cg.session;
        BridgeTestSupport.addCard(cg.game, 0, "Memnite", ZoneType.Hand);
        BridgeTestSupport.addCard(cg.game, 0, "Plains", ZoneType.Battlefield);
        seedLibraries(cg, "Plains", 5);
        parkTestFrame(session, "p1", 7);

        final String projBefore = projectionOf(session, "p1");
        final String hashBefore = hashOf(metaOf(session, "p1"));
        Assert.assertNotNull(hashBefore);

        BridgeTestSupport.addCard(cg.game, 0, "Plains", ZoneType.Battlefield);

        final String projAfter = projectionOf(session, "p1");
        Assert.assertNotEquals(projAfter, projBefore, "visible change must project");
        Assert.assertNotEquals(hashOf(metaOf(session, "p1")), hashBefore,
                "digest must not be constant: visible change must change it");
        session.shutdown(1000);
    }

    // ---- determinism guard (passes pre- and post-fix) ----

    @Test(timeOut = 120000)
    public void testDigestDeterminismSameState() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws82-pre-det");
        final BridgeSession session = cg.session;
        BridgeTestSupport.addCard(cg.game, 0, "Memnite", ZoneType.Hand);
        seedLibraries(cg, "Plains", 5);
        parkTestFrame(session, "p1", 7);

        Assert.assertEquals(projectionOf(session, "p1"), projectionOf(session, "p1"));
        Assert.assertEquals(hashOf(metaOf(session, "p1")), hashOf(metaOf(session, "p1")));
        session.shutdown(1000);
    }

    // ---- non-actor exposure guard (passes pre- and post-fix) ----

    @Test(timeOut = 120000)
    public void testNonActorDigestWithheld() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws82-pre-nonactor");
        final BridgeSession session = cg.session;
        BridgeTestSupport.addCard(cg.game, 0, "Memnite", ZoneType.Hand);
        seedLibraries(cg, "Plains", 5);
        parkTestFrame(session, "p1", 7);

        final JsonObject asP2 = metaOf(session, "p2");
        Assert.assertTrue(asP2.get("state_hash").isJsonNull(), asP2.toString());
        Assert.assertEquals(asP2.get("revision").getAsInt(), -1);
        Assert.assertTrue(asP2.get("pending_decision").isJsonNull());
        final JsonObject asPublic = metaOf(session, null);
        Assert.assertTrue(asPublic.get("state_hash").isJsonNull(), asPublic.toString());
        session.shutdown(1000);
    }

    // ---- failure semantics: no fabricated digest (FAILS pre-fix: returns "unavailable") ----

    @Test(timeOut = 120000)
    public void testDigestFailureNotFabricated() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws82-pre-fail");
        final BridgeSession session = cg.session;
        BridgeTestSupport.addCard(cg.game, 0, "Memnite", ZoneType.Hand);
        seedLibraries(cg, "Plains", 5);
        parkTestFrame(session, "p1", 7);

        StateProjection.failRequiredReadsForTests = true;
        try {
            try {
                final JsonObject meta = metaOf(session, "p1");
                Assert.fail("projection failure must not yield a digest, got: " + meta);
            } catch (BridgeProjectionException expected) {
                // Fail-closed: no plausible hash emitted.
            }
        } finally {
            StateProjection.failRequiredReadsForTests = false;
        }
        // Recovers afterwards.
        Assert.assertNotNull(hashOf(metaOf(session, "p1")));
        session.shutdown(1000);
    }

    // ---- Concern C: internal audit sorted Library (FAILS pre-fix: reorder invisible) ----

    @Test(timeOut = 120000)
    public void testInternalAuditLibraryOrderBlindness() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws82-pre-order");
        final BridgeSession session = cg.session;
        BridgeTestSupport.addCard(cg.game, 0, "Memnite", ZoneType.Hand);
        for (int i = 0; i < 4; i++) {
            BridgeTestSupport.addCard(cg.game, 0, "Plains", ZoneType.Library);
        }
        BridgeTestSupport.addCard(cg.game, 0, "Island", ZoneType.Library);
        parkTestFrame(session, "p1", 7);

        final String before = InternalAuditFingerprint.ofGame(cg.game, session);
        final String obsBefore = hashOf(metaOf(session, "p1"));
        final String projBefore = projectionOf(session, "p1");
        final forge.game.player.Player p1 = cg.game.getPlayers().get(0);
        final java.util.List<Card> lib =
                new java.util.ArrayList<>(p1.getCardsIn(ZoneType.Library));
        Assert.assertEquals(lib.size(), 5);
        // Real semantic reorder: move the top card to the bottom.
        p1.getZone(ZoneType.Library).reorder(lib.get(0), lib.size() - 1);
        final String after = InternalAuditFingerprint.ofGame(cg.game, session);
        Assert.assertNotEquals(after, before,
                "internal audit must detect library reordering (order is semantic)");
        // The hidden order change is invisible to the principal: same observation.
        Assert.assertEquals(projectionOf(session, "p1"), projBefore);
        Assert.assertEquals(hashOf(metaOf(session, "p1")), obsBefore);
        session.shutdown(1000);
    }

    // ---- timestamp isolation: engine clock advances, observation fixed ----

    @Test(timeOut = 120000)
    public void testTimestampIsolation() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws82-ts");
        final BridgeSession session = cg.session;
        BridgeTestSupport.addCard(cg.game, 0, "Memnite", ZoneType.Hand);
        seedLibraries(cg, "Plains", 5);
        parkTestFrame(session, "p1", 7);

        final String projBefore = projectionOf(session, "p1");
        final String obsBefore = hashOf(metaOf(session, "p1"));
        final String auditBefore = InternalAuditFingerprint.ofGame(cg.game, session);
        Assert.assertNotNull(obsBefore);

        // Advance the privileged engine clock with no visible change.
        cg.game.getNextTimestamp();
        cg.game.getNextTimestamp();

        Assert.assertEquals(projectionOf(session, "p1"), projBefore);
        Assert.assertEquals(hashOf(metaOf(session, "p1")), obsBefore,
                "engine timestamp must not influence the observation digest");
        Assert.assertNotEquals(InternalAuditFingerprint.ofGame(cg.game, session), auditBefore,
                "internal audit is timestamp-sensitive (same-process only)");
        session.shutdown(1000);
    }

    // ---- Concern B post-fix: same names, different objects => same digest ----

    @Test(timeOut = 120000)
    public void testSameNameObjectSwapInvisible() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws82-swap");
        final BridgeSession session = cg.session;
        BridgeTestSupport.addCard(cg.game, 0, "Memnite", ZoneType.Hand);
        BridgeTestSupport.addCard(cg.game, 1, "Serra Angel", ZoneType.Hand);
        BridgeTestSupport.addCard(cg.game, 1, "Plains", ZoneType.Hand);
        seedLibraries(cg, "Plains", 5);
        parkTestFrame(session, "p1", 7);

        final String projBefore = projectionOf(session, "p1");
        final String hashBefore = hashOf(metaOf(session, "p1"));
        Assert.assertNotNull(hashBefore);

        // Same name and count, but a different Java object (hence a different
        // System.identityHashCode): must not move the principal-visible digest.
        swapCard(cg, 1, ZoneType.Hand, "Serra Angel", "Serra Angel");

        Assert.assertEquals(projectionOf(session, "p1"), projBefore);
        Assert.assertEquals(hashOf(metaOf(session, "p1")), hashBefore,
                "process-local object identity must not influence the observation digest");
        session.shutdown(1000);
    }

    // ---- derivability: digest recomputed from the sanitized envelope ----

    @Test(timeOut = 120000)
    public void testObservationDigestDerivableFromEnvelope() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws82-derive");
        final BridgeSession session = cg.session;
        BridgeTestSupport.addCard(cg.game, 0, "Memnite", ZoneType.Hand);
        BridgeTestSupport.addCard(cg.game, 1, "Serra Angel", ZoneType.Hand);
        BridgeTestSupport.addCard(cg.game, 0, "Plains", ZoneType.Battlefield);
        seedLibraries(cg, "Plains", 5);
        parkTestFrame(session, "p1", 7);

        final com.google.gson.JsonObject state =
                StateProjection.gameState(session, "p1");
        final JsonObject bridge = StateProjection.bridgeMeta(session, "p1", state);
        final String wireHash = hashOf(bridge);
        Assert.assertNotNull(wireHash);
        Assert.assertTrue(ObservationDigest.isHexDigest(wireHash), wireHash);
        Assert.assertFalse(wireHash.equals("unavailable"));
        Assert.assertFalse(wireHash.equals("hash-error"));
        Assert.assertFalse(wireHash.equals("sha-unavailable"));

        // Client-side recompute from the received sanitized bytes (no recursion:
        // the digest field itself is excluded).
        final JsonObject bridgeMinusHash = bridge.deepCopy();
        bridgeMinusHash.remove("state_hash");
        Assert.assertEquals(ObservationDigest.digestOf(state, bridgeMinusHash), wireHash);

        // Non-actor envelope carries no digest and none is derivable for them.
        Assert.assertTrue(metaOf(session, "p2").get("state_hash").isJsonNull());
        session.shutdown(1000);
    }

    // ---- canonical form unit properties ----

    @Test(timeOut = 120000)
    public void testObservationDigestCanonicalForm() {
        final JsonObject left =
                com.google.gson.JsonParser.parseString("{\"b\":2,\"a\":1}").getAsJsonObject();
        final JsonObject right =
                com.google.gson.JsonParser.parseString("{\"a\":1,\"b\":2}").getAsJsonObject();
        Assert.assertEquals(ObservationDigest.canonical(left), ObservationDigest.canonical(right));
        Assert.assertEquals(ObservationDigest.digestOf(stateShell(left), bridgeShell()),
                ObservationDigest.digestOf(stateShell(right), bridgeShell()));
        // Semantic array order is preserved, never sorted away.
        final JsonObject first =
                com.google.gson.JsonParser.parseString("{\"list\":[1,2]}").getAsJsonObject();
        final JsonObject second =
                com.google.gson.JsonParser.parseString("{\"list\":[2,1]}").getAsJsonObject();
        Assert.assertNotEquals(ObservationDigest.canonical(first), ObservationDigest.canonical(second));
        Assert.assertTrue(ObservationDigest.isHexDigest(
                ObservationDigest.digestOf(stateShell(first), bridgeShell())));
        Assert.assertFalse(ObservationDigest.isHexDigest("unavailable"));
        Assert.assertFalse(ObservationDigest.isHexDigest(null));
    }

    private static JsonObject stateShell(JsonObject inner) {
        final JsonObject state = new JsonObject();
        state.add("probe", inner);
        return state;
    }

    private static JsonObject bridgeShell() {
        final JsonObject meta = new JsonObject();
        meta.addProperty("session_status", "RUNNING");
        meta.addProperty("revision", 7);
        return meta;
    }

    // ---- launched: revision/actor binding authoritative; digests observation-bound ----

    @Test(timeOut = 300000)
    public void testRevisionBindingAndObservationDigests() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws82-launched");
        final BridgeSession session = cg.session;
        BridgeTestSupport.addCard(cg.game, 0, "Memnite", ZoneType.Hand);
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < 5; i++) {
                BridgeTestSupport.addCard(cg.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(cg);
        final DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        Assert.assertNotNull(frame, "never reached p1 main phase");
        Assert.assertEquals(frame.status, DecisionFrame.Status.SUPPORTED);
        final String actor = frame.actorPlayerId;
        Assert.assertEquals(actor, "p1");

        final String projBefore = projectionOf(session, actor);
        final String obsBefore = hashOf(metaOf(session, actor));
        Assert.assertTrue(ObservationDigest.isHexDigest(obsBefore));
        final String auditBefore =
                InternalAuditFingerprint.ofGame(session.getGame(), session);

        // Negative controls: wrong actor, unknown option, stale revision.
        final DecisionFrame.Option pass = BridgeTestSupport.findOption(frame, "pass_priority");
        Assert.assertNotNull(pass);
        final String other = actor.equals("p1") ? "p2" : "p1";
        Assert.assertFalse(session.submit(other, pass.optionId, "pass_priority",
                frame.revision).applied);
        Assert.assertFalse(session.submit(actor, "opt-does-not-exist", "pass_priority",
                frame.revision).applied);
        Assert.assertFalse(session.submit(actor, pass.optionId, "pass_priority",
                frame.revision - 1).applied);
        Assert.assertFalse(session.submit(actor, pass.optionId, "cast_spell",
                frame.revision).applied);
        // Rejected submissions mutate nothing: observation and audit identical.
        Assert.assertEquals(projectionOf(session, actor), projBefore);
        Assert.assertEquals(hashOf(metaOf(session, actor)), obsBefore);
        Assert.assertEquals(InternalAuditFingerprint.ofGame(session.getGame(), session),
                auditBefore);

        // Real submission: pre digest binds the actor's envelope; post advances it.
        final BridgeSession.SubmitOutcome outcome = session.submit(actor, pass.optionId,
                "pass_priority", frame.revision);
        Assert.assertTrue(outcome.applied,
                "pass failed: " + outcome.errorCode + " " + outcome.errorMessage);
        Assert.assertTrue(ObservationDigest.isHexDigest(outcome.preObservationDigest));
        Assert.assertTrue(ObservationDigest.isHexDigest(outcome.postObservationDigest));
        Assert.assertEquals(outcome.preObservationDigest, obsBefore);
        Assert.assertNotEquals(outcome.postObservationDigest, outcome.preObservationDigest);
        Assert.assertNotEquals(outcome.postStateHash, outcome.preStateHash);
        session.shutdown(5000);
    }

    // ---- launched: pre-capture projection failure rejects with engine untouched ----

    @Test(timeOut = 300000)
    public void testSubmitPreCaptureFailureFailsClosed() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws82-prefail");
        final BridgeSession session = cg.session;
        BridgeTestSupport.addCard(cg.game, 0, "Memnite", ZoneType.Hand);
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < 5; i++) {
                BridgeTestSupport.addCard(cg.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(cg);
        final DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        Assert.assertNotNull(frame, "never reached p1 main phase");
        final DecisionFrame.Option pass = BridgeTestSupport.findOption(frame, "pass_priority");
        Assert.assertNotNull(pass);
        final String obsBefore = hashOf(metaOf(session, frame.actorPlayerId));

        // Break authoritative reads: the valid submission must reject here with
        // the engine untouched and the option unconsumed (retryable).
        StateProjection.failRequiredReadsForTests = true;
        try {
            final BridgeSession.SubmitOutcome rejected = session.submit(frame.actorPlayerId,
                    pass.optionId, "pass_priority", frame.revision);
            Assert.assertFalse(rejected.applied);
            Assert.assertEquals(rejected.errorCode, BridgeErrors.PROJECTION_FAILED);
        } finally {
            StateProjection.failRequiredReadsForTests = false;
        }
        Assert.assertEquals(session.getCurrentFrame().revision, frame.revision);
        Assert.assertEquals(hashOf(metaOf(session, frame.actorPlayerId)), obsBefore);

        // Retry with the same option succeeds: nothing was consumed or mutated.
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                pass.optionId, "pass_priority", frame.revision);
        Assert.assertTrue(outcome.applied,
                "retry failed: " + outcome.errorCode + " " + outcome.errorMessage);
        Assert.assertTrue(ObservationDigest.isHexDigest(outcome.preObservationDigest));
        Assert.assertTrue(ObservationDigest.isHexDigest(outcome.postObservationDigest));
        session.shutdown(5000);
    }
}
