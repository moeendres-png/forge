package forge.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.StaticData;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.model.FModel;
import org.testng.Assert;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared real-engine fixtures. No mocks: every game is a real Forge game with real
 * cards; every decision flows through the real controller boundary.
 */
public final class BridgeTestSupport {
    private static volatile boolean engineReady;

    private BridgeTestSupport() { }

    /** One-time headless engine init for the JVM. Prints init timing for evidence. */
    public static synchronized void ensureEngine() {
        if (engineReady) {
            return;
        }
        // In-JVM tests need a bound engine identity for the usability gate. This is a
        // shape-valid test binding only (all zeros); the exact-SHA binding is proven by
        // the separate-process test whose environment carries the real Forge SHA.
        // An operator-provided value (system property or environment) is never clobbered.
        if (System.getProperty("forge.engine.sha") == null
                && System.getenv("FORGE_ENGINE_SHA") == null) {
            System.setProperty("forge.engine.sha", "0000000000000000000000000000000000000000");
        }
        final long start = System.nanoTime();
        HeadlessBridgeGui.install();
        FModel.initialize(null, null);
        Assert.assertNotNull(StaticData.instance(), "card database must load");
        final long millis = (System.nanoTime() - start) / 1_000_000L;
        System.err.println("[test] engine initialized in " + millis + " ms");
        engineReady = true;
    }

    public static String deckResource(String name) {
        final String path = "/forge/bridge/decks/" + name;
        try (InputStream in = BridgeTestSupport.class.getResourceAsStream(path)) {
            Assert.assertNotNull(in, "missing test deck " + path);
            final String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Compact to a single line: JSONL requests must not contain raw newlines.
            return JsonParser.parseString(raw).toString();
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static JsonObject rpc(BridgeEngine engine, String json) {
        try {
            final BridgeProtocol.Request request = BridgeProtocol.parse(json);
            return JsonParser.parseString(engine.dispatch(request)).getAsJsonObject();
        } catch (BridgeProtocol.MalformedRequestException e) {
            throw new AssertionError(e);
        }
    }

    public static void assertOk(JsonObject response) {
        Assert.assertTrue(response.get("success").getAsBoolean(),
                "expected success, got: " + response);
        Assert.assertEquals(response.get("protocol_version").getAsString(), "2.0.0");
    }

    public static void assertError(JsonObject response, String code) {
        Assert.assertFalse(response.get("success").getAsBoolean(), "expected failure: " + response);
        Assert.assertEquals(response.get("errors").getAsJsonArray().get(0).getAsJsonObject()
                .get("code").getAsString(), code, "response: " + response);
    }

    public static String startEngine(BridgeEngine engine) {
        final JsonObject response = rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"start\","
                        + "\"message_type\":\"start_engine\"}");
        assertOk(response);
        return response.get("request_id").getAsString();
    }

    public static String importDeck(BridgeEngine engine, String requestId, String deckJson) {
        final JsonObject response = rpc(engine, "{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"" + requestId + "\",\"message_type\":\"import_deck\","
                + "\"payload\":{\"deck\":" + deckJson + "}}");
        assertOk(response);
        return response.get("payload").getAsJsonObject().getAsJsonObject("deck_handle")
                .get("handle_id").getAsString();
    }

    /** Imports the four simple fixture decks. */
    public static List<String> importPod(BridgeEngine engine) {
        final List<String> handles = new ArrayList<>(4);
        for (int i = 1; i <= 4; i++) {
            handles.add(importDeck(engine, "import-" + i, deckResource("deck" + i + ".json")));
        }
        return handles;
    }

    public static JsonObject createGame(BridgeEngine engine, String requestId, String gameId,
            List<String> handles) {
        final StringBuilder decks = new StringBuilder();
        for (int i = 0; i < handles.size(); i++) {
            if (i > 0) {
                decks.append(',');
            }
            decks.append('"').append(handles.get(i)).append('"');
        }
        // R5: no starting_player_seat, no starting_life — Forge rules own both.
        final String payload = "{\"request\":{\"game_id\":\"" + gameId + "\",\"format\":\"commander\","
                + "\"deck_handles\":[" + decks + "]}}";
        final JsonObject response = rpc(engine, "{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"" + requestId + "\",\"message_type\":\"create_commander_game\","
                + "\"payload\":" + payload + "}");
        assertOk(response);
        return response;
    }

    public static void startGame(BridgeEngine engine, String gameId) {
        final JsonObject response = rpc(engine, "{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"start-" + gameId + "\",\"message_type\":\"start_game\","
                + "\"game_id\":\"" + gameId + "\"}");
        assertOk(response);
    }

    public static JsonObject legalActions(BridgeEngine engine, String gameId, String actorId) {
        final JsonObject response = rpc(engine, "{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"la-" + System.nanoTime() + "\",\"message_type\":\"get_legal_actions\","
                + "\"game_id\":\"" + gameId + "\",\"payload\":{\"actor_id\":\"" + actorId + "\"}}");
        assertOk(response);
        return response.get("payload").getAsJsonObject();
    }

    public static JsonObject pollLegalActions(BridgeEngine engine, String gameId, String actorId) {
        return rpc(engine, "{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"la-" + System.nanoTime() + "\",\"message_type\":\"get_legal_actions\","
                + "\"game_id\":\"" + gameId + "\",\"payload\":{\"actor_id\":\"" + actorId + "\"}}");
    }

    /** Polls until a decision frame is parked or the timeout elapses. */
    public static DecisionFrame awaitFrame(BridgeSession session, long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            final DecisionFrame frame = session.getCurrentFrame();
            if (frame != null) {
                return frame;
            }
            if (session.isTerminal()) {
                return session.getCurrentFrame();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return session.getCurrentFrame();
            }
        }
        return session.getCurrentFrame();
    }
    public static DecisionFrame.Option findOption(DecisionFrame frame, String actionType) {
        for (DecisionFrame.Option option : frame.options) {
            if (option.actionType.equals(actionType)) {
                return option;
            }
        }
        return null;
    }

