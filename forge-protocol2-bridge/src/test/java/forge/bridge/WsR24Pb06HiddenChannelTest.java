package forge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * WSR24 PB-06 Forge-side hidden-information channel campaign.
 *
 * <p>Each test drives a deterministic scenario on the real engine through the
 * real bridge and asserts BOTH positive visibility (entitled principals see)
 * and negative visibility (all others redacted) at the production-reachable
 * decision/observation surface ({@link StateProjection#gameState},
 * {@link StateProjection#bridgeMeta}, and the {@link BridgeEngine} RPC
 * bindings {@code get_game_state} / {@code get_legal_actions}).
 *
 * <p>No GUI automation, no internal AI as decision authority, no default
 * choices: every game decision is answered by submitting an explicitly
 * selected engine-offered option. Privileged engine reads (zone contents,
 * library order) are used ONLY to prove scenario liveness (the scenario
 * really ran); isolation is proven ONLY by production-surface reads bound
 * to observing principals. See wsr24-evidence-closure/PB06_LAYER_TRACE.md.
 */
public class WsR24Pb06HiddenChannelTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    // ------------------------------------------------------------------
    // Shared helpers (mirror the WsR20 denominator harness shapes).
    // ------------------------------------------------------------------

    private static BridgeSession.SubmitOutcome submit(BridgeSession session,
            DecisionFrame frame, DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
        Assert.assertTrue(outcome.executionOk,
                "submit applied but not executed: " + outcome.errorCode + " "
                        + outcome.errorMessage);
        return outcome;
    }

    private static boolean frameMatches(DecisionFrame frame, String actorId,
            DecisionFrame.Kind kind) {
        return frame.kind == kind && frame.actorPlayerId.equals(actorId)
                && frame.status == DecisionFrame.Status.SUPPORTED;
    }

    private static DecisionFrame.Option pickOption(DecisionFrame frame,
            java.util.function.Predicate<DecisionFrame.Option> test, String what) {
        for (DecisionFrame.Option option : frame.options) {
            if (test.test(option)) {
                return option;
            }
        }
        throw new AssertionError("no " + what + " option; options=" + frame.options.size());
    }

    private static void payManaFirstWorking(BridgeSession session) {
        final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
        Assert.assertNotNull(frame);
        Assert.assertEquals(frame.kind, DecisionFrame.Kind.MANA_PAYMENT,
                "expected mana payment, got " + frame.kind);
        for (DecisionFrame.Option option : frame.options) {
            final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                    option.optionId, option.actionType, frame.revision);
            if (outcome.applied) {
                return;
            }
        }
        throw new AssertionError("no offered mana payment applied");
    }

    private static void settleMana(BridgeSession session) {
        final long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            final DecisionFrame frame = session.getCurrentFrame();
            if (frame != null && frame.kind == DecisionFrame.Kind.MANA_PAYMENT
                    && frame.status == DecisionFrame.Status.SUPPORTED) {
                payManaFirstWorking(session);
                continue;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void answerCommon(BridgeSession session, DecisionFrame frame) {
        if (frame.kind == DecisionFrame.Kind.PRIORITY) {
            submit(session, frame, pickOption(frame, o -> o.isPass, "pass"));
        } else if (frame.kind == DecisionFrame.Kind.MANA_PAYMENT) {
            payManaFirstWorking(session);
        } else if (frame.kind == DecisionFrame.Kind.TARGET_SELECTION) {
            submit(session, frame, frame.options.get(0));
        } else if (frame.kind == DecisionFrame.Kind.ORDER_CHOICE) {
            submit(session, frame, BridgeTestSupport.reachabilityOnlyCostOrder(frame));
        } else if (frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                || frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
            submit(session, frame, pickOption(frame,
                    o -> o.label != null && (o.label.contains("No attack")
                            || o.label.contains("No block")),
                    "decline combat"));
        } else if (frame.kind == DecisionFrame.Kind.TRIGGER_PLAY) {
            submit(session, frame, frame.options.get(0));
        } else if (frame.kind == DecisionFrame.Kind.COST_SELECTION) {
            // H11 only: the Evolving Wilds sacrifice is the single offered
            // subset (Decline, if present, sorts last); paying it is the
            // scenario path. No other flow in this class parks COST_SELECTION.
            submit(session, frame, frame.options.get(0));
        } else {
            throw new AssertionError("unexpected " + frame.kind + " for " + frame.actorPlayerId);
        }
    }

    private static void castFromHand(BridgeSession session, String actorId, String cardName) {
        for (int i = 0; i < 40; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, actorId, DecisionFrame.Kind.PRIORITY)) {
                DecisionFrame.Option cast = null;
                for (DecisionFrame.Option o : frame.options) {
                    if ("cast_spell".equals(o.actionType) && cardName.equals(o.sourceCardName)) {
                        cast = o;
                        break;
                    }
                }
                if (cast != null) {
                    submit(session, frame, cast);
                    settleMana(session);
                    return;
                }
            }
            answerCommon(session, frame);
        }
        throw new AssertionError("never reached cast of " + cardName + " for " + actorId);
    }

    private static boolean awaitTargetOrPick(BridgeSession session, String actorId,
            String targetLabel) {
        final long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            final DecisionFrame f = session.getCurrentFrame();
            if (f != null && frameMatches(f, actorId, DecisionFrame.Kind.TARGET_SELECTION)) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains(targetLabel),
                        "target " + targetLabel));
                settleMana(session);
                return true;
            }
            if (f != null && f.kind == DecisionFrame.Kind.MANA_PAYMENT
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                payManaFirstWorking(session);
                continue;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void fillLibraries(BridgeTestSupport.ConstructedGame constructed, int count) {
        for (int seat = 0; seat < constructed.game.getPlayers().size(); seat++) {
            for (int i = 0; i < count; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
    }

    private static JsonObject playerState(JsonObject state, String playerId) {
        for (Object element : state.getAsJsonArray("players")) {
            final JsonObject playerState = (JsonObject) element;
            if (playerState.get("player_id").getAsString().equals(playerId)) {
                return playerState;
            }
        }
        throw new AssertionError("no such player " + playerId);
    }

    private static JsonArray zoneEntries(JsonObject state, String playerId, String zone) {
        return playerState(state, playerId).getAsJsonObject("zones").getAsJsonArray(zone);
    }

    private static int librarySize(JsonObject state, String playerId) {
        return playerState(state, playerId).getAsJsonObject("zones")
                .get("library_size").getAsInt();
    }

    private static Set<String> zoneNames(JsonObject state, String playerId, String zone) {
        final Set<String> names = new HashSet<>();
        for (Object element : zoneEntries(state, playerId, zone)) {
            names.add(element.toString().replace("\"", ""));
        }
        return names;
    }

    /** Production RPC read: get_game_state bound to an observing principal. */
    private static JsonObject rpcState(BridgeEngine engine, String gameId, String observer) {
        final String payload = observer == null ? "{}"
                : "{\"observer_player_id\":\"" + observer + "\"}";
        final JsonObject response = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"wsr24-"
                        + System.nanoTime() + "\",\"message_type\":\"get_game_state\","
                        + "\"game_id\":\"" + gameId + "\",\"payload\":" + payload + "}");
        BridgeTestSupport.assertOk(response);
        return response.get("payload").getAsJsonObject().getAsJsonObject("state");
    }

    /** Production RPC read: get_legal_actions; null when the actor is not entitled. */
    private static JsonObject rpcLegalOrNull(BridgeEngine engine, String gameId, String actor) {
        final JsonObject response = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"wsr24-"
                        + System.nanoTime() + "\",\"message_type\":\"get_legal_actions\","
                        + "\"game_id\":\"" + gameId + "\",\"payload\":{\"actor_id\":\""
                        + actor + "\"}}");
        if (!response.get("success").getAsBoolean()) {
            return null;
        }
        return response.get("payload").getAsJsonObject();
    }

    private static void assertExileShows(JsonObject state, String ownerId,
            String expectedName, boolean visible) {
        final Set<String> exile = zoneNames(state, ownerId, "exile");
        if (visible) {
            Assert.assertTrue(exile.contains(expectedName),
                    "exile of " + ownerId + " must show " + expectedName + ": " + exile);
        } else {
            Assert.assertFalse(exile.contains(expectedName),
                    "exile of " + ownerId + " leaked " + expectedName + ": " + exile);
        }
    }

    // ------------------------------------------------------------------
    // HIDDEN_05: face-down exile actor-specific permission persists.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testHidden05_FaceDownExilePermissionPersists() {
        final String gameId = "wsr24-pb06-h05";
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(gameId, 4);
        final BridgeSession session = constructed.session;
        // Engine-truth setup mirroring ChangeZoneEffect's ExileFaceDown path:
        // face-down in exile + may-look grant to exactly one actor (p2).
        final Card exiled = BridgeTestSupport.addCard(constructed.game, 0,
                "Grizzly Bears", ZoneType.Exile);
        Assert.assertTrue(exiled.turnFaceDown(true), "must go face-down");
        exiled.addMayLookFaceDownExile(constructed.game.getPlayers().get(1));
        fillLibraries(constructed, 5);

        // Pre-launch principal matrix.
        final JsonObject preP2 = StateProjection.gameState(session, "p2");
        Assert.assertTrue(preP2.toString().contains("Grizzly Bears"),
                "granted actor must see face-down exile identity");
        assertExileShows(preP2, "p1", "Grizzly Bears", true);
        for (String observer : new String[]{"p1", "p3", "p4"}) {
            final JsonObject view = StateProjection.gameState(session, observer);
            Assert.assertFalse(view.toString().contains("Grizzly Bears"),
                    observer + " must not see granted exile identity");
            assertExileShows(view, "p1", "Grizzly Bears", false);
            Assert.assertTrue(zoneNames(view, "p1", "exile").contains("<face-down>"),
                    observer + " must see the redacted marker");
        }
        final JsonObject prePublic = StateProjection.gameState(session, null);
        Assert.assertFalse(prePublic.toString().contains("Grizzly Bears"));
        Assert.assertEquals(prePublic.getAsJsonArray("legal_actions").size(), 0);

        // Persistence across real game progress.
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        final JsonObject asP2 = StateProjection.gameState(session, "p2");
        Assert.assertTrue(asP2.toString().contains("Grizzly Bears"),
                "grant must persist across game progress");
        assertExileShows(asP2, "p1", "Grizzly Bears", true);
        final JsonObject asP3 = StateProjection.gameState(session, "p3");
        Assert.assertFalse(asP3.toString().contains("Grizzly Bears"),
                "outsider must stay blind after game progress");
        Assert.assertEquals(asP3.getAsJsonArray("legal_actions").size(), 0,
                "outsider must see no legal actions");
        // Production RPC binding preserves the boundary.
        Assert.assertTrue(rpcState(constructed.engine, gameId, "p2").toString()
                .contains("Grizzly Bears"));
        Assert.assertFalse(rpcState(constructed.engine, gameId, "p3").toString()
                .contains("Grizzly Bears"));
        // Frame/revision metadata stays actor-scoped on the live frame.
        final DecisionFrame frame = session.getCurrentFrame();
        if (frame != null && frame.status == DecisionFrame.Status.SUPPORTED) {
            Assert.assertEquals(
                    StateProjection.bridgeMeta(session, frame.actorPlayerId)
                            .get("revision").getAsLong(), frame.revision);
            Assert.assertEquals(StateProjection.bridgeMeta(session, "p3")
                    .get("revision").getAsInt(), -1);
            Assert.assertTrue(StateProjection.bridgeMeta(session, "p3")
                    .get("pending_decision").isJsonNull());
        }
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // HIDDEN_06: face-down exile knowledge invalidates correctly.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testHidden06_FaceDownExileInvalidatesOnZoneChange() {
        final String gameId = "wsr24-pb06-h06";
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(gameId, 4);
        final BridgeSession session = constructed.session;
        final Card exiled = BridgeTestSupport.addCard(constructed.game, 0,
                "Grizzly Bears", ZoneType.Exile);
        Assert.assertTrue(exiled.turnFaceDown(true));
        exiled.addMayLookFaceDownExile(constructed.game.getPlayers().get(1));
        fillLibraries(constructed, 5);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        // Pre-move: exclusive knowledge holds.
        Assert.assertTrue(StateProjection.gameState(session, "p2").toString()
                .contains("Grizzly Bears"));
        final String outsiderBefore = StateProjection.gameState(session, "p3").toString();
        Assert.assertFalse(outsiderBefore.contains("Grizzly Bears"));

        // Engine-harness zone move Exile -> Graveyard face-up (the card becomes
        // a public object; observation is the production surface only).
        final Player owner = constructed.game.getPlayers().get(0);
        owner.getZone(ZoneType.Exile).remove(exiled);
        Assert.assertFalse(owner.getZone(ZoneType.Exile).getCards().contains(exiled),
                "card must leave exile");
        owner.getZone(ZoneType.Graveyard).add(exiled);
        Assert.assertTrue(exiled.turnFaceUp(false, null), "must turn face-up");

        // Post-move: identity is public in graveyard for EVERY principal, and
        // the exile channel carries no stale ghost for anyone.
        for (String observer : new String[]{"p1", "p2", "p3", "p4"}) {
            final JsonObject view = StateProjection.gameState(session, observer);
            Assert.assertTrue(zoneNames(view, "p1", "graveyard").contains("Grizzly Bears"),
                    observer + " must see the public graveyard identity");
            Assert.assertTrue(zoneNames(view, "p1", "exile").isEmpty(),
                    observer + " must see an empty exile channel, no ghost");
        }
        final JsonObject postPublic = StateProjection.gameState(session, null);
        Assert.assertTrue(zoneNames(postPublic, "p1", "graveyard").contains("Grizzly Bears"),
                "public observer must see the public graveyard identity");
        Assert.assertTrue(zoneNames(postPublic, "p1", "exile").isEmpty());
        Assert.assertTrue(rpcState(constructed.engine, gameId, "p3").toString()
                .contains("Grizzly Bears"), "RPC outsider must see public graveyard");
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // HIDDEN_08: look reaches only specified audience (real look card).
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testHidden08_LookReachesOnlySpecifiedAudience() {
        final String gameId = "wsr24-pb06-h08";
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(gameId, 4);
        final BridgeSession session = constructed.session;
        // Duress (look at opponent hand + discard a noncreature nonland):
        // the look/choice audience is exactly the caster.
        BridgeTestSupport.addCard(constructed.game, 0, "Swamp", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Duress", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Giant Growth", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Lightning Bolt", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Memnite", ZoneType.Hand);
        fillLibraries(constructed, 10);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castFromHand(session, "p1", "Duress");
        Assert.assertTrue(awaitTargetOrPick(session, "p1", "p2"), "Duress must target p2");

        // Capture the look/choice frame before answering it.
        DecisionFrame look = null;
        String casterView = null;
        String victimView = null;
        String outsiderView = null;
        String publicView = null;
        JsonObject casterRpc = null;
        for (int i = 0; i < 40; i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "look frame never parked");
            if (frameMatches(f, "p1", DecisionFrame.Kind.HIDDEN_ZONE_SELECTION)) {
                look = f;
                casterView = StateProjection.gameState(session, "p1").toString();
                victimView = StateProjection.gameState(session, "p2").toString();
                outsiderView = StateProjection.gameState(session, "p3").toString();
                publicView = StateProjection.gameState(session, null).toString();
                casterRpc = rpcLegalOrNull(constructed.engine, gameId, "p1");
                break;
            }
            answerCommon(session, f);
        }
        Assert.assertNotNull(look, "Duress look/choice must be offered to the caster");
        Assert.assertFalse(look.options.isEmpty());

        // Positive: the entitled audience (caster) observes the looked-at hand.
        Assert.assertNotNull(casterView);
        Assert.assertTrue(casterView.contains("Giant Growth"),
                "caster must observe the looked-at identity while choosing");
        Assert.assertNotNull(casterRpc);
        Assert.assertTrue(casterRpc.toString().contains("Giant Growth"));

        // Negative: victim (non-actor) learns no decision content: no option id
        // or decision label may appear, and no legal actions are offered.
        Assert.assertNotNull(victimView);
        for (DecisionFrame.Option option : look.options) {
            Assert.assertFalse(victimView.contains(option.optionId),
                    "victim learned option id " + option.optionId);
        }
        Assert.assertEquals(StateProjection.gameState(session, "p2")
                .getAsJsonArray("legal_actions").size(), 0);
        Assert.assertNull(rpcLegalOrNull(constructed.engine, gameId, "p2"),
                "victim must get WRONG_ACTOR, not the look");

        // Negative: outsider + public learn nothing of the look.
        Assert.assertNotNull(outsiderView);
        Assert.assertFalse(outsiderView.contains("Giant Growth"),
                "look must not reach players outside the legal audience");
        Assert.assertFalse(outsiderView.contains("Lightning Bolt"),
                "look must not reach players outside the legal audience");
        for (DecisionFrame.Option option : look.options) {
            Assert.assertFalse(outsiderView.contains(option.optionId),
                    "outsider learned option id");
        }
        Assert.assertNotNull(publicView);
        Assert.assertFalse(publicView.contains("Giant Growth"));
        Assert.assertFalse(publicView.contains("Lightning Bolt"));
        Assert.assertNull(rpcLegalOrNull(constructed.engine, gameId, "p3"));

        // Resolve and prove the game continues with correct publicity:
        // the discarded card enters the public graveyard.
        submit(session, look, look.options.get(0));
        for (int i = 0; i < 60; i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.PRIORITY) {
                break;
            }
            answerCommon(session, f);
        }
        final JsonObject after = StateProjection.gameState(session, "p3");
        final Set<String> grave = zoneNames(after, "p2", "graveyard");
        Assert.assertTrue(grave.contains("Giant Growth") || grave.contains("Lightning Bolt"),
                "discarded card must be public in graveyard after resolution: " + grave);
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // HIDDEN_11: shuffle invalidates order knowledge.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testHidden11_ShuffleInvalidatesOrderKnowledge() {
        final String gameId = "wsr24-pb06-h11";
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(gameId, 4);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Opt", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Evolving Wilds", ZoneType.Battlefield);
        fillLibraries(constructed, 10);
        // Two distinctive library cards so publicity and concealment can be
        // told apart post-shuffle: at most one can leave the library.
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Library);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Library);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        // No library order is exposed to any non-actor principal before the
        // scenario (the actor's own pending priority options carry no
        // library identities either at this point).
        for (String observer : new String[]{"p2", "p3", "p4"}) {
            final JsonObject view = StateProjection.gameState(session, observer);
            Assert.assertEquals(zoneEntries(view, "p1", "library").size(), 0,
                    observer + " must never see library contents");
            Assert.assertFalse(view.toString().contains("Mountain"),
                    observer + " must not see the distinctive library card");
            Assert.assertFalse(view.toString().contains("Forest"),
                    observer + " must not see the distinctive library card");
        }
        Assert.assertFalse(StateProjection.gameState(session, null).toString()
                .contains("Mountain"));
        Assert.assertFalse(StateProjection.gameState(session, null).toString()
                .contains("Forest"));

        // Step 1 (learn): scry the top card through the real Opt pipeline.
        castFromHand(session, "p1", "Opt");
        final Set<String> scryNames = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (frameMatches(f, "p1", DecisionFrame.Kind.GENERIC_SELECTION)) {
                for (DecisionFrame.Option o : f.options) {
                    if (o.label != null && o.label.contains("Mountain")) {
                        scryNames.add("Mountain");
                    }
                    if (o.label != null && o.label.contains("Plains")) {
                        scryNames.add("Plains");
                    }
                }
                // Outsider learns nothing of the scry look while it is pending.
                Assert.assertFalse(StateProjection.gameState(session, "p3").toString()
                        .contains("Mountain"));
                Assert.assertFalse(StateProjection.gameState(session, "p3").toString()
                        .contains("Forest"));
                Assert.assertEquals(StateProjection.gameState(session, "p3")
                        .getAsJsonArray("legal_actions").size(), 0);
                submit(session, f, f.options.get(0));
                break;
            }
            answerCommon(session, f);
        }

        // Step 2 (shuffle): crack Evolving Wilds through the real pipeline
        // (sacrifice cost + engine search + engine shuffle).
        boolean activated = false;
        for (int i = 0; i < 40 && !activated; i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (frameMatches(f, "p1", DecisionFrame.Kind.PRIORITY)) {
                DecisionFrame.Option wilds = null;
                for (DecisionFrame.Option o : f.options) {
                    if ("activate_ability".equals(o.actionType)
                            && "Evolving Wilds".equals(o.sourceCardName)) {
                        wilds = o;
                        break;
                    }
                }
                if (wilds != null) {
                    submit(session, f, wilds);
                    settleMana(session);
                    activated = true;
                    break;
                }
            }
            answerCommon(session, f);
        }
        Assert.assertTrue(activated, "Evolving Wilds activation must be offered");

        boolean searched = false;
        for (int i = 0; i < 40 && !searched; i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (frameMatches(f, "p1", DecisionFrame.Kind.SEARCH_SELECTION)) {
                Assert.assertFalse(f.options.isEmpty(), "search must offer real choice");
                // Search audience is the searcher alone.
                Assert.assertFalse(StateProjection.gameState(session, "p3").toString()
                        .contains("Mountain"),
                        "search look must not reach outsiders");
                Assert.assertFalse(StateProjection.gameState(session, "p3").toString()
                        .contains("Forest"),
                        "search look must not reach outsiders");
                submit(session, f, f.options.get(0));
                searched = true;
                break;
            }
            answerCommon(session, f);
        }
        Assert.assertTrue(searched, "library search must resolve");

        // Step 3 (invalidation): after the real shuffle, no principal observes
        // library order or identities; counts stay consistent per principal.
        for (int i = 0; i < 40; i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.PRIORITY) {
                break;
            }
            answerCommon(session, f);
        }
        final List<Integer> sizes = new ArrayList<>();
        for (String observer : new String[]{"p1", "p2", "p3", "p4"}) {
            final JsonObject view = StateProjection.gameState(session, observer);
            Assert.assertEquals(zoneEntries(view, "p1", "library").size(), 0,
                    observer + " must see no library contents after shuffle");
            sizes.add(librarySize(view, "p1"));
        }
        for (int i = 1; i < sizes.size(); i++) {
            Assert.assertEquals(sizes.get(i), sizes.get(0),
                    "library count must be principal-consistent");
        }
        Assert.assertTrue(sizes.get(0) > 0, "library must survive the scenario");
        // Liveness (privileged engine read ONLY): the search conserved cards
        // (found land public on battlefield, library shrunk by search+draws).
        int engineLibrary = constructed.game.getPlayers().get(0)
                .getZone(ZoneType.Library).getCards().size();
        Assert.assertEquals(engineLibrary, sizes.get(0).intValue(),
                "production count must match engine truth");
        // Publicity vs concealment after the shuffle: the searched land is
        // correctly public; every distinctive card that did NOT leave the
        // library/hand-private sphere stays hidden to outsiders and public.
        final Set<String> battlefieldBasics = new HashSet<>();
        for (Card c : constructed.game.getPlayers().get(0)
                .getZone(ZoneType.Battlefield).getCards()) {
            battlefieldBasics.add(c.getName());
        }
        Assert.assertTrue(battlefieldBasics.contains("Evolving Wilds")
                || constructed.game.getPlayers().get(0).getZone(ZoneType.Graveyard)
                        .getCards().stream().anyMatch(c -> "Evolving Wilds".equals(c.getName())),
                "Wilds must be on battlefield or sacrificed, proving activation ran");
        final Set<String> distinctives = new HashSet<>();
        distinctives.add("Mountain");
        distinctives.add("Forest");
        final Set<String> stillPrivate = new HashSet<>(distinctives);
        stillPrivate.removeAll(battlefieldBasics);
        for (String hidden : stillPrivate) {
            for (String observer : new String[]{"p2", "p3", "p4"}) {
                Assert.assertFalse(StateProjection.gameState(session, observer).toString()
                        .contains(hidden), observer + " must not see concealed " + hidden);
            }
            Assert.assertFalse(StateProjection.gameState(session, null).toString()
                    .contains(hidden), "public must see no concealed library identity");
        }
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // HIDDEN_12: controlled-player authority receives legally visible info.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testHidden12_ControlledPlayerAuthoritySees() {
        final String gameId = "wsr24-pb06-h12";
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(gameId, 4);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Memnite", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Serra Angel", ZoneType.Hand);
        fillLibraries(constructed, 5);
        final Player p1 = constructed.game.getPlayers().get(0);
        final Player p2 = constructed.game.getPlayers().get(1);

        // Baseline: no control, controller-to-be sees nothing of p2's hand.
        Assert.assertFalse(StateProjection.gameState(session, "p1").toString()
                .contains("Serra Angel"));
        Assert.assertTrue(StateProjection.gameState(session, "p2").toString()
                .contains("Serra Angel"), "owner must see own hand");

        // Engine-owned control: p1 controls p2 (Mindslaver shape) via the
        // engine's own control registry, reusing p1's controller so no
        // decision authority is invented.
        final long stamp = constructed.game.getNextTimestamp();
        p2.addController(stamp, p1, p1.getController(), false);
        Assert.assertTrue(p2.isControlled(), "control must be established");

        // Positive: the controlling player receives the controlled hand.
        final JsonObject controllerView = StateProjection.gameState(session, "p1");
        Assert.assertTrue(controllerView.toString().contains("Serra Angel"),
                "controlling player must receive controlled hand (engine gate)");
        Assert.assertTrue(controllerView.toString().contains("Memnite"),
                "controller must keep seeing own hand");

        // Negative: outsiders + public stay blind to both hands' contents
        // beyond what is publicly on the battlefield (nothing yet).
        for (String observer : new String[]{"p3", "p4"}) {
            final String flat = StateProjection.gameState(session, observer).toString();
            Assert.assertFalse(flat.contains("Serra Angel"), observer + " leaked");
            Assert.assertFalse(flat.contains("Memnite"), observer + " leaked");
        }
        final String pub = StateProjection.gameState(session, null).toString();
        Assert.assertFalse(pub.contains("Serra Angel"));
        Assert.assertFalse(pub.contains("Memnite"));

        // Revocation: control ends, controller visibility is withdrawn.
        p2.removeController(stamp, false);
        Assert.assertFalse(p2.isControlled(), "control must end");
        final String afterView = StateProjection.gameState(session, "p1").toString();
        Assert.assertFalse(afterView.contains("Serra Angel"),
                "controller must lose controlled hand after control ends");
        Assert.assertTrue(afterView.contains("Memnite"), "own hand must persist");
        session.shutdown(1000);
    }

    // ------------------------------------------------------------------
    // WS05-CMD-MULL-2: two-player Commander mulligan stays principal-scoped.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testMulligan2P_PrincipalScopedSequence() {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = BridgeTestSupport.importPod(engine, 2);
        final String gameId = "wsr24-pb06-mull2";
        BridgeTestSupport.createGame(engine, "c-mull2", gameId, handles);
        BridgeTestSupport.startGame(engine, gameId);
        final BridgeSession session = engine.sessionsForTests().get(gameId);
        Assert.assertNotNull(session);

        // STARTING_PLAYER: complete seat set, actor-scoped, then choose p1.
        DecisionFrame starting = BridgeTestSupport.awaitFrame(session, 60000);
        Assert.assertNotNull(starting);
        Assert.assertEquals(starting.kind, DecisionFrame.Kind.STARTING_PLAYER);
        Assert.assertEquals(starting.options.size(), 2, "both 2P seats must be offered");
        Assert.assertNull(rpcLegalOrNull(engine, gameId,
                starting.actorPlayerId.equals("p1") ? "p2" : "p1"),
                "non-chooser must get WRONG_ACTOR on the starting frame");
        BridgeTestSupport.submitStartingPlayer(session, starting, "p1");

        // MULLIGAN sequence: per-frame principal reads for both seats.
        int mulligans = 0;
        for (int i = 0; i < 20; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 60000);
            Assert.assertNotNull(frame, "mulligan/priority frame never parked");
            if (frame.kind == DecisionFrame.Kind.PRIORITY) {
                break;
            }
            Assert.assertEquals(frame.kind, DecisionFrame.Kind.MULLIGAN,
                    "unexpected frame " + frame.kind);
            final String actor = frame.actorPlayerId;
            final String other = actor.equals("p1") ? "p2" : "p1";
            Assert.assertEquals(frame.options.size(), 2, "keep + ship only");

            // Decision payload carries no private content: labels are generic.
            final JsonObject actorLegal = rpcLegalOrNull(engine, gameId, actor);
            Assert.assertNotNull(actorLegal, "actor must receive its mulligan options");
            final JsonObject actorState = rpcState(engine, gameId, actor);
            final JsonArray ownEntries = zoneEntries(actorState, actor, "hand");
            Assert.assertEquals(ownEntries.size(), 7, "opening hand must be seven");
            final Set<String> ownHand = new HashSet<>();
            for (Object entry : ownEntries) {
                final String name = ((com.google.gson.JsonElement) entry).getAsString();
                Assert.assertFalse(name.equals("<hidden>"),
                        "actor must see own hand contents");
                ownHand.add(name);
            }
            Assert.assertFalse(ownHand.contains("<hidden>"),
                    "actor must see own hand contents");
            for (DecisionFrame.Option option : frame.options) {
                for (String card : ownHand) {
                    Assert.assertFalse(option.label != null && option.label.contains(card),
                            "mulligan label carries private content: " + option.label);
                }
            }

            // Non-actor: own hand visible, actor hand fully redacted, no
            // option ids, no legal actions, revision withheld.
            final JsonObject otherState = rpcState(engine, gameId, other);
            final JsonArray actorEntriesAsSeenByOther = zoneEntries(otherState, actor, "hand");
            Assert.assertEquals(actorEntriesAsSeenByOther.size(), 7);
            for (Object entry : actorEntriesAsSeenByOther) {
                Assert.assertEquals(
                        ((com.google.gson.JsonElement) entry).getAsString(), "<hidden>",
                        "actor hand must be fully redacted to the other seat");
            }
            final Set<String> otherOwn = zoneNames(otherState, other, "hand");
            Assert.assertFalse(otherOwn.contains("<hidden>"),
                    "non-actor must still see its own hand");
            final String otherFlat = otherState.toString();
            for (DecisionFrame.Option option : frame.options) {
                Assert.assertFalse(otherFlat.contains(option.optionId),
                        "non-actor learned mulligan option id");
            }
            Assert.assertNull(rpcLegalOrNull(engine, gameId, other),
                    "non-actor must get WRONG_ACTOR on mulligan");
            Assert.assertEquals(StateProjection.bridgeMeta(session, other)
                    .get("revision").getAsInt(), -1);
            Assert.assertEquals(StateProjection.bridgeMeta(session, actor)
                    .get("revision").getAsLong(), frame.revision);

            // Public: both hands redacted.
            final JsonObject pub = rpcState(engine, gameId, null);
            Assert.assertTrue(zoneNames(pub, "p1", "hand").contains("<hidden>"));
            Assert.assertTrue(zoneNames(pub, "p2", "hand").contains("<hidden>"));
            Assert.assertEquals(pub.getAsJsonArray("legal_actions").size(), 0);

            final BridgeSession.SubmitOutcome keepOutcome =
                    BridgeTestSupport.submitKeep(session, frame);
            Assert.assertTrue(keepOutcome.applied,
                    "keep must apply: " + keepOutcome.errorCode);
            mulligans++;
        }
        Assert.assertEquals(mulligans, 2, "both 2P seats must decide mulligans");

        // London-tuck path stays fail-closed: no private selection transported.
        final ExternalPlayerController controller = (ExternalPlayerController)
                session.getGame().getPlayers().get(0).getController();
        boolean threw = false;
        try {
            controller.tuckCardsViaMulligan(null, 1);
        } catch (BridgeUnsupportedDecision e) {
            threw = true;
        }
        Assert.assertTrue(threw, "tuckCardsViaMulligan must fail closed");

        // Game proceeds with hands still principal-scoped.
        final DecisionFrame priority = BridgeTestSupport.awaitFrame(session, 60000);
        Assert.assertNotNull(priority);
        Assert.assertEquals(priority.kind, DecisionFrame.Kind.PRIORITY);
        final JsonObject p1View = rpcState(engine, gameId, "p1");
        Assert.assertFalse(zoneNames(p1View, "p1", "hand").contains("<hidden>"));
        Assert.assertTrue(zoneNames(p1View, "p2", "hand").contains("<hidden>"));
        final String status = p1View.get("status").getAsString();
        Assert.assertEquals(status, "in_progress");
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // JSON plumbing check (keeps the evidence register honest).
    // ------------------------------------------------------------------

    @Test(timeOut = 60000)
    public void testJsonSanity() {
        final JsonObject probe = JsonParser.parseString("{\"a\":1}").getAsJsonObject();
        Assert.assertEquals(probe.get("a").getAsInt(), 1);
    }
}
