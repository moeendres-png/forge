package forge.bridge;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.google.gson.JsonObject;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * WSR30 / PB-08 creation-seed acknowledgement.
 *
 * <p>The committed evidence shows the gap: Forge declares
 * {@code seed_supported: true}, the Lab driver sends the seed, and the live
 * game state carries an explicit root seed — but {@code create_commander_game}
 * acknowledged nothing, so the Lab classified Forge as
 * {@code UNCONTROLLED_ENGINE_RNG} from the create response alone.
 *
 * <p>The acknowledgement is derived from the engine's own accepted state
 * ({@code MyRandom.getRootSeed()/isExplicitSeed()}), never from the request
 * value. A request echo proves what the caller sent, not what the Rules Core
 * accepted, so the negative controls here force engine rejection and engine
 * divergence and require the create transaction to fail closed.
 */
public class Wsr30SeedAcknowledgementTest {

    private static final long SEED = 424242L;

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    @AfterMethod
    public void clearEngineRngState() {
        BridgeSession.seedInstallFaultForTests = null;
        forge.util.MyRandom.clearBinding();
    }

    private static BridgeEngine engineWithPod(List<String> handles) {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        handles.addAll(BridgeTestSupport.importPod(engine, 2));
        return engine;
    }

    private static JsonObject create(BridgeEngine engine, String gameId, List<String> handles,
            String extraRequestFields) {
        final StringBuilder decks = new StringBuilder();
        for (int i = 0; i < handles.size(); i++) {
            if (i > 0) {
                decks.append(',');
            }
            decks.append('"').append(handles.get(i)).append('"');
        }
        final String fields = extraRequestFields == null ? "" : "," + extraRequestFields;
        return BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"create-" + gameId
                        + "\",\"message_type\":\"create_commander_game\",\"payload\":{\"request\":"
                        + "{\"game_id\":\"" + gameId + "\",\"format\":\"commander\","
                        + "\"deck_handles\":[" + decks + "]" + fields + "}}}");
    }

    private static JsonObject publicState(BridgeEngine engine, String gameId) {
        final JsonObject response = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"state-" + gameId
                        + "\",\"message_type\":\"get_game_state\",\"game_id\":\"" + gameId
                        + "\",\"payload\":{}}");
        BridgeTestSupport.assertOk(response);
        return response.get("payload").getAsJsonObject().getAsJsonObject("state");
    }

    @Test(timeOut = 300000)
    public void createAcknowledgesTheEngineBoundSeed() {
        final List<String> handles = new ArrayList<>();
        final BridgeEngine engine = engineWithPod(handles);
        final JsonObject response =
                create(engine, "wsr30-seed-ack", handles, "\"seed\":" + SEED);
        BridgeTestSupport.assertOk(response);
        final JsonObject rng =
                response.get("payload").getAsJsonObject().getAsJsonObject("rng");
        assertNotNull(rng, "the create response must carry the engine acknowledgement: " + response);
        assertEquals(rng.get("rules_seed").getAsLong(), SEED);
        assertTrue(rng.get("explicit_seed").getAsBoolean());
        // Same-JVM readback: what was acknowledged is the engine's accepted state.
        assertTrue(forge.util.MyRandom.isExplicitSeed());
        assertEquals(forge.util.MyRandom.getRootSeed().longValue(), SEED);

        BridgeTestSupport.startGame(engine, "wsr30-seed-ack");
        try {
            final JsonObject binding =
                    publicState(engine, "wsr30-seed-ack").getAsJsonObject("rng_binding");
            assertTrue(binding.get("explicit_seed").getAsBoolean(),
                    "the running game must report an explicit binding: " + binding);
            assertEquals(binding.get("root_seed").getAsLong(), SEED);
        } finally {
            engine.sessionsForTests().get("wsr30-seed-ack").shutdown(5000);
        }
    }

    @Test(timeOut = 300000)
    public void acknowledgementIsNotARequestEcho() {
        final List<String> handles = new ArrayList<>();
        final BridgeEngine engine = engineWithPod(handles);
        // A decoy rules_seed the bridge must ignore: the acknowledgement has to
        // come from the engine's accepted state, not from request fields.
        final JsonObject response = create(engine, "wsr30-seed-decoy", handles,
                "\"seed\":" + SEED + ",\"rules_seed\":111");
        BridgeTestSupport.assertOk(response);
        final JsonObject rng =
                response.get("payload").getAsJsonObject().getAsJsonObject("rng");
        assertEquals(rng.get("rules_seed").getAsLong(), SEED,
                "the acknowledgement follows the engine, not the request decoy");
        assertFalse(rng.get("rules_seed").getAsLong() == 111L);
    }

    @Test(timeOut = 120000)
    public void engineRejectionFailsClosedAndRegistersNoSession() {
        final List<String> handles = new ArrayList<>();
        final BridgeEngine engine = engineWithPod(handles);
        BridgeSession.seedInstallFaultForTests = "reject";
        final JsonObject response =
                create(engine, "wsr30-seed-reject", handles, "\"seed\":" + SEED);
        BridgeTestSupport.assertError(response, BridgeErrors.SEED_UNSUPPORTED);
        // The failed creation must not be reachable as a live session.
        final JsonObject probe = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"probe\","
                        + "\"message_type\":\"get_game_state\",\"game_id\":\"wsr30-seed-reject\","
                        + "\"payload\":{}}");
        BridgeTestSupport.assertError(probe, BridgeErrors.UNKNOWN_GAME);
    }

    @Test(timeOut = 120000)
    public void engineDivergenceFailsClosed() {
        final List<String> handles = new ArrayList<>();
        final BridgeEngine engine = engineWithPod(handles);
        // The engine binds a different seed than the caller requested. The
        // transaction must refuse to call that an acceptance.
        BridgeSession.seedInstallFaultForTests = "mismatch";
        final JsonObject response =
                create(engine, "wsr30-seed-mismatch", handles, "\"seed\":" + SEED);
        BridgeTestSupport.assertError(response, BridgeErrors.SEED_UNSUPPORTED);
        assertEquals(forge.util.MyRandom.getRootSeed().longValue(), SEED + 1,
                "the negative control really did diverge");
    }

    @Test(timeOut = 300000)
    public void noSeedRequestedMeansNoAcknowledgement() {
        final List<String> handles = new ArrayList<>();
        final BridgeEngine engine = engineWithPod(handles);
        final JsonObject response = create(engine, "wsr30-seed-none", handles, null);
        BridgeTestSupport.assertOk(response);
        assertFalse(response.get("payload").getAsJsonObject().has("rng"),
                "an unseeded creation must acknowledge nothing: " + response);
    }

    @Test(timeOut = 300000)
    public void unseededGameDoesNotInheritAStaleExplicitBinding() {
        final List<String> handles = new ArrayList<>();
        final BridgeEngine engine = engineWithPod(handles);
        // A stale explicit binding, exactly what an abandoned seeded create
        // leaves behind. An unseeded game must not run under it while the
        // evidence still claims UNCONTROLLED_ENGINE_RNG.
        forge.util.MyRandom.bindSeed(999999L);
        assertTrue(forge.util.MyRandom.isExplicitSeed());

        final JsonObject unseeded = create(engine, "wsr30-seed-b", handles, null);
        BridgeTestSupport.assertOk(unseeded);
        BridgeTestSupport.startGame(engine, "wsr30-seed-b");
        try {
            final JsonObject state = publicState(engine, "wsr30-seed-b");
            final JsonObject binding = state.getAsJsonObject("rng_binding");
            assertTrue(binding.get("root_seed").isJsonNull(),
                    "an unseeded game must not run under a stale seed: " + binding);
            assertFalse(binding.get("explicit_seed").getAsBoolean(),
                    "an unseeded game must report an uncontrolled binding: " + binding);
            assertFalse(forge.util.MyRandom.isExplicitSeed());
            assertNull(forge.util.MyRandom.getRootSeed());
        } finally {
            engine.sessionsForTests().get("wsr30-seed-b").shutdown(5000);
        }
    }
}
