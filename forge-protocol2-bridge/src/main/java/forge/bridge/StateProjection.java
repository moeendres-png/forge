package forge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameOutcome;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Principal-scoped projection of authoritative Forge state into Lab GameState JSON.
 *
 * <p>Projection happens bridge-side from live engine objects. Only information the
 * observer principal may legally see is emitted: opponent hands become
 * {@code "<hidden>"} placeholders (count is public), libraries are always empty
 * (order hidden), and face-down cards stay {@code "<face-down>"} unless
 * {@link CardView#canBeShownTo(PlayerView)} grants the observer a look. A null
 * observer receives the public-only view (all hands redacted).
 */
public final class StateProjection {
    private StateProjection() { }

    /** Package-private test seam: forces every required read to fail (R4 regression). */
    static volatile boolean failRequiredReadsForTests;

    /** R10 seams: fail one authoritative reader family at a time. Test-only. */
    static volatile boolean failManaPoolForTests;
    static volatile boolean failCommanderDamageForTests;
    static volatile boolean failCommanderCastsForTests;

    private interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    /**
     * Required-field reader: any failure (or an injected test fault) aborts the whole
     * projection with {@link BridgeProjectionException}. No plausible defaults.
     */
    private static <T> T require(String field, ThrowingSupplier<T> reader) {
        if (failRequiredReadsForTests) {
            throw new BridgeProjectionException(field, "injected test fault");
        }
        try {
            return reader.get();
        } catch (BridgeProjectionException e) {
            throw e;
        } catch (Throwable t) {
            throw new BridgeProjectionException(field, t);
        }
    }

    public static JsonObject gameState(BridgeSession session, String observerPlayerId) {
        final Game game = session.getGame();
        if (game == null) {
            throw new BridgeProjectionException("game", "no game object");
        }
        final JsonObject state = new JsonObject();
        state.addProperty("game_id", session.getGameId());
        state.add("seed", JsonNull.INSTANCE);
        state.add("rng_counter", JsonNull.INSTANCE);
        state.addProperty("status", statusOf(session));
        final PhaseHandler phases = require("phase_handler", () -> game.getPhaseHandler());
        final int turn = require("turn_number", () -> Math.max(0, phases.getTurn()));
        final String activeId =
                require("active_player", () -> idOrNull(session, phases.getPlayerTurn()));
        final String priorityId =
                require("priority_player", () -> idOrNull(session, phases.getPriorityPlayer()));
        final PhaseType phase = require("phase", () -> phases.getPhase());
        final String phaseName;
        final String step;
        if (phase == null) {
            // Legitimate pre-first-turn engine state (no phase has begun yet): documented
            // structural mapping, applied only when the read itself succeeded.
            phaseName = "beginning";
            step = null;
        } else {
            phaseName = mapPhase(phase);
            step = phase.name();
        }
        state.addProperty("turn_number", turn);
        if (activeId == null) {
            state.add("active_player_id", JsonNull.INSTANCE);
        } else {
            state.addProperty("active_player_id", activeId);
        }
        if (priorityId == null) {
            state.add("priority_player_id", JsonNull.INSTANCE);
        } else {
            state.addProperty("priority_player_id", priorityId);
        }
        state.addProperty("phase", phaseName);
        if (step == null) {
            state.add("step", JsonNull.INSTANCE);
        } else {
            state.addProperty("step", step);
        }
        final Player observer = observerPlayerId == null ? null : session.playerById(observerPlayerId);
        final PlayerView observerView = observer == null ? null : observer.getView();
        final List<Player> enginePlayers =
                require("players", () -> session.registryPlayers());
        final JsonArray players = new JsonArray();
        for (Player player : enginePlayers) {
            players.add(playerState(session, player, observer, observerView));
        }
        state.add("players", players);
        state.add("stack", stackState(game, observerView));
        final DecisionFrame frame = session.getCurrentFrame();
        final boolean actorScoped = observer != null && frame != null
                && observerPlayerId.equals(frame.actorPlayerId);
        state.add("legal_actions", actorScoped ? legalActions(session) : new JsonArray());
        state.add("winner_ids", winnersState(session, game));
        state.addProperty("event_sequence", (int) Math.min(Integer.MAX_VALUE, session.auditSize()));
        return state;
    }

    /**
     * R9 principal-scoped bridge metadata. Frame-bound fields (revision, pending
     * decision incl. kind/status/actor/count/reason, state hash) are exposed ONLY to
     * the frame's actor; anyone else receives null/-1. Session diagnostics that name
     * only public roster structure (fail reasons name callbacks and seat ids, never
     * card data) remain visible.
     */
    public static JsonObject bridgeMeta(BridgeSession session, String observerPlayerId) {
        final JsonObject meta = new JsonObject();
        meta.addProperty("session_status", session.getStatus().name());
        final DecisionFrame frame = session.getCurrentFrame();
        final boolean actorScoped = observerPlayerId != null && frame != null
                && observerPlayerId.equals(frame.actorPlayerId);
        if (actorScoped) {
            meta.addProperty("revision", frame.revision);
            meta.add("pending_decision", decisionSummary(frame));
        } else {
            meta.addProperty("revision", -1);
            meta.add("pending_decision", JsonNull.INSTANCE);
        }
        if (actorScoped) {
            try {
                meta.addProperty("state_hash", StateHash.ofGame(session.getGame(), session));
            } catch (Throwable t) {
                meta.addProperty("state_hash", "unavailable");
            }
        } else {
            // The digest covers private zones; only the actor may hold it.
            meta.add("state_hash", JsonNull.INSTANCE);
        }
        if (!session.getFailReason().isEmpty()) {
            meta.addProperty("fail_reason", session.getFailReason());
        }
        if (!session.getLastExecutionError().isEmpty()) {
            meta.addProperty("last_execution_error", session.getLastExecutionError());
        }
        return meta;
    }

    /** Backwards-compatible entry point: public observer, fully redacted metadata. */
    public static JsonObject bridgeMeta(BridgeSession session) {
        return bridgeMeta(session, null);
    }

    public static JsonObject decisionSummary(DecisionFrame frame) {
        final JsonObject summary = new JsonObject();
        summary.addProperty("revision", frame.revision);
        summary.addProperty("kind", frame.kind.name());
        summary.addProperty("status", frame.status.name());
        summary.addProperty("actor", frame.actorPlayerId);
        summary.addProperty("options", frame.options.size());
        if (!frame.reason.isEmpty()) {
            summary.addProperty("reason", frame.reason);
        }
        return summary;
    }

    public static JsonArray legalActions(BridgeSession session) {
        final JsonArray actions = new JsonArray();
        final DecisionFrame frame = session.getCurrentFrame();
        if (frame == null || frame.status != DecisionFrame.Status.SUPPORTED) {
            return actions;
        }
        for (DecisionFrame.Option option : frame.options) {
            if (option.isConsumed()) {
                continue;
            }
            actions.add(legalAction(frame, option));
        }
        return actions;
    }

    public static JsonObject legalAction(DecisionFrame frame, DecisionFrame.Option option) {
        final JsonObject action = new JsonObject();
        action.addProperty("action_id", option.optionId);
        action.addProperty("actor_id", frame.actorPlayerId);
        action.addProperty("action_type", option.actionType);
        if (option.sourceCardName == null) {
            action.add("source_object_id", JsonNull.INSTANCE);
        } else {
            action.addProperty("source_object_id", option.sourceCardName);
        }
        action.add("target_ids", new JsonArray());
        action.add("allowed_target_ids", new JsonArray());
        action.add("modes", new JsonArray());
        action.add("choices_schema", new JsonObject());
        action.add("cost", new JsonObject());
        final JsonObject metadata = new JsonObject();
        metadata.addProperty("revision", frame.revision);
        metadata.addProperty("frame_kind", frame.kind.name());
        metadata.addProperty("label", option.label);
        action.add("metadata", metadata);
        return action;
    }

    private static JsonObject playerState(BridgeSession session, Player player, Player observer,
            PlayerView observerView) {
        final JsonObject state = new JsonObject();
        state.addProperty("player_id", session.playerIdOf(player));
        state.addProperty("seat", session.seatOf(player));
        final int life = require("life:" + state.get("player_id").getAsString(), () -> player.getLife());
        final int poison = require("poison:" + state.get("player_id").getAsString(),
                () -> Math.max(0, player.getPoisonCounters()));
        state.addProperty("life", life);
        state.addProperty("poison_counters", poison);
        state.add("commander_damage_received", commanderDamage(session, player));
        state.add("commander_cast_count", commanderCasts(session, player));
        state.add("mana_pool", manaPool(player));
        state.add("zones", zones(player, observer, observerView));
        final int landRemaining = require("land_plays:" + state.get("player_id").getAsString(),
                () -> Math.max(0, player.getMaxLandPlays() - player.getLandsPlayedThisTurn()));
        state.addProperty("land_plays_remaining", landRemaining);
        final boolean lost =
                require("has_lost:" + state.get("player_id").getAsString(), () -> player.hasLost());
        state.addProperty("has_lost", lost);
        String lossReason = null;
        try {
            if (lost && player.getOutcome() != null && player.getOutcome().lossState != null) {
                lossReason = player.getOutcome().lossState.name();
            }
        } catch (Throwable t) {
            lossReason = null;
        }
        if (lossReason == null) {
            state.add("loss_reason", JsonNull.INSTANCE);
        } else {
            state.addProperty("loss_reason", lossReason);
        }
        return state;
    }

    /**
     * R10: commander damage is an advertised visible field, so its read is required.
     * A legitimately empty map (no damage dealt) still succeeds; only read failure
     * throws. Never a plausible {} on exception.
     */
    private static JsonObject commanderDamage(BridgeSession session, Player player) {
        return require("commander_damage", () -> {
            if (failCommanderDamageForTests) {
                throw new BridgeProjectionException("commander_damage", "injected test fault");
            }
            final JsonObject damage = new JsonObject();
            for (Map.Entry<Card, Integer> entry : player.getCommanderDamage()) {
                damage.addProperty(entry.getKey().getName(), entry.getValue());
            }
            return damage;
        });
    }

    /**
     * R10: commander cast counts feed the advertised tax visibility; the read is
     * required and all-or-nothing. Never a plausible {} on exception.
     */
    private static JsonObject commanderCasts(BridgeSession session, Player player) {
        return require("commander_casts", () -> {
            if (failCommanderCastsForTests) {
                throw new BridgeProjectionException("commander_casts", "injected test fault");
            }
            final JsonObject casts = new JsonObject();
            final List<String> names = session.commanderNames(session.seatOf(player));
            final Map<String, Card> byName = new LinkedHashMap<>();
            for (ZoneType zone : new ZoneType[] { ZoneType.Command, ZoneType.Battlefield,
                    ZoneType.Graveyard, ZoneType.Exile, ZoneType.Hand, ZoneType.Library }) {
                for (Card card : player.getCardsIn(zone)) {
                    byName.putIfAbsent(card.getName(), card);
                }
            }
            for (String name : names) {
                final Card card = byName.get(name);
                casts.addProperty(name, card == null ? 0 : Math.max(0, player.getCommanderCast(card)));
            }
            return casts;
        });
    }

    /**
     * R10: the mana pool is authoritative engine state; its read is required.
     * A legitimately empty pool still succeeds. Never a plausible {} on exception.
     */
    private static JsonObject manaPool(Player player) {
        return require("mana_pool", () -> {
            if (failManaPoolForTests) {
                throw new BridgeProjectionException("mana_pool", "injected test fault");
            }
            final JsonObject pool = new JsonObject();
            final PlayerView view = player.getView();
            pool.addProperty("W", Math.max(0, view.getMana(MagicColor.WHITE)));
            pool.addProperty("U", Math.max(0, view.getMana(MagicColor.BLUE)));
            pool.addProperty("B", Math.max(0, view.getMana(MagicColor.BLACK)));
            pool.addProperty("R", Math.max(0, view.getMana(MagicColor.RED)));
            pool.addProperty("G", Math.max(0, view.getMana(MagicColor.GREEN)));
            pool.addProperty("C", Math.max(0, view.getMana(MagicColor.COLORLESS)));
            return pool;
        });
    }

    private static JsonObject zones(Player player, Player observer, PlayerView observerView) {
        final JsonObject zones = new JsonObject();
        zones.add("library", new JsonArray());
        zones.add("hand", require("zones.hand", () -> handZone(player, observer)));
        zones.add("battlefield", require("zones.battlefield", () -> battlefieldZone(player, observerView)));
        zones.add("graveyard", require("zones.graveyard", () -> namesZone(player, ZoneType.Graveyard)));
        zones.add("exile", require("zones.exile", () -> exileZone(player, observerView)));
        zones.add("command", require("zones.command", () -> namesZone(player, ZoneType.Command)));
        return zones;
    }

    private static JsonArray handZone(Player player, Player observer) {
        final JsonArray hand = new JsonArray();
        final List<String> names = zoneNames(player, ZoneType.Hand);
        if (observer != null && observer.equals(player)) {
            for (String name : names) {
                hand.add(name);
            }
        } else {
            for (int i = 0; i < names.size(); i++) {
                hand.add("<hidden>");
            }
        }
        return hand;
    }

    private static JsonArray battlefieldZone(Player player, PlayerView observerView) {
        final JsonArray zone = new JsonArray();
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            zone.add(shownName(card, observerView));
        }
        return zone;
    }

    private static JsonArray exileZone(Player player, PlayerView observerView) {
        final JsonArray zone = new JsonArray();
        for (Card card : player.getCardsIn(ZoneType.Exile)) {
            zone.add(shownName(card, observerView));
        }
        return zone;
    }

    /**
     * R3 literal visibility: Forge's view authorization decides, using both native
     * gates exactly as the engine defines them — {@code canBeShownTo} for the zone
     * gate and {@code canFaceDownBeShownTo} for the face gate (battlefield cards are
     * zone-visible to all, but face-down identity additionally requires the face
     * gate, e.g. controller or an explicit may-look grant). Anything not shown to
     * the observer is a redacted marker, never a name.
     */
    private static String shownName(Card card, PlayerView observerView) {
        final boolean faceDown;
        try {
            faceDown = card.isFaceDown();
        } catch (Throwable t) {
            throw new BridgeProjectionException("card.face_down", t);
        }
        if (observerView == null) {
            if (faceDown) {
                return "<face-down>";
            }
            try {
                return card.getName();
            } catch (Throwable t) {
                throw new BridgeProjectionException("card.name", t);
            }
        }
        final CardView view;
        try {
            view = card.getView();
        } catch (Throwable t) {
            throw new BridgeProjectionException("card.view", t);
        }
        final boolean shown;
        try {
            shown = view != null && view.canBeShownTo(observerView)
                    && view.canFaceDownBeShownTo(observerView);
        } catch (Throwable t) {
            throw new BridgeProjectionException("card.visibility", t);
        }
        if (!shown) {
            return faceDown ? "<face-down>" : "<hidden>";
        }
        if (!faceDown) {
            try {
                return card.getName();
            } catch (Throwable t) {
                throw new BridgeProjectionException("card.name", t);
            }
        }
        // Face-down but shown to this observer (controller or explicit may-look grant):
        // the observer is entitled to the true identity. The engine's current-state
        // name is blank while face-down, so use the native alternate (true) state,
        // falling back to the immutable paper identity. Never reached for unauthorized
        // observers (they received a marker above).
        try {
            final CardView.CardStateView alternate = view.getAlternateState();
            if (alternate != null && alternate.getName() != null
                    && !alternate.getName().isEmpty()) {
                return alternate.getName();
            }
            if (card.getPaperCard() != null && card.getPaperCard().getName() != null) {
                return card.getPaperCard().getName();
            }
        } catch (Throwable t) {
            throw new BridgeProjectionException("card.truename", t);
        }
        throw new BridgeProjectionException("card.truename", "no true identity available");
    }

    private static JsonArray namesZone(Player player, ZoneType zone) {
        final JsonArray result = new JsonArray();
        for (String name : zoneNames(player, zone)) {
            result.add(name);
        }
        return result;
    }

    private static List<String> zoneNames(Player player, ZoneType zone) {
        final List<String> names = new ArrayList<>();
        for (Card card : player.getCardsIn(zone)) {
            try {
                names.add(card.getName());
            } catch (Throwable t) {
                throw new BridgeProjectionException("zone." + zone.name(), t);
            }
        }
        return names;
    }

    private static JsonArray stackState(Game game, PlayerView observerView) {
        return require("stack", () -> {
            final JsonArray stack = new JsonArray();
            for (SpellAbilityStackInstance si : game.getStack()) {
                stack.add(stackText(si, observerView));
            }
            return stack;
        });
    }

    private static JsonArray winnersState(BridgeSession session, Game game) {
        return require("winners", () -> {
            final JsonArray winners = new JsonArray();
            if (game.isGameOver()) {
                final GameOutcome outcome = game.getOutcome();
                if (outcome == null || !outcome.isDraw()) {
                    for (Player player : game.getPlayers()) {
                        if (!player.hasLost()) {
                            winners.add(session.playerIdOf(player));
                        }
                    }
                }
            }
            return winners;
        });
    }

    private static String stackText(SpellAbilityStackInstance si, PlayerView observerView) {
        final Card source = si.getSourceCard();
        if (source != null) {
            final boolean faceDown;
            try {
                faceDown = source.isFaceDown();
            } catch (Throwable t) {
                throw new BridgeProjectionException("stack.facedown", t);
            }
            if (faceDown) {
                boolean shown = false;
                try {
                    final CardView view = source.getView();
                    shown = observerView != null && view != null && view.canBeShownTo(observerView);
                } catch (Throwable t) {
                    throw new BridgeProjectionException("stack.visibility", t);
                }
                if (!shown) {
                    return "<face-down spell>";
                }
            }
        }
        final String text = si.getStackDescription();
        return text == null || text.isEmpty() ? "<spell>" : text;
    }

    private static String idOrNull(BridgeSession session, Player player) {
        return player == null ? null : session.playerIdOf(player);
    }

    private static String statusOf(BridgeSession session) {
        switch (session.getStatus()) {
            case CREATED:
                return "not_started";
            case RUNNING:
                return "in_progress";
            case OVER:
                return "completed";
            case FAILED:
            case CLOSED:
            default:
                return "aborted";
        }
    }

    private static String mapPhase(PhaseType phase) {
        switch (phase) {
            case UNTAP:
            case UPKEEP:
            case DRAW:
                return "beginning";
            case MAIN1:
                return "precombat_main";
            case COMBAT_BEGIN:
            case COMBAT_DECLARE_ATTACKERS:
            case COMBAT_DECLARE_BLOCKERS:
            case COMBAT_FIRST_STRIKE_DAMAGE:
            case COMBAT_DAMAGE:
            case COMBAT_END:
                return "combat";
            case MAIN2:
                return "postcombat_main";
            case END_OF_TURN:
            case CLEANUP:
            default:
                return "ending";
        }
    }
}
