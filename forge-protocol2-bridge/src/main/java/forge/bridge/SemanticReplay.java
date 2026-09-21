package forge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * WS227 Forge-native semantic replay observability (additive, projection-only).
 *
 * <p>Serializes authoritative Forge Rules/Core state into stable neutral semantics
 * for the {@code semantic-replay-tape/1.0.0} consumer without creating a second
 * Rules engine. The bridge never computes legality: fingerprints and digests are
 * projections over the complete native option set and native state already
 * enumerated by Core. Raw Java identity, array index, random UUIDs and labels
 * alone are never sufficient; every fingerprint joins Core-authoritative fields.
 *
 * <p>Indistinguishable options either carry a stable authoritative
 * occurrence/provenance identity or collide and fail closed as ambiguous.
 * Resolution is exactly-once: zero matches is missing, more than one is
 * ambiguous, never first-match.
 */
public final class SemanticReplay {
    /** Neutral provider surface version (additive over Protocol 2.0.0). */
    public static final String SEMANTIC_REPLAY_VERSION = "forge-semantic-replay/1.0.0";
    /** Canonicalization discipline version. */
    public static final String CANONICALIZATION_VERSION = "forge-semantic-canonical/1.0.0";
    /** Option-identity discipline version. */
    public static final String OPTION_IDENTITY_VERSION = "forge-semantic-option-identity/1.0.0";
    /** State-digest discipline version. */
    public static final String STATE_DIGEST_VERSION = "forge-semantic-state-digest/1.0.0";
    /** Decision-protocol version binding opaque decision classes. */
    public static final String DECISION_PROTOCOL_VERSION = "forge-decision-protocol/1.0.0";
    /** Neutral tape contract this surface maps onto (read-only, Lab-owned). */
    public static final String TAPE_CONTRACT = "semantic-replay-tape/1.0.0";

    private static final Pattern BARE_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern OBJECT_ID_ATTR = Pattern.compile("object_id='[^']*'");
    private static final Pattern GAMELOG_SHORT_ID = Pattern.compile("\\[[0-9a-fA-F]{3,}\\]");
    private static final Pattern CHOICE_SHORT_ID = Pattern.compile(" \\[[0-9a-fA-F]{3,}\\]");

    private SemanticReplay() { }

    // ---- decision class (opaque versioned semantic string, Forge namespace) ----

    /**
     * Neutral decision class for a native Forge decision family.
     * Opaque to the Lab consumer; strict equality both sides; unknown Kinds
     * fail closed (no generic fallback class is ever synthesized).
     * @param kind the native Forge decision family
     * @return versioned neutral class, e.g. forge:priority/1
     */
    public static String decisionClass(final DecisionFrame.Kind kind) {
        if (kind == null) {
            throw new BridgeProjectionException("decision_class", "null kind");
        }
        return "forge:" + kind.name().toLowerCase() + "/1";
    }

    /**
     * Whether a decision class denotes lifecycle (concession) rather than a
     * discretionary Rules choice. Only the native CONCESSION family maps to
     * lifecycle; PRIORITY concede options remain priority decisions whose
     * selected semantics indicate concession.
     * @param kind the native family
     * @return true for lifecycle
     */
    public static boolean isLifecycle(final DecisionFrame.Kind kind) {
        return kind == DecisionFrame.Kind.CONCESSION;
    }

    // ---- redaction (mirrors WS218 redact_text lineage, Forge-native) ----

    /**
     * Redacts process-local identities from human labels while preserving Rules
     * text. Removes object_id attributes, bare UUIDs, GameLog short-ids and
     * choice short-ids. Never returns null.
     * @param label the raw label
     * @return redacted label
     */
    public static String redactLabel(final String label) {
        if (label == null) {
            return "";
        }
        String redacted = OBJECT_ID_ATTR.matcher(label).replaceAll("object_id='#'");
        redacted = BARE_UUID.matcher(redacted).replaceAll("#");
        redacted = CHOICE_SHORT_ID.matcher(redacted).replaceAll(" [#]");
        redacted = GAMELOG_SHORT_ID.matcher(redacted).replaceAll("[#]");
        return redacted;
    }

    // ---- entity semantics (Core-authoritative, stable, principal-scoped) ----

