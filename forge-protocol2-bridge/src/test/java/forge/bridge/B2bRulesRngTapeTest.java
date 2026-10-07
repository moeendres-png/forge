package forge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Commander-Lab #561 G4-K (decision record 6005365186; batch 6 B2b decision
 * 6035022676): the keyed, orchestration-only {@code get_rules_rng_tape}.
 * Library shuffle results only; every result is an HMAC under the launch key
 * whose input binds game id, stream, seat and sequence; no card name leaves;
 * a swallowed subscriber exception poisons the tape.
 */
public class B2bRulesRngTapeTest {

    private static final byte[] KEY = new byte[32];

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    @AfterMethod(alwaysRun = true)
    public void principalLaunch() {
        OrchestrationKey.keyForTests(null);
        BridgeSession.rngTapeFaultForTests = null;
    }

    /** A real four-seat game of template decks, parked at p1's first priority. */
    private static BridgeEngine parkedAtFirstPriority(String gameId, long seed) {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = new ArrayList<>();
        for (int seat = 1; seat <= 4; seat++) {
            handles.add(BridgeTestSupport.importDeck(engine, "import-" + gameId + "-" + seat,
                    B2bCheckpointLibraryTest.templateDeck(gameId + "-" + seat)));
        }
        final StringBuilder decks = new StringBuilder();
        for (int i = 0; i < handles.size(); i++) {
            decks.append(i == 0 ? "" : ",").append('"').append(handles.get(i)).append('"');
        }
        BridgeTestSupport.assertOk(BridgeTestSupport.rpc(engine, "{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"c-" + gameId + "\",\"message_type\":\"create_commander_game\","
                + "\"payload\":{\"request\":{\"game_id\":\"" + gameId + "\",\"format\":\"commander\","
                + "\"seed\":" + seed + ",\"deck_handles\":[" + decks + "]}}}"));
        BridgeTestSupport.startGame(engine, gameId);
        final BridgeSession session = engine.sessionsForTests().get(gameId);
        final DecisionFrame priority = BridgeTestSupport.driveStartToPriority(session, "p1", 180000);
        Assert.assertEquals(priority.kind, DecisionFrame.Kind.PRIORITY);
        return engine;
    }

    private static JsonObject tape(BridgeEngine engine, String gameId) {
        return BridgeTestSupport.rpc(engine, "{\"protocol_version\":\"2.0.0\",\"request_id\":\"rng-"
                + gameId + "-" + System.nanoTime() + "\",\"message_type\":\"get_rules_rng_tape\","
                + "\"game_id\":\"" + gameId + "\"}");
    }

    /** Kills a channel that answers without the orchestration key. */
    @Test(timeOut = 300000)
    public void anRngTapeWithoutAKeyIsRefused() {
        OrchestrationKey.keyForTests(null);
        final BridgeEngine engine = parkedAtFirstPriority("rng-nokey", 56201L);
        final JsonObject response = tape(engine, "rng-nokey");
        BridgeTestSupport.assertError(response, BridgeErrors.ORCHESTRATION_CHANNEL_NOT_ENABLED);
        Assert.assertFalse(response.toString().contains("rules_rng_results"), response.toString());
        engine.sessionsForTests().get("rng-nokey").shutdown(5000);
    }

