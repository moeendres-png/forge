package forge.bridge;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.StaticData;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AF03: Commander deck legality is decided by the Rules Core.
 *
 * <p>{@code import_deck} resolved card names and checked the commander count and
 * the 100-card total, but never asked the engine whether the deck was a legal
 * Commander deck. The Lab's negative probe proved the consequence: the executed
 * bridge ACCEPTED a real non-Commander card as commander, and a deck whose colour
 * identity violated its commander.
 *
 * <p>These tests assert the fix at the provider boundary, and assert that the fix
 * is the engine's own validation rather than a bridge-side reimplementation: the
 * same decks are run through {@link DeckFormat} directly, so if the bridge ever
 * grows a private legality rule the two diverge and the test fails.
 */
class Wsr28CommanderLegalityTest {

    /** The contract-legal white commander used by build_deck on the Lab side. */
    private static final String WHITE_COMMANDER = "Isamaru, Hound of Konda";

    /** A non-basic, non-legendary black land, so only colour identity is wrong. */
    private static final String BLACK_NONBASIC = "Shivan Reef";

    private static final int MAINBOARD = 99;

    /** The card database is required; the shared support loads it once. */
    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    private static final String DECK_TEMPLATE = "{\"protocol_version\":\"2.0.0\","
            + "\"request_id\":\"%s\",\"message_type\":\"import_deck\","
            + "\"payload\":{\"deck\":{\"deck_id\":\"wsr28\",\"name\":\"WSR28 probe\","
            + "\"commander_names\":[\"%s\"],\"mainboard\":[%s]}}}";

    private static String repeat(String card) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAINBOARD; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(card).append('"');
        }
        return sb.toString();
    }

    private static String shortDeck() {
        return "\"Plains\"";
    }

    /** A started engine. The bridge is stateful, so it cannot be recreated per call. */
    private static BridgeEngine started() {
        final BridgeEngine engine = new BridgeEngine();
        engine.dispatch(parse("{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"h\",\"message_type\":\"engine_hello\"}"));
        engine.dispatch(parse("{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"c\",\"message_type\":\"get_capabilities\"}"));
        engine.dispatch(parse("{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"s\",\"message_type\":\"start_engine\"}"));
        return engine;
    }

    private static BridgeProtocol.Request parse(String json) {
        try {
            return BridgeProtocol.parse(json);
        } catch (BridgeProtocol.MalformedRequestException e) {
            throw new AssertionError(e);
        }
    }

    private static JsonObject send(BridgeEngine engine, String json) {
        return JsonParser.parseString(engine.dispatch(parse(json))).getAsJsonObject();
    }

    private static JsonObject importDeck(BridgeEngine engine, String commander, String cards) {
        return send(engine, String.format(DECK_TEMPLATE, "d", commander, cards));
    }

    @Test(timeOut = 180000)
    void legalMonoColourCommanderDeckIsAccepted() {
        final JsonObject response = importDeck(started(), WHITE_COMMANDER, repeat("Plains"));
        assertTrue(response.get("success").getAsBoolean(), response.toString());
    }

    @Test(timeOut = 180000)
    void nonCommanderCardAsCommanderIsRefused() {
        final JsonObject response = importDeck(started(), "Hill Giant", repeat("Plains"));
        assertFalse(response.get("success").getAsBoolean(), response.toString());
        assertTrue(response.toString().contains("illegal commander"), response.toString());
    }

    @Test(timeOut = 180000)
    void colourIdentityViolationIsRefusedForThatReason() {
        final JsonObject response = importDeck(started(), WHITE_COMMANDER, repeat(BLACK_NONBASIC));
        assertFalse(response.get("success").getAsBoolean(), response.toString());
        // The engine's own colour-identity rule, not an incidental size or copy
        // limit. Using a legendary here would pass for the wrong reason.
        assertTrue(response.toString().contains("do not match the commanders"), response.toString());
    }

    @Test(timeOut = 180000)
    void unknownCardNameIsRefused() {
        final JsonObject response =
                importDeck(started(), WHITE_COMMANDER, repeat("Definitely Not A Real Card Name"));
        assertFalse(response.get("success").getAsBoolean(), response.toString());
        assertTrue(response.toString().contains("unresolvable"), response.toString());
    }

    @Test(timeOut = 180000)
    void wrongDeckSizeIsRefused() {
        final JsonObject response = importDeck(started(), WHITE_COMMANDER, shortDeck());
        assertFalse(response.get("success").getAsBoolean(), response.toString());
        assertTrue(response.toString().contains("exactly 100 cards"), response.toString());
    }

    @Test(timeOut = 180000)
    void refusalCarriesADistinctCodeFromAnUnreadableDeck() {
        // AF03 must be able to tell "I could not read this" from "the engine read
        // it and refused it", which is the distinction the gate exists to observe.
        final JsonObject response = importDeck(started(), "Hill Giant", repeat("Plains"));
        assertTrue(response.toString().contains(BridgeErrors.DECK_NOT_LEGAL), response.toString());
        assertFalse(response.toString().contains(BridgeErrors.DECK_IMPORT_FAILED),
                response.toString());
    }

    @Test(timeOut = 180000)
    void theRulesCoreAgreesWithTheBridgeOnEveryCase() {
        // The bridge must be delegating, not reimplementing.
        assertEquals(null, engineConformance(WHITE_COMMANDER, "Plains"));
        assertNotNull(engineConformance("Hill Giant", "Plains"));
        assertNotNull(engineConformance(WHITE_COMMANDER, BLACK_NONBASIC));
    }

    /** The engine's own answer for a deck built the way the bridge builds it. */
    private static String engineConformance(String commander, String card) {
        final Deck deck = new Deck("wsr28");
        final PaperCard commanderCard = StaticData.instance().getCommonCards().getCard(commander);
        assertNotNull(commanderCard, "commander must resolve: " + commander);
        deck.getOrCreate(DeckSection.Commander).add(Collections.singletonList(commanderCard));
        final List<PaperCard> mainboard = new ArrayList<>();
        for (int i = 0; i < MAINBOARD; i++) {
            final PaperCard resolved = StaticData.instance().getCommonCards().getCard(card);
            assertNotNull(resolved, "card must resolve: " + card);
            mainboard.add(resolved);
        }
        deck.getMain().add(mainboard);
        return DeckFormat.Commander.getDeckConformanceProblem(deck);
    }
}