    /**
     * Stable semantic identity for a native game entity. Players map to
     * immutable principal ids (pN from the session registry, never display
     * names). Cards map to name plus controller/owner principal, zone, tapped,
     * power/toughness, damage, sorted counters and an occurrence index among
     * same-name/controller/zone siblings. Identical siblings share the same
     * fingerprint and therefore fail closed as ambiguous by design.
     * @param entity the native entity
     * @param session the owning session (for principal mapping)
     * @return stable semantic string
     */
    public static String entitySemantic(final GameEntity entity, final BridgeSession session) {
        if (entity == null) {
            throw new BridgeProjectionException("entity", "null entity");
        }
        if (session == null) {
            throw new BridgeProjectionException("entity", "null session");
        }
        try {
            if (entity instanceof Player) {
                final Player player = (Player) entity;
                return "player:" + session.playerIdOf(player);
            }
            if (entity instanceof Card) {
                return cardSemantic((Card) entity, session);
            }
            return "entity:" + redactLabel(entity.toString());
        } catch (BridgeProjectionException e) {
            throw e;
        } catch (Throwable t) {
            throw new BridgeProjectionException("entity", t);
        }
    }

    private static String cardSemantic(final Card card, final BridgeSession session) {
        final String name;
        try {
            name = card.getName();
        } catch (Throwable t) {
            throw new BridgeProjectionException("card.name", t);
        }
        if (name == null || name.isEmpty()) {
            throw new BridgeProjectionException("card.name", "empty name");
        }
        final String controllerId;
        final String ownerId;
        try {
            final Player controller = card.getController();
            controllerId = controller == null ? "none" : session.playerIdOf(controller);
        } catch (Throwable t) {
            throw new BridgeProjectionException("card.controller", t);
        }
        try {
            final Player owner = card.getOwner();
            ownerId = owner == null ? "none" : session.playerIdOf(owner);
        } catch (Throwable t) {
            throw new BridgeProjectionException("card.owner", t);
        }
        final String zone;
        try {
            zone = card.getZone() == null || card.getZone().getZoneType() == null
                    ? "unknown" : card.getZone().getZoneType().name();
        } catch (Throwable t) {
            throw new BridgeProjectionException("card.zone", t);
        }
        final boolean tapped;
        try {
            tapped = card.isTapped();
        } catch (Throwable t) {
            throw new BridgeProjectionException("card.tapped", t);
        }
        String power;
        String toughness;
        try {
            power = Integer.toString(card.getNetPower());
        } catch (Throwable t) {
            power = "NA";
        }
        try {
            toughness = Integer.toString(card.getNetToughness());
        } catch (Throwable t) {
            toughness = "NA";
        }
        final int damage;
        try {
            damage = card.getDamage();
        } catch (Throwable t) {
            throw new BridgeProjectionException("card.damage", t);
        }
        final String counters = countersSemantic(card);
        final int occurrence = occurrenceIndex(card, session, name, controllerId, zone);
        final StringBuilder sb = new StringBuilder();
        sb.append("card:name=").append(name);
        sb.append(":controller=").append(controllerId);
        sb.append(":owner=").append(ownerId);
        sb.append(":zone=").append(zone);
        sb.append(":tapped=").append(tapped);
        sb.append(":power=").append(power);
        sb.append(":toughness=").append(toughness);
        sb.append(":damage=").append(damage);
        sb.append(":counters=").append(counters);
        sb.append(":occurrence=").append(occurrence);
        return sb.toString();
    }

    private static String countersSemantic(final Card card) {
        try {
            final Map<String, Integer> sorted = new TreeMap<>();
            for (forge.game.card.CounterType type : card.getCounters().elementSet()) {
                if (type == null || type.getName() == null) {
                    continue;
                }
                sorted.put(type.getName(), Integer.valueOf(card.getCounters(type)));
            }
            final StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, Integer> entry : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return sb.toString();
        } catch (Throwable t) {
            throw new BridgeProjectionException("card.counters", t);
        }
    }

