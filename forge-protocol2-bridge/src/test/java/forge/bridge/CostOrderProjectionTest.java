package forge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/**
 * Structured Protocol-2 projection for CR 601.2h cost-order pilot decisions.
 */
public class CostOrderProjectionTest {

    @Test
    public void projectsNativeCostOrderIndicesWithoutLabelParsing() {
        final DecisionFrame.Option option = DecisionFrame.costOrderOption(
                "cost_order",
                "Pay order: #1 second; #0 first;",
                new ArrayList<>(),
                "COST_ORDER",
                Arrays.asList(Integer.valueOf(1), Integer.valueOf(0)));
        final DecisionFrame frame = new DecisionFrame(
                17L,
                DecisionFrame.Kind.ORDER_CHOICE,
                DecisionFrame.Status.SUPPORTED,
                "",
                "p1",
                0,
                Collections.singletonList(option),
                "pre-state");

        final JsonObject action = StateProjection.legalAction(frame, option);
        final JsonArray indices = action.getAsJsonObject("metadata")
                .getAsJsonArray("cost_order_indices");

        Assert.assertNotNull(indices, "cost order must be machine-readable");
        Assert.assertEquals(indices.size(), 2);
        Assert.assertEquals(indices.get(0).getAsInt(), 1);
        Assert.assertEquals(indices.get(1).getAsInt(), 0);
        Assert.assertEquals(action.get("action_type").getAsString(), "cost_order");
    }

    @Test
    public void ordinaryPayloadOptionDoesNotInventCostOrderMetadata() {
        final DecisionFrame.Option option = DecisionFrame.payloadOption(
                "choose_mode", "Mode", null, "native", "STRING");
        final DecisionFrame frame = new DecisionFrame(
                18L,
                DecisionFrame.Kind.MODE_SELECTION,
                DecisionFrame.Status.SUPPORTED,
                "",
                "p1",
                0,
                Collections.singletonList(option),
                "pre-state");

        final JsonObject action = StateProjection.legalAction(frame, option);
        Assert.assertFalse(action.getAsJsonObject("metadata").has("cost_order_indices"));
    }
}
