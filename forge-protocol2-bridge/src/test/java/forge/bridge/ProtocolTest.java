package forge.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Protocol-2 envelope tests: parsing, version gate, identity and capabilities.
 * No engine initialization required; the handshake surface is stateless.
 */
public class ProtocolTest {

    @BeforeClass
    public void ensureIdentity() {
        // Gameplay handlers (including start_engine) require a bound engine identity.
        // Bind a shape-valid test value only when the operator bound nothing.
        if (System.getProperty("forge.engine.sha") == null
                && System.getenv("FORGE_ENGINE_SHA") == null) {
            System.setProperty("forge.engine.sha", "0000000000000000000000000000000000000000");
        }
    }

    private static JsonObject dispatch(String json) {
        final BridgeEngine engine = new BridgeEngine();
        try {
            return JsonParser.parseString(engine.dispatch(BridgeProtocol.parse(json))).getAsJsonObject();
        } catch (BridgeProtocol.MalformedRequestException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void testParseCanonicalRequest() throws Exception {
        final BridgeProtocol.Request request = BridgeProtocol.parse(
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"r1\","
                        + "\"message_type\":\"get_game_state\",\"game_id\":\"g1\","
                        + "\"payload\":{\"observer_player_id\":\"p1\"}}");
        Assert.assertEquals(request.requestId, "r1");
        Assert.assertEquals(request.protocolVersion, "2.0.0");
        Assert.assertEquals(request.messageType, "get_game_state");
        Assert.assertEquals(request.gameId, "g1");
        Assert.assertEquals(request.payload.get("observer_player_id").getAsString(), "p1");
    }

    @Test
    public void testParseLegacyAliases() throws Exception {
        final BridgeProtocol.Request request = BridgeProtocol.parse(
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"r2\","
                        + "\"method\":\"get_game_state\",\"params\":{\"a\":1}}");
        Assert.assertEquals(request.messageType, "get_game_state");
        Assert.assertEquals(request.payload.get("a").getAsInt(), 1);
    }

    @Test(expectedExceptions = BridgeProtocol.MalformedRequestException.class)
    public void testMalformedLineRejected() throws Exception {
        BridgeProtocol.parse("this is not json");
    }

    @Test(expectedExceptions = BridgeProtocol.MalformedRequestException.class)
    public void testNonObjectRejected() throws Exception {
        BridgeProtocol.parse("[1,2,3]");
    }

    @Test
    public void testMalformedLineYieldsBoundedError() {
        final String response = BridgeProtocol.error("", BridgeErrors.MALFORMED_REQUEST, "nope", 0);
        final JsonObject obj = JsonParser.parseString(response).getAsJsonObject();
        Assert.assertFalse(obj.get("success").getAsBoolean());
        Assert.assertEquals(obj.get("protocol_version").getAsString(), "2.0.0");
    }

    @Test
    public void testProtocolVersionMismatchRejected() {
        final JsonObject response = dispatch(
                "{\"protocol_version\":\"1.0.0\",\"request_id\":\"r3\",\"message_type\":\"start_engine\"}");
        Assert.assertFalse(response.get("success").getAsBoolean());
        Assert.assertEquals(response.get("errors").getAsJsonArray().get(0).getAsJsonObject()
                .get("code").getAsString(), BridgeErrors.PROTOCOL_VERSION_MISMATCH);
    }

    @Test
    public void testMissingProtocolVersionRejected() {
        final JsonObject response = dispatch(
                "{\"request_id\":\"r3b\",\"message_type\":\"start_engine\"}");
        Assert.assertFalse(response.get("success").getAsBoolean());
        Assert.assertEquals(response.get("errors").getAsJsonArray().get(0).getAsJsonObject()
                .get("code").getAsString(), BridgeErrors.PROTOCOL_VERSION_MISMATCH);
    }

    @Test(expectedExceptions = BridgeProtocol.MalformedRequestException.class)
    public void testMissingRequestIdRejected() throws Exception {
        BridgeProtocol.parse("{\"protocol_version\":\"2.0.0\",\"message_type\":\"start_engine\"}");
    }

    @Test(expectedExceptions = BridgeProtocol.MalformedRequestException.class)
    public void testEmptyRequestIdRejected() throws Exception {
        BridgeProtocol.parse(
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"\",\"message_type\":\"start_engine\"}");
    }

    @Test
    public void testMatchingAliasesSucceed() throws Exception {
        final BridgeProtocol.Request request = BridgeProtocol.parse(
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"r5\","
                        + "\"message_type\":\"get_capabilities\",\"method\":\"get_capabilities\","
                        + "\"payload\":{\"a\":1},\"params\":{\"a\":1}}");
        Assert.assertEquals(request.messageType, "get_capabilities");
        Assert.assertEquals(request.payload.get("a").getAsInt(), 1);
    }

    @Test(expectedExceptions = BridgeProtocol.MalformedRequestException.class)
    public void testContradictoryMethodRejected() throws Exception {
        BridgeProtocol.parse(
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"r6\","
                        + "\"message_type\":\"get_capabilities\",\"method\":\"start_engine\"}");
    }

    @Test(expectedExceptions = BridgeProtocol.MalformedRequestException.class)
    public void testContradictoryParamsRejected() throws Exception {
        BridgeProtocol.parse(
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"r7\","
                        + "\"message_type\":\"get_capabilities\","
                        + "\"payload\":{\"a\":1},\"params\":{\"a\":2}}");
    }

    @Test
    public void testIdentityMissingFailsClosed() {
        final String saved = System.getProperty("forge.engine.sha");
        System.setProperty("forge.engine.sha", "not-a-sha");
        try {
            final JsonObject response = dispatch("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"noid\",\"message_type\":\"start_engine\"}");
            Assert.assertFalse(response.get("success").getAsBoolean());
            Assert.assertEquals(response.get("errors").getAsJsonArray().get(0).getAsJsonObject()
                    .get("code").getAsString(), BridgeErrors.ENGINE_IDENTITY_UNAVAILABLE);
        } finally {
            if (saved == null) {
                System.clearProperty("forge.engine.sha");
            } else {
                System.setProperty("forge.engine.sha", saved);
            }
        }
    }

    @Test
    public void testUnknownMessageRejected() {
        final JsonObject response = dispatch(
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"r4\",\"message_type\":\"do_magic\"}");
        Assert.assertFalse(response.get("success").getAsBoolean());
        Assert.assertEquals(response.get("errors").getAsJsonArray().get(0).getAsJsonObject()
                .get("code").getAsString(), BridgeErrors.UNKNOWN_MESSAGE);
    }

    @Test
    public void testStartEngineHandshake() {
        final JsonObject first = dispatch(
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"s1\",\"message_type\":\"start_engine\"}");
        Assert.assertTrue(first.get("success").getAsBoolean());
        Assert.assertEquals(first.get("payload").getAsJsonObject().get("status").getAsString(),
                "started");
        Assert.assertFalse(first.get("payload").getAsJsonObject().get("already_started").getAsBoolean());
        final JsonObject second = dispatch(
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"s2\",\"message_type\":\"start_engine\"}");
        // Same engine instance is stateful across requests.
        Assert.assertTrue(second.get("success").getAsBoolean());
    }

    @Test
    public void testProviderVersionIdentity() {
        final JsonObject response = dispatch("{\"protocol_version\":\"2.0.0\",\"request_id\":\"v1\","
                + "\"message_type\":\"get_provider_version\"}");
        Assert.assertTrue(response.get("success").getAsBoolean());
        final JsonObject payload = response.get("payload").getAsJsonObject();
        Assert.assertEquals(payload.get("provider").getAsString(), "forge");
        Assert.assertEquals(payload.get("release").getAsString(), "2.0.14");
        Assert.assertEquals(payload.get("protocol_version").getAsString(), "2.0.0");
        Assert.assertEquals(payload.get("bridge_name").getAsString(), "forge-protocol2-bridge");
        Assert.assertFalse(payload.get("bridge_version").getAsString().isEmpty());
        // Order-independent contract: the wire reports exactly what the resolver holds.
        Assert.assertEquals(payload.get("engine_commit").getAsString(), VersionInfo.engineCommit());
        Assert.assertEquals(
                payload.get("engine_commit_source").getAsString(), VersionInfo.engineCommitSource());
        final String commit = payload.get("engine_commit").getAsString();
        if ("unknown".equals(commit)) {
            Assert.assertTrue(VersionInfo.engineCommitSource().endsWith("invalid")
                    || "unavailable".equals(VersionInfo.engineCommitSource()));
        } else {
            Assert.assertTrue(commit.matches("[0-9a-f]{40}"), "commit: " + commit);
        }
        Assert.assertEquals(response.get("request_id").getAsString(), "v1");
    }

    @Test
    public void testCapabilitiesAreConservative() {
        final JsonObject response = dispatch("{\"protocol_version\":\"2.0.0\",\"request_id\":\"c1\","
                + "\"message_type\":\"get_capabilities\"}");
        Assert.assertTrue(response.get("success").getAsBoolean());
        final JsonObject caps = response.get("payload").getAsJsonObject()
                .getAsJsonObject("capabilities");
        Assert.assertTrue(caps.get("commander_supported").getAsBoolean());
        Assert.assertTrue(caps.get("multiplayer_supported").getAsBoolean());
        Assert.assertTrue(caps.get("deck_import_supported").getAsBoolean());
        Assert.assertTrue(caps.get("headless_supported").getAsBoolean());
        // Global action flags stay false: only a bounded subset is proven.
        Assert.assertFalse(caps.get("legal_actions_supported").getAsBoolean());
        Assert.assertFalse(caps.get("action_submission_supported").getAsBoolean());
        Assert.assertFalse(caps.get("seed_supported").getAsBoolean());
        Assert.assertFalse(caps.get("replay_supported").getAsBoolean());
        // R14: external event export disabled for principal privacy (audit internal).
        Assert.assertFalse(caps.get("event_log_supported").getAsBoolean());
        Assert.assertFalse(caps.get("target_selection_supported").getAsBoolean());
        Assert.assertFalse(caps.get("mode_selection_supported").getAsBoolean());
        Assert.assertFalse(caps.get("trigger_order_supported").getAsBoolean());
        Assert.assertFalse(caps.get("mulligan_supported").getAsBoolean());
        Assert.assertFalse(caps.get("concede_supported").getAsBoolean());
        Assert.assertEquals(caps.get("runtime_kind").getAsString(), "external_rules_engine");
        Assert.assertTrue(caps.get("notes").getAsJsonArray().size() > 0);
    }

    @Test
    public void testUnsupportedMessagesFailClosed() {
        final String[] messages = { "add_player", "select_targets", "choose_modes", "order_triggers",
                "concede", "export_replay", "engine_hello", "probe" };
        for (String message : messages) {
            final JsonObject response = dispatch("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"u-" + message + "\",\"message_type\":\"" + message + "\"}");
            Assert.assertFalse(response.get("success").getAsBoolean(), message);
            Assert.assertEquals(response.get("request_id").getAsString(), "u-" + message);
        }
    }

    @Test
    public void testImportRequiresStartedEngine() {
        final JsonObject response = dispatch("{\"protocol_version\":\"2.0.0\",\"request_id\":\"e1\","
                + "\"message_type\":\"import_deck\",\"payload\":{\"deck\":{}}}");
        Assert.assertFalse(response.get("success").getAsBoolean());
        Assert.assertEquals(response.get("errors").getAsJsonArray().get(0).getAsJsonObject()
                .get("code").getAsString(), BridgeErrors.ENGINE_NOT_STARTED);
    }
}