    /**
     * Stable occurrence index among same-name/controller/zone siblings, sorted
     * by authoritative tie-breakers (power, toughness, damage, counters,
     * tapped). Siblings that remain identical share the same index fingerprint
     * downstream and therefore collide as ambiguous (fail closed, never
     * first-match).
     */
    private static int occurrenceIndex(final Card card, final BridgeSession session,
            final String name, final String controllerId, final String zone) {
        try {
            final Game game = session.getGame();
            if (game == null) {
                return 0;
            }
            final List<String> siblingKeys = new ArrayList<>();
            String selfKey = null;
            for (Player player : session.registryPlayers()) {
                final String pid;
                try {
                    pid = session.playerIdOf(player);
                } catch (Throwable t) {
                    continue;
                }
                if (!pid.equals(controllerId)) {
                    continue;
                }
                for (ZoneType zoneType : ZoneType.values()) {
                    if (!zoneType.name().equals(zone)) {
                        continue;
                    }
                    Iterable<Card> cards;
                    try {
                        cards = player.getCardsIn(zoneType);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (cards == null) {
                        continue;
                    }
                    for (Card sibling : cards) {
                        if (sibling == null) {
                            continue;
                        }
                        String siblingName;
                        try {
                            siblingName = sibling.getName();
                        } catch (Throwable t) {
                            continue;
                        }
                        if (!name.equals(siblingName)) {
                            continue;
                        }
                        final String key = siblingTieKey(sibling);
                        siblingKeys.add(key);
                        if (sibling == card) {
                            selfKey = key;
                        }
                    }
                }
            }
            if (selfKey == null) {
                return 0;
            }
            Collections.sort(siblingKeys);
            int occurrence = 0;
            for (String key : siblingKeys) {
                if (key.equals(selfKey)) {
                    return occurrence;
                }
                occurrence++;
            }
            return 0;
        } catch (Throwable t) {
            throw new BridgeProjectionException("card.occurrence", t);
        }
    }

    private static String siblingTieKey(final Card card) {
        String power = "NA";
        String toughness = "NA";
        try {
            power = Integer.toString(card.getNetPower());
        } catch (Throwable t) {
            power = "NA";
        }
        try {
            toughness = Integer.toString(card.getNetToughness());
        } catch (Throwable t) {
            toughness = "NA";
        }
        int damage = 0;
        try {
            damage = card.getDamage();
        } catch (Throwable t) {
            damage = 0;
        }
        boolean tapped = false;
        try {
            tapped = card.isTapped();
        } catch (Throwable t) {
            tapped = false;
        }
        String counters = "";
        try {
            counters = countersSemantic(card);
        } catch (Throwable t) {
            counters = "";
        }
        return "p=" + power + "|t=" + toughness + "|d=" + damage + "|c=" + counters
                + "|tap=" + tapped;
    }

    // ---- option fingerprint (Core-authoritative fields only) ----

    /**
     * Stable semantic fingerprint for one native legal option. Inputs are ONLY
     * authoritative data: decision class, actor, action type, redacted label,
     * source card name, boolean/int/string values, payload kind plus entity
     * semantics, and frame numeric bounds for free-input/divided frames.
     * Never hashed: optionId random UUIDs, object/source/ability/card UUIDs,
     * game/player/decision UUIDs, timestamps, nanos, identityHashCodes.
     * @param frame the parked frame
     * @param option the native option
     * @param session the owning session
     * @return hex SHA-256 fingerprint
     */
    public static String optionFingerprint(final DecisionFrame frame,
            final DecisionFrame.Option option, final BridgeSession session) {
        if (frame == null || option == null || session == null) {
            throw new BridgeProjectionException("option_fingerprint", "null input");
        }
        final StringBuilder canonical = new StringBuilder();
        canonical.append("identity=").append(OPTION_IDENTITY_VERSION).append('|');
        canonical.append("canonical=").append(CANONICALIZATION_VERSION).append('|');
        canonical.append("class=").append(decisionClass(frame.kind)).append('|');
        canonical.append("actor=").append(frame.actorPlayerId).append('|');
        canonical.append("action=").append(nullToEmpty(option.actionType)).append('|');
        canonical.append("label=").append(redactLabel(option.label)).append('|');
        canonical.append("source=").append(nullToEmpty(option.sourceCardName)).append('|');
        canonical.append("confirm=").append(option.confirmValue == null
                ? "" : option.confirmValue.toString()).append('|');
        canonical.append("int=").append(option.intValue == null
                ? "" : option.intValue.toString()).append('|');
        canonical.append("string=").append(redactLabel(option.stringValue)).append('|');
        canonical.append("payloadKind=").append(nullToEmpty(option.payloadKind)).append('|');
        canonical.append("payload=").append(payloadSemantic(frame, option, session)).append('|');
        canonical.append("pass=").append(option.isPass).append('|');
        canonical.append("keep=").append(option.isKeep).append('|');
        canonical.append("concede=").append(option.isConcede).append('|');
        if (frame.freeInput) {
            canonical.append("min=").append(frame.inputMin).append('|');
            canonical.append("max=").append(frame.inputMax).append('|');
        }
        if (frame.kind == DecisionFrame.Kind.DIVIDED_ALLOCATION) {
            canonical.append("dividedTotal=").append(frame.dividedTotal).append('|');
            canonical.append("dividedMin=").append(frame.dividedMinPerTarget).append('|');
            canonical.append("dividedUpTo=").append(frame.dividedUpTo).append('|');
        }
        return sha256Hex(canonical.toString());
    }

    private static String payloadSemantic(final DecisionFrame frame,
            final DecisionFrame.Option option, final BridgeSession session) {
        final StringBuilder sb = new StringBuilder();
        if (option.nativePlayer != null) {
            try {
                sb.append("player=").append(session.playerIdOf(option.nativePlayer)).append(';');
            } catch (Throwable t) {
                throw new BridgeProjectionException("payload.player", t);
            }
        }
        if (option.nativeBinding != null) {
            sb.append(spellAbilitySemantic(option.nativeBinding, session)).append(';');
        }
        final Object payload = option.nativePayload;
        if (payload == null) {
            return sb.toString();
        }
        try {
            if (payload instanceof Player) {
                sb.append(entitySemantic((Player) payload, session)).append(';');
            } else if (payload instanceof Card) {
                sb.append(entitySemantic((Card) payload, session)).append(';');
            } else if (payload instanceof GameEntity) {
                sb.append(entitySemantic((GameEntity) payload, session)).append(';');
            } else if (payload instanceof List) {
                final List<?> list = (List<?>) payload;
                final List<String> parts = new ArrayList<>();
                for (Object element : list) {
                    parts.add(listElementSemantic(element, session));
                }
                Collections.sort(parts);
                for (String part : parts) {
                    sb.append(part).append(';');
                }
                sb.append("count=").append(parts.size()).append(';');
            } else if (payload instanceof String) {
                sb.append("text=").append(redactLabel((String) payload)).append(';');
            } else {
                sb.append("kind=").append(payload.getClass().getSimpleName()).append(';');
            }
        } catch (BridgeProjectionException e) {
            throw e;
        } catch (Throwable t) {
            throw new BridgeProjectionException("payload", t);
        }
        return sb.toString();
    }

    private static String listElementSemantic(final Object element, final BridgeSession session) {
        if (element == null) {
            return "null";
        }
        if (element instanceof Card) {
            return entitySemantic((Card) element, session);
        }
        if (element instanceof Player) {
            return entitySemantic((Player) element, session);
        }
        if (element instanceof GameEntity) {
            return entitySemantic((GameEntity) element, session);
        }
        if (element instanceof SpellAbility) {
            return spellAbilitySemantic((SpellAbility) element, session);
        }
        return "item:" + redactLabel(element.toString());
    }

    private static String spellAbilitySemantic(final SpellAbility ability,
            final BridgeSession session) {
        try {
            final StringBuilder sb = new StringBuilder();
            sb.append("spellability:");
            final Card host;
            try {
                host = ability.getHostCard();
            } catch (Throwable t) {
                throw new BridgeProjectionException("ability.host", t);
            }
            if (host != null) {
                try {
                    sb.append("host=").append(host.getName()).append(';');
                } catch (Throwable t) {
                    throw new BridgeProjectionException("ability.hostname", t);
                }
            }
            try {
                if (ability.getApi() != null) {
                    sb.append("api=").append(ability.getApi().name()).append(';');
                }
            } catch (Throwable t) {
                throw new BridgeProjectionException("ability.api", t);
            }
            try {
                final Player activator = ability.getActivatingPlayer();
                if (activator != null) {
                    sb.append("activator=").append(session.playerIdOf(activator)).append(';');
                }
            } catch (Throwable t) {
                throw new BridgeProjectionException("ability.activator", t);
            }
            try {
                final String description = ability.getDescription();
                if (description != null && !description.isEmpty()) {
                    sb.append("text=").append(redactLabel(description)).append(';');
                }
            } catch (Throwable t) {
                throw new BridgeProjectionException("ability.text", t);
            }
            return sb.toString();
        } catch (BridgeProjectionException e) {
            throw e;
        } catch (Throwable t) {
            throw new BridgeProjectionException("spellability", t);
        }
    }

    /**
     * Human-readable stable semantic key for debugging (not normative; the
     * fingerprint is). Never contains random UUIDs.
     */
    public static String semanticKey(final DecisionFrame frame,
            final DecisionFrame.Option option, final BridgeSession session) {
        return decisionClass(frame.kind) + "|" + frame.actorPlayerId + "|"
                + nullToEmpty(option.actionType) + "|" + redactLabel(option.label) + "|"
                + nullToEmpty(option.sourceCardName) + "|"
                + optionFingerprint(frame, option, session).substring(0, 12);
    }

    // ---- legal set (complete current multiset, never pre-filtered) ----

    /**
     * Digest over the sorted multiset of current legal option fingerprints.
     * Counts preserved; order normalized. Computed over the complete parked
     * set; the bridge never pre-filters to the recorded choice.
     */
    public static String legalSetDigest(final DecisionFrame frame, final BridgeSession session) {
        if (frame == null || session == null) {
            throw new BridgeProjectionException("legal_set", "null input");
        }
        final List<String> prints = new ArrayList<>();
        for (DecisionFrame.Option option : frame.options) {
            if (option.isConsumed()) {
                continue;
            }
            prints.add(optionFingerprint(frame, option, session));
        }
        Collections.sort(prints);
        final StringBuilder canonical = new StringBuilder();
        canonical.append("identity=").append(OPTION_IDENTITY_VERSION).append('|');
        canonical.append("size=").append(prints.size()).append('|');
        for (String print : prints) {
            canonical.append(print).append('|');
        }
        return sha256Hex(canonical.toString());
    }

    /**
     * Current legal-set size (unconsumed options).
     */
    public static int legalSetSize(final DecisionFrame frame) {
        if (frame == null) {
            throw new BridgeProjectionException("legal_set", "null frame");
        }
        int size = 0;
        for (DecisionFrame.Option option : frame.options) {
            if (!option.isConsumed()) {
                size++;
            }
        }
        return size;
    }

    /**
     * Resolves a recorded semantic fingerprint to EXACTLY ONE current native
     * option. Zero matches fails as missing; more than one fails as ambiguous;
     * never first-match, never fuzzy.
     * @param frame the current native frame
     * @param session the owning session
     * @param recordedFingerprint the recorded semantic fingerprint
     * @return the single current native option
     * @throws SemanticReplayDivergence with MISSING or AMBIGUOUS identity
     */
    public static DecisionFrame.Option resolveExactlyOnce(final DecisionFrame frame,
            final BridgeSession session, final String recordedFingerprint) {
        if (frame == null || session == null || recordedFingerprint == null
                || recordedFingerprint.isEmpty()) {
            throw new SemanticReplayDivergence("CHOSEN_OPTION_MISSING",
                    "recorded semantic option is required");
        }
        final List<DecisionFrame.Option> matches = new ArrayList<>();
        for (DecisionFrame.Option option : frame.options) {
            if (option.isConsumed()) {
                continue;
            }
            final String current;
            try {
                current = optionFingerprint(frame, option, session);
            } catch (Throwable t) {
                throw new SemanticReplayDivergence("CHOSEN_OPTION_MISSING",
                        "option projection failed: " + t.getMessage());
            }
            if (recordedFingerprint.equals(current)) {
                matches.add(option);
            }
        }
        if (matches.isEmpty()) {
            throw new SemanticReplayDivergence("CHOSEN_OPTION_MISSING",
                    "recorded semantic option matches no current legal option");
        }
        if (matches.size() > 1) {
            throw new SemanticReplayDivergence("CHOSEN_OPTION_AMBIGUOUS",
                    "recorded semantic option matches " + matches.size()
                            + " current legal options");
        }
        return matches.get(0);
    }

    // ---- RNG / event coordinates (Core-owned, regenerate-not-inject) ----

    /**
     * RNG binding snapshot: explicit root seed plus call coordinates.
     */
    public static JsonObject rngBinding(final BridgeSession session) {
        final JsonObject binding = new JsonObject();
        final Long seed = session == null ? null : session.getSeedBinding();
        if (seed == null) {
            binding.add("root_seed", null);
        } else {
            binding.addProperty("root_seed", seed.longValue());
        }
        binding.addProperty("explicit_seed", forge.util.MyRandom.isExplicitSeed());
        binding.addProperty("require_explicit_seed", true);
        final Long root = forge.util.MyRandom.getRootSeed();
        if (root == null) {
            binding.add("rules_root_seed", null);
        } else {
            binding.addProperty("rules_root_seed", root.longValue());
        }
        binding.addProperty("rules_calls", forge.util.MyRandom.getCallCount());
        return binding;
    }

    /**
     * Event offset: internal audit size (monotonic provider coordinate, not an
     * engine log export). Deterministic for a fixed decision path; never
     * serialized audit content (which carries unstable hashes).
     */
    public static long eventOffset(final BridgeSession session) {
        if (session == null) {
            throw new BridgeProjectionException("event_offset", "null session");
        }
        return session.auditSize();
    }

    /**
     * Event digest over semantic step coordinates (sequence/class/actor/
     * selected prints/numeric/calls before-after/turn before-after/
     * observation/post). Mirrors WS218 event_digest_for_step lineage.
     */
    public static String eventDigest(final long sequence, final String decisionClass,
            final String actor, final String selectedPrints, final String numeric,
            final long callsBefore, final long callsAfter, final int turnBefore,
            final int turnAfter, final String observationDigest, final String postDigest) {
        final StringBuilder canonical = new StringBuilder();
        canonical.append("canonical=").append(CANONICALIZATION_VERSION).append('|');
        canonical.append("sequence=").append(sequence).append('|');
        canonical.append("class=").append(nullToEmpty(decisionClass)).append('|');
        canonical.append("actor=").append(nullToEmpty(actor)).append('|');
        canonical.append("selected=").append(nullToEmpty(selectedPrints)).append('|');
        canonical.append("numeric=").append(nullToEmpty(numeric)).append('|');
        canonical.append("callsBefore=").append(callsBefore).append('|');
        canonical.append("callsAfter=").append(callsAfter).append('|');
        canonical.append("turnBefore=").append(turnBefore).append('|');
        canonical.append("turnAfter=").append(turnAfter).append('|');
        canonical.append("observation=").append(nullToEmpty(observationDigest)).append('|');
        canonical.append("post=").append(nullToEmpty(postDigest)).append('|');
        return sha256Hex(canonical.toString());
    }

    // ---- state / checkpoint surface (bridge serializes native state) ----

    /**
     * Public state digest: turn/phase/step, seats, life/poison/counts, sorted
     * public permanents, graveyard name order, command names, stack name order,
     * commander damage rows. No hand/mana/granted arrays, no UUIDs.
     */
    public static String publicStateDigest(final BridgeSession session) {
        if (session == null) {
            throw new BridgeProjectionException("public_state", "null session");
        }
        final Game game = session.getGame();
        if (game == null) {
            throw new BridgeProjectionException("public_state", "no game");
        }
        final StringBuilder canonical = new StringBuilder();
        canonical.append("state=").append(STATE_DIGEST_VERSION).append('|');
        canonical.append("canonical=").append(CANONICALIZATION_VERSION).append('|');
        int turn = 0;
        String phase = "beginning";
        String step = "";
        String active = "none";
        String priority = "none";
        try {
            turn = Math.max(0, game.getPhaseHandler().getTurn());
            final PhaseType phaseType = game.getPhaseHandler().getPhase();
            if (phaseType == null) {
                phase = "beginning";
                step = "";
            } else {
                phase = phaseType.name();
                step = phaseType.name();
            }
            if (game.getPhaseHandler().getPlayerTurn() != null) {
                active = session.playerIdOf(game.getPhaseHandler().getPlayerTurn());
            }
            if (game.getPhaseHandler().getPriorityPlayer() != null) {
                priority = session.playerIdOf(game.getPhaseHandler().getPriorityPlayer());
            }
        } catch (Throwable t) {
            throw new BridgeProjectionException("public_state.turn", t);
        }
        canonical.append("turn=").append(turn).append('|');
        canonical.append("phase=").append(phase).append('|');
        canonical.append("step=").append(step).append('|');
        canonical.append("active=").append(active).append('|');
        canonical.append("priority=").append(priority).append('|');
        final List<Player> roster;
        try {
            roster = session.registryPlayers();
        } catch (Throwable t) {
            throw new BridgeProjectionException("public_state.roster", t);
        }
        for (Player player : roster) {
            final String pid;
            try {
                pid = session.playerIdOf(player);
            } catch (Throwable t) {
                throw new BridgeProjectionException("public_state.player", t);
            }
            int life = 0;
            int poison = 0;
            boolean lost = false;
            try {
                life = player.getLife();
                poison = Math.max(0, player.getPoisonCounters());
                lost = player.hasLost();
            } catch (Throwable t) {
                throw new BridgeProjectionException("public_state.life:" + pid, t);
            }
            canonical.append("player=").append(pid).append(',');
            canonical.append("life=").append(life).append(',');
            canonical.append("poison=").append(poison).append(',');
            canonical.append("lost=").append(lost).append('|');
            canonical.append("librarySize=").append(zoneSize(player, ZoneType.Library)).append('|');
            canonical.append("battlefield=").append(publicBattlefield(player, session)).append('|');
            canonical.append("graveyard=").append(zoneNamesInOrder(player, ZoneType.Graveyard))
                    .append('|');
            canonical.append("exile=").append(zoneNamesInOrder(player, ZoneType.Exile)).append('|');
            canonical.append("command=").append(zoneNamesSorted(player, ZoneType.Command))
                    .append('|');
            canonical.append("commanderDamage=").append(commanderDamageRows(player)).append('|');
        }
        canonical.append("stack=").append(stackNamesInOrder(game)).append('|');
        canonical.append("over=").append(game.isGameOver()).append('|');
        return sha256Hex(canonical.toString());
    }

    /**
     * Principal observation digest: public digest plus the observer's own hand
     * names (sorted multiset) and entitled face-up identities. Opponent hands
     * contribute counts only; libraries contribute sizes only. Computed over the
     * observer's principal-scoped view; only the owner may hold it.
     */
    public static String principalObservationDigest(final BridgeSession session,
            final String observerPlayerId) {
        if (session == null || observerPlayerId == null) {
            throw new BridgeProjectionException("observation", "null input");
        }
        final Player observer = session.playerById(observerPlayerId);
        if (observer == null) {
            throw new BridgeProjectionException("observation", "unknown observer");
        }
        final StringBuilder canonical = new StringBuilder();
        canonical.append("public=").append(publicStateDigest(session)).append('|');
        canonical.append("observer=").append(observerPlayerId).append('|');
        final List<Player> roster = session.registryPlayers();
        for (Player player : roster) {
            final String pid = session.playerIdOf(player);
            if (pid.equals(observerPlayerId)) {
                canonical.append("hand:").append(pid).append('=')
                        .append(ownHandSorted(player, observer)).append('|');
            } else {
                int count = 0;
                try {
                    count = player.getCardsIn(ZoneType.Hand).size();
                } catch (Throwable t) {
                    throw new BridgeProjectionException("observation.hand:" + pid, t);
                }
                canonical.append("hand:").append(pid).append("=hidden*").append(count).append('|');
            }
        }
        return sha256Hex(canonical.toString());
    }

    /**
     * Terminal seat outcomes sorted by seat: {seat,won,lost,left,life}.
     * won=hasWon, lost=hasLost without concession, left=conceded, life=life.
     */
    public static JsonArray terminalOutcomes(final BridgeSession session) {
        final JsonArray outcomes = new JsonArray();
        if (session == null || session.getGame() == null) {
            return outcomes;
        }
        final List<Player> roster;
        try {
            roster = session.registryPlayers();
        } catch (Throwable t) {
            throw new BridgeProjectionException("terminal", t);
        }
        final List<JsonObject> rows = new ArrayList<>();
        for (Player player : roster) {
            final JsonObject row = new JsonObject();
            final int seat;
            final String pid;
            try {
                seat = session.seatOf(player);
                pid = session.playerIdOf(player);
            } catch (Throwable t) {
                throw new BridgeProjectionException("terminal.player", t);
            }
            boolean won = false;
            boolean lost = false;
            boolean left = false;
            int life = 0;
            try {
                won = player.hasWon();
                final boolean hasLost = player.hasLost();
                final boolean conceded;
                try {
                    conceded = player.conceded();
                } catch (Throwable t) {
                    throw new BridgeProjectionException("terminal.conceded", t);
                }
                left = hasLost && conceded;
                lost = hasLost && !conceded;
                life = player.getLife();
            } catch (BridgeProjectionException e) {
                throw e;
            } catch (Throwable t) {
                throw new BridgeProjectionException("terminal.state:" + pid, t);
            }
            row.addProperty("seat", seat);
            row.addProperty("player_id", pid);
            row.addProperty("won", won);
            row.addProperty("lost", lost);
            row.addProperty("left", left);
            row.addProperty("life", life);
            rows.add(row);
        }
        rows.sort((a, b) -> Integer.compare(a.get("seat").getAsInt(),
                b.get("seat").getAsInt()));
        for (JsonObject row : rows) {
            outcomes.add(row);
        }
        return outcomes;
    }

    // ---- helpers (stable, no UUIDs/timestamps/identityHash) ----

    private static int zoneSize(final Player player, final ZoneType zone) {
        try {
            return player.getCardsIn(zone).size();
        } catch (Throwable t) {
            throw new BridgeProjectionException("zone.size:" + zone.name(), t);
        }
    }

    private static String zoneNamesInOrder(final Player player, final ZoneType zone) {
        try {
            final StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Card card : player.getCardsIn(zone)) {
                if (!first) {
                    sb.append('|');
                }
                first = false;
                sb.append(card.getName());
            }
            return sb.toString();
        } catch (Throwable t) {
            throw new BridgeProjectionException("zone:" + zone.name(), t);
        }
    }

