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
        public final Map<String, Map<String, Integer>> commanderDamage = new LinkedHashMap<>();
    }

    /** One battlefield placement: card name plus owner/controller seat ids. */
    public static final class Placement {
        public final String cardName;
        public final String controllerId;
        public final String ownerId;
        public final boolean tapped;
        public final Map<String, Integer> counters;
        public final String attachedTo;

        Placement(String cardName, String controllerId, String ownerId, boolean tapped,
                Map<String, Integer> counters, String attachedTo) {
            this.cardName = cardName;
            this.controllerId = controllerId;
            this.ownerId = ownerId;
            this.tapped = tapped;
            this.counters = counters;
            this.attachedTo = attachedTo;
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
                boolean tapped = false;
                if (entry.has("tapped") && !entry.get("tapped").isJsonNull()) {
                    try {
                        tapped = entry.get("tapped").getAsBoolean();
                    } catch (Exception e) {
                        throw new IllegalArgumentException("tapped must be a boolean");
                    }
                }
                final Map<String, Integer> counters = new LinkedHashMap<>();
                if (entry.has("counters") && !entry.get("counters").isJsonNull()) {
                    if (!entry.get("counters").isJsonObject()) {
                        throw new IllegalArgumentException("counters must be an object");
                    }
                    for (Map.Entry<String, JsonElement> counter
                            : entry.getAsJsonObject("counters").entrySet()) {
                        try {
                            final int amount = counter.getValue().getAsInt();
                            if (amount < 0) {
                                throw new IllegalArgumentException("counter amounts must be >= 0");
                            }
                            if (amount > 0) {
                                counters.put(counter.getKey(), amount);
                            }
                        } catch (IllegalArgumentException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new IllegalArgumentException("counter amounts must be integers");
                        }
                    }
                }
                String attachedTo = "";
                if (entry.has("attached_to") && !entry.get("attached_to").isJsonNull()) {
                    attachedTo = cleanCardName(optString(entry, "attached_to", ""));
                }
                plan.battlefield.add(new Placement(name, controller, owner.isEmpty()
                        ? controller : owner, tapped, counters, attachedTo));
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
        if (neutral.has("players") && neutral.get("players").isJsonArray()) {
            for (JsonElement element : neutral.getAsJsonArray("players")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                final JsonObject playerEntry = element.getAsJsonObject();
                final String playerId = optString(playerEntry, "id", "");
                if (playerId.isEmpty() || !playerEntry.has("commander_damage_taken")
                        || playerEntry.get("commander_damage_taken").isJsonNull()) {
                    continue;
                }
                if (!playerEntry.get("commander_damage_taken").isJsonObject()) {
                    throw new IllegalArgumentException("commander_damage_taken must be an object");
                }
                final Map<String, Integer> damage = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> dealt
                        : playerEntry.getAsJsonObject("commander_damage_taken").entrySet()) {
                    final String commanderName = cleanCardName(dealt.getKey());
                    if (commanderName.isEmpty()) {
                        continue;
                    }
                    try {
                        final int amount = dealt.getValue().getAsInt();
                        if (amount < 0) {
                            throw new IllegalArgumentException(
                                    "commander damage must be >= 0");
                        }
                        if (amount > 0) {
                            damage.put(commanderName, amount);
                        }
                    } catch (IllegalArgumentException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalArgumentException("commander damage must be integers");
                    }
                }
                if (!damage.isEmpty()) {
                    plan.commanderDamage.put(playerId, damage);
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
        // Battlefield placements: real commander identities first (command zone),
        // then library identities (preserving singleton deck identity), else create
        // via Card.fromPaperCard for genuinely absent cards (basic fill or slot
        // cards outside the 100).
        final List<Card> placed = new ArrayList<>();
        for (Placement placement : plan.battlefield) {
            final Player owner = session.playerById(placement.ownerId);
            final Player controller = session.playerById(placement.controllerId);
            if (owner == null || controller == null) {
                throw new IllegalStateException("unknown placement player");
            }
            Card card = takeCommander(owner, placement.cardName);
            if (card == null) {
                card = takeFromLibrary(owner, placement.cardName);
            }
            if (card == null) {
                card = createCard(owner, placement.cardName);
            }
            try {
                controller.getZone(ZoneType.Battlefield).add(card);
            } catch (Throwable t) {
                throw new IllegalStateException("battlefield placement failed");
            }
            if (placement.tapped) {
                try {
                    card.setTapped(true);
                } catch (Throwable t) {
                    throw new IllegalStateException("tap failed");
                }
            }
            for (Map.Entry<String, Integer> counter : placement.counters.entrySet()) {
                final forge.game.card.CounterType counterType;
                try {
                    counterType = forge.game.card.CounterEnumType.valueOf(counter.getKey());
                } catch (Exception e) {
                    throw new IllegalStateException("unknown counter: " + counter.getKey());
                }
                try {
                    card.addCounterInternal(counterType, counter.getValue(), null, false, null,
                            null);
                } catch (Throwable t) {
                    throw new IllegalStateException("counter placement failed");
                }
            }
            placed.add(card);
            final Map<String, String> details = new LinkedHashMap<>();
            details.put("card", placement.cardName);
            details.put("controller", placement.controllerId);
            details.put("owner", placement.ownerId);
            session.audit("scenario_placed_battlefield", details);
        }
        // Aura attachments resolve after all placements so named hosts exist.
        // Uses the native attach path (legality + timestamps); the attach target
        // is matched by card name among the controller's battlefield cards.
        for (int i = 0; i < plan.battlefield.size(); i++) {
            final Placement placement = plan.battlefield.get(i);
            if (placement.attachedTo == null || placement.attachedTo.isEmpty()) {
                continue;
            }
            final Card aura = placed.get(i);
            final Player controller = session.playerById(placement.controllerId);
            Card host = null;
            try {
                for (Card candidate : controller.getCardsIn(ZoneType.Battlefield)) {
                    if (candidate != null && candidate != aura
                            && placement.attachedTo.equals(candidate.getName())) {
                        host = candidate;
                        break;
                    }
                }
            } catch (Throwable t) {
                throw new IllegalStateException("attach host lookup failed");
            }
            if (host == null) {
                throw new IllegalStateException(
                        "attach host not on battlefield: " + placement.attachedTo);
            }
            try {
                aura.attachToEntity(host, null);
            } catch (Throwable t) {
                throw new IllegalStateException("attach failed");
            }
            final Map<String, String> details = new LinkedHashMap<>();
            details.put("card", placement.cardName);
            details.put("attached_to", placement.attachedTo);
            session.audit("scenario_attached", details);
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
        // Commander damage seeding resolves commander identities across all
        // players' native commander collections first (identity-correct), then
        // any battlefield card of that name. Audited per player/commander.
        for (Map.Entry<String, Map<String, Integer>> entry : plan.commanderDamage.entrySet()) {
            final Player player = session.playerById(entry.getKey());
            if (player == null) {
                throw new IllegalStateException("unknown damage player");
            }
            for (Map.Entry<String, Integer> dealt : entry.getValue().entrySet()) {
                final Card commander = findCommander(game, session, dealt.getKey());
                if (commander == null) {
                    throw new IllegalStateException(
                            "commander not found: " + dealt.getKey());
                }
                try {
                    player.addCommanderDamage(commander, dealt.getValue().intValue());
                } catch (Throwable t) {
                    throw new IllegalStateException("commander damage setup failed");
                }
                final Map<String, String> details = new LinkedHashMap<>();
                details.put("player", entry.getKey());
                details.put("commander", dealt.getKey());
                details.put("damage", dealt.getValue().toString());
                session.audit("scenario_commander_damage", details);
            }
        }
        final Map<String, String> applied = new LinkedHashMap<>();
        applied.put("battlefield", Integer.toString(plan.battlefield.size()));
        applied.put("hands", Integer.toString(plan.hands.size()));
        applied.put("life", Integer.toString(plan.life.size()));
        applied.put("commander_damage", Integer.toString(plan.commanderDamage.size()));
        session.audit("scenario_applied", applied);
    }

    /**
     * Real commander identity first: the same Card object the engine keys
     * commander damage, casts and SBA movement on. Falls back to any battlefield
     * card of that name.
     */
    private static Card findCommander(Game game, BridgeSession session, String commanderName) {
        try {
            for (Player player : session.registryPlayers()) {
                for (Card commander : player.getCommanders()) {
                    try {
                        if (commanderName.equals(commander.getName())) {
                            return commander;
                        }
                    } catch (Throwable t) {
                        continue;
                    }
                }
            }
            for (Player player : session.registryPlayers()) {
                for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
                    try {
                        if (commanderName.equals(card.getName())) {
                            return card;
                        }
                    } catch (Throwable t) {
                        continue;
                    }
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    /**
     * Real commander identity from the owner's native commander collection, so
     * placement preserves commander movement/tax/damage semantics. Anything
     * else stays untouched in the command zone.
     */
    private static Card takeCommander(Player owner, String cardName) {
        try {
            for (Card commander : owner.getCommanders()) {
                try {
                    if (cardName.equals(commander.getName())
                            && owner.getZone(ZoneType.Command).contains(commander)) {
                        owner.getZone(ZoneType.Command).remove(commander);
                        return commander;
                    }
                } catch (Throwable t) {
                    continue;
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
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
