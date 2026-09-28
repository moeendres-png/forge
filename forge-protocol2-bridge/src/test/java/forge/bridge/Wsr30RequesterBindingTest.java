package forge.bridge;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.game.zone.ZoneType;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * WSR30 / PB-06 requester binding.
 *
 * <p>The committed Lab run shows the defect precisely: Forge returned four
 * distinct, correctly redacted state views (the requester's own hand real,
 * every opponent card {@code "<hidden>"}), but no view named the principal it
 * was projected for. The Lab therefore recorded
 * {@code SCOPING_NOT_ESTABLISHED_ACTOR_MARKING_ABSENT} and could not tell a
 * demonstrated leak from content that might be the requester's own.
 *
 * <p>These tests pin the binding to the validated request/session principal
 * context and prove it is never inferred from which seat happens to hold
 * visible cards.
 */
public class Wsr30RequesterBindingTest {

    private static final int PLAYERS = 4;

    private static final String[] SEAT_CARDS = {"Island", "Mountain", "Forest", "Plains"};

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    private static JsonObject stateFor(BridgeTestSupport.ConstructedGame constructed,
            String observer) {
        final String payload = observer == null ? "{}"
                : "{\"observer_player_id\":\"" + observer + "\"}";
        final JsonObject response = BridgeTestSupport.rpc(constructed.engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"wsr30-"
                        + System.nanoTime() + "\",\"message_type\":\"get_game_state\","
                        + "\"game_id\":\"" + constructed.session.getGameId()
                        + "\",\"payload\":" + payload + "}");
        BridgeTestSupport.assertOk(response);
        return response.get("payload").getAsJsonObject().getAsJsonObject("state");
    }

    private static List<JsonObject> playersOf(JsonObject state) {
        final List<JsonObject> players = new ArrayList<>();
        for (JsonElement element : state.getAsJsonArray("players")) {
            players.add(element.getAsJsonObject());
        }
        return players;
    }

    private static JsonObject playerAtSeat(JsonObject state, int seat) {
        for (JsonObject player : playersOf(state)) {
            if (player.get("seat").getAsInt() == seat) {
                return player;
            }
        }
        throw new AssertionError("no player at seat " + seat + ": " + state);
    }

    private static List<String> zoneEntries(JsonObject player, String zone) {
        final List<String> entries = new ArrayList<>();
        for (JsonElement element : player.getAsJsonObject("zones").getAsJsonArray(zone)) {
            entries.add(element.getAsString());
        }
        return entries;
    }

    private static int markedSeatCount(JsonObject state) {
        int marked = 0;
        for (JsonObject player : playersOf(state)) {
            if (player.get("is_actor").getAsBoolean()) {
                marked++;
            }
        }
        return marked;
    }

    private static int theMarkedSeat(JsonObject state) {
        for (JsonObject player : playersOf(state)) {
            if (player.get("is_actor").getAsBoolean()) {
                return player.get("seat").getAsInt();
            }
        }
        throw new AssertionError("no marked principal: " + state);
    }

    /** A live four-seat game with exactly one real card in each seat's hand. */
    private static BridgeTestSupport.ConstructedGame liveGame(String gameId) {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(gameId, PLAYERS);
        for (int seat = 0; seat < PLAYERS; seat++) {
            BridgeTestSupport.addCard(constructed.game, seat, SEAT_CARDS[seat], ZoneType.Hand);
        }
        BridgeTestSupport.launchConstructed(constructed);
        return constructed;
    }

    @Test(timeOut = 300000)
    public void fourRequestersAreBoundToTheirOwnSeat() {
        final BridgeTestSupport.ConstructedGame constructed = liveGame("wsr30-bind-a");
        try {
            for (int seat = 0; seat < PLAYERS; seat++) {
                final String observer = "p" + (seat + 1);
                final JsonObject state = stateFor(constructed, observer);
                assertEquals(state.get("observer_player_id").getAsString(), observer,
                        "the response must name the principal it was projected for");
                assertEquals(markedSeatCount(state), 1,
                        "exactly one principal must be marked: " + state);
                assertEquals(theMarkedSeat(state), seat,
                        "the marked principal must be the requester");
            }
        } finally {
            constructed.session.shutdown(5000);
        }
    }