    /**
     * The keyed tape carries the engine's opening library shuffles as dense,
     * per-seat streams with call coordinates, and no card name of any zone.
     */
    @Test(timeOut = 300000)
    public void thePayloadCarriesNoCardNames() {
        OrchestrationKey.keyForTests(KEY);
        final BridgeEngine engine = parkedAtFirstPriority("rng-names", 56202L);
        final BridgeSession session = engine.sessionsForTests().get("rng-names");
        final JsonObject response = tape(engine, "rng-names");
        BridgeTestSupport.assertOk(response);
        final JsonObject payload = response.getAsJsonObject("payload");
        Assert.assertEquals(payload.get("engine_state").getAsString(), "PARKED");
        Assert.assertEquals(payload.get("observation_scope").getAsString(), "orchestration_keyed_digests");
        Assert.assertTrue(payload.get("rules_seed_explicit").getAsBoolean());
        final long calls = payload.get("rules_random_calls").getAsLong();
        Assert.assertTrue(calls > 0, "the opening shuffles consumed Rules RNG");
        Assert.assertTrue(payload.get("privileged_state_digest").getAsString().matches("[0-9a-f]{64}"));
        final JsonArray results = payload.getAsJsonArray("rules_rng_results");
        Assert.assertTrue(results.size() >= 4, "one opening shuffle per seat at least: " + results);
        final Set<Integer> seats = new HashSet<>();
        long previousAfter = 0;
        for (int i = 0; i < results.size(); i++) {
            final JsonObject entry = results.get(i).getAsJsonObject();
            Assert.assertEquals(entry.get("operation").getAsString(), "LIBRARY_SHUFFLE");
            Assert.assertEquals(entry.get("sequence").getAsInt(), i, "dense engine order");
            final int seat = entry.get("seat").getAsInt();
            seats.add(seat);
            Assert.assertEquals(entry.get("stream").getAsString(), "library_shuffle:P" + (seat + 1));
            final long before = entry.get("before_lower_bound").getAsLong();
            final long after = entry.get("after").getAsLong();
            Assert.assertTrue(before >= previousAfter && after - before >= 98,
                    "a 99-card shuffle lies within its coordinates: " + entry);
            Assert.assertTrue(after <= calls);
            previousAfter = after;
            Assert.assertEquals(entry.get("library_size").getAsInt(), 99);
            Assert.assertTrue(entry.get("result_digest").getAsString().matches("[0-9a-f]{64}"));
            Assert.assertEquals(entry.keySet(), new HashSet<>(Arrays.asList("operation", "stream",
                    "sequence", "seat", "before_lower_bound", "after", "library_size", "result_digest")));
        }
        Assert.assertEquals(seats, new HashSet<>(Arrays.asList(0, 1, 2, 3)));
        Assert.assertEquals(payload.get("tape_schema").getAsString(), "forge-rules-rng-tape/2");
        final JsonObject semantics = payload.getAsJsonObject("coordinate_semantics");
        Assert.assertTrue(semantics.get("before_lower_bound").getAsString().contains("lower_bound"));
        Assert.assertTrue(semantics.get("after").getAsString().endsWith("_exact"));
        Assert.assertEquals(semantics.get("sequence").getAsString(), "global_engine_order_across_seats",
                "one dense counter across seats, as the XMage tape");
        final String text = response.toString();
        final Set<String> names = new HashSet<>();
        for (Player player : session.registryPlayers()) {
            for (ZoneType zone : ZoneType.values()) {
                for (Card card : player.getCardsIn(zone)) {
                    names.add(card.getName());
                }
            }
        }
        Assert.assertTrue(names.contains("Mountain") && names.contains(B2bCheckpointLibraryTest.ROGRAKH));
        for (String name : names) {
            Assert.assertFalse(text.contains(name), "card name [" + name + "] in payload: " + text);
        }
        session.shutdown(5000);
    }

    /** Kills an HMAC whose input does not bind game id, stream, seat or sequence. */
    @Test
    public void theHmacChangesWithGameIdStreamSeatOrSequence() {
        OrchestrationKey.keyForTests(KEY);
        final List<String> order = Arrays.asList("2", "0", "1", "3");
        final String base = RulesRngTape.resultDigest("g1", "library_shuffle:P1", 0, 0, 4, order);
        Assert.assertEquals(RulesRngTape.resultDigest("g1", "library_shuffle:P1", 0, 0, 4, order), base,
                "deterministic under one key");
        Assert.assertNotEquals(RulesRngTape.resultDigest("g2", "library_shuffle:P1", 0, 0, 4, order), base,
                "game_id is bound");
        Assert.assertNotEquals(RulesRngTape.resultDigest("g1", "library_shuffle:P2", 0, 0, 4, order), base,
                "stream is bound");
        Assert.assertNotEquals(RulesRngTape.resultDigest("g1", "library_shuffle:P1", 1, 0, 4, order), base,
                "seat is bound");
        Assert.assertNotEquals(RulesRngTape.resultDigest("g1", "library_shuffle:P1", 0, 1, 4, order), base,
                "sequence is bound");
        Assert.assertNotEquals(RulesRngTape.resultDigest("g1", "library_shuffle:P1", 0, 0, 4,
                Arrays.asList("0", "2", "1", "3")), base, "the resulting order is bound");
        final byte[] other = new byte[32];
        other[0] = 1;
        OrchestrationKey.keyForTests(other);
        Assert.assertNotEquals(RulesRngTape.resultDigest("g1", "library_shuffle:P1", 0, 0, 4, order), base,
                "without the launch key a guess cannot be tested");
    }

