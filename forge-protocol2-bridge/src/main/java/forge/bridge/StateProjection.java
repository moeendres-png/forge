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

    public static JsonObject gameState(BridgeSession session, String observerPlayerId) {
        final Game game = session.getGame();
        final JsonObject state = new JsonObject();
        state.addProperty("game_id", session.getGameId());
        state.add("seed", JsonNull.INSTANCE);
        state.add("rng_counter", JsonNull.INSTANCE);
        state.addProperty("status", statusOf(session));
        int turn = 0;
        String activeId = null;
        String priorityId = null;
        String phaseName = "beginning";
        String step = null;
        final Player observer = observerPlayerId == null ? null : session.playerById(observerPlayerId);
        final PlayerView observerView = observer == null ? null : observer.getView();
        if (game != null) {
            final PhaseHandler phases;
            try {
                phases = game.getPhaseHandler();
                turn = Math.max(0, phases.getTurn());
                activeId = idOrNull(session, phases.getPlayerTurn());
                priorityId = idOrNull(session, phases.getPriorityPlayer());
                final PhaseType phase = phases.getPhase();
                if (phase != null) {
                    phaseName = mapPhase(phase);
                    step = phase.name();
                }
            } catch (Throwable t) {
                turn = 0;
            }
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
        final JsonArray players = new JsonArray();
        if (game != null) {
            for (Player player : game.getPlayers()) {
                players.add(playerState(session, player, observer, observerView));
            }
        }
        state.add("players", players);
        final JsonArray stack = new JsonArray();
        if (game != null) {
            try {
                for (SpellAbilityStackInstance si : game.getStack()) {
                    stack.add(stackText(si, observerView));
                }
            } catch (Throwable t) {
                // Stack unreadable mid-transition; report what we have.
            }
        }
        state.add("stack", stack);
        state.add("legal_actions", legalActions(session));
        final JsonArray winners = new JsonArray();
        if (game != null) {
            try {
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
            } catch (Throwable t) {
                // Leave winners empty rather than fabricate.
            }
        }
        state.add("winner_ids", winners);
        state.addProperty("event_sequence", (int) Math.min(Integer.MAX_VALUE, session.auditSize()));
        return state;
    }

    public static JsonObject bridgeMeta(BridgeSession session) {
        final JsonObject meta = new JsonObject();
        meta.addProperty("session_status", session.getStatus().name());
        final DecisionFrame frame = session.getCurrentFrame();
        if (frame == null) {
            meta.addProperty("revision", -1);
            meta.add("pending_decision", JsonNull.INSTANCE);
        } else {
            meta.addProperty("revision", frame.revision);
            meta.add("pending_decision", decisionSummary(frame));
        }
        try {
            meta.addProperty("state_hash", StateHash.ofGame(session.getGame(), session));
        } catch (Throwable t) {
            meta.addProperty("state_hash", "unavailable");
        }
        if (!session.getFailReason().isEmpty()) {
            meta.addProperty("fail_reason", session.getFailReason());
        }
        if (!session.getLastExecutionError().isEmpty()) {
            meta.addProperty("last_execution_error", session.getLastExecutionError());
        }
        return meta;
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
        state.addProperty("seat", Math.max(0, session.seatOf(player)));
        int life = 40;
        int poison = 0;
        try {
            life = player.getLife();
            poison = player.getPoisonCounters();
        } catch (Throwable t) {
            // Pre-game defaults stand.
        }
        state.addProperty("life", life);
        state.addProperty("poison_counters", Math.max(0, poison));
        state.add("commander_damage_received", commanderDamage(session, player));
        state.add("commander_cast_count", commanderCasts(session, player));
        state.add("mana_pool", manaPool(player));
        state.add("zones", zones(player, observer, observerView));
        int landRemaining = 1;
        try {
            landRemaining = Math.max(0, player.getMaxLandPlays() - player.getLandsPlayedThisTurn());
        } catch (Throwable t) {
            landRemaining = 1;
        }
        state.addProperty("land_plays_remaining", landRemaining);
        boolean lost = false;
        String lossReason = null;
        try {
            lost = player.hasLost();
            if (lost && player.getOutcome() != null && player.getOutcome().lossState != null) {
                lossReason = player.getOutcome().lossState.name();
            }
        } catch (Throwable t) {
            lost = false;
        }
        state.addProperty("has_lost", lost);
        if (lossReason == null) {
            state.add("loss_reason", JsonNull.INSTANCE);
        } else {
            state.addProperty("loss_reason", lossReason);
        }
        return state;
    }

    private static JsonObject commanderDamage(BridgeSession session, Player player) {
        final JsonObject damage = new JsonObject();
        try {
            for (Map.Entry<Card, Integer> entry : player.getCommanderDamage()) {
                damage.addProperty(entry.getKey().getName(), entry.getValue());
            }
        } catch (Throwable t) {
            // Leave empty rather than fabricate.
        }
        return damage;
    }

    private static JsonObject commanderCasts(BridgeSession session, Player player) {
        final JsonObject casts = new JsonObject();
        try {
            final List<String> names = session.commanderNames(session.seatOf(player));
            final Map<String, Card> byName = new LinkedHashMap<>();
            for (ZoneType zone : new ZoneType[] { ZoneType.Command, ZoneType.Battlefield,
                    ZoneType.Graveyard, ZoneType.Exile, ZoneType.Hand, ZoneType.Library }) {
                try {
                    for (Card card : player.getCardsIn(zone)) {
                        byName.putIfAbsent(card.getName(), card);
                    }
                } catch (Throwable ignored) {
                    // Skip unreadable zones.
                }
            }
            for (String name : names) {
                final Card card = byName.get(name);
                casts.addProperty(name, card == null ? 0 : Math.max(0, player.getCommanderCast(card)));
            }
        } catch (Throwable t) {
            // Leave empty rather than fabricate.
        }
        return casts;
    }

    private static JsonObject manaPool(Player player) {
        final JsonObject pool = new JsonObject();
        try {
            final PlayerView view = player.getView();
            pool.addProperty("W", Math.max(0, view.getMana(MagicColor.WHITE)));
            pool.addProperty("U", Math.max(0, view.getMana(MagicColor.BLUE)));
            pool.addProperty("B", Math.max(0, view.getMana(MagicColor.BLACK)));
            pool.addProperty("R", Math.max(0, view.getMana(MagicColor.RED)));
            pool.addProperty("G", Math.max(0, view.getMana(MagicColor.GREEN)));
            pool.addProperty("C", Math.max(0, view.getMana(MagicColor.COLORLESS)));
        } catch (Throwable t) {
            // Leave empty rather than fabricate.
        }
        return pool;
    }

    private static JsonObject zones(Player player, Player observer, PlayerView observerView) {
        final JsonObject zones = new JsonObject();
        zones.add("library", new JsonArray());
        zones.add("hand", handZone(player, observer));
        zones.add("battlefield", battlefieldZone(player, observerView));
        zones.add("graveyard", namesZone(player, ZoneType.Graveyard));
        zones.add("exile", exileZone(player, observerView));
        zones.add("command", namesZone(player, ZoneType.Command));
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
        try {
            for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
                zone.add(shownName(card, observerView));
            }
        } catch (Throwable t) {
            // Return what we have.
        }
        return zone;
    }

    private static JsonArray exileZone(Player player, PlayerView observerView) {
        final JsonArray zone = new JsonArray();
        try {
            for (Card card : player.getCardsIn(ZoneType.Exile)) {
                zone.add(shownName(card, observerView));
            }
        } catch (Throwable t) {
            // Return what we have.
        }
        return zone;
    }

    private static String shownName(Card card, PlayerView observerView) {
        try {
            if (!card.isFaceDown()) {
                final CardView view = card.getView();
                if (observerView == null || (view != null && view.canBeShownTo(observerView))) {
                    return card.getName();
                }
                return card.isFaceDown() ? "<face-down>" : card.getName();
            }
            final CardView view = card.getView();
            if (observerView != null && view != null && view.canBeShownTo(observerView)) {
                return card.getName();
            }
            return "<face-down>";
        } catch (Throwable t) {
            return "<unreadable>";
        }
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
        try {
            for (Card card : player.getCardsIn(zone)) {
                names.add(card.getName());
            }
        } catch (Throwable t) {
            // Return what we have.
        }
        return names;
    }

    private static String stackText(SpellAbilityStackInstance si, PlayerView observerView) {
        try {
            final Card source = si.getSourceCard();
            if (source != null && source.isFaceDown()) {
                final CardView view = source.getView();
                if (observerView == null || view == null || !view.canBeShownTo(observerView)) {
                    return "<face-down spell>";
                }
            }
            final String text = si.getStackDescription();
            return text == null || text.isEmpty() ? "<spell>" : text;
        } catch (Throwable t) {
            return "<unreadable>";
        }
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