    @Test(timeOut = 300000)
    public void ownHandIsRealAndEveryOpponentHandStaysHidden() {
        final BridgeTestSupport.ConstructedGame constructed = liveGame("wsr30-bind-b");
        try {
            final JsonObject state = stateFor(constructed, "p2");
            for (JsonObject player : playersOf(state)) {
                final List<String> hand = zoneEntries(player, "hand");
                if (player.get("is_actor").getAsBoolean()) {
                    assertEquals(hand, List.of("Mountain"),
                            "the requester must see its own hand: " + hand);
                } else {
                    for (String entry : hand) {
                        assertEquals(entry, "<hidden>",
                                "an opponent hand must be a placeholder: " + hand);
                    }
                }
            }
        } finally {
            constructed.session.shutdown(5000);
        }
    }

    @Test(timeOut = 300000)
    public void anEmptyHandedRequesterIsStillMarked() {
        // The marker must not be inferred from which seat happens to hold
        // visible content: p4 has no cards at all and is still the principal.
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr30-bind-g", PLAYERS);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Hand);
        BridgeTestSupport.launchConstructed(constructed);
        try {
            final JsonObject state = stateFor(constructed, "p4");
            assertEquals(state.get("observer_player_id").getAsString(), "p4");
            assertEquals(markedSeatCount(state), 1);
            assertEquals(theMarkedSeat(state), 3, "p4 is seat 3: " + state);
            assertTrue(zoneEntries(playerAtSeat(state, 3), "hand").isEmpty(),
                    "the marked principal holds no cards, so the marker is not content-derived");
        } finally {
            constructed.session.shutdown(5000);
        }
    }

    @Test(timeOut = 300000)
    public void publicViewBindsNoPrincipalAndMarksNobody() {
        final BridgeTestSupport.ConstructedGame constructed = liveGame("wsr30-bind-d");
        try {
            final JsonObject state = stateFor(constructed, null);
            assertTrue(state.get("observer_player_id").isJsonNull(),
                    "the public view has no requester");
            assertEquals(markedSeatCount(state), 0,
                    "the public view must not mark a principal");
            for (JsonObject player : playersOf(state)) {
                for (String entry : zoneEntries(player, "hand")) {
                    assertEquals(entry, "<hidden>", "the public view hides every hand");
                }
            }
        } finally {
            constructed.session.shutdown(5000);
        }
    }

    @Test(timeOut = 120000)
    public void unknownObserverIsRefusedRatherThanMarked() {
        final BridgeTestSupport.ConstructedGame constructed = liveGame("wsr30-bind-e");
        try {
            final JsonObject response = BridgeTestSupport.rpc(constructed.engine,
                    "{\"protocol_version\":\"2.0.0\",\"request_id\":\"wsr30-unknown\","
                            + "\"message_type\":\"get_game_state\",\"game_id\":\""
                            + constructed.session.getGameId()
                            + "\",\"payload\":{\"observer_player_id\":\"p99\"}}");
            BridgeTestSupport.assertError(response, BridgeErrors.WRONG_ACTOR);
        } finally {
            constructed.session.shutdown(5000);
        }
    }

    @Test(timeOut = 300000)
    public void principalMetadataIsSeatScopedNotAnIdentityLeak() {
        final BridgeTestSupport.ConstructedGame constructed = liveGame("wsr30-bind-f");
        try {
            for (int seat = 0; seat < PLAYERS; seat++) {
                final JsonObject state = stateFor(constructed, "p" + (seat + 1));
                final Set<String> ids = new HashSet<>();
                for (JsonObject player : playersOf(state)) {
                    ids.add(player.get("player_id").getAsString());
                    assertTrue(player.get("is_actor").isJsonPrimitive()
                                    && player.get("is_actor").getAsJsonPrimitive().isBoolean(),
                            "the marker is a boolean, not an identity");
                }
                assertEquals(ids, Set.of("p1", "p2", "p3", "p4"),
                        "principal metadata must stay seat-scoped");
            }
        } finally {
            constructed.session.shutdown(5000);
        }
    }

    @Test(timeOut = 300000)
    public void anOpponentWithRealContentDoesNotBecomeTheMarkedPrincipal() {
        // The exact false-accusation trap: p1's view contains p1's real hand
        // while p2..p4 are placeholders. Only p1 may be marked.
        final BridgeTestSupport.ConstructedGame constructed = liveGame("wsr30-bind-h");
        try {
            final JsonObject state = stateFor(constructed, "p1");
            assertEquals(markedSeatCount(state), 1);
            assertEquals(theMarkedSeat(state), 0);
            assertFalse(playerAtSeat(state, 1).get("is_actor").getAsBoolean(),
                    "an opponent with content is not the actor");
            assertEquals(zoneEntries(playerAtSeat(state, 1), "hand"), List.of("<hidden>"));
        } finally {
            constructed.session.shutdown(5000);
        }
    }
}
