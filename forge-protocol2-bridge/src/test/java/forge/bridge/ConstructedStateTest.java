package forge.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Commander-Lab #441 decision (c): the Lab's generic lane reads the engine's
 * own normalized constructed state at the first mulligan decision, before it
 * is answered, and compares it with a record's requested state.
 *
 * <p>The read is an orchestration channel: refused on a launch without an
 * orchestration key, and each seat's library and hand leave only as an HMAC
 * under that key. No hidden card name and no object identity leaves.</p>
 */
public class ConstructedStateTest {

    private static final String ROGRAKH = "Rograkh, Son of Rohgahh";
    private static final byte[] KEY = new byte[32];

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    @AfterMethod(alwaysRun = true)
    public void principalLaunch() {
        OrchestrationKey.keyForTests(null);
    }

    private static String deck(String tag, String odd) {
        final List<String> mainboard = new ArrayList<>();
        for (int i = 0; i < 99; i++) {
            mainboard.add("\"Mountain\"");
        }
        if (odd != null) {
            mainboard.set(0, "\"" + odd + "\"");
        }
        return "{\"deck_id\":\"" + tag + "\",\"name\":\"" + tag + "\",\"commander_names\":[\""
                + ROGRAKH + "\"],\"mainboard\":[" + String.join(",", mainboard) + "],\"sideboard\":[]}";
    }

    /** A four-seat game parked at its first mulligan decision; seat 2 may hold one odd card. */
    private static BridgeEngine parkedAtFirstMulligan(String tag, String odd) {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = new ArrayList<>();
        for (int seat = 1; seat <= 4; seat++) {
            handles.add(BridgeTestSupport.importDeck(engine, "import-" + tag + "-" + seat,
                    deck(tag + "-" + seat, seat == 2 ? odd : null)));
        }
        BridgeTestSupport.createGame(engine, "c-" + tag, tag, handles);
        BridgeTestSupport.startGame(engine, tag);
        final BridgeSession session = engine.sessionsForTests().get(tag);
        final DecisionFrame starting = BridgeTestSupport.awaitFrame(session, 60000);
        Assert.assertNotNull(starting);
        Assert.assertEquals(starting.kind, DecisionFrame.Kind.STARTING_PLAYER);
        Assert.assertTrue(BridgeTestSupport.submitStartingPlayer(session, starting, "p1").applied);
        final DecisionFrame mulligan = BridgeTestSupport.awaitFrame(session, 60000);
        Assert.assertNotNull(mulligan, "session " + session.getStatus() + " " + session.getFailReason());
        Assert.assertEquals(mulligan.kind, DecisionFrame.Kind.MULLIGAN,
                "read at the first mulligan decision, before anything is answered");
        return engine;
    }

    private static JsonObject request(BridgeEngine engine, String tag) {
        return BridgeTestSupport.rpc(engine, "{\"protocol_version\":\"2.0.0\",\"request_id\":\"cs-"
                + tag + "\",\"message_type\":\"get_constructed_state\",\"game_id\":\"" + tag + "\"}");
    }

    private static JsonObject player(JsonObject state, String pid) {
        for (JsonElement element : state.getAsJsonArray("players")) {
            if (pid.equals(element.getAsJsonObject().get("player_id").getAsString())) {
                return element.getAsJsonObject();
            }
        }
        throw new AssertionError("no player " + pid);
    }

