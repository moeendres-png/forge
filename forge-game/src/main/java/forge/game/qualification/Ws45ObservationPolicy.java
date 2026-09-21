/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011 Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package forge.game.qualification;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.IntFunction;

/**
 * WS-45 typed actor-observation policy owned by the isolated Forge rules process.
 *
 * <p>This deliberately does not retain canonical qualification JSON. The provider may translate
 * declarative input into these typed records, but qualification output is reconstructed from this
 * engine-side state and live Forge Card/Player identities. That separation is required by the
 * WS-44 v1.0.4 no-request-echo contract.</p>
 *
 * <p>Policy-only fields (channel names, honey sentinels and audit obligations) are carried as typed
 * metadata because they govern/describe the actor-view boundary rather than Magic legality. Card
 * and player referents are always native Forge IDs and are validated against the live Game.</p>
 */
public final class Ws45ObservationPolicy {
    private Ws45ObservationPolicy() {
    }

    public enum FactKind {
        KNOWN_OBJECT_IDENTITY,
        KNOWN_LIBRARY_RANGE,
        FACE_DOWN_LOOK_PERMISSION,
        TEMPORARY_PERMISSION
    }

    public record KnowledgeFact(
            FactKind kind,
            int viewerPlayerId,
            Integer objectCardId,
            Integer libraryPlayerId,
            String zone,
            String permission,
            String scope,
            Integer start,
            Integer count,
            Boolean ordered,
            Boolean persistsWhileSameExileObject,
            String beforeEvent,
            Integer controlledPlayerId,
            Integer controllerPlayerId,
            Integer permissionViewerPlayerId,
            boolean permissionViewerAllPlayers) {
    }

    public record ViewerPolicy(
            int viewerPlayerId,
            boolean channelsUnderTestPresent,
            List<String> channelsUnderTest,
            boolean honeySentinelsPresent,
            List<String> honeySentinels,
            List<String> invalidationConditions,
            boolean obligationPresent,
            String obligation,
            boolean orderedKnownInformationPresent,
            List<String> orderedKnownInformation,
            boolean permittedPublicMetadataPresent,
            List<String> permittedPublicMetadata,
            boolean prohibitedMetadataPresent,
            List<String> prohibitedMetadata,
            List<KnowledgeFact> facts) {
        public ViewerPolicy {
            channelsUnderTest = List.copyOf(channelsUnderTest);
            honeySentinels = List.copyOf(honeySentinels);
            invalidationConditions = List.copyOf(invalidationConditions);
            orderedKnownInformation = List.copyOf(orderedKnownInformation);
            permittedPublicMetadata = List.copyOf(permittedPublicMetadata);
            prohibitedMetadata = List.copyOf(prohibitedMetadata);
            facts = List.copyOf(facts);
        }
    }

    public record ObservationPolicy(String channelPolicy, List<ViewerPolicy> viewers) {
        public ObservationPolicy {
            if (channelPolicy == null) {
                throw new IllegalArgumentException("WS45_KNOWLEDGE_CHANNEL_POLICY_REQUIRED");
            }
            viewers = List.copyOf(viewers);
        }
    }

    private static final Map<Game, ObservationPolicy> STATE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static Player playerById(final Game game, final int id) {
        for (final Player player : game.getPlayers()) {
            if (player.getId() == id) {
                return player;
            }
        }
        throw new IllegalArgumentException("WS45_KNOWLEDGE_UNKNOWN_PLAYER_ID:" + id);
    }

    private static Card cardById(final Game game, final int id) {
        final Card card = game.findById(id);
        if (card == null) {
            throw new IllegalArgumentException("WS45_KNOWLEDGE_UNKNOWN_CARD_ID:" + id);
        }
        return card;
    }

