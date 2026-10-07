package forge.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.StaticData;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lossless library materialization at the turn-one checkpoint
 * (Commander-Lab #561 batch 6 B2b; Coordinator decision 6035022676, item 2,
 * conditions C1, C2, C4-C8).
 *
 * <p>The request mirrors the Lab's frozen record: a {@code deck_state} array
 * (per player: {@code library_template}, {@code opening_hand_size},
 * {@code checkpoint_hand}, optionally {@code checkpoint_library} as
 * {@code COMPLETE_TOP_TO_BOTTOM} runs of semantic objects and template counts)
 * and the record's {@code semantic_objects}. The shape and the failure codes
 * follow the qualified XMage path ({@code XmageLosslessHiddenPlan}).
 *
 * <p><b>Seam (C1).</b> Applied once, from the session's
 * {@code GameEventTurnPhase} subscriber when turn one enters its precombat
 * main phase: after the untap, upkeep and draw steps (so the engine's own
 * turn-one draw has happened) and before the first priority of that phase.
 * Never at {@code GameEventTurnBegan} and never in the post-untap start-game
 * hook: both run before the draw, which would then take the requested top
 * card. Every precondition is checked before anything is mutated: the
 * materialized player drew exactly the declared number of cards this turn
 * (one), its library holds exactly the declared template remainder (derived
 * from the request's runs, never a constant), every library and hand card is
 * the declared template identity, and the player is the active player.
 *
 * <p><b>Construction (C2, C4).</b> Exactly the declared library objects are
 * created ({@code Card.fromPaperCard}), placed at their declared positions
 * among the engine's own cards, and the zone is ordered with one native
 * {@link Zone#setCards} call. Declared non-template hand objects are added the
 * same way; the template hand is never scripted (C7). No shuffle, no draw and
 * no {@code GameEventCardChangeZone} is fired; the only events are one
 * {@code GameEventZone} {@code ComplexUpdate} per touched zone (a view refresh,
 * the class the event tape keeps in its known-but-ignored set).
 *
 * <p><b>Verification (C6).</b> The bridge reads the zones back: every declared
 * object at its exact position (also catching {@code Zone.add}'s silent drop
 * on an empty zone), the non-object cards exactly the engine's own cards (no
 * undeclared extra object, none missing), the identity order, and the hand
 * composition. A mismatch fails closed and leaves only as a code; no card name
 * ever appears in a failure or a published count (C5, C6).
 */
final class CheckpointMaterialization {

    static final String LIBRARY_COMPLETENESS = "COMPLETE_TOP_TO_BOTTOM";
    static final String HAND_COMPLETENESS = "COMPLETE";

    /** A coded refusal: the message is the code, optionally a semantic id or player id. */
    static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code;

        Rejected(String code, String subject) {
            super(subject == null ? code : code + " " + subject);
            this.code = code;
        }
    }

    record LibraryObject(String semanticId, String cardIdentity, String owner, int position) {
    }

    record HandObject(String semanticId, String cardIdentity, String owner) {
    }

    record PlayerDeck(String playerId, String templateIdentity, int templateCount,
            int openingHandSize, List<String> libraryOrder, int templateRemainder,
            Integer handTemplateCount, int declaredDraws) {
        boolean declaresLibrary() {
            return libraryOrder != null;
        }
    }

    /** The outcome of one successful application: performed checks by kind. */
    static final class Result {
        final Map<String, Integer> checksByKind;
        /** Engine draws this turn, per materialized player, read at the seam before mutation. */
        final Map<String, Integer> drawsAtSeam;
        /** The engine phase and turn at the seam. */
        final PhaseType phaseAtSeam;
        final int turnAtSeam;

        Result(Map<String, Integer> checksByKind, Map<String, Integer> drawsAtSeam,
                PhaseType phaseAtSeam, int turnAtSeam) {
            this.checksByKind = Collections.unmodifiableMap(new LinkedHashMap<>(checksByKind));
            this.drawsAtSeam = Collections.unmodifiableMap(new LinkedHashMap<>(drawsAtSeam));
            this.phaseAtSeam = phaseAtSeam;
            this.turnAtSeam = turnAtSeam;
        }
    }

    private final List<PlayerDeck> decks;
    private final List<LibraryObject> libraryObjects;
    private final List<HandObject> handObjects;

    private CheckpointMaterialization(List<PlayerDeck> decks, List<LibraryObject> libraryObjects,
            List<HandObject> handObjects) {
        this.decks = List.copyOf(decks);
        this.libraryObjects = List.copyOf(libraryObjects);
        this.handObjects = List.copyOf(handObjects);
    }

    List<PlayerDeck> decks() {
        return decks;
    }

    List<LibraryObject> libraryObjects() {
        return libraryObjects;
    }

    List<HandObject> handObjects() {
        return handObjects;
    }

    /** Whether the plan declares a complete checkpoint library for {@code playerId}. */
    boolean declaresLibrary(String playerId) {
        for (PlayerDeck deck : decks) {
            if (deck.playerId().equals(playerId) && deck.declaresLibrary()) {
                return true;
            }
        }
        return false;
    }

    /** Whether the plan declares the checkpoint hand of {@code playerId}. */
    boolean declaresHand(String playerId) {
        for (PlayerDeck deck : decks) {
            if (deck.playerId().equals(playerId) && deck.handTemplateCount() != null) {
                return true;
            }
        }
        return false;
    }

    private static Rejected fail(String code, String subject) {
        return new Rejected(code, subject);
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()) {
            return null;
        }
        final String value = object.get(key).getAsString();
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private static int nonNegativeInt(JsonObject object, String key, String subject) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isNumber()) {
            throw fail("INVALID_DECK_STATE", subject + " requires integer " + key);
        }
        final double raw = object.get(key).getAsDouble();
        if (raw < 0 || raw != Math.rint(raw) || raw > Integer.MAX_VALUE) {
            throw fail("INVALID_DECK_STATE", subject + " " + key + " must be a non-negative integer");
        }
        return (int) raw;
    }

    /** The bridge's principal id for a record player label ({@code P1} or {@code p1}). */
    static String principalId(String label) {
        if (label == null) {
            return null;
        }
        final String lower = label.trim().toLowerCase(Locale.ROOT);
        return lower.matches("p[1-9]") ? lower : null;
    }

    /**
     * Parses a {@code checkpoint_materialization} request object. A request
     * without {@code deck_state} entries is refused: an empty declaration
     * would make the checkpoint vacuous.
     */
    static CheckpointMaterialization parse(JsonObject request) {
        if (request == null) {
            throw fail("INVALID_DECK_STATE", "checkpoint_materialization must be an object");
        }
        if (!request.has("deck_state") || !request.get("deck_state").isJsonArray()
                || request.getAsJsonArray("deck_state").isEmpty()) {
            throw fail("INVALID_DECK_STATE", "deck_state must be a non-empty array");
        }
        if (!request.has("semantic_objects") || !request.get("semantic_objects").isJsonArray()) {
            throw fail("INVALID_DECK_STATE", "semantic_objects must be an array");
        }
        final Map<String, JsonObject> objectsById = new LinkedHashMap<>();
        for (JsonElement element : request.getAsJsonArray("semantic_objects")) {
            if (!element.isJsonObject()) {
                throw fail("INVALID_DECK_STATE", "semantic object must be an object");
            }
            final JsonObject object = element.getAsJsonObject();
            final String semanticId = text(object, "semantic_id");
            if (semanticId == null) {
                throw fail("INVALID_DECK_STATE", "semantic object requires semantic_id");
            }
            if (objectsById.put(semanticId, object) != null) {
                throw fail("DUPLICATE_SEMANTIC_OBJECT", semanticId);
            }
        }

        final List<PlayerDeck> decks = new ArrayList<>();
        final List<LibraryObject> libraryObjects = new ArrayList<>();
        final Set<String> covered = new HashSet<>();
        final Set<String> seenPlayers = new HashSet<>();
        for (JsonElement element : request.getAsJsonArray("deck_state")) {
            if (!element.isJsonObject()) {
                throw fail("INVALID_DECK_STATE", "deck_state entry must be an object");
            }
            final JsonObject deck = element.getAsJsonObject();
            final String label = text(deck, "player_id");
            final String playerId = principalId(label);
            if (playerId == null || !seenPlayers.add(playerId)) {
                throw fail("INVALID_DECK_STATE", "missing, malformed or duplicate player_id");
            }
            final JsonObject template = deck.has("library_template")
                    && deck.get("library_template").isJsonObject()
                    ? deck.getAsJsonObject("library_template") : null;
            final String templateIdentity = text(template, "card_identity");
            if (templateIdentity == null) {
                throw fail("INVALID_DECK_STATE", playerId + " requires library_template.card_identity");
            }
            final int templateCount = nonNegativeInt(template, "count", playerId + " library_template");

            List<String> libraryOrder = null;
            int templateRemainder = 0;
            if (deck.has("checkpoint_library") && !deck.get("checkpoint_library").isJsonNull()) {
                if (!deck.get("checkpoint_library").isJsonObject()) {
                    throw fail("INVALID_DECK_STATE", playerId + " checkpoint_library must be an object");
                }
                final JsonObject library = deck.getAsJsonObject("checkpoint_library");
                if (!LIBRARY_COMPLETENESS.equals(text(library, "completeness"))) {
                    throw fail("PARTIAL_LIBRARY_REQUEST", playerId);
                }
                if (!library.has("runs") || !library.get("runs").isJsonArray()
                        || library.getAsJsonArray("runs").isEmpty()) {
                    throw fail("INVALID_DECK_STATE", playerId + " checkpoint_library requires runs");
                }
                libraryOrder = new ArrayList<>();
                for (JsonElement runElement : library.getAsJsonArray("runs")) {
                    if (!runElement.isJsonObject()) {
                        throw fail("INVALID_DECK_STATE", playerId + " library run must be an object");
                    }
                    final JsonObject run = runElement.getAsJsonObject();
                    final String semanticId = text(run, "semantic_id");
                    if (semanticId != null) {
                        if (run.size() != 1) {
                            throw fail("INVALID_DECK_STATE",
                                    playerId + " an object run names only its semantic_id");
                        }
                        final JsonObject object = objectsById.get(semanticId);
                        if (object == null || !"library".equals(text(object, "zone"))) {
                            throw fail("UNKNOWN_LIBRARY_OBJECT", semanticId);
                        }
                        if (!playerId.equals(principalId(text(object, "owner")))) {
                            throw fail("LIBRARY_OBJECT_OWNER_MISMATCH", semanticId);
                        }
                        if (!covered.add(semanticId)) {
                            throw fail("DUPLICATE_LIBRARY_OBJECT", semanticId);
                        }
                        final int position = libraryOrder.size();
                        if (!object.has("zone_position") || !object.get("zone_position").isJsonPrimitive()
                                || !object.getAsJsonPrimitive("zone_position").isNumber()
                                || object.get("zone_position").getAsDouble() != position) {
                            throw fail("LIBRARY_POSITION_MISMATCH", semanticId);
                        }
                        final String identity = text(object, "card_identity");
                        if (identity == null) {
                            throw fail("INVALID_CARD_IDENTITY", semanticId);
                        }
                        libraryObjects.add(new LibraryObject(semanticId, identity, playerId, position));
                        libraryOrder.add(identity);
                        continue;
                    }
                    final String identity = text(run, "card_identity");
                    if (identity == null || run.size() != 2) {
                        throw fail("INVALID_DECK_STATE",
                                playerId + " a template run names card_identity and count only");
                    }
                    if (!identity.equals(templateIdentity)) {
                        throw fail("LIBRARY_RUN_NOT_TEMPLATE", playerId);
                    }
                    final int count = nonNegativeInt(run, "count", playerId + " library run");
                    if (count == 0) {
                        throw fail("INVALID_DECK_STATE", playerId + " library run count must be positive");
                    }
                    for (int index = 0; index < count; index++) {
                        libraryOrder.add(identity);
                    }
                    templateRemainder += count;
                }
            }

            Integer handTemplateCount = null;
            int openingHandSize = -1;
            int declaredDraws = 0;
            if (deck.has("checkpoint_hand") && !deck.get("checkpoint_hand").isJsonNull()) {
                if (!deck.get("checkpoint_hand").isJsonObject()) {
                    throw fail("INVALID_DECK_STATE", playerId + " checkpoint_hand must be an object");
                }
                final JsonObject hand = deck.getAsJsonObject("checkpoint_hand");
                if (!HAND_COMPLETENESS.equals(text(hand, "completeness"))) {
                    throw fail("INVALID_DECK_STATE", playerId + " checkpoint_hand is not COMPLETE");
                }
                if (!templateIdentity.equals(text(hand, "template_card_identity"))) {
                    throw fail("HAND_TEMPLATE_MISMATCH", playerId);
                }
                handTemplateCount = nonNegativeInt(hand, "template_count", playerId + " checkpoint_hand");
                openingHandSize = nonNegativeInt(deck, "opening_hand_size", playerId);
                declaredDraws = handTemplateCount - openingHandSize;
                // C7: the template hand is the engine's own opening hand plus its
                // draws this turn. Anything else would need a scripted hand, which
                // falls under the G2 exact_hand_after_draw gate.
                if (declaredDraws < 0 || declaredDraws > 1) {
                    throw fail("EXACT_HAND_AFTER_DRAW_UNSUPPORTED", playerId);
                }
            }
            if (libraryOrder != null) {
                if (handTemplateCount == null) {
                    throw fail("HAND_DECLARATION_REQUIRED", playerId);
                }
                // C1: the checkpoint follows exactly one draw by this player.
                if (declaredDraws != 1) {
                    throw fail("EXACT_HAND_AFTER_DRAW_UNSUPPORTED", playerId);
                }
                // The template remainder is the engine's own post-draw library:
                // the template deck less the template hand.
                if (templateCount - handTemplateCount != templateRemainder) {
                    throw fail("LIBRARY_TEMPLATE_MISMATCH", playerId);
                }
            }
            decks.add(new PlayerDeck(playerId, templateIdentity, templateCount, openingHandSize,
                    libraryOrder == null ? null : List.copyOf(libraryOrder), templateRemainder,
                    handTemplateCount, declaredDraws));
        }

        final List<HandObject> handObjects = new ArrayList<>();
        for (Map.Entry<String, JsonObject> entry : objectsById.entrySet()) {
            final String zone = text(entry.getValue(), "zone");
            if ("library".equals(zone) && !covered.contains(entry.getKey())) {
                // A declared library object the runs leave out would be an
                // undeclared position: refused, never placed anywhere.
                throw fail("PARTIAL_LIBRARY_REQUEST", entry.getKey());
            }
            if ("hand".equals(zone)) {
                final String owner = principalId(text(entry.getValue(), "owner"));
                boolean declared = false;
                for (PlayerDeck deck : decks) {
                    if (deck.playerId().equals(owner) && deck.handTemplateCount() != null) {
                        declared = true;
                    }
                }
                if (!declared) {
                    throw fail("PARTIAL_HAND_REQUEST", entry.getKey());
                }
                final String identity = text(entry.getValue(), "card_identity");
                if (identity == null) {
                    throw fail("INVALID_CARD_IDENTITY", entry.getKey());
                }
                handObjects.add(new HandObject(entry.getKey(), identity, owner));
            }
        }
        return new CheckpointMaterialization(decks, libraryObjects, handObjects);
    }

    /**
     * Creation-time validation against the registered roster, the card
     * database, the imported template decks and the scenario plan: every
     * declared player is seated, every object identity resolves, each declared
     * template is exactly that seat's imported main deck, and no declared hand
     * is also scripted by the scenario (C7).
     */
    void validate(BridgeSession session, Map<String, List<String>> mainboardsByPlayer,
            ScenarioBootstrap.Plan scenario) {
        for (PlayerDeck deck : decks) {
            if (session.playerById(deck.playerId()) == null) {
                throw fail("UNKNOWN_DECK_PLAYER", deck.playerId());
            }
            final List<String> mainboard = mainboardsByPlayer.get(deck.playerId());
            if (mainboard == null || mainboard.size() != deck.templateCount()
                    || !mainboard.stream().allMatch(deck.templateIdentity()::equals)) {
                throw fail("LIBRARY_TEMPLATE_MISMATCH", deck.playerId());
            }
            if (deck.handTemplateCount() != null && scenario != null
                    && scenario.hands.containsKey(deck.playerId())) {
                throw fail("HAND_SCRIPT_CONFLICT", deck.playerId());
            }
        }
        for (LibraryObject object : libraryObjects) {
            resolve(object.cardIdentity(), object.semanticId());
        }
        for (HandObject object : handObjects) {
            resolve(object.cardIdentity(), object.semanticId());
        }
    }

    private static PaperCard resolve(String identity, String semanticId) {
        final PaperCard paper;
        try {
            paper = StaticData.instance().getCommonCards().getCard(identity);
        } catch (Throwable t) {
            throw fail("INVALID_CARD_IDENTITY", semanticId);
        }
        if (paper == null) {
            throw fail("INVALID_CARD_IDENTITY", semanticId);
        }
        return paper;
    }

    private static Card create(Player owner, String identity, String semanticId) {
        try {
            return Card.fromPaperCard(resolve(identity, semanticId), owner);
        } catch (Rejected e) {
            throw e;
        } catch (Throwable t) {
            throw fail("OBJECT_CREATION_FAILED", semanticId);
        }
    }

    private static boolean allNamed(List<Card> cards, String identity) {
        for (Card card : cards) {
            if (!identity.equals(card.getName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Applies the plan at the C1 seam on the game thread. Checks every
     * precondition for every declared player before any mutation, then
     * materializes and verifies. {@code fault} is a test-only stimulus
     * ({@code null} in production).
     */
    Result apply(BridgeSession session, Game game, String fault) {
        if (game == null) {
            throw fail("CHECKPOINT_SEAM_MISMATCH", null);
        }
        final int turn = game.getPhaseHandler().getTurn();
        final PhaseType phase = game.getPhaseHandler().getPhase();
        if (turn != 1 || phase != PhaseType.MAIN1) {
            throw fail("CHECKPOINT_SEAM_MISMATCH", null);
        }
        final Player active = game.getPhaseHandler().getPlayerTurn();

        // ---- preconditions (no mutation) ----
        final Map<String, List<Card>> engineLibraries = new LinkedHashMap<>();
        final Map<String, List<Card>> engineHands = new LinkedHashMap<>();
        final Map<String, Integer> drawsAtSeam = new LinkedHashMap<>();
        for (PlayerDeck deck : decks) {
            final Player player = session.playerById(deck.playerId());
            if (player == null) {
                throw fail("UNKNOWN_DECK_PLAYER", deck.playerId());
            }
            if (deck.handTemplateCount() != null
                    && player.getNumDrawnThisTurn() != deck.declaredDraws()) {
                throw fail("DRAW_COUNT_MISMATCH", deck.playerId());
            }
            if (deck.declaresLibrary()) {
                final List<Card> library = new ArrayList<>(player.getCardsIn(ZoneType.Library));
                if (library.size() != deck.templateRemainder()) {
                    throw fail("LIBRARY_COUNT_MISMATCH", deck.playerId());
                }
                if (!allNamed(library, deck.templateIdentity())) {
                    throw fail("LIBRARY_TEMPLATE_MISMATCH", deck.playerId());
                }
                if (active == null || active != player) {
                    throw fail("CHECKPOINT_NOT_ACTIVE_PLAYER", deck.playerId());
                }
                engineLibraries.put(deck.playerId(), library);
                drawsAtSeam.put(deck.playerId(), player.getNumDrawnThisTurn());
            }
            if (deck.handTemplateCount() != null) {
                final List<Card> hand = new ArrayList<>(player.getCardsIn(ZoneType.Hand));
                if (hand.size() != deck.handTemplateCount() || !allNamed(hand, deck.templateIdentity())) {
                    throw fail("HAND_TEMPLATE_MISMATCH", deck.playerId());
                }
                engineHands.put(deck.playerId(), hand);
            }
        }

        // ---- materialization: create, place, order with native setCards ----
        final Map<String, Card> created = new LinkedHashMap<>();
        for (PlayerDeck deck : decks) {
            if (!deck.declaresLibrary()) {
                continue;
            }
            final Player owner = session.playerById(deck.playerId());
            final Card[] order = new Card[deck.libraryOrder().size()];
            for (LibraryObject object : libraryObjects) {
                if (object.owner().equals(deck.playerId())) {
                    final Card card = create(owner, object.cardIdentity(), object.semanticId());
                    created.put(object.semanticId(), card);
                    order[object.position()] = card;
                }
            }
            int engineIndex = 0;
            final List<Card> own = engineLibraries.get(deck.playerId());
            for (int position = 0; position < order.length; position++) {
                if (order[position] == null) {
                    order[position] = own.get(engineIndex++);
                }
            }
            final List<Card> ordered = new ArrayList<>(List.of(order));
            if ("swap_positions".equals(fault) && libraryObjects.size() >= 2) {
                final int a = libraryObjects.get(0).position();
                final int b = libraryObjects.get(1).position();
                Collections.swap(ordered, a, b);
            }
            if ("extra_object".equals(fault)) {
                ordered.add(create(owner, deck.templateIdentity(), "fault-extra"));
            }
            if ("drop_object".equals(fault) && !libraryObjects.isEmpty()) {
                ordered.remove(libraryObjects.get(0).position());
            }
            owner.getZone(ZoneType.Library).setCards(ordered);
        }
        for (PlayerDeck deck : decks) {
            if (deck.handTemplateCount() == null) {
                continue;
            }
            final Player owner = session.playerById(deck.playerId());
            final List<Card> hand = new ArrayList<>(engineHands.get(deck.playerId()));
            boolean added = false;
            for (HandObject object : handObjects) {
                if (object.owner().equals(deck.playerId())) {
                    final Card card = create(owner, object.cardIdentity(), object.semanticId());
                    created.put(object.semanticId(), card);
                    hand.add(card);
                    added = true;
                }
            }
            if (added) {
                owner.getZone(ZoneType.Hand).setCards(hand);
            }
        }

        // ---- verification (C6): coded, engine-direct ----
        return new Result(verify(session, engineLibraries, engineHands, created), drawsAtSeam,
                phase, turn);
    }

    private Map<String, Integer> verify(BridgeSession session, Map<String, List<Card>> engineLibraries,
            Map<String, List<Card>> engineHands, Map<String, Card> created) {
        final Map<String, Integer> checks = new LinkedHashMap<>();
        // Every declared library object at its exact position, in the library.
        for (LibraryObject object : libraryObjects) {
            checks.merge("library_object", 1, Integer::sum);
            final Player owner = session.playerById(object.owner());
            final Zone library = owner.getZone(ZoneType.Library);
            final Card card = created.get(object.semanticId());
            final List<Card> live = new ArrayList<>(owner.getCardsIn(ZoneType.Library));
            if (card == null || !library.contains(card) || card.getZone() != library) {
                throw fail("LIBRARY_OBJECT_MISSING", object.semanticId());
            }
            if (object.position() >= live.size() || live.get(object.position()) != card) {
                throw fail("LIBRARY_POSITION_MISMATCH", object.semanticId());
            }
        }
        for (PlayerDeck deck : decks) {
            if (!deck.declaresLibrary()) {
                continue;
            }
            final Player owner = session.playerById(deck.playerId());
            final List<Card> live = new ArrayList<>(owner.getCardsIn(ZoneType.Library));
            // Membership: apart from the declared objects, exactly the engine's
            // own pre-checkpoint cards, each once.
            checks.merge("library_membership", 1, Integer::sum);
            final Set<Card> declared = Collections.newSetFromMap(new IdentityHashMap<>());
            for (LibraryObject object : libraryObjects) {
                if (object.owner().equals(deck.playerId())) {
                    declared.add(created.get(object.semanticId()));
                }
            }
            final Set<Card> own = Collections.newSetFromMap(new IdentityHashMap<>());
            own.addAll(engineLibraries.get(deck.playerId()));
            final Set<Card> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Card card : live) {
                if (!seen.add(card)) {
                    throw fail("LIBRARY_DUPLICATE_OBJECT", deck.playerId());
                }
                if (!declared.contains(card) && !own.contains(card)) {
                    throw fail("LIBRARY_UNDECLARED_OBJECT", deck.playerId());
                }
            }
            for (Card card : own) {
                if (!seen.contains(card)) {
                    throw fail("LIBRARY_ENGINE_CARD_MISSING", deck.playerId());
                }
            }
            // Identity order, top to bottom, exactly the declared runs.
            checks.merge("library_order", 1, Integer::sum);
            if (live.size() != deck.libraryOrder().size()) {
                throw fail("LIBRARY_ORDER_MISMATCH", deck.playerId());
            }
            for (int index = 0; index < live.size(); index++) {
                if (!deck.libraryOrder().get(index).equals(live.get(index).getName())) {
                    throw fail("LIBRARY_ORDER_MISMATCH", deck.playerId());
                }
            }
        }
        for (HandObject object : handObjects) {
            checks.merge("hand_object", 1, Integer::sum);
            final Player owner = session.playerById(object.owner());
            final Card card = created.get(object.semanticId());
            final Zone hand = owner.getZone(ZoneType.Hand);
            if (card == null || !hand.contains(card) || card.getZone() != hand) {
                throw fail("HAND_OBJECT_MISSING", object.semanticId());
            }
        }
        for (PlayerDeck deck : decks) {
            if (deck.handTemplateCount() == null) {
                continue;
            }
            checks.merge("hand_composition", 1, Integer::sum);
            final Player owner = session.playerById(deck.playerId());
            final Set<Card> allowed = Collections.newSetFromMap(new IdentityHashMap<>());
            allowed.addAll(engineHands.get(deck.playerId()));
            int objects = 0;
            for (HandObject object : handObjects) {
                if (object.owner().equals(deck.playerId())) {
                    allowed.add(created.get(object.semanticId()));
                    objects++;
                }
            }
            final List<Card> live = new ArrayList<>(owner.getCardsIn(ZoneType.Hand));
            if (live.size() != deck.handTemplateCount() + objects) {
                throw fail("HAND_COMPOSITION_MISMATCH", deck.playerId());
            }
            for (Card card : live) {
                if (!allowed.contains(card)) {
                    throw fail("HAND_COMPOSITION_MISMATCH", deck.playerId());
                }
            }
        }
        return checks;
    }
}
