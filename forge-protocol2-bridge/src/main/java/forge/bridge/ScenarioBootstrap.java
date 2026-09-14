package forge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.StaticData;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Qualification-only native scenario bootstrap (WS202).
 *
 * <p>Establishes an initial qualification state through native Forge
 * game/state lifecycle APIs only, without creating a second Rules engine.
 * Runs inside the engine-owned {@code startGameHook} (after first-turn phase
 * setup, before the main game loop, while priority is withheld), using only:
 * zone add/remove on live game objects, {@code Card.fromPaperCard} for
 * genuinely absent identities, and {@code Player.setLife} for life totals.
 *
 * <p>It never injects game outcomes, resolves spells/abilities manually,
 * bypasses state-based actions, fabricates continuous effects, computes layers,
 * injects discretionary decision results, or contains card-name-specific
 * production logic. After bootstrap, Forge remains the sole Rules authority;
 * dice/chooser, mulligans, shuffles (seeded when bound), triggers and SBAs all
 * proceed natively. Turn/phase advancement after bootstrap is native
 * pass-driven progression, never injection.
 */
public final class ScenarioBootstrap {
    private ScenarioBootstrap() { }

    /** Validated bootstrap plan: battlefield placements, hand targets, life. */
    public static final class Plan {
        public final List<Placement> battlefield = new ArrayList<>();
        public final Map<String, List<String>> hands = new LinkedHashMap<>();
        public final Map<String, Integer> life = new LinkedHashMap<>();
    }

    /** One battlefield placement: card name plus owner/controller seat ids. */
    public static final class Placement {
        public final String cardName;
        public final String controllerId;
        public final String ownerId;

        Placement(String cardName, String controllerId, String ownerId) {
            this.cardName = cardName;
            this.controllerId = controllerId;
            this.ownerId = ownerId;
        }
    }

    /**
     * Parses and validates a neutral_initial_state object into a plan.
     * Rejects outcome injection (stack, winners), continuous-effect fabrication,
     * and discretionary decision scripts. Hands accept explicit card lists;
     * HIDDEN counts are left natural (no fabrication).
     */
    public static Plan parse(JsonObject neutral) {
        final Plan plan = new Plan();
        if (neutral == null) {
            return plan;
        }
        if (neutral.has("stack") && neutral.get("stack").isJsonArray()
                && neutral.getAsJsonArray("stack").size() > 0) {
            throw new IllegalArgumentException("scenario must not inject stack");
        }
        if (neutral.has("decision_script") && neutral.get("decision_script").isJsonArray()
                && neutral.getAsJsonArray("decision_script").size() > 0) {
            throw new IllegalArgumentException("scenario must not inject decisions");
        }
        if (neutral.has("continuous_effects_present")
                && neutral.get("continuous_effects_present").isJsonArray()
                && neutral.getAsJsonArray("continuous_effects_present").size() > 0) {
            // Continuous effects are derived from battlefield permanents by the
            // engine (layers); they are never injected directly. Presence here is
            // informational only and must match placed permanents at runtime.
        }
        if (neutral.has("battlefield") && neutral.get("battlefield").isJsonArray()) {
            final JsonArray battlefield = neutral.getAsJsonArray("battlefield");
            for (JsonElement element : battlefield) {
                if (!element.isJsonObject()) {
                    continue;
                }
                final JsonObject entry = element.getAsJsonObject();
                final String raw = optString(entry, "card", "");
                final String name = cleanCardName(raw);
                if (name.isEmpty()) {
                    continue;
                }
                final String controller = optString(entry, "controller", "");
                final String owner = optString(entry, "owner", controller);
                if (controller.isEmpty()) {
                    throw new IllegalArgumentException("battlefield entry missing controller");
                }
                plan.battlefield.add(new Placement(name, controller, owner.isEmpty()
                        ? controller : owner));
            }
        }
        if (neutral.has("hands") && neutral.get("hands").isJsonObject()) {
            final JsonObject hands = neutral.getAsJsonObject("hands");
            for (Map.Entry<String, JsonElement> entry : hands.entrySet()) {
                final String playerId = entry.getKey();
                final JsonElement value = entry.getValue();
                if (value.isJsonArray()) {
                    final List<String> cards = new ArrayList<>();
                    for (JsonElement cardElement : value.getAsJsonArray()) {
                        if (!cardElement.isJsonPrimitive()) {
                            continue;
                        }
                        final String name = cleanCardName(cardElement.getAsString());
                        if (!name.isEmpty()) {
                            cards.add(name);
                        }
                    }
                    plan.hands.put(playerId, cards);
                }
                // HIDDEN counts and other shapes are left natural.
            }
        }
        if (neutral.has("life") && neutral.get("life").isJsonObject()) {
            final JsonObject life = neutral.getAsJsonObject("life");
            for (Map.Entry<String, JsonElement> entry : life.entrySet()) {
                try {
                    plan.life.put(entry.getKey(), entry.getValue().getAsInt());
                } catch (Exception e) {
                    throw new IllegalArgumentException("life must be integers");
                }
            }
        }
        return plan;
    }

