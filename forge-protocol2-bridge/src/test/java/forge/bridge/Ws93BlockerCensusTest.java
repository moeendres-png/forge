package forge.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.StaticData;
import forge.item.PaperCard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * WS93 test-only blocker census harness. Zero production changes.
 *
 * <p>Actual-card grounding: every slot's key card names are resolved against the real
 * Forge CardDb (fails closed if unresolvable). Protocol blockers (targets / modes /
 * triggers / concede) are RUNTIME_VERIFIED through real {@link BridgeEngine#dispatch}.
 * Classifier blockers are recorded as CODE_DERIVED from the pinned adjudication and
 * explicitly labeled as such; no per-card engine reachability beyond the first
 * blocker is claimed.
 */
public final class Ws93BlockerCensusTest {

    private static String req(String requestId, String messageType, String payload) {
        return "{\"protocol_version\":\"2.0.0\",\"request_id\":\"" + requestId
                + "\",\"message_type\":\"" + messageType + "\",\"payload\":" + payload + "}";
    }

    private static String errorCode(JsonObject response) {
        return response.get("errors").getAsJsonArray().get(0).getAsJsonObject()
                .get("code").getAsString();
    }

    private static void resolveCards(String slot, String... names) {
        for (String name : names) {
            final PaperCard card = StaticData.instance().getCommonCards().getCard(name);
            Assert.assertNotNull(card, slot + ": unresolvable actual card: " + name);
        }
    }

    @Test
    public void censusFirstBlockers() throws Exception {
        BridgeTestSupport.ensureEngine();
        final BridgeEngine engine = new BridgeEngine();
        final List<Map<String, String>> rows = new ArrayList<>();

        // Runtime-verify protocol-level blockers on a real engine instance.
        BridgeTestSupport.assertOk(BridgeTestSupport.rpc(engine, req("s", "start_engine", "{}")));
        final Map<String, String> proto = new LinkedHashMap<>();
        proto.put("select_targets",
                errorCode(BridgeTestSupport.rpc(engine, req("t", "select_targets", "{}"))));
        proto.put("choose_modes",
                errorCode(BridgeTestSupport.rpc(engine, req("m", "choose_modes", "{}"))));
        proto.put("order_triggers",
                errorCode(BridgeTestSupport.rpc(engine, req("o", "order_triggers", "{}"))));
        proto.put("concede", errorCode(BridgeTestSupport.rpc(engine, req("c", "concede", "{}"))));
        Assert.assertFalse(proto.get("select_targets").isEmpty());
        Assert.assertFalse(proto.get("choose_modes").isEmpty());
        Assert.assertFalse(proto.get("order_triggers").isEmpty());
        Assert.assertFalse(proto.get("concede").isEmpty());

        // Actual-card grounding per slot (real CardDb resolution) + first-blocker mapping.
        // Blocker codes: P=protocol runtime-verified; C=classifier code-derived.
        add(rows, "RQ-C3-A03", "Drudge Skeletons,Lightning Bolt,Swamp,Mountain",
                "TARGETING", "C+P",
                "first scripted cast needs target selection; classifier TARGETING + select_targets unsupported");
        resolveCards("RQ-C3-A03", "Drudge Skeletons", "Lightning Bolt", "Swamp", "Mountain");
        add(rows, "RQ-C3-A04", "Stonecoil Serpent,Doubling Season,Hardened Scales",
                "X_VALUE", "C",
                "scripted X=3 blocked before replacement ordering is reached");
        resolveCards("RQ-C3-A04", "Stonecoil Serpent", "Doubling Season", "Hardened Scales");
        add(rows, "RQ-C3-B01", "Llanowar Elves,Soul Warden,Forest",
                "MANA_PAYMENT_CHOICE", "C",
                "Elves cast requires nonzero mana payment; trigger ordering additionally blocked");
        resolveCards("RQ-C3-B01", "Llanowar Elves", "Soul Warden", "Forest");
        add(rows, "RQ-C3-C01", "Force of Will,Llanowar Elves,Turn to Frog,Island,Forest",
                "TARGETING", "C+P",
                "Force targets Elves-spell; alternate-cost + hidden-zone selection further blocked");
        resolveCards("RQ-C3-C01", "Force of Will", "Llanowar Elves", "Turn to Frog", "Island", "Forest");
        add(rows, "RQ-C3-C03", "Fireball,Mountain",
                "X_VALUE", "C",
                "scripted X=5 blocked; targeting + nonzero payment further blocked");
        resolveCards("RQ-C3-C03", "Fireball", "Mountain");
        add(rows, "RQ-C3-D06", "Casualties of War,Ornithopter,Runeclaw Bear,Forest,Swamp",
                "MODAL", "C+P",
                "Charm modal + per-mode targets; choose_modes + select_targets unsupported");
        resolveCards("RQ-C3-D06", "Casualties of War", "Ornithopter", "Runeclaw Bear", "Forest", "Swamp");
        add(rows, "RQ-C3-E01", "Runeclaw Bear,Propaganda,Island",
                "DECLARE_ATTACKERS", "C",
                "combat declaration callback unsupported; Propaganda tax unreachable");
        resolveCards("RQ-C3-E01", "Runeclaw Bear", "Propaganda", "Island");
        add(rows, "RQ-C3-E02", "Carnage Tyrant,Runeclaw Bear,Llanowar Elves",
                "DECLARE_ATTACKERS", "C",
                "attack declaration precedes blockers + damage assignment (both also blocked)");
        resolveCards("RQ-C3-E02", "Carnage Tyrant", "Runeclaw Bear", "Llanowar Elves");
        add(rows, "RQ-C3-F01", "Rampant Growth,Forest",
                "MANA_PAYMENT_CHOICE", "C",
                "nonzero cast payment precedes search + hidden-zone selection (both also blocked)");
        resolveCards("RQ-C3-F01", "Rampant Growth", "Forest");
        add(rows, "RQ-C3-G02", "Murder,Ghalta, Primal Hunger,Swamp,Forest",
                "TARGETING", "C+P",
                "Murder needs target; Commander movement + taxed recast further blocked");
        resolveCards("RQ-C3-G02", "Murder", "Ghalta, Primal Hunger", "Swamp", "Forest");
        add(rows, "RQ-C3-G03", "Ghalta, Primal Hunger",
                "DECLARE_ATTACKERS", "C",
                "attack declaration precedes blockers; commander-damage observation is visible");
        resolveCards("RQ-C3-G03", "Ghalta, Primal Hunger");
        add(rows, "RQ-C3-G04", "Control Magic",
                "PROTOCOL_CONCEDE_UNSUPPORTED", "P",
                "engine-native concession exists (WS59/WS76) but is NOT_REACHED via bridge");
        resolveCards("RQ-C3-G04", "Control Magic");
        add(rows, "RQ-C3-H01", "Clone,Humility,Grizzly Bears,Island",
                "MANA_PAYMENT_CHOICE", "C",
                "Clone cast needs nonzero payment; copy-choice selection further blocked");
        resolveCards("RQ-C3-H01", "Clone", "Humility", "Grizzly Bears", "Island");
        add(rows, "RQ-C3-I01", "Momentary Blink,Runeclaw Bear",
                "TARGETING", "C+P",
                "Blink targets own Bear; nonzero payment further blocked");
        resolveCards("RQ-C3-I01", "Momentary Blink", "Runeclaw Bear");
        add(rows, "RQ-C3-J02", "Delina, Wild Mage,Runeclaw Bear",
                "DECLARE_ATTACKERS", "C",
                "attack declaration precedes triggered target selection (also blocked)");
        resolveCards("RQ-C3-J02", "Delina, Wild Mage", "Runeclaw Bear");

        final StringBuilder json = new StringBuilder("{\"slots\":[");
        for (int i = 0; i < rows.size(); i++) {
            final Map<String, String> r = rows.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"slot\":\"").append(r.get("slot")).append("\",");
            json.append("\"cards\":\"").append(r.get("cards").replace("\"", "'")).append("\",");
            json.append("\"first_blocker\":\"").append(r.get("first_blocker")).append("\",");
            json.append("\"evidence\":\"").append(r.get("evidence")).append("\",");
            json.append("\"note\":\"").append(r.get("note").replace("\"", "'")).append("\"}");
        }
        json.append("],\"protocol_blockers_runtime\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : proto.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("\"").append(e.getKey()).append("\":\"").append(e.getValue()).append("\"");
        }
        json.append("},\"behavior_scoring\":\"NOT_RUN\"}");
        final JsonObject parsed = JsonParser.parseString(json.toString()).getAsJsonObject();
        Assert.assertEquals(parsed.getAsJsonArray("slots").size(), 15);

        final Path out = Paths.get(System.getProperty("ws93.census.out",
                "/tmp/ws93-blocker-census.json"));
        Files.write(out, json.toString().getBytes(StandardCharsets.UTF_8));
        System.err.println("[ws93] census slots=15 -> " + out);
    }

    private static void add(List<Map<String, String>> rows, String slot, String cards,
            String blocker, String evidence, String note) {
        final Map<String, String> r = new LinkedHashMap<>();
        r.put("slot", slot);
        r.put("cards", cards);
        r.put("first_blocker", blocker);
        r.put("evidence", evidence);
        r.put("note", note);
        rows.add(r);
    }
}
