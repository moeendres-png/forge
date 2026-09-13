package forge.bridge;

import com.google.gson.JsonObject;
import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * WS87 fail-closed internal-audit tests.
 *
 * <p>Bounded same-process engineering evidence only: a complete valid
 * fingerprint and an incomplete fingerprint must be unambiguously
 * distinguishable. Invalid fingerprints carry a null digest and can never
 * prove no-mutation. Gameplay authority stays with Forge Rules Core +
 * DecisionFrame actor/revision/option binding.
 */
public class InternalAuditFailClosedTest {
    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    @AfterMethod
    public void clearSeams() {
        InternalAuditFingerprint.clearTestSeams();
        StateProjection.failRequiredReadsForTests = false;
    }

    private static BridgeTestSupport.ConstructedGame newGame(String gameId) {
        return BridgeTestSupport.buildConstructedGame(gameId);
    }

    private static void seed(BridgeTestSupport.ConstructedGame cg) {
        BridgeTestSupport.addCard(cg.game, 0, "Memnite", ZoneType.Hand);
        BridgeTestSupport.addCard(cg.game, 0, "Plains", ZoneType.Hand);
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < 3; i++) {
                BridgeTestSupport.addCard(cg.game, seat, "Plains", ZoneType.Library);
            }
        }
    }

    private static void assertInvalid(InternalAuditFingerprint.Fingerprint fp, String fragment) {
        Assert.assertNotNull(fp, "fingerprint must not be null");
        Assert.assertFalse(fp.valid, "must be invalid: " + fp);
        Assert.assertNull(fp.digest, "invalid digest must be null, got: " + fp.digest);
        Assert.assertNotNull(fp.failure, "invalid must carry a failure reason");
        Assert.assertTrue(fp.failure.contains(fragment),
                "failure '" + fp.failure + "' must mention " + fragment);
        Assert.assertFalse(fp.toAuditString().matches("[0-9a-f]{64}"),
                "audit string must never look like valid hex: " + fp.toAuditString());
        Assert.assertTrue(fp.toAuditString().startsWith("UNAVAILABLE:"),
                "audit string must be explicit UNAVAILABLE: " + fp.toAuditString());
        Assert.assertFalse(ObservationDigest.isHexDigest(fp.toAuditString()),
                "invalid audit string must fail the wire gate");
    }

    private static void assertValidHex(InternalAuditFingerprint.Fingerprint fp) {
        Assert.assertNotNull(fp);
        Assert.assertTrue(fp.valid, "must be valid: " + fp);
        Assert.assertNotNull(fp.digest, "valid digest must not be null");
        Assert.assertTrue(fp.digest.matches("[0-9a-f]{64}"), "digest must be 64-hex: " + fp.digest);
        Assert.assertNull(fp.failure, "valid must carry no failure");
        Assert.assertTrue(ObservationDigest.isHexDigest(fp.toAuditString()),
                "valid audit string is the digest itself");
    }

    // ---- per-zone failures ----

    @Test(timeOut = 120000)
    public void testPerZoneFailuresAreInvalid() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws87-perzone");
        final BridgeSession session = cg.session;
        seed(cg);
        assertValidHex(InternalAuditFingerprint.ofGame(cg.game, session));
        try {
            InternalAuditFingerprint.failHandForTests = true;
            assertInvalid(InternalAuditFingerprint.ofGame(cg.game, session), "Hand");
            InternalAuditFingerprint.failHandForTests = false;

            InternalAuditFingerprint.failBattlefieldForTests = true;
            assertInvalid(InternalAuditFingerprint.ofGame(cg.game, session), "Battlefield");
            InternalAuditFingerprint.failBattlefieldForTests = false;

            InternalAuditFingerprint.failGraveyardForTests = true;
            assertInvalid(InternalAuditFingerprint.ofGame(cg.game, session), "Graveyard");
            InternalAuditFingerprint.failGraveyardForTests = false;

            InternalAuditFingerprint.failExileForTests = true;
            assertInvalid(InternalAuditFingerprint.ofGame(cg.game, session), "Exile");
            InternalAuditFingerprint.failExileForTests = false;

            InternalAuditFingerprint.failCommandForTests = true;
            assertInvalid(InternalAuditFingerprint.ofGame(cg.game, session), "Command");
            InternalAuditFingerprint.failCommandForTests = false;
        } finally {
            InternalAuditFingerprint.clearTestSeams();
            session.shutdown(1000);
        }
        assertValidHex(InternalAuditFingerprint.ofGame(cg.game, session));
    }

    @Test(timeOut = 120000)
    public void testLibraryFailureIsInvalid() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws87-library");
        final BridgeSession session = cg.session;
        seed(cg);
        assertValidHex(InternalAuditFingerprint.ofGame(cg.game, session));
        try {
            InternalAuditFingerprint.failLibraryForTests = true;
            assertInvalid(InternalAuditFingerprint.ofGame(cg.game, session), "library");
        } finally {
            InternalAuditFingerprint.clearTestSeams();
            session.shutdown(1000);
        }
        assertValidHex(InternalAuditFingerprint.ofGame(cg.game, session));
    }

    @Test(timeOut = 120000)
    public void testStackFailureIsInvalid() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws87-stack");
        final BridgeSession session = cg.session;
        seed(cg);
        assertValidHex(InternalAuditFingerprint.ofGame(cg.game, session));
        try {
            InternalAuditFingerprint.failStackForTests = true;
            assertInvalid(InternalAuditFingerprint.ofGame(cg.game, session), "stack");
        } finally {
            InternalAuditFingerprint.clearTestSeams();
            session.shutdown(1000);
        }
        assertValidHex(InternalAuditFingerprint.ofGame(cg.game, session));
    }

    @Test(timeOut = 120000)
    public void testTurnPhaseReaderFailuresAreInvalid() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws87-turnphase");
        final BridgeSession session = cg.session;
        seed(cg);
        assertValidHex(InternalAuditFingerprint.ofGame(cg.game, session));
        try {
            InternalAuditFingerprint.failTurnForTests = true;
            assertInvalid(InternalAuditFingerprint.ofGame(cg.game, session), "turn");
            InternalAuditFingerprint.failTurnForTests = false;

            InternalAuditFingerprint.failPhaseForTests = true;
            assertInvalid(InternalAuditFingerprint.ofGame(cg.game, session), "phase");
            InternalAuditFingerprint.failPhaseForTests = false;

            InternalAuditFingerprint.failPriorityForTests = true;
            assertInvalid(InternalAuditFingerprint.ofGame(cg.game, session), "priority");
            InternalAuditFingerprint.failPriorityForTests = false;

            InternalAuditFingerprint.failActiveForTests = true;
            assertInvalid(InternalAuditFingerprint.ofGame(cg.game, session), "active");
            InternalAuditFingerprint.failActiveForTests = false;

            InternalAuditFingerprint.failTimestampForTests = true;
            assertInvalid(InternalAuditFingerprint.ofGame(cg.game, session), "timestamp");
            InternalAuditFingerprint.failTimestampForTests = false;

            InternalAuditFingerprint.failGameOverForTests = true;
            assertInvalid(InternalAuditFingerprint.ofGame(cg.game, session), "game-over");
            InternalAuditFingerprint.failGameOverForTests = false;

            InternalAuditFingerprint.failRegistryForTests = true;
            assertInvalid(InternalAuditFingerprint.ofGame(cg.game, session), "registry");
            InternalAuditFingerprint.failRegistryForTests = false;

            InternalAuditFingerprint.failPlayerStatsForTests = true;
            assertInvalid(InternalAuditFingerprint.ofGame(cg.game, session), "player-stats");
            InternalAuditFingerprint.failPlayerStatsForTests = false;

            InternalAuditFingerprint.failShaForTests = true;
            assertInvalid(InternalAuditFingerprint.ofGame(cg.game, session), "sha");
            InternalAuditFingerprint.failShaForTests = false;

            assertInvalid(InternalAuditFingerprint.ofGame(null, session), "no-game");
        } finally {
            InternalAuditFingerprint.clearTestSeams();
            session.shutdown(1000);
        }
        assertValidHex(InternalAuditFingerprint.ofGame(cg.game, session));
    }

    // ---- validity / no-mutation gates ----

    @Test(timeOut = 120000)
    public void testInvalidCannotCompareAsValidIdentity() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws87-nocompare");
        final BridgeSession session = cg.session;
        seed(cg);
        final InternalAuditFingerprint.Fingerprint valid =
                InternalAuditFingerprint.ofGame(cg.game, session);
        assertValidHex(valid);
        InternalAuditFingerprint.Fingerprint invalid;
        try {
            InternalAuditFingerprint.failHandForTests = true;
            invalid = InternalAuditFingerprint.ofGame(cg.game, session);
            assertInvalid(invalid, "Hand");
            // Invalid vs valid: no proof.
            Assert.assertFalse(InternalAuditFingerprint.Fingerprint.sameValidIdentity(invalid, valid));
            Assert.assertFalse(InternalAuditFingerprint.Fingerprint.sameValidIdentity(valid, invalid));
            // Invalid vs invalid (same failure): still no proof — must be UNKNOWN.
            final InternalAuditFingerprint.Fingerprint invalid2 =
                    InternalAuditFingerprint.ofGame(cg.game, session);
            assertInvalid(invalid2, "Hand");
            Assert.assertFalse(InternalAuditFingerprint.Fingerprint.sameValidIdentity(
                    invalid, invalid2),
                    "two invalid fingerprints must never prove no-mutation, even when equal");
            Assert.assertFalse(invalid.toAuditString().matches("[0-9a-f]{64}"));
            Assert.assertFalse(invalid2.toAuditString().matches("[0-9a-f]{64}"));
        } finally {
            InternalAuditFingerprint.clearTestSeams();
            session.shutdown(1000);
        }
    }

    @Test(timeOut = 300000)
    public void testRejectedNoMutationRefusesInvalidFingerprints() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws87-reject-unknown");
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
        Assert.assertTrue(frame.preStateHash.valid, "park-time must be valid: " + frame.preStateHash);
        final DecisionFrame.Option pass = BridgeTestSupport.findOption(frame, "pass_priority");
        Assert.assertNotNull(pass);

        // Rejected submissions with valid capture: bounded no-mutation PASS allowed.
        final InternalAuditFingerprint.Fingerprint before =
                InternalAuditFingerprint.ofGame(session.getGame(), session);
        assertValidHex(before);
        Assert.assertFalse(session.submit("p2", pass.optionId, "pass_priority",
                frame.revision).applied);
        final InternalAuditFingerprint.Fingerprint afterValidReject =
                InternalAuditFingerprint.ofGame(session.getGame(), session);
        Assert.assertTrue(InternalAuditFingerprint.Fingerprint.sameValidIdentity(
                afterValidReject, before));

        // Rejected submission while capture is unavailable: pre is UNKNOWN.
        // The rejection code itself is unchanged (actor/revision binding governs).
        try {
            InternalAuditFingerprint.failHandForTests = true;
            final BridgeSession.SubmitOutcome rejected = session.submit("p2", pass.optionId,
                    "pass_priority", frame.revision);
            Assert.assertFalse(rejected.applied);
            Assert.assertEquals(rejected.errorCode, BridgeErrors.WRONG_ACTOR);
            assertInvalid(rejected.preStateHash, "Hand");
            // UNKNOWN: even though pre==post by construction, it must NOT count as PASS.
            Assert.assertFalse(InternalAuditFingerprint.Fingerprint.sameValidIdentity(
                    rejected.preStateHash, before),
                    "invalid rejected pre must not prove no-mutation against valid baseline");
            Assert.assertFalse(InternalAuditFingerprint.Fingerprint.sameValidIdentity(
                    rejected.preStateHash, rejected.postStateHash),
                    "invalid pre/post pair must not prove no-mutation");
        } finally {
            InternalAuditFingerprint.clearTestSeams();
        }
        // Recovers: same option still submittable (nothing consumed by rejects).
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                pass.optionId, "pass_priority", frame.revision);
        Assert.assertTrue(outcome.applied,
                "retry failed: " + outcome.errorCode + " " + outcome.errorMessage);
        Assert.assertTrue(outcome.preStateHash.valid, "pre must be valid: " + outcome.preStateHash);
        Assert.assertTrue(outcome.postStateHash.valid, "post must be valid: " + outcome.postStateHash);
        session.shutdown(5000);
    }

    @Test(timeOut = 120000)
    public void testValidFingerprintDeterministicInOneProcess() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws87-determinism");
        final BridgeSession session = cg.session;
        seed(cg);
        final InternalAuditFingerprint.Fingerprint first =
                InternalAuditFingerprint.ofGame(cg.game, session);
        final InternalAuditFingerprint.Fingerprint second =
                InternalAuditFingerprint.ofGame(cg.game, session);
        assertValidHex(first);
        assertValidHex(second);
        Assert.assertEquals(second.digest, first.digest);
        Assert.assertTrue(InternalAuditFingerprint.Fingerprint.sameValidIdentity(first, second));
        session.shutdown(1000);
    }

    @Test(timeOut = 120000)
    public void testLibraryReorderChangesValidFingerprint() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws87-liborder");
        final BridgeSession session = cg.session;
        BridgeTestSupport.addCard(cg.game, 0, "Memnite", ZoneType.Hand);
        for (int i = 0; i < 4; i++) {
            BridgeTestSupport.addCard(cg.game, 0, "Plains", ZoneType.Library);
        }
        BridgeTestSupport.addCard(cg.game, 0, "Island", ZoneType.Library);
        final InternalAuditFingerprint.Fingerprint before =
                InternalAuditFingerprint.ofGame(cg.game, session);
        assertValidHex(before);
        final forge.game.player.Player p1 = cg.game.getPlayers().get(0);
        final java.util.List<Card> lib =
                new java.util.ArrayList<>(p1.getCardsIn(ZoneType.Library));
        Assert.assertEquals(lib.size(), 5);
        p1.getZone(ZoneType.Library).reorder(lib.get(0), lib.size() - 1);
        final InternalAuditFingerprint.Fingerprint after =
                InternalAuditFingerprint.ofGame(cg.game, session);
        assertValidHex(after);
        Assert.assertNotEquals(after.digest, before.digest,
                "Library reorder must change the valid fingerprint (order-sensitive)");
        Assert.assertFalse(InternalAuditFingerprint.Fingerprint.sameValidIdentity(after, before));
        session.shutdown(1000);
    }

    @Test(timeOut = 300000)
    public void testStackContentChangesValidFingerprint() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws87-stackorder");
        final BridgeSession session = cg.session;
        BridgeTestSupport.addCard(cg.game, 0, "Memnite", ZoneType.Hand);
        BridgeTestSupport.addCard(cg.game, 0, "Plains", ZoneType.Hand);
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < 10; i++) {
                BridgeTestSupport.addCard(cg.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(cg);
        final DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        Assert.assertNotNull(frame, "never reached p1 main phase");
        final InternalAuditFingerprint.Fingerprint emptyStack =
                InternalAuditFingerprint.ofGame(session.getGame(), session);
        assertValidHex(emptyStack);
        Assert.assertTrue(session.getGame().getStack().isEmpty(), "expected empty stack at rest");
        final DecisionFrame.Option cast = BridgeTestSupport.findOption(frame, "cast_spell");
        Assert.assertNotNull(cast, "Memnite cast must be offered");
        final BridgeSession.SubmitOutcome outcome = session.submit("p1", cast.optionId,
                "cast_spell", frame.revision);
        Assert.assertTrue(outcome.applied, "cast failed: " + outcome.errorCode);
        Assert.assertEquals(session.getGame().getStack().size(), 1);
        final InternalAuditFingerprint.Fingerprint withStack =
                InternalAuditFingerprint.ofGame(session.getGame(), session);
        assertValidHex(withStack);
        Assert.assertNotEquals(withStack.digest, emptyStack.digest,
                "stack content must move the valid fingerprint; stack order preserved "
                        + "(no sorting — code appends encounter order)");
        session.shutdown(5000);
    }

    // ---- gameplay authority + protocol boundary ----

    @Test(timeOut = 300000)
    public void testFingerprintFailureDoesNotBlockGameplay() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws87-noblock");
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
        try {
            // Engineering fingerprint unavailable, but Rules + binding still govern.
            InternalAuditFingerprint.failHandForTests = true;
            final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                    pass.optionId, "pass_priority", frame.revision);
            Assert.assertTrue(outcome.applied,
                    "fingerprint failure must not abort a legal action: "
                            + outcome.errorCode + " " + outcome.errorMessage);
            assertInvalid(outcome.preStateHash, "Hand");
            // Observation digests (the wire contract) are unaffected and still valid.
            Assert.assertTrue(ObservationDigest.isHexDigest(outcome.preObservationDigest),
                    outcome.preObservationDigest);
            Assert.assertTrue(ObservationDigest.isHexDigest(outcome.postObservationDigest),
                    outcome.postObservationDigest);
            // Audit records UNKNOWN, never a fabricated valid identity.
            boolean foundUnknown = false;
            for (BridgeSession.AuditEvent event : session.auditSnapshot()) {
                if (event.details.toString().contains("UNAVAILABLE")) {
                    foundUnknown = true;
                }
                Assert.assertFalse(event.details.toString().contains("internal-unreadable"),
                        "no hashed error markers in audit: " + event.details);
            }
            Assert.assertTrue(foundUnknown, "audit must record UNAVAILABLE on fingerprint failure");
        } finally {
            InternalAuditFingerprint.clearTestSeams();
            session.shutdown(5000);
        }
    }

    @Test(timeOut = 300000)
    public void testNoInternalIdentityReachesExternalResponses() {
        final BridgeTestSupport.ConstructedGame cg = newGame("ws87-nowire");
        final BridgeEngine engine = cg.engine;
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
        final InternalAuditFingerprint.Fingerprint internal =
                InternalAuditFingerprint.ofGame(session.getGame(), session);
        assertValidHex(internal);
        final DecisionFrame.Option pass = BridgeTestSupport.findOption(frame, "pass_priority");
        Assert.assertNotNull(pass);
        final JsonObject submitted = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"ws87-wire\","
                        + "\"message_type\":\"submit_action\",\"game_id\":\"ws87-nowire\",\"payload\":{"
                        + "\"revision\":" + frame.revision + ",\"proposal\":{\"proposal_id\":\"w1\","
                        + "\"actor_id\":\"p1\",\"legal_action_id\":\"" + pass.optionId + "\","
                        + "\"action_type\":\"pass_priority\"}}}");
        BridgeTestSupport.assertOk(submitted);
        final String flat = submitted.toString();
        // Wire pre/post are observation digests, never the internal fingerprint.
        final JsonObject decision = submitted.get("payload").getAsJsonObject()
                .getAsJsonObject("decision");
        Assert.assertTrue(ObservationDigest.isHexDigest(
                decision.get("pre_state_hash").getAsString()));
        Assert.assertTrue(ObservationDigest.isHexDigest(
                decision.get("post_state_hash").getAsString()));
        Assert.assertFalse(flat.contains("UNAVAILABLE"), flat);
        Assert.assertFalse(flat.contains("internal-"), flat);
        Assert.assertFalse(flat.contains("pre_hash"), flat);
        Assert.assertFalse(flat.contains("post_hash"), flat);
        Assert.assertFalse(flat.contains("preStateHash"), flat);
        // Wire digest is recomputable from the current actor's observation
        // (derivable client-side; non-actors correctly receive null).
        final DecisionFrame next = session.getCurrentFrame();
        if (next != null) {
            final JsonObject actorState = StateProjection.gameState(session, next.actorPlayerId);
            final JsonObject actorBridge =
                    StateProjection.bridgeMeta(session, next.actorPlayerId, actorState);
            Assert.assertTrue(actorBridge.get("state_hash") != null
                    && !actorBridge.get("state_hash").isJsonNull(),
                    "current actor must carry a digest: " + actorBridge);
            final JsonObject actorMinus = actorBridge.deepCopy();
            actorMinus.remove("state_hash");
            Assert.assertEquals(ObservationDigest.digestOf(actorState, actorMinus),
                    actorBridge.get("state_hash").getAsString());
            // Non-actor still withheld.
            final String nonActor = next.actorPlayerId.equals("p1") ? "p2" : "p1";
            Assert.assertTrue(StateProjection.bridgeMeta(session, nonActor)
                    .get("state_hash").isJsonNull());
        }
        session.shutdown(5000);
    }
}