    /**
     * Submits passes until the parked frame belongs to the given player's main phase
     * (or the iteration budget is spent). Returns the main-phase frame, or null.
     */
    public static DecisionFrame drivePassesToMainPhase(BridgeSession session, String playerId,
            int maxPasses) {
        for (int i = 0; i < maxPasses; i++) {
            final DecisionFrame frame = awaitFrame(session, 30000);
            if (frame == null) {
                return null;
            }
            if (frame.kind == DecisionFrame.Kind.PRIORITY
                    && frame.status == DecisionFrame.Status.SUPPORTED
                    && frame.actorPlayerId.equals(playerId)
                    && isMainPhase(session)) {
                return frame;
            }
            if (frame.kind == DecisionFrame.Kind.MULLIGAN) {
                submitKeep(session, frame);
            } else if (frame.status == DecisionFrame.Status.SUPPORTED) {
                submitPass(session, frame);
            } else {
                return frame;
            }
        }
        return null;
    }

    public static boolean isMainPhase(BridgeSession session) {
        try {
            final String phase = session.getGame().getPhaseHandler().getPhase().name();
            return phase.equals("MAIN1") || phase.equals("MAIN2");
        } catch (Throwable t) {
            return false;
        }
    }

    public static BridgeSession.SubmitOutcome submitKeep(BridgeSession session, DecisionFrame frame) {
        for (DecisionFrame.Option option : frame.options) {
            if (option.isKeep) {
                return session.submit(frame.actorPlayerId, option.optionId, option.actionType,
                        frame.revision);
            }
        }
        throw new AssertionError("no keep option parked");
    }

    public static BridgeSession.SubmitOutcome submitPass(BridgeSession session, DecisionFrame frame) {
        for (DecisionFrame.Option option : frame.options) {
            if (option.isPass) {
                return session.submit(frame.actorPlayerId, option.optionId, option.actionType,
                        frame.revision);
            }
        }
        throw new AssertionError("no pass option parked");
    }

    public static BridgeSession.SubmitOutcome submitStartingPlayer(BridgeSession session,
            DecisionFrame frame, String playerId) {
        Assert.assertEquals(frame.kind, DecisionFrame.Kind.STARTING_PLAYER);
        for (DecisionFrame.Option option : frame.options) {
            if (playerId.equals(option.sourceCardName)) {
                return session.submit(frame.actorPlayerId, option.optionId, option.actionType,
                        frame.revision);
            }
        }
        throw new AssertionError("no starting-player option for " + playerId);
    }

    /**
     * Drives game start: answers the Forge-selected chooser's STARTING_PLAYER frame by
     * selecting the given player, keeps all mulligans, and returns the first priority
     * frame. Returns the chooser's actor id through the wall clock of frames.
     */
    public static DecisionFrame driveStartToPriority(BridgeSession session, String choosePlayerId,
            long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        int iterations = 0;
        while (System.currentTimeMillis() < deadline) {
            if (++iterations > 60) {
                throw new AssertionError("too many start iterations");
            }
            final DecisionFrame frame = awaitFrame(session, Math.max(1000,
                    deadline - System.currentTimeMillis()));
            Assert.assertNotNull(frame, "no decision frame parked before timeout");
            if (frame.kind == DecisionFrame.Kind.PRIORITY) {
                return frame;
            }
            if (frame.kind == DecisionFrame.Kind.STARTING_PLAYER) {
                final BridgeSession.SubmitOutcome outcome =
                        submitStartingPlayer(session, frame, choosePlayerId);
                Assert.assertTrue(outcome.applied,
                        "starting-player choice failed: " + outcome.errorCode);
                continue;
            }
            Assert.assertEquals(frame.kind, DecisionFrame.Kind.MULLIGAN, "unexpected frame kind");
            final BridgeSession.SubmitOutcome outcome = submitKeep(session, frame);
            Assert.assertTrue(outcome.applied, "keep not applied: " + outcome.errorCode);
        }
        throw new AssertionError("priority frame never parked");
    }