    private static String zoneNamesSorted(final Player player, final ZoneType zone) {
        try {
            final List<String> names = new ArrayList<>();
            for (Card card : player.getCardsIn(zone)) {
                names.add(card.getName());
            }
            Collections.sort(names);
            return String.join("|", names);
        } catch (Throwable t) {
            throw new BridgeProjectionException("zone:" + zone.name(), t);
        }
    }

    private static String ownHandSorted(final Player player, final Player observer) {
        try {
            if (!observer.equals(player)) {
                throw new BridgeProjectionException("hand", "not owner");
            }
            final List<String> names = new ArrayList<>();
            for (Card card : player.getCardsIn(ZoneType.Hand)) {
                names.add(card.getName());
            }
            Collections.sort(names);
            return String.join("|", names);
        } catch (BridgeProjectionException e) {
            throw e;
        } catch (Throwable t) {
            throw new BridgeProjectionException("hand", t);
        }
    }

    private static String publicBattlefield(final Player player, final BridgeSession session) {
        try {
            final List<String> entries = new ArrayList<>();
            for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
                final StringBuilder entry = new StringBuilder();
                try {
                    entry.append(card.getName());
                } catch (Throwable t) {
                    throw new BridgeProjectionException("battlefield.name", t);
                }
                boolean tapped = false;
                try {
                    tapped = card.isTapped();
                } catch (Throwable t) {
                    throw new BridgeProjectionException("battlefield.tapped", t);
                }
                entry.append(":tap=").append(tapped);
                try {
                    entry.append(":p=").append(card.getNetPower());
                } catch (Throwable t) {
                    entry.append(":p=NA");
                }
                try {
                    entry.append(":t=").append(card.getNetToughness());
                } catch (Throwable t) {
                    entry.append(":t=NA");
                }
                try {
                    entry.append(":d=").append(card.getDamage());
                } catch (Throwable t) {
                    throw new BridgeProjectionException("battlefield.damage", t);
                }
                entry.append(":c=").append(countersSemantic(card));
                entries.add(entry.toString());
            }
            Collections.sort(entries);
            return String.join("|", entries);
        } catch (BridgeProjectionException e) {
            throw e;
        } catch (Throwable t) {
            throw new BridgeProjectionException("battlefield", t);
        }
    }

    private static String commanderDamageRows(final Player player) {
        try {
            final Map<String, Integer> sorted = new TreeMap<>();
            for (Map.Entry<Card, Integer> entry : player.getCommanderDamage()) {
                if (entry.getKey() == null) {
                    continue;
                }
                sorted.put(entry.getKey().getName(), entry.getValue());
            }
            final StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, Integer> entry : sorted.entrySet()) {
                if (!first) {
                    sb.append('|');
                }
                first = false;
                sb.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return sb.toString();
        } catch (Throwable t) {
            throw new BridgeProjectionException("commander_damage", t);
        }
    }

    private static String stackNamesInOrder(final Game game) {
        try {
            final StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (forge.game.spellability.SpellAbilityStackInstance si : game.getStack()) {
                if (!first) {
                    sb.append('|');
                }
                first = false;
                String text = si.getStackDescription();
                if (text == null || text.isEmpty()) {
                    text = "<spell>";
                }
                sb.append(redactLabel(text));
            }
            return sb.toString();
        } catch (Throwable t) {
            throw new BridgeProjectionException("stack", t);
        }
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    /**
     * Hex SHA-256 over UTF-8 bytes. Never returns unstable markers.
     */
    public static String sha256Hex(final String text) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Throwable e) {
            throw new BridgeProjectionException("sha256", e);
        }
    }

    /**
     * Canonical JSON string with sorted keys and compact separators
     * (mirrors WS218 json.dumps sort_keys/separators lineage for interop
     * documentation; digests above use the same ordering discipline).
     */
    public static String canonicalJson(final JsonObject obj) {
        final StringBuilder sb = new StringBuilder();
        appendCanonical(obj, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendCanonical(final com.google.gson.JsonElement element,
            final StringBuilder sb) {
        if (element == null || element.isJsonNull()) {
            sb.append("null");
        } else if (element.isJsonObject()) {
            final JsonObject obj = element.getAsJsonObject();
            final Map<String, com.google.gson.JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : obj.entrySet()) {
                sorted.put(entry.getKey(), entry.getValue());
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, com.google.gson.JsonElement> entry : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(entry.getKey())).append('"').append(':');
                appendCanonical(entry.getValue(), sb);
            }
            sb.append('}');
        } else if (element.isJsonArray()) {
            final JsonArray array = element.getAsJsonArray();
            sb.append('[');
            boolean first = true;
            for (com.google.gson.JsonElement item : array) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendCanonical(item, sb);
            }
            sb.append(']');
        } else if (element.isJsonPrimitive()) {
            final com.google.gson.JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                sb.append(primitive.getAsBoolean() ? "true" : "false");
            } else if (primitive.isNumber()) {
                sb.append(primitive.getAsString());
            } else {
                sb.append('"').append(escape(primitive.getAsString())).append('"');
            }
        } else {
            sb.append("null");
        }
    }

    private static String escape(final String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /** Fail-closed replay divergence (no partial mutation may continue). */
    public static final class SemanticReplayDivergence extends RuntimeException {
        private final String code;

        public SemanticReplayDivergence(final String code, final String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