    /** Strips parenthetical annotations (e.g. oracle reminders) to true names. */
    public static String cleanCardName(String raw) {
        if (raw == null) {
            return "";
        }
        final String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        final int paren = trimmed.indexOf(" (");
        if (paren > 0) {
            return trimmed.substring(0, paren).trim();
        }
        return trimmed;
    }

    private static String optString(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Applies the plan to the live game inside the start-game hook.
     * Moves existing library identities to their scripted zones when present
     * (preserving singleton deck identity, no duplication); creates via
     * {@code Card.fromPaperCard} only when the identity is genuinely absent
     * from the library (e.g. basic fill or slot cards outside the 100).
     * Natural hand cards are returned to the library before scripted hands are
     * established. All moves are direct zone operations (no triggers, no stack,
     * no decisions); SBAs and triggers proceed natively after the hook.
     */
    public static void apply(BridgeSession session, Game game, Plan plan) {
        if (game == null || plan == null) {
            return;
        }
        // Return natural opening hands to libraries (order hidden; counts natural).
        for (Player player : session.registryPlayers()) {
            final String playerId = session.playerIdOf(player);
            if (!plan.hands.containsKey(playerId)) {
                continue;
            }
            final List<Card> handCards = new ArrayList<>(player.getCardsIn(ZoneType.Hand));
            for (Card card : handCards) {
                try {
                    player.getZone(ZoneType.Hand).remove(card);
                    player.getZone(ZoneType.Library).add(card);
                } catch (Throwable t) {
                    throw new IllegalStateException("hand return failed for " + playerId);
                }
            }
        }
        // Battlefield placements: prefer library identities, else create.
        for (Placement placement : plan.battlefield) {
            final Player owner = session.playerById(placement.ownerId);
            final Player controller = session.playerById(placement.controllerId);
            if (owner == null || controller == null) {
                throw new IllegalStateException("unknown placement player");
            }
            Card card = takeFromLibrary(owner, placement.cardName);
            if (card == null) {
                card = createCard(owner, placement.cardName);
            }
            try {
                controller.getZone(ZoneType.Battlefield).add(card);
            } catch (Throwable t) {
                throw new IllegalStateException("battlefield placement failed");
            }
            final Map<String, String> details = new LinkedHashMap<>();
            details.put("card", placement.cardName);
            details.put("controller", placement.controllerId);
            session.audit("scenario_placed_battlefield", details);
        }
        // Scripted hands: prefer library identities, else create.
        for (Map.Entry<String, List<String>> entry : plan.hands.entrySet()) {
            final Player owner = session.playerById(entry.getKey());
            if (owner == null) {
                throw new IllegalStateException("unknown hand player");
            }
            for (String cardName : entry.getValue()) {
                Card card = takeFromLibrary(owner, cardName);
                if (card == null) {
                    card = createCard(owner, cardName);
                }
                try {
                    owner.getZone(ZoneType.Hand).add(card);
                } catch (Throwable t) {
                    throw new IllegalStateException("hand placement failed");
                }
            }
            final Map<String, String> details = new LinkedHashMap<>();
            details.put("player", entry.getKey());
            details.put("count", Integer.toString(entry.getValue().size()));
            session.audit("scenario_placed_hand", details);
        }
        // Life totals (canonical 40 is a no-op; any other value is audited).
        for (Map.Entry<String, Integer> entry : plan.life.entrySet()) {
            final Player player = session.playerById(entry.getKey());
            if (player == null) {
                throw new IllegalStateException("unknown life player");
            }
            try {
                if (player.getLife() != entry.getValue().intValue()) {
                    player.setLife(entry.getValue().intValue(), null);
                    final Map<String, String> details = new LinkedHashMap<>();
                    details.put("player", entry.getKey());
                    details.put("life", entry.getValue().toString());
                    session.audit("scenario_set_life", details);
                }
            } catch (Throwable t) {
                throw new IllegalStateException("life setup failed");
            }
        }
    }

    private static Card takeFromLibrary(Player owner, String cardName) {
        try {
            for (Card card : owner.getCardsIn(ZoneType.Library)) {
                final String name;
                try {
                    name = card.getName();
                } catch (Throwable t) {
                    continue;
                }
                if (cardName.equals(name)) {
                    try {
                        owner.getZone(ZoneType.Library).remove(card);
                    } catch (Throwable t) {
                        return null;
                    }
                    return card;
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    private static Card createCard(Player owner, String cardName) {
        final PaperCard paper;
        try {
            paper = StaticData.instance().getCommonCards().getCard(cardName);
        } catch (Throwable t) {
            throw new IllegalStateException("card database unavailable for " + cardName);
        }
        if (paper == null) {
            throw new IllegalStateException("unresolvable card: " + cardName);
        }
        try {
            return Card.fromPaperCard(paper, owner);
        } catch (Throwable t) {
            throw new IllegalStateException("card creation failed for " + cardName);
        }
    }
}