    /**
     * Kills a subscriber whose exception Guava would swallow silently: the
     * shuffle subscriber throws after recording, and the tape is refused as
     * poisoned instead of being served as if complete.
     */
    @Test(timeOut = 300000)
    public void aSwallowedSubscriberExceptionPoisonsTheTape() {
        OrchestrationKey.keyForTests(KEY);
        BridgeSession.rngTapeFaultForTests = "throw_in_shuffle_subscriber";
        final BridgeEngine engine = parkedAtFirstPriority("rng-poison", 56203L);
        final JsonObject response = tape(engine, "rng-poison");
        BridgeTestSupport.assertError(response, BridgeErrors.RULES_RNG_TAPE_FAILED);
        final String message = response.getAsJsonArray("errors").get(0).getAsJsonObject()
                .get("message").getAsString();
        Assert.assertEquals(message, "RULES_RNG_TAPE_POISONED:SHUFFLE_SUBSCRIBER_ERROR");
        engine.sessionsForTests().get("rng-poison").shutdown(5000);
    }

    /** An unconfirmed shuffle (a controller result without its event) is refused, never served. */
    @Test
    public void anUnconfirmedShuffleIsRefused() {
        OrchestrationKey.keyForTests(KEY);
        final RulesRngTape tape = new RulesRngTape("g-unit");
        final List<Object> before = Arrays.asList(new Object(), new Object(), new Object());
        final List<Object> after = Arrays.asList(before.get(2), before.get(0), before.get(1));
        tape.onControllerShuffle(0, before, after, 5);
        try {
            tape.results();
            Assert.fail("an unconfirmed shuffle was served");
        } catch (RulesRngTape.Refused expected) {
            Assert.assertEquals(expected.getMessage(), "RULES_RNG_TAPE_INCOMPLETE");
        }
        tape.onShuffleEvent(0);
        final JsonArray results = tape.results();
        Assert.assertEquals(results.size(), 1);
        tape.onShuffleEvent(1);
        try {
            tape.results();
            Assert.fail("an event without a result was served");
        } catch (RulesRngTape.Refused expected) {
            Assert.assertEquals(expected.getMessage(), "RULES_RNG_TAPE_POISONED:SHUFFLE_WITHOUT_RESULT");
        }
    }

    /** Kills a missing coordinate check: a 3-card shuffle that consumed fewer than 2 calls poisons. */
    @Test
    public void anImpossibleCoordinatePairPoisonsTheTape() {
        OrchestrationKey.keyForTests(KEY);
        final RulesRngTape tape = new RulesRngTape("g-coord");
        final List<Object> before = Arrays.asList(new Object(), new Object(), new Object());
        final List<Object> after = Arrays.asList(before.get(2), before.get(0), before.get(1));
        tape.observeEvent(10);
        tape.onControllerShuffle(0, before, after, 11);
        tape.onShuffleEvent(0);
        try {
            tape.results();
            Assert.fail("a coordinate pair that cannot contain the shuffle was served");
        } catch (RulesRngTape.Refused expected) {
            Assert.assertEquals(expected.getMessage(),
                    "RULES_RNG_TAPE_POISONED:RNG_COORDINATE_INCONSISTENT");
        }
    }

    /** Kills a missing seat check: a shuffle event confirming another seat's result poisons. */
    @Test
    public void aShuffleEventForAnotherSeatPoisonsTheTape() {
        OrchestrationKey.keyForTests(KEY);
        final RulesRngTape tape = new RulesRngTape("g-seat");
        final List<Object> before = Arrays.asList(new Object(), new Object(), new Object());
        final List<Object> after = Arrays.asList(before.get(1), before.get(2), before.get(0));
        tape.onControllerShuffle(0, before, after, 5);
        tape.onShuffleEvent(2);
        try {
            tape.results();
            Assert.fail("a result confirmed by another seat's event was served");
        } catch (RulesRngTape.Refused expected) {
            Assert.assertEquals(expected.getMessage(), "RULES_RNG_TAPE_POISONED:SHUFFLE_SEAT_MISMATCH");
        }
    }

    @Test
    public void theCapabilitiesDeclareTheKeyedTape() {
        final JsonObject caps = BridgeTestSupport.rpc(new BridgeEngine(),
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"caps\",\"message_type\":\"get_capabilities\"}")
                .getAsJsonObject("payload").getAsJsonObject("capabilities");
        Assert.assertTrue(caps.get("rules_rng_tape_supported").getAsBoolean());
        Assert.assertEquals(caps.get("rules_rng_tape_scope").getAsString(),
                "orchestration_keyed_library_shuffle_results_refused_without_launch_key");
    }
}