    /** Install and validate typed observation state against the live Forge object graph. */
    public static void install(final Game game, final ObservationPolicy policy) {
        if (game == null || policy == null) {
            throw new IllegalArgumentException("WS45_KNOWLEDGE_GAME_AND_POLICY_REQUIRED");
        }
        final List<ViewerPolicy> validated = new ArrayList<>();
        for (final ViewerPolicy viewerPolicy : policy.viewers()) {
            final Player viewer = playerById(game, viewerPolicy.viewerPlayerId());
            final List<KnowledgeFact> facts = new ArrayList<>();
            for (final KnowledgeFact fact : viewerPolicy.facts()) {
                if (fact.viewerPlayerId() != viewer.getId()) {
                    throw new IllegalArgumentException("WS45_KNOWLEDGE_VIEWER_MISMATCH");
                }
                if (fact.objectCardId() != null) {
                    cardById(game, fact.objectCardId());
                }
                if (fact.libraryPlayerId() != null) {
                    final Player libraryPlayer = playerById(game, fact.libraryPlayerId());
                    if (fact.kind() == FactKind.KNOWN_LIBRARY_RANGE) {
                        if (fact.start() == null || fact.count() == null || fact.ordered() == null
                                || fact.start() < 0 || fact.count() < 0
                                || libraryPlayer.getZone(ZoneType.Library).size()
                                        < fact.start() + fact.count()) {
                            throw new IllegalArgumentException("WS45_KNOWLEDGE_LIBRARY_RANGE_INVALID");
                        }
                    }
                }
                if (fact.controlledPlayerId() != null) {
                    playerById(game, fact.controlledPlayerId());
                }
                if (fact.controllerPlayerId() != null) {
                    playerById(game, fact.controllerPlayerId());
                }
                if (fact.permissionViewerPlayerId() != null) {
                    playerById(game, fact.permissionViewerPlayerId());
                }
                if (fact.kind() == FactKind.FACE_DOWN_LOOK_PERMISSION) {
                    final Card card = cardById(game, required(fact.objectCardId(), "faceDownObject"));
                    card.addMayLookFaceDownExile(viewer);
                    if (!card.mayPlayerLook(viewer)) {
                        throw new IllegalStateException("WS45_NATIVE_FACE_DOWN_LOOK_NOT_APPLIED");
                    }
                } else if (fact.kind() == FactKind.TEMPORARY_PERMISSION
                        && "look_at_face_down_exile".equals(fact.permission())
                        && fact.objectCardId() != null) {
                    final Card card = cardById(game, fact.objectCardId());
                    card.addMayLookFaceDownExile(viewer);
                    if (!card.mayPlayerLook(viewer)) {
                        throw new IllegalStateException("WS45_NATIVE_TEMP_LOOK_NOT_APPLIED");
                    }
                }
                facts.add(fact);
            }
            validated.add(new ViewerPolicy(
                    viewerPolicy.viewerPlayerId(),
                    viewerPolicy.channelsUnderTestPresent(), viewerPolicy.channelsUnderTest(),
                    viewerPolicy.honeySentinelsPresent(), viewerPolicy.honeySentinels(),
                    viewerPolicy.invalidationConditions(),
                    viewerPolicy.obligationPresent(), viewerPolicy.obligation(),
                    viewerPolicy.orderedKnownInformationPresent(), viewerPolicy.orderedKnownInformation(),
                    viewerPolicy.permittedPublicMetadataPresent(), viewerPolicy.permittedPublicMetadata(),
                    viewerPolicy.prohibitedMetadataPresent(), viewerPolicy.prohibitedMetadata(),
                    facts));
        }
        STATE.put(game, new ObservationPolicy(policy.channelPolicy(), validated));
    }

    public static ObservationPolicy get(final Game game) {
        final ObservationPolicy policy = STATE.get(game);
        if (policy == null) {
            throw new IllegalStateException("WS45_KNOWLEDGE_POLICY_NOT_INSTALLED");
        }
        return policy;
    }

    public static void clear(final Game game) {
        STATE.remove(game);
    }