    /** The digest computed independently, as the Lab computes it from the record. */
    private static String expected(byte[] key, String seat, String... nameTabCount) throws Exception {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        final List<String> tokens = new ArrayList<>(Arrays.asList(
                StateProjection.CONSTRUCTED_STATE_SCHEMA, "library_and_hand", seat));
        tokens.addAll(Arrays.asList(nameTabCount));
        for (String token : tokens) {
            mac.update(token.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '\n');
        }
        final StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal()) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }

    @Test(timeOut = 240000)
    public void aLaunchWithoutAnOrchestrationKeyIsRefused() {
        OrchestrationKey.keyForTests(null);
        final BridgeEngine engine = parkedAtFirstMulligan("cs-nokey", null);
        BridgeTestSupport.assertError(request(engine, "cs-nokey"),
                BridgeErrors.ORCHESTRATION_CHANNEL_NOT_ENABLED);
    }

    @Test(timeOut = 240000)
    public void theConstructedStateIsTheRequestedNaturalGameStart() throws Exception {
        OrchestrationKey.keyForTests(KEY);
        final BridgeEngine engine = parkedAtFirstMulligan("cs-4p", null);
        final JsonObject response = request(engine, "cs-4p");
        BridgeTestSupport.assertOk(response);
        final JsonObject state = response.getAsJsonObject("payload").getAsJsonObject("constructed_state");
        Assert.assertEquals(state.get("schema").getAsString(), StateProjection.CONSTRUCTED_STATE_SCHEMA);
        Assert.assertEquals(state.get("observation_scope").getAsString(), "orchestration_keyed_digests");
        Assert.assertEquals(state.get("stack_size").getAsInt(), 0);
        Assert.assertEquals(state.getAsJsonArray("players").size(), 4);
        for (int seat = 1; seat <= 4; seat++) {
            final JsonObject player = player(state, "P" + seat);
            Assert.assertEquals(player.get("seat").getAsInt(), seat);
            Assert.assertEquals(player.get("life").getAsInt(), 40);
            Assert.assertEquals(player.get("poison").getAsInt(), 0);
            Assert.assertFalse(player.get("lost").getAsBoolean());
            Assert.assertFalse(player.get("left").getAsBoolean());
            Assert.assertEquals(player.get("hand_size").getAsInt(), 7, "opening hand drawn");
            Assert.assertEquals(player.get("library_size").getAsInt(), 92);
            Assert.assertEquals(player.get("library_and_hand_digest").getAsString(),
                    expected(KEY, "P" + seat, "Mountain\t99"),
                    "library and hand together are the 99-card main deck");
            Assert.assertEquals(player.get("battlefield_size").getAsInt(), 0);
            Assert.assertEquals(player.get("graveyard_size").getAsInt(), 0);
            Assert.assertEquals(player.get("exile_size").getAsInt(), 0);
            Assert.assertTrue(player.get("library_shuffles").getAsInt() >= 1,
                    "the engine shuffled this library before the opening draw (CR 103.3)");
            Assert.assertEquals(player.getAsJsonArray("commanders").size(), 1);
            final JsonObject commander = player.getAsJsonArray("commanders").get(0).getAsJsonObject();
            Assert.assertEquals(commander.get("card_identity").getAsString(), ROGRAKH);
            Assert.assertEquals(commander.get("owner").getAsString(), "P" + seat);
            Assert.assertEquals(commander.get("zone").getAsString(), "command");
            Assert.assertEquals(commander.get("prior_command_zone_cast_count").getAsInt(), 0);
            // Native attributes (schema /3): controlled by its owner, face up,
            // untapped, no counters, nothing attached.
            Assert.assertEquals(commander.get("controller").getAsString(),
                    commander.get("owner").getAsString());
            Assert.assertEquals(commander.getAsJsonObject("counters").size(), 0);
            Assert.assertFalse(commander.get("face_down").getAsBoolean());
            Assert.assertFalse(commander.get("tapped").getAsBoolean());
            Assert.assertEquals(commander.get("attachments").getAsInt(), 0);
        }
        final String text = response.toString();
        Assert.assertFalse(text.contains("Mountain"), "no hidden card name may appear: " + text);
    }

    /**
     * Counters are keyed by the engine's own counter name (CounterType.getName(),
     * as the battlefield projection emits it), lower-cased, never by the enum
     * constant: P1P1 is "+1/+1", ACQUIREDTASTE "acquired taste", CHARGE "charge".
     */
    @Test(timeOut = 240000)
    public void aCommandersCountersAreKeyedByTheirNativeNames() {
        OrchestrationKey.keyForTests(KEY);
        final BridgeEngine engine = parkedAtFirstMulligan("cs-counters", null);
        final BridgeSession session = engine.sessionsForTests().get("cs-counters");
        final forge.game.player.Player p1 = session.getGame().getPlayers().get(0);
        final forge.game.card.Card commander = p1.getCommanders().get(0);
        commander.setCounters(forge.game.card.CounterEnumType.P1P1, 2);
        commander.setCounters(forge.game.card.CounterEnumType.ACQUIREDTASTE, 1);
        commander.setCounters(forge.game.card.CounterEnumType.CHARGE, 3);
        final JsonObject response = request(engine, "cs-counters");
        BridgeTestSupport.assertOk(response);
        final JsonObject state = response.getAsJsonObject("payload").getAsJsonObject("constructed_state");
        // Exactly the one commander that was given counters carries them.
        JsonObject counters = null;
        for (JsonElement element : state.getAsJsonArray("players")) {
            final JsonObject seen = element.getAsJsonObject().getAsJsonArray("commanders").get(0)
                    .getAsJsonObject().getAsJsonObject("counters");
            if (seen.size() > 0) {
                Assert.assertNull(counters, "only one commander was given counters");
                counters = seen;
            }
        }
        Assert.assertNotNull(counters);
        Assert.assertEquals(counters.size(), 3, counters.toString());
        Assert.assertEquals(counters.get("+1/+1").getAsInt(), 2);
        Assert.assertEquals(counters.get("acquired taste").getAsInt(), 1);
        Assert.assertEquals(counters.get("charge").getAsInt(), 3);
        Assert.assertNull(counters.get("p1p1"));
        Assert.assertNull(counters.get("acquiredtaste"));
    }

    @Test(timeOut = 240000)
    public void aSubstituteCardChangesTheDigestAndAnotherKeyCannotTestAGuess() throws Exception {
        OrchestrationKey.keyForTests(KEY);
        final BridgeEngine engine = parkedAtFirstMulligan("cs-odd", "Lightning Bolt");
        final JsonObject response = request(engine, "cs-odd");
        BridgeTestSupport.assertOk(response);
        final JsonObject state = response.getAsJsonObject("payload").getAsJsonObject("constructed_state");
        final String odd = player(state, "P2").get("library_and_hand_digest").getAsString();
        Assert.assertNotEquals(odd, expected(KEY, "P2", "Mountain\t99"), "a substitute deck is not equal");
        Assert.assertEquals(odd, expected(KEY, "P2", "Lightning Bolt\t1", "Mountain\t98"));
        Assert.assertEquals(player(state, "P1").get("library_and_hand_digest").getAsString(),
                expected(KEY, "P1", "Mountain\t99"));
        final byte[] other = new byte[32];
        other[0] = 1;
        Assert.assertNotEquals(odd, expected(other, "P2", "Lightning Bolt\t1", "Mountain\t98"),
                "without the launch key a guess cannot be tested");
    }

    @Test
    public void theCapabilitiesDeclareTheOrchestrationScope() {
        final BridgeEngine engine = new BridgeEngine();
        final JsonObject caps = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"caps\",\"message_type\":\"get_capabilities\"}")
                .getAsJsonObject("payload").getAsJsonObject("capabilities");
        Assert.assertTrue(caps.get("constructed_state_supported").getAsBoolean());
        Assert.assertEquals(caps.get("constructed_state_scope").getAsString(),
                "orchestration_keyed_digests_refused_without_launch_key");
    }
}
