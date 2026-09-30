package forge.bridge;

import com.google.gson.JsonObject;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

/**
 * R-5 / AF01 regression: an explicit decision_class on get_legal_actions
 * must name the Rules-Core decision that is actually pending.
 */
public class Af01UnsupportedDecisionClassTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    @Test
    public void mismatchedDecisionClassFailsClosedWithoutMutation() {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = BridgeTestSupport.importPod(engine, 4);
        final String gameId = "af01-unsupported-decision-class";
        BridgeTestSupport.createGame(engine, "create-af01", gameId, handles);
        BridgeTestSupport.startGame(engine, gameId);

        final BridgeSession session = engine.sessionsForTests().get(gameId);
        Assert.assertNotNull(session, "live Forge session must exist");
        final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 30000);
        Assert.assertNotNull(frame, "Rules Core must park a decision");

        final String actor = frame.actorPlayerId;
        final long revision = frame.revision;

        final JsonObject baseline = BridgeTestSupport.legalActions(engine, gameId, actor);
        final String actualClass = baseline.getAsJsonObject("decision")
                .get("decision_class").getAsString();
        Assert.assertFalse(actualClass.isEmpty(), "pending decision class must be explicit");

        final JsonObject rejected = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\","
                        + "\"request_id\":\"af01-bad-class\","
                        + "\"message_type\":\"get_legal_actions\","
                        + "\"game_id\":\"" + gameId + "\","
                        + "\"payload\":{\"actor_id\":\"" + actor + "\","
                        + "\"decision_class\":\"wsr22_unsupported_decision_class\"}}");
        BridgeTestSupport.assertError(rejected, BridgeErrors.UNSUPPORTED_DECISION);

        final DecisionFrame unchanged = session.getCurrentFrame();
        Assert.assertNotNull(unchanged, "rejection must not consume the pending decision");
        Assert.assertEquals(unchanged.revision, revision, "rejection mutated the decision revision");
        Assert.assertEquals(unchanged.actorPlayerId, actor, "rejection mutated the decision actor");
        Assert.assertEquals(unchanged.kind, frame.kind, "rejection mutated the decision kind");

        final JsonObject accepted = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\","
                        + "\"request_id\":\"af01-good-class\","
                        + "\"message_type\":\"get_legal_actions\","
                        + "\"game_id\":\"" + gameId + "\","
                        + "\"payload\":{\"actor_id\":\"" + actor + "\","
                        + "\"decision_class\":\"" + actualClass + "\"}}");
        BridgeTestSupport.assertOk(accepted);
        Assert.assertEquals(accepted.getAsJsonObject("payload")
                        .getAsJsonObject("decision").get("decision_class").getAsString(),
                actualClass, "matching class must preserve the authoritative decision");
        Assert.assertEquals(session.getCurrentFrame().revision, revision,
                "read-only legal-action retrieval must not consume the decision");
    }
}