    /**
     * Drives mulligans (always keep) until the first priority frame parks.
     * Only valid when no STARTING_PLAYER frame intervenes (constructed games).
     */
    public static DecisionFrame driveKeepsToPriority(BridgeSession session, long timeoutMillis) {        final long deadline = System.currentTimeMillis() + timeoutMillis;
        int iterations = 0;
        while (System.currentTimeMillis() < deadline) {
            if (++iterations > 40) {
                throw new AssertionError("too many mulligan iterations");
            }
            final DecisionFrame frame = awaitFrame(session, Math.max(1000,
                    deadline - System.currentTimeMillis()));
            Assert.assertNotNull(frame, "no decision frame parked before timeout");
            if (frame.kind == DecisionFrame.Kind.PRIORITY) {
                return frame;
            }
            Assert.assertEquals(frame.kind, DecisionFrame.Kind.MULLIGAN, "unexpected frame kind");
            final BridgeSession.SubmitOutcome outcome = submitKeep(session, frame);
            Assert.assertTrue(outcome.applied, "keep not applied: " + outcome.errorCode);
        }
        throw new AssertionError("priority frame never parked");
    }

    /** Real game object with caller-populated zones (no shuffle, deterministic). */
    public static final class ConstructedGame {
        public final BridgeEngine engine;
        public final BridgeSession session;
        public final Game game;

        ConstructedGame(BridgeEngine engine, BridgeSession session, Game game) {
            this.engine = engine;
            this.session = session;
            this.game = game;
        }
    }

    /**
     * Builds a real 4-player Commander game object from the real fixture decks, then
     * hands zone control to the caller. The caller populates zones and launches with
     * a custom starter that skips shuffle/mulligan deterministically.
     */
    public static ConstructedGame buildConstructedGame(String gameId) {
        final BridgeEngine engine = new BridgeEngine();
        startEngine(engine);
        final List<String> handles = importPod(engine);
        final Map<String, String> handleToDeck = new LinkedHashMap<>();
        for (String handle : handles) {
            handleToDeck.put(handle, engine.decksForTests().get(handle).deckId);
        }
        final List<RegisteredPlayer> players = new ArrayList<>(4);
        final List<List<String>> seatCommanders = new ArrayList<>(4);
        final BridgeSession session = new BridgeSession(gameId, handleToDeck);
        int seat = 0;
        for (String handle : handles) {
            final BridgeEngine.ImportedDeck deck = engine.decksForTests().get(handle);
            final RegisteredPlayer player = RegisteredPlayer.forCommander(deck.forgeDeck);
            player.setStartingLife(40);
            player.setPlayer(new BridgeLobbyPlayer("forge-p" + (seat + 1), session));
            players.add(player);
            seatCommanders.add(new ArrayList<>(deck.commanderNames));
            seat++;
        }
        session.setSeatCommanderNames(seatCommanders);
        final GameRules rules = new GameRules(GameType.Commander);
        rules.setAppliedVariants(EnumSet.of(GameType.Commander));
        final Match match = new Match(rules, players, "H4F-constructed");
        final Game game = match.createGame();
        session.attach(match, game);
        engine.sessionsForTests().put(gameId, session);
        return new ConstructedGame(engine, session, game);
    }

    public static Card addCard(Game game, int seat, String cardName, ZoneType zone) {
        final Player owner = game.getPlayers().get(seat);
        final PaperCard paper = StaticData.instance().getCommonCards().getCard(cardName);
        Assert.assertNotNull(paper, "card must resolve: " + cardName);
        final Card card = Card.fromPaperCard(paper, owner);
        owner.getZone(zone).add(card);
        return card;
    }

    /** Custom starter: deterministic opening (no shuffle, no mulligan). */
    public static void launchConstructed(ConstructedGame constructed) {
        final Game game = constructed.game;
        constructed.session.launchStarter(() -> {
            game.setAge(GameStage.Play);
            game.getTriggerHandler().runTrigger(TriggerType.NewGame, AbilityKey.newMap(), true);
            game.getPhaseHandler().startFirstTurn(game.getPlayers().get(0));
        });
    }
}
