package forge.bridge;

import com.google.gson.JsonObject;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * R15 hidden-info family: principal-scoped observations at 2P/3P/5P
 * (4P retained via BridgeEngineTest#testHiddenInformationAdversary).
 *
 * <p>Per count: each seat holds a distinct canary card in hand; every
 * observer's projection must contain exactly its own canary and no other
 * seat's card name, option ID, or hand content (hands redacted as
 * {@code <hidden>}); legal_actions stay actor-scoped; the public
 * observer sees no private cards. Mirrors the 4P adversary structure.</p>
 */
public class WsR15HiddenInfoFamilyTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    private static final String[] CANARIES = {
            "Memnite", "Serra Angel", "Runeclaw Bear", "Grizzly Bears", "Ornithopter"
    };

    private static JsonObject playerState(JsonObject state, String playerId) {
        return state.getAsJsonArray("players").asList().stream()
                .map(e -> e.getAsJsonObject())
                .filter(o -> o.get("player_id").getAsString().equals(playerId))
                .findFirst().orElseThrow(() -> new AssertionError("no such player " + playerId));
    }

    private static void qualifyHiddenInfo(int playerCount) {
        final String tag = "wsr15-hidden-" + playerCount + "p";
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(tag, playerCount);
        final BridgeSession session = constructed.session;
        for (int seat = 0; seat < playerCount; seat++) {
            BridgeTestSupport.addCard(constructed.game, seat, CANARIES[seat], ZoneType.Hand);
            BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Battlefield);
            for (int i = 0; i < 5; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (int observer = 1; observer <= playerCount; observer++) {
            final String observerId = "p" + observer;
            final JsonObject state = StateProjection.gameState(session, observerId);
            final String flat = state.toString();
            Assert.assertTrue(flat.contains(CANARIES[observer - 1]),
                    observerId + " must see own canary");
            for (int other = 1; other <= playerCount; other++) {
                if (other == observer) {
                    continue;
                }
                Assert.assertFalse(flat.contains(CANARIES[other - 1]),
                        observerId + " leaked " + CANARIES[other - 1]);
            }
            for (int seat = 1; seat <= playerCount; seat++) {
                if (seat == observer) {
                    continue;
                }
                final JsonObject hidden = playerState(state, "p" + seat);
                for (int i = 0; i < hidden.getAsJsonObject("zones")
                        .getAsJsonArray("hand").size(); i++) {
                    Assert.assertEquals(hidden.getAsJsonObject("zones")
                            .getAsJsonArray("hand").get(i).getAsString(), "<hidden>",
                            "opponent hand must be redacted");
                }
            }
        }
        final JsonObject asPublic = StateProjection.gameState(session, null);
        Assert.assertEquals(asPublic.getAsJsonArray("legal_actions").size(), 0,
                "public observer must see no legal actions");
        for (int s = 0; s < playerCount; s++) {
            Assert.assertFalse(asPublic.toString().contains(CANARIES[s]),
                    "public observer leaked " + CANARIES[s]);
        }
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testHiddenInfoTwoPlayers() {
        qualifyHiddenInfo(2);
    }

    @Test(timeOut = 300000)
    public void testHiddenInfoThreePlayers() {
        qualifyHiddenInfo(3);
    }

    @Test(timeOut = 300000)
    public void testHiddenInfoFivePlayers() {
        qualifyHiddenInfo(5);
    }
}