    /** Actor-view identity authority used by qualification provider surfaces. */
    public static boolean canPilotSeeIdentity(final Game game, final Player viewer, final Card card) {
        if (card.getView().canBeShownTo(viewer.getView()) || card.mayPlayerLook(viewer)) {
            return true;
        }
        final ObservationPolicy policy = STATE.get(game);
        if (policy == null) {
            return false;
        }
        for (final ViewerPolicy vp : policy.viewers()) {
            if (vp.viewerPlayerId() != viewer.getId()) {
                continue;
            }
            for (final KnowledgeFact fact : vp.facts()) {
                if (fact.kind() == FactKind.KNOWN_OBJECT_IDENTITY
                        && fact.objectCardId() != null
                        && fact.objectCardId() == card.getId()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Reconstruct the provider-neutral knowledge_state JSON from typed engine state. Semantic IDs
     * are supplied only as normalization functions over already validated native IDs.
     */
    public static String toContractJson(
            final Game game,
            final IntFunction<String> playerSemantic,
            final IntFunction<String> cardSemantic) {
        final ObservationPolicy policy = get(game);
        final StringBuilder out = new StringBuilder();
        out.append("{\"channel_policy\":").append(json(policy.channelPolicy()))
                .append(",\"viewer_states\":[");
        boolean firstViewer = true;
        for (final ViewerPolicy vp : policy.viewers()) {
            if (!firstViewer) out.append(',');
            firstViewer = false;
            out.append('{');
            boolean firstKey = true;
            if (vp.channelsUnderTestPresent()) {
                firstKey = field(out, firstKey, "channels_under_test", stringList(vp.channelsUnderTest()));
            }
            firstKey = field(out, firstKey, "face_down_look_permissions",
                    factArray(vp, FactKind.FACE_DOWN_LOOK_PERMISSION, playerSemantic, cardSemantic));
            if (vp.honeySentinelsPresent()) {
                firstKey = field(out, firstKey, "honey_sentinels", stringList(vp.honeySentinels()));
            }
            firstKey = field(out, firstKey, "invalidation_conditions", stringList(vp.invalidationConditions()));
            firstKey = field(out, firstKey, "known_library_ranges",
                    factArray(vp, FactKind.KNOWN_LIBRARY_RANGE, playerSemantic, cardSemantic));
            firstKey = field(out, firstKey, "known_object_identities",
                    knownObjectList(vp, cardSemantic));
            if (vp.obligationPresent()) {
                firstKey = field(out, firstKey, "obligation", json(vp.obligation()));
            }
            if (vp.orderedKnownInformationPresent()) {
                firstKey = field(out, firstKey, "ordered_known_information", stringList(vp.orderedKnownInformation()));
            }
            if (vp.permittedPublicMetadataPresent()) {
                firstKey = field(out, firstKey, "permitted_public_metadata", stringList(vp.permittedPublicMetadata()));
            }
            if (vp.prohibitedMetadataPresent()) {
                firstKey = field(out, firstKey, "prohibited_metadata", stringList(vp.prohibitedMetadata()));
            }
            firstKey = field(out, firstKey, "temporary_permissions",
                    factArray(vp, FactKind.TEMPORARY_PERMISSION, playerSemantic, cardSemantic));
            field(out, firstKey, "viewer", json(requiredSemantic(playerSemantic, vp.viewerPlayerId(), "viewer")));
            out.append('}');
        }
        return out.append("]}").toString();
    }

    private static String factArray(
            final ViewerPolicy vp,
            final FactKind kind,
            final IntFunction<String> playerSemantic,
            final IntFunction<String> cardSemantic) {
        final StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (final KnowledgeFact fact : vp.facts()) {
            if (fact.kind() != kind) continue;
            if (!first) out.append(',');
            first = false;
            out.append('{');
            boolean fk = true;
            if (kind == FactKind.FACE_DOWN_LOOK_PERMISSION) {
                fk = field(out, fk, "object", json(requiredSemantic(cardSemantic,
                        required(fact.objectCardId(), "faceDownObject"), "object")));
                if (fact.scope() != null) fk = field(out, fk, "scope", json(fact.scope()));
                field(out, fk, "viewer", json(requiredSemantic(playerSemantic, fact.viewerPlayerId(), "viewer")));
            } else if (kind == FactKind.KNOWN_LIBRARY_RANGE) {
                if (fact.beforeEvent() != null) fk = field(out, fk, "before_event", json(fact.beforeEvent()));
                fk = field(out, fk, "count", String.valueOf(required(fact.count(), "count")));
                fk = field(out, fk, "ordered", String.valueOf(required(fact.ordered(), "ordered")));
                fk = field(out, fk, "player", json(requiredSemantic(playerSemantic,
                        required(fact.libraryPlayerId(), "libraryPlayer"), "libraryPlayer")));
                fk = field(out, fk, "start", String.valueOf(required(fact.start(), "start")));
                field(out, fk, "viewer", json(requiredSemantic(playerSemantic, fact.viewerPlayerId(), "viewer")));
            } else if (kind == FactKind.TEMPORARY_PERMISSION) {
                if (fact.controlledPlayerId() != null) {
                    fk = field(out, fk, "controlled_player", json(requiredSemantic(playerSemantic,
                            fact.controlledPlayerId(), "controlledPlayer")));
                }
                if (fact.controllerPlayerId() != null) {
                    fk = field(out, fk, "controller", json(requiredSemantic(playerSemantic,
                            fact.controllerPlayerId(), "controller")));
                }
                if (fact.objectCardId() != null) {
                    fk = field(out, fk, "object", json(requiredSemantic(cardSemantic,
                            fact.objectCardId(), "object")));
                }
                if (fact.permission() != null) fk = field(out, fk, "permission", json(fact.permission()));
                if (fact.persistsWhileSameExileObject() != null) {
                    fk = field(out, fk, "persists_while_in_same_exile_object",
                            String.valueOf(fact.persistsWhileSameExileObject()));
                }
                if (fact.zone() != null) fk = field(out, fk, "zone", json(fact.zone()));
                final String permissionViewer;
                if (fact.permissionViewerAllPlayers()) {
                    permissionViewer = "ALL_PLAYERS";
                } else if (fact.permissionViewerPlayerId() != null) {
                    permissionViewer = requiredSemantic(playerSemantic,
                            fact.permissionViewerPlayerId(), "permissionViewer");
                } else {
                    permissionViewer = requiredSemantic(playerSemantic, fact.viewerPlayerId(), "viewer");
                }
                field(out, fk, "viewer", json(permissionViewer));
            }
            out.append('}');
        }
        return out.append(']').toString();
    }

    private static String knownObjectList(final ViewerPolicy vp, final IntFunction<String> cardSemantic) {
        final StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (final KnowledgeFact fact : vp.facts()) {
            if (fact.kind() != FactKind.KNOWN_OBJECT_IDENTITY) continue;
            if (!first) out.append(',');
            first = false;
            out.append(json(requiredSemantic(cardSemantic,
                    required(fact.objectCardId(), "knownObject"), "knownObject")));
        }
        return out.append(']').toString();
    }

    private static String stringList(final List<String> values) {
        final StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (final String value : values) {
            if (!first) out.append(',');
            first = false;
            out.append(json(value));
        }
        return out.append(']').toString();
    }

    private static boolean field(
            final StringBuilder out, final boolean first, final String name, final String jsonValue) {
        if (!first) out.append(',');
        out.append(json(name)).append(':').append(jsonValue);
        return false;
    }

    private static String json(final String value) {
        if (value == null) return "null";
        final StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('\"').toString();
    }

    private static String requiredSemantic(
            final IntFunction<String> resolver, final int nativeId, final String label) {
        final String value = resolver.apply(nativeId);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("WS45_SEMANTIC_ID_UNAVAILABLE:" + label + ':' + nativeId);
        }
        return value;
    }

    private static <T> T required(final T value, final String label) {
        if (value == null) throw new IllegalArgumentException("WS45_REQUIRED_FIELD:" + label);
        return value;
    }
}
