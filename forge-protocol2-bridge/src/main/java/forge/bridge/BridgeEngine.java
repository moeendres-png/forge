package forge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import forge.StaticData;
import forge.card.CardDb;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameLogEntry;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protocol-2 message dispatch and Forge lifecycle ownership.
 *
 * <p>Implements exactly the messages whose semantics are real and verified against the
 * pinned engine. Everything else fails closed with explicit unsupported/error results.
 * No capability is claimed beyond the tested surface; the bounded proven subset is
 * described in capability notes while the global action flags stay false.
 */
public final class BridgeEngine {
    private volatile boolean started;
    private volatile boolean shutDown;
    private volatile long startNanos;

    private final Map<String, ImportedDeck> decks = new ConcurrentHashMap<>();
    private final Map<String, BridgeSession> sessions = new ConcurrentHashMap<>();

    /** Thrown when no valid 40-hex engine identity is bound (hard gate F3). */
    static final class EngineIdentityException extends RuntimeException {
        EngineIdentityException() {
            super("no valid 40-hex Forge engine SHA bound");
        }
    }

    /**
     * Production usability gate: gameplay handlers require a bound engine identity.
     * Only identity observability (get_provider_version/get_capabilities) and engine
     * shutdown stay served without one.
     */
    private static void requireUsable() {
        if (VersionInfo.engineCommitIfValid() == null) {
            throw new EngineIdentityException();
        }
    }

    /** One imported deck: Lab identity plus the real Forge Deck. */
    static final class ImportedDeck {
        final String handleId;
        final String deckId;
        final String name;
        final String deckHash;
        final List<String> commanderNames;
        final Deck forgeDeck;

        ImportedDeck(String handleId, String deckId, String name, String deckHash,
                List<String> commanderNames, Deck forgeDeck) {
            this.handleId = handleId;
            this.deckId = deckId;
            this.name = name;
            this.deckHash = deckHash;
            this.commanderNames = Collections.unmodifiableList(new ArrayList<>(commanderNames));
            this.forgeDeck = forgeDeck;
        }
    }

    public String dispatch(BridgeProtocol.Request request) {
        if (request.messageType == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.UNKNOWN_MESSAGE,
                    "missing message_type/method", 0);
        }
        if (!BridgeProtocol.PROTOCOL_VERSION.equals(request.protocolVersion)) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.PROTOCOL_VERSION_MISMATCH,
                    "protocol_version is required and must equal 2.0.0", 0);
        }
        final String type = request.messageType;
        try {
            final boolean identityExempt = type.equals(BridgeProtocol.GET_PROVIDER_VERSION)
                    || type.equals(BridgeProtocol.GET_CAPABILITIES)
                    || type.equals(BridgeProtocol.SHUTDOWN_ENGINE);
            if (!identityExempt) {
                requireUsable();
            }
            switch (type) {
                case BridgeProtocol.START_ENGINE:
                    return startEngine(request);
                case BridgeProtocol.GET_CAPABILITIES:
                    return getCapabilities(request);
                case BridgeProtocol.GET_PROVIDER_VERSION:
                    return getProviderVersion(request);
                case BridgeProtocol.IMPORT_DECK:
                    return importDeck(request);
                case BridgeProtocol.CREATE_COMMANDER_GAME:
                    return createCommanderGame(request);
                case BridgeProtocol.ADD_PLAYER:
                    return BridgeProtocol.unsupported(request.requestId, BridgeErrors.UNKNOWN_MESSAGE,
                            "add_player is not supported: game rosters are fixed at creation", 0);
                case BridgeProtocol.START_GAME:
                    return startGame(request);
                case BridgeProtocol.GET_GAME_STATE:
                    return getGameState(request);
                case BridgeProtocol.GET_LEGAL_ACTIONS:
                    return getLegalActions(request);
                case BridgeProtocol.SUBMIT_ACTION:
                    return submitAction(request);
                case BridgeProtocol.PASS_PRIORITY:
                    return passPriority(request);
                case BridgeProtocol.SELECT_TARGETS:
                    return BridgeProtocol.unsupported(request.requestId, BridgeErrors.UNKNOWN_MESSAGE,
                            "select_targets is not supported: target selection is not represented", 0);
                case BridgeProtocol.CHOOSE_MODES:
                    return BridgeProtocol.unsupported(request.requestId, BridgeErrors.UNKNOWN_MESSAGE,
                            "choose_modes is not supported: modal choices are not represented", 0);
                case BridgeProtocol.ORDER_TRIGGERS:
                    return BridgeProtocol.unsupported(request.requestId, BridgeErrors.UNKNOWN_MESSAGE,
                            "order_triggers is not supported: trigger ordering is not represented", 0);
                case BridgeProtocol.RESOLVE_MULLIGAN:
                    return resolveMulligan(request);
                case BridgeProtocol.CONCEDE:
                    return BridgeProtocol.unsupported(request.requestId, BridgeErrors.UNKNOWN_MESSAGE,
                            "concede is not supported by this bridge version", 0);
                case BridgeProtocol.EXPORT_EVENT_LOG:
                    return exportEventLog(request);
                case BridgeProtocol.EXPORT_REPLAY:
                    return BridgeProtocol.unsupported(request.requestId, BridgeErrors.UNKNOWN_MESSAGE,
                            "export_replay is not supported: deterministic replay is not claimed", 0);
                case BridgeProtocol.SHUTDOWN_GAME:
                    return shutdownGame(request);
                case BridgeProtocol.SHUTDOWN_ENGINE:
                    return shutdownEngine(request);
                // Narrow compatibility aliases with identical, verified semantics.
                case "create_game":
                    return createGameAlias(request);
                case "get_state":
                    return getGameState(request);
                case "shutdown":
                    return shutdownGame(request);
                case "get_event_log":
                    return exportEventLog(request);
                default:
                    return BridgeProtocol.error(request.requestId, BridgeErrors.UNKNOWN_MESSAGE,
                            "unknown message type: " + type, 0);
            }
        } catch (EngineIdentityException e) {
            return identityError(request);
        } catch (Throwable e) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.INTERNAL_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), 0);
        }
    }

    private static String identityError(BridgeProtocol.Request request) {
        return BridgeProtocol.error(request.requestId, BridgeErrors.ENGINE_IDENTITY_UNAVAILABLE,
                "no valid 40-hex Forge engine SHA bound", 0);
    }

    public boolean isShutDown() {
        return shutDown;
    }

    // ---- handshake ----

    private String startEngine(BridgeProtocol.Request request) {
        final JsonObject payload = new JsonObject();
        if (shutDown) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.ENGINE_SHUT_DOWN,
                    "engine is shut down", 0);
        }
        final boolean already = started;
        started = true;
        if (startNanos == 0) {
            startNanos = System.nanoTime();
        }
        payload.addProperty("engine", VersionInfo.PROVIDER);
        payload.addProperty("protocol_version", BridgeProtocol.PROTOCOL_VERSION);
        payload.addProperty("already_started", already);
        payload.addProperty("status", "started");
        return BridgeProtocol.ok(request.requestId, payload, 0);
    }

    private String getCapabilities(BridgeProtocol.Request request) {
        final JsonObject caps = new JsonObject();
        caps.addProperty("commander_supported", true);
        caps.addProperty("partner_supported", false);
        caps.addProperty("multiplayer_supported", true);
        caps.addProperty("max_players", 4);
        caps.addProperty("headless_supported", true);
        caps.addProperty("seed_supported", false);
        caps.addProperty("deck_import_supported", true);
        caps.addProperty("legal_actions_supported", false);
        caps.addProperty("action_submission_supported", false);
        caps.addProperty("event_log_supported", true);
        caps.addProperty("replay_supported", false);
        caps.addProperty("stack_visible", true);
        caps.addProperty("priority_visible", true);
        caps.addProperty("commander_damage_visible", true);
        caps.addProperty("commander_tax_visible", true);
        caps.addProperty("starting_state_injection_supported", false);
        caps.addProperty("scenario_injection_supported", false);
        caps.addProperty("healthcheck_supported", true);
        caps.addProperty("target_selection_supported", false);
        caps.addProperty("mode_selection_supported", false);
        caps.addProperty("trigger_order_supported", false);
        caps.addProperty("mulligan_supported", false);
        caps.addProperty("concede_supported", false);
        caps.addProperty("game_shutdown_supported", true);
        caps.addProperty("engine_shutdown_supported", true);
        caps.addProperty("runtime_kind", "external_rules_engine");
        final JsonArray notes = new JsonArray();
        notes.add("bounded proven subset only: priority pass, targetless nonmodal zero-mana "
                + "execution (no discretionary mana payment; nonzero-mana candidates fail closed), "
                + "fixed-output mana abilities, binary mulligan keep/ship, external starting-player "
                + "choice; global legal_actions_supported and action_submission_supported stay false "
                + "until the full decision surface qualifies");
        notes.add("partner commanders import but pod-level partner lifecycle is not yet qualified");
        notes.add("mulligan keep/ship is externally decided per player; London-tuck selection aborts loudly");
        notes.add("seeds are rejected: engine RNG is global and same-seed determinism is not claimed");
        notes.add("replay is not offered: deterministic replay is not claimed");
        caps.add("notes", notes);
        final JsonObject payload = new JsonObject();
        payload.add("capabilities", caps);
        return BridgeProtocol.ok(request.requestId, payload, 0);
    }

    private String getProviderVersion(BridgeProtocol.Request request) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("provider", VersionInfo.PROVIDER);
        payload.addProperty("release", VersionInfo.RELEASE);
        payload.addProperty("engine_commit", VersionInfo.engineCommit());
        payload.addProperty("engine_commit_source", VersionInfo.engineCommitSource());
        payload.addProperty("protocol_version", BridgeProtocol.PROTOCOL_VERSION);
        payload.addProperty("bridge_name", VersionInfo.BRIDGE_NAME);
        payload.addProperty("bridge_version", VersionInfo.BRIDGE_VERSION);
        payload.addProperty("java_version", System.getProperty("java.version", "unknown"));
        return BridgeProtocol.ok(request.requestId, payload, 0);
    }

    // ---- deck import: real Forge Deck from Lab card names ----

    private String importDeck(BridgeProtocol.Request request) {
        if (!started) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.ENGINE_NOT_STARTED,
                    "start_engine first", 0);
        }
        final JsonObject deckJson = BridgeProtocol.optObject(request.payload, "deck");
        if (deckJson == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.DECK_IMPORT_FAILED,
                    "missing deck object", 0);
        }
        final String deckId = BridgeProtocol.optString(deckJson, "deck_id", "").trim();
        final String name = BridgeProtocol.optString(deckJson, "name", deckId);
        final List<String> commanders = stringList(deckJson, "commander_names");
        final List<String> mainboard = stringList(deckJson, "mainboard");
        if (deckId.isEmpty()) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.DECK_IMPORT_FAILED,
                    "deck_id is required", 0);
        }
        if (commanders.size() < 1 || commanders.size() > 2) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.DECK_IMPORT_FAILED,
                    "Commander requires one commander or two partners", 0);
        }
        if (commanders.size() + mainboard.size() != 100) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.DECK_IMPORT_FAILED,
                    "Commander deck must contain exactly 100 cards; observed "
                            + (commanders.size() + mainboard.size()), 0);
        }
        final CardDb db;
        try {
            db = StaticData.instance().getCommonCards();
        } catch (Throwable e) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.DECK_IMPORT_FAILED,
                    "card database unavailable: " + e.getMessage(), 0);
        }
        final Deck forgeDeck = new Deck(name == null || name.isEmpty() ? deckId : name);
        final List<String> unresolved = new ArrayList<>();
        final CardPool commanderPool = forgeDeck.getOrCreate(DeckSection.Commander);
        for (String cardName : commanders) {
            final PaperCard card = db.getCard(cardName);
            if (card == null) {
                unresolved.add(cardName);
            } else {
                commanderPool.add(Collections.singletonList(card));
            }
        }
        final CardPool mainPool = forgeDeck.getMain();
        for (String cardName : mainboard) {
            final PaperCard card = db.getCard(cardName);
            if (card == null) {
                unresolved.add(cardName);
            } else {
                mainPool.add(Collections.singletonList(card));
            }
        }
        if (!unresolved.isEmpty()) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.DECK_IMPORT_FAILED,
                    "unresolvable card names: " + String.join(", ", unresolved), 0);
        }
        String deckHash = BridgeProtocol.optString(deckJson, "deck_hash", null);
        if (deckHash == null || !deckHash.matches("[0-9a-f]{64}")) {
            final List<String> canonical = new ArrayList<>(commanders);
            final List<String> sortedMain = new ArrayList<>(mainboard);
            Collections.sort(sortedMain);
            canonical.addAll(sortedMain);
            deckHash = StateHash.sha256(String.join("\n", canonical));
        }
        final String handleId = "deck-" + UUID.randomUUID();
        decks.put(handleId, new ImportedDeck(handleId, deckId, forgeDeck.getName(), deckHash,
                commanders, forgeDeck));
        final JsonObject handle = new JsonObject();
        handle.addProperty("backend", "forge");
        handle.addProperty("handle_id", handleId);
        handle.addProperty("deck_id", deckId);
        handle.addProperty("deck_hash", deckHash);
        final JsonArray commanderArray = new JsonArray();
        for (String commander : commanders) {
            commanderArray.add(commander);
        }
        handle.add("commander_names", commanderArray);
        handle.addProperty("accepted_cards", commanders.size() + mainboard.size());
        handle.add("rejected_cards", new JsonArray());
        handle.add("warnings", new JsonArray());
        final JsonObject payload = new JsonObject();
        payload.add("deck_handle", handle);
        return BridgeProtocol.ok(request.requestId, payload, 0);
    }

    // ---- game creation: four real players, real Commander game ----

    private String createGameAlias(BridgeProtocol.Request request) {
        if (BridgeProtocol.optObject(request.payload, "request") != null) {
            return createCommanderGame(request);
        }
        return BridgeProtocol.unsupported(request.requestId, BridgeErrors.UNKNOWN_MESSAGE,
                "create_game only supports commander-game creation via a request object", 0);
    }

    private String createCommanderGame(BridgeProtocol.Request request) {
        if (!started) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.ENGINE_NOT_STARTED,
                    "start_engine first", 0);
        }
        final JsonObject gameRequest = BridgeProtocol.optObject(request.payload, "request");
        if (gameRequest == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.GAME_CREATION_FAILED,
                    "missing request object", 0);
        }
        final String gameId = BridgeProtocol.optString(gameRequest, "game_id", "").trim();
        if (gameId.isEmpty()) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.GAME_CREATION_FAILED,
                    "game_id is required", 0);
        }
        if (sessions.containsKey(gameId)) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.GAME_CREATION_FAILED,
                    "game_id already exists", 0);
        }
        final String format = BridgeProtocol.optString(gameRequest, "format", "commander");
        if (!"commander".equals(format)) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.GAME_CREATION_FAILED,
                    "only commander format is supported, got " + format, 0);
        }
        if (gameRequest.has("seed") && !gameRequest.get("seed").isJsonNull()) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.SEED_UNSUPPORTED,
                    "seeds are not supported: engine RNG is global and not reproducible", 0);
        }
        final List<String> handles = stringList(gameRequest, "deck_handles");
        if (handles.size() != 4) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.PLAYER_COUNT_UNSUPPORTED,
                    "this bridge qualifies exactly four players; got " + handles.size(), 0);
        }
        final List<ImportedDeck> pod = new ArrayList<>(4);
        for (String handle : handles) {
            final ImportedDeck deck = decks.get(handle);
            if (deck == null) {
                return BridgeProtocol.error(request.requestId, BridgeErrors.UNKNOWN_DECK_HANDLE,
                        "unknown deck handle: " + handle, 0);
            }
            pod.add(deck);
        }
        if (gameRequest.has("starting_player_seat") && !gameRequest.get("starting_player_seat").isJsonNull()) {
            return BridgeProtocol.error(request.requestId,
                    BridgeErrors.STARTING_PLAYER_SEAT_UNSUPPORTED,
                    "starting_player_seat injection is not supported: Forge selects the chooser "
                            + "and the choice is externalized through a STARTING_PLAYER frame",
                    0);
        }
        if (gameRequest.has("starting_life") && !gameRequest.get("starting_life").isJsonNull()) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.STARTING_LIFE_UNSUPPORTED,
                    "starting_life injection is not supported: canonical Commander starting life "
                            + "is established by the pinned Forge rules/runtime",
                    0);
        }
        final Map<String, String> handleToDeck = new LinkedHashMap<>();
        for (int i = 0; i < handles.size(); i++) {
            handleToDeck.put(handles.get(i), pod.get(i).deckId);
        }
        final BridgeSession session = new BridgeSession(gameId, handleToDeck);
        final List<RegisteredPlayer> players = new ArrayList<>(4);
        final List<List<String>> seatCommanders = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            final ImportedDeck deck = pod.get(i);
            final RegisteredPlayer player = RegisteredPlayer.forCommander(deck.forgeDeck);
            player.setPlayer(new BridgeLobbyPlayer("forge-p" + (i + 1), session));
            players.add(player);
            seatCommanders.add(new ArrayList<>(deck.commanderNames));
        }
        session.setSeatCommanderNames(seatCommanders);
        final GameRules rules = new GameRules(GameType.Commander);
        rules.setAppliedVariants(EnumSet.of(GameType.Commander));
        final Match match = new Match(rules, players, "WS-A1D-H4F");
        final Game game;
        try {
            game = match.createGame();
        } catch (Throwable e) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.GAME_CREATION_FAILED,
                    "engine rejected game creation: " + e.getMessage(), 0);
        }
        session.attach(match, game);
        sessions.put(gameId, session);
        session.audit("game_created", creationDetails(gameId, handles));
        final JsonObject payload = new JsonObject();
        payload.addProperty("game_id", gameId);
        payload.addProperty("player_count", 4);
        payload.addProperty("status", "created");
        final JsonArray seats = new JsonArray();
        for (int i = 0; i < 4; i++) {
            final JsonObject seat = new JsonObject();
            seat.addProperty("seat", i);
            seat.addProperty("player_id", "p" + (i + 1));
            seat.addProperty("name", "forge-p" + (i + 1));
            seats.add(seat);
        }
        payload.add("seats", seats);
        return BridgeProtocol.ok(request.requestId, payload, (int) session.auditSize());
    }

    private Map<String, String> creationDetails(String gameId, List<String> handles) {
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("game_id", gameId);
        details.put("deck_handles", String.join(",", handles));
        return details;
    }

    private String startGame(BridgeProtocol.Request request) {
        final BridgeSession session = requireSession(request);
        if (session == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.UNKNOWN_GAME,
                    "unknown game_id: " + request.gameId, 0);
        }
        synchronized (session) {
            if (session.getStatus() != BridgeSession.Status.CREATED) {
                final JsonObject payload = new JsonObject();
                payload.addProperty("game_id", session.getGameId());
                payload.addProperty("status", session.getStatus().name().toLowerCase());
                return BridgeProtocol.ok(request.requestId, payload, (int) session.auditSize());
            }
        }
        try {
            session.launch();
        } catch (Throwable e) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.INTERNAL_ERROR,
                    "could not start game thread: " + e.getMessage(), 0);
        }
        final JsonObject payload = new JsonObject();
        payload.addProperty("game_id", session.getGameId());
        payload.addProperty("status", "started");
        return BridgeProtocol.ok(request.requestId, payload, (int) session.auditSize());
    }

    // ---- observation ----

    private String getGameState(BridgeProtocol.Request request) {
        final BridgeSession session = requireSession(request);
        if (session == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.UNKNOWN_GAME,
                    "unknown game_id: " + request.gameId, 0);
        }
        if (session.getGame() == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.INTERNAL_ERROR,
                    "game object missing", (int) session.auditSize());
        }
        final String observer = BridgeProtocol.optString(request.payload, "observer_player_id", null);
        if (observer != null && session.playerById(observer) == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.WRONG_ACTOR,
                    "unknown observer: " + observer, (int) session.auditSize());
        }
        final JsonObject payload = new JsonObject();
        try {
            payload.add("state", StateProjection.gameState(session, observer));
        } catch (BridgeProjectionException e) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.PROJECTION_FAILED,
                    "authoritative state unreadable: " + e.getMessage(), (int) session.auditSize());
        }
        payload.add("bridge", StateProjection.bridgeMeta(session));
        return BridgeProtocol.ok(request.requestId, payload, (int) session.auditSize());
    }

    private String getLegalActions(BridgeProtocol.Request request) {
        final BridgeSession session = requireSession(request);
        if (session == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.UNKNOWN_GAME,
                    "unknown game_id: " + request.gameId, 0);
        }
        // R3: legal options are principal-scoped. The caller names the principal; only
        // the current frame's actor receives its options.
        final String actorId = BridgeProtocol.optString(request.payload, "actor_id", null);
        if (actorId == null || actorId.isEmpty()) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.MALFORMED_REQUEST,
                    "actor_id is required", (int) session.auditSize());
        }
        final DecisionFrame frame = session.getCurrentFrame();
        if (frame == null) {
            final JsonObject payload = new JsonObject();
            payload.add("actions", new JsonArray());
            final JsonObject decision = new JsonObject();
            decision.addProperty("status", "no_pending_decision");
            payload.add("decision", decision);
            return BridgeProtocol.ok(request.requestId, payload, (int) session.auditSize());
        }
        if (!actorId.equals(frame.actorPlayerId)) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.WRONG_ACTOR,
                    "decision belongs to " + frame.actorPlayerId, (int) session.auditSize());
        }
        final JsonObject payload = new JsonObject();
        payload.add("actions", StateProjection.legalActions(session));
        payload.add("decision", StateProjection.decisionSummary(frame));
        return BridgeProtocol.ok(request.requestId, payload, (int) session.auditSize());
    }

    // ---- submission ----

    private static Long requiredRevision(BridgeProtocol.Request request, JsonObject holder,
            BridgeSession session) throws MalformedPayloadException {
        if (holder == null || !holder.has("revision") || holder.get("revision").isJsonNull()) {
            throw new MalformedPayloadException("revision is required");
        }
        try {
            return holder.get("revision").getAsLong();
        } catch (Exception e) {
            throw new MalformedPayloadException("revision must be an integer");
        }
    }

    private static final class MalformedPayloadException extends Exception {
        MalformedPayloadException(String message) {
            super(message);
        }
    }

    private static String nonEmpty(JsonObject holder, String key) {
        if (holder == null) {
            return null;
        }
        final String value = BridgeProtocol.optString(holder, key, null);
        return value == null || value.isEmpty() ? null : value;
    }

    private String submitAction(BridgeProtocol.Request request) {
        final BridgeSession session = requireSession(request);
        if (session == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.UNKNOWN_GAME,
                    "unknown game_id: " + request.gameId, 0);
        }
        final JsonObject proposal = BridgeProtocol.optObject(request.payload, "proposal");
        if (proposal == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.MALFORMED_REQUEST,
                    "missing proposal object", (int) session.auditSize());
        }
        final String actorId = nonEmpty(proposal, "actor_id");
        final String legalActionId = nonEmpty(proposal, "legal_action_id");
        final String actionType = nonEmpty(proposal, "action_type");
        if (actorId == null || legalActionId == null || actionType == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.MALFORMED_REQUEST,
                    "proposal requires non-empty actor_id, legal_action_id and action_type",
                    (int) session.auditSize());
        }
        final Long revision;
        try {
            revision = requiredRevision(request, request.payload, session);
        } catch (MalformedPayloadException e) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.MALFORMED_REQUEST,
                    e.getMessage(), (int) session.auditSize());
        }
        session.setLastExecutionError("");
        final BridgeSession.SubmitOutcome outcome = session.submit(actorId, legalActionId, actionType, revision);
        return submitResponse(request, session, outcome);
    }

    private String passPriority(BridgeProtocol.Request request) {
        final BridgeSession session = requireSession(request);
        if (session == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.UNKNOWN_GAME,
                    "unknown game_id: " + request.gameId, 0);
        }
        final String actorId = nonEmpty(request.payload, "actor_id");
        if (actorId == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.MALFORMED_REQUEST,
                    "actor_id is required", (int) session.auditSize());
        }
        final Long revision;
        try {
            revision = requiredRevision(request, request.payload, session);
        } catch (MalformedPayloadException e) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.MALFORMED_REQUEST,
                    e.getMessage(), (int) session.auditSize());
        }
        final DecisionFrame frame = session.getCurrentFrame();
        if (frame == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.NO_PENDING_DECISION,
                    "engine is not awaiting an external decision", (int) session.auditSize());
        }
        if (frame.kind != DecisionFrame.Kind.PRIORITY) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.UNSUPPORTED_DECISION,
                    "parked decision is not a priority decision", (int) session.auditSize());
        }
        if (!actorId.equals(frame.actorPlayerId)) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.WRONG_ACTOR,
                    "decision belongs to " + frame.actorPlayerId, (int) session.auditSize());
        }
        if (revision.longValue() != frame.revision) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.STALE_REVISION,
                    "frame revision " + frame.revision + " expected, got " + revision,
                    (int) session.auditSize());
        }
        DecisionFrame.Option pass = null;
        for (DecisionFrame.Option option : frame.options) {
            if (option.isPass) {
                pass = option;
                break;
            }
        }
        if (pass == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.UNSUPPORTED_DECISION,
                    "parked priority decision offers no pass option", (int) session.auditSize());
        }
        session.setLastExecutionError("");
        final BridgeSession.SubmitOutcome outcome =
                session.submit(actorId, pass.optionId, "pass_priority", revision);
        return submitResponse(request, session, outcome);
    }

    private String resolveMulligan(BridgeProtocol.Request request) {
        final BridgeSession session = requireSession(request);
        if (session == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.UNKNOWN_GAME,
                    "unknown game_id: " + request.gameId, 0);
        }
        final String playerId = nonEmpty(request.payload, "player_id");
        if (playerId == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.MALFORMED_REQUEST,
                    "player_id is required", (int) session.auditSize());
        }
        final Long revision;
        try {
            revision = requiredRevision(request, request.payload, session);
        } catch (MalformedPayloadException e) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.MALFORMED_REQUEST,
                    e.getMessage(), (int) session.auditSize());
        }
        final boolean keep;
        if (!request.payload.has("keep") || request.payload.get("keep").isJsonNull()) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.MALFORMED_REQUEST,
                    "keep is required and must be an explicit boolean", (int) session.auditSize());
        }
        try {
            keep = request.payload.get("keep").getAsBoolean();
        } catch (Exception e) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.MALFORMED_REQUEST,
                    "keep must be a boolean", (int) session.auditSize());
        }
        final List<String> bottom = stringList(request.payload, "bottom_card_ids");
        if (!bottom.isEmpty()) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.UNSUPPORTED_DECISION,
                    "London-tuck card selection is not represented", (int) session.auditSize());
        }
        final DecisionFrame frame = session.getCurrentFrame();
        if (frame == null || frame.kind != DecisionFrame.Kind.MULLIGAN) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.NO_PENDING_DECISION,
                    "no mulligan decision is parked", (int) session.auditSize());
        }
        if (!playerId.equals(frame.actorPlayerId)) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.WRONG_ACTOR,
                    "decision belongs to " + frame.actorPlayerId, (int) session.auditSize());
        }
        if (revision.longValue() != frame.revision) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.STALE_REVISION,
                    "frame revision " + frame.revision + " expected, got " + revision,
                    (int) session.auditSize());
        }
        DecisionFrame.Option chosen = null;
        for (DecisionFrame.Option option : frame.options) {
            if (option.isKeep == keep) {
                chosen = option;
                break;
            }
        }
        if (chosen == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.UNSUPPORTED_DECISION,
                    "parked mulligan decision offers no matching option", (int) session.auditSize());
        }
        session.setLastExecutionError("");
        final BridgeSession.SubmitOutcome outcome =
                session.submit(playerId, chosen.optionId, "mulligan", revision);
        return submitResponse(request, session, outcome);
    }

    private String submitResponse(BridgeProtocol.Request request, BridgeSession session,
            BridgeSession.SubmitOutcome outcome) {
        if (!outcome.applied) {
            return BridgeProtocol.fail(request.requestId, "error", outcome.errorCode,
                    outcome.errorMessage, BridgeErrors.NO_PENDING_DECISION.equals(outcome.errorCode),
                    null, (int) session.auditSize());
        }
        final JsonObject payload = new JsonObject();
        try {
            payload.add("state", StateProjection.gameState(session, null));
        } catch (BridgeProjectionException e) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.PROJECTION_FAILED,
                    "authoritative state unreadable: " + e.getMessage(), (int) session.auditSize());
        }
        final JsonObject decision = new JsonObject();
        decision.addProperty("executed", outcome.executionOk);
        decision.addProperty("pre_state_hash", outcome.preStateHash);
        decision.addProperty("post_state_hash", outcome.postStateHash);
        if (!outcome.executionOk && !session.getLastExecutionError().isEmpty()) {
            decision.addProperty("execution_note", session.getLastExecutionError());
        }
        payload.add("decision", decision);
        final DecisionFrame next = session.getCurrentFrame();
        if (next == null) {
            payload.add("next_decision", new JsonObject());
        } else {
            payload.add("next_decision", StateProjection.decisionSummary(next));
        }
        final Game game = session.getGame();
        payload.addProperty("game_over", game != null && game.isGameOver());
        payload.add("bridge", StateProjection.bridgeMeta(session));
        return BridgeProtocol.ok(request.requestId, payload, (int) session.auditSize());
    }

    // ---- audit ----

    private String exportEventLog(BridgeProtocol.Request request) {
        final BridgeSession session = requireSession(request);
        if (session == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.UNKNOWN_GAME,
                    "unknown game_id: " + request.gameId, 0);
        }
        final JsonArray events = new JsonArray();
        final List<String> rawLines = new ArrayList<>();
        int sequence = 0;
        final Game game = session.getGame();
        if (game != null) {
            try {
                final List<GameLogEntry> entries = game.getGameLog().getLogEntries(null);
                final List<GameLogEntry> chronological = new ArrayList<>(entries);
                Collections.reverse(chronological);
                for (GameLogEntry entry : chronological) {
                    final JsonObject event = new JsonObject();
                    event.addProperty("event_id", session.getGameId() + ":engine:" + sequence);
                    event.addProperty("game_id", session.getGameId());
                    event.addProperty("sequence", sequence);
                    event.addProperty("event_type", "forge_log:" + entry.type().name().toLowerCase());
                    event.add("actor_id", JsonNull.INSTANCE);
                    final JsonObject payload = new JsonObject();
                    payload.addProperty("message", entry.message());
                    payload.add("source_card", JsonNull.INSTANCE);
                    event.add("payload", payload);
                    event.add("pre_state_hash", JsonNull.INSTANCE);
                    event.add("post_state_hash", JsonNull.INSTANCE);
                    event.add("occurred_at", JsonNull.INSTANCE);
                    events.add(event);
                    rawLines.add(sequence + " forge_log:" + entry.type().name().toLowerCase()
                            + " " + entry.message());
                    sequence++;
                }
            } catch (Throwable t) {
                // Engine log unreadable; bridge audit still exported.
            }
        }
        for (BridgeSession.AuditEvent audit : session.auditSnapshot()) {
            final JsonObject event = new JsonObject();
            event.addProperty("event_id", session.getGameId() + ":bridge:" + sequence);
            event.addProperty("game_id", session.getGameId());
            event.addProperty("sequence", sequence);
            event.addProperty("event_type", "bridge:" + audit.type);
            event.add("actor_id", JsonNull.INSTANCE);
            final JsonObject payload = new JsonObject();
            for (Map.Entry<String, String> detail : audit.details.entrySet()) {
                payload.addProperty(detail.getKey(), detail.getValue());
            }
            event.add("payload", payload);
            event.add("pre_state_hash", JsonNull.INSTANCE);
            event.add("post_state_hash", JsonNull.INSTANCE);
            event.add("occurred_at", JsonNull.INSTANCE);
            events.add(event);
            rawLines.add(sequence + " bridge:" + audit.type + " " + payload);
            sequence++;
        }
        final JsonArray rawArray = new JsonArray();
        for (String line : rawLines) {
            rawArray.add(line);
        }
        final JsonObject log = new JsonObject();
        log.addProperty("backend", "forge");
        log.addProperty("session_id", session.getGameId());
        log.add("events", events);
        log.add("raw_lines", rawArray);
        log.addProperty("log_sha256", StateHash.sha256(String.join("\n", rawLines)));
        final JsonObject payload = new JsonObject();
        payload.add("log", log);
        return BridgeProtocol.ok(request.requestId, payload, (int) session.auditSize());
    }

    // ---- shutdown ----

    private String shutdownGame(BridgeProtocol.Request request) {
        final BridgeSession session = requireSession(request);
        if (session == null) {
            return BridgeProtocol.error(request.requestId, BridgeErrors.UNKNOWN_GAME,
                    "unknown game_id: " + request.gameId, 0);
        }
        session.shutdown(5000);
        final JsonObject payload = new JsonObject();
        payload.addProperty("game_id", session.getGameId());
        payload.addProperty("status", session.getStatus().name().toLowerCase());
        return BridgeProtocol.ok(request.requestId, payload, (int) session.auditSize());
    }

    private String shutdownEngine(BridgeProtocol.Request request) {
        for (BridgeSession session : sessions.values()) {
            try {
                session.shutdown(2000);
            } catch (Throwable t) {
                // Best effort; the process exits anyway.
            }
        }
        shutDown = true;
        final JsonObject payload = new JsonObject();
        payload.addProperty("status", "engine_shut_down");
        return BridgeProtocol.ok(request.requestId, payload, 0);
    }

    // ---- helpers ----

    private BridgeSession requireSession(BridgeProtocol.Request request) {
        if (request.gameId == null || request.gameId.isEmpty()) {
            return null;
        }
        return sessions.get(request.gameId);
    }

    static List<String> stringList(JsonObject obj, String key) {
        final List<String> result = new ArrayList<>();
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return result;
        }
        final JsonElement element = obj.get(key);
        if (!element.isJsonArray()) {
            return result;
        }
        final JsonArray array = element.getAsJsonArray();
        for (JsonElement item : array) {
            if (!item.isJsonNull()) {
                try {
                    result.add(item.getAsString());
                } catch (Exception e) {
                    // Skip non-string entries.
                }
            }
        }
        return result;
    }

    // Visible for tests: reset engine state between isolated test runs.
    void resetForTests() {
        started = false;
        shutDown = false;
        startNanos = 0;
        decks.clear();
        sessions.clear();
    }

    Map<String, ImportedDeck> decksForTests() {
        return decks;
    }

    Map<String, BridgeSession> sessionsForTests() {
        return sessions;
    }
}
