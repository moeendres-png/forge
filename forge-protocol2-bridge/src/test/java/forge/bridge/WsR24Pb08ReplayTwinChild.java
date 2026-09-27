package forge.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * WSR24 PB-08 second-fixture clean-process twin child: {@code scry-search}.
 *
 * <p>Same reusable mechanism as {@link WS227SeparateProcessTest}/{@code WS227ReplayChild}
 * (semantic tape contract, seed-bound Rules RNG, exactly-once semantic resolution,
 * rng/event/digest coordinates, first-divergence exit codes), applied to a different
 * decision family: Opt scry (GENERIC_SELECTION look) + Evolving Wilds activation and
 * library search (SEARCH_SELECTION) + engine shuffle. One game per process, no state
 * injection, no outcome injection, no second Rules engine.
 *
 * <p>Usage: {@code record|replay seed gameId recordPath expectedCoreSha}.
 * Exit codes mirror WS227: 0 pass, 2 usage, 3 core-SHA gate, 4 tape binding,
 * 5 flow, 6 decision divergence (first divergence printed), 7 outcome mismatch.
 */
public final class WsR24Pb08ReplayTwinChild {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private WsR24Pb08ReplayTwinChild() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("usage: record|replay seed gameId recordPath expectedCoreSha");
            System.exit(2);
            return;
        }
        final String mode = args[0];
        final long seed = Long.parseLong(args[1]);
        final String gameId = args[2];
        final Path recordPath = Paths.get(args[3]);
        final String expectedSha = args[4];
        final String bound = VersionInfo.engineCommitIfValid();
        if (!expectedSha.equals(bound)) {
            System.err.println("[wsr24child] core SHA mismatch: expected=" + expectedSha
                    + " bound=" + bound + " source=" + VersionInfo.engineCommitSource());
            System.exit(3);
            return;
        }
        BridgeTestSupport.ensureEngine();
        if ("record".equals(mode)) {
            record(seed, gameId, recordPath, expectedSha);
        } else if ("replay".equals(mode)) {
            replay(seed, gameId, recordPath, expectedSha);
        } else {
            System.err.println("[wsr24child] unknown mode " + mode);
            System.exit(2);
        }
    }

    private static BridgeTestSupport.ConstructedGame scryFixture(String gameId, long seed) {
        forge.util.MyRandom.bindSeed(seed);
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(gameId, 4);
        constructed.session.setSeedBinding(Long.valueOf(seed));
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Opt", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Evolving Wilds", ZoneType.Battlefield);
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < 10; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Library);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Library);
        BridgeTestSupport.launchConstructed(constructed);
        drivePassesToMain(constructed.session, "p1");
        return constructed;
    }

    private static void drivePassesToMain(BridgeSession session, String playerId) {
        for (int i = 0; i < 12; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 30000);
            if (frame == null) {
                throw new IllegalStateException("no frame while driving to main");
            }
            if (frame.kind == DecisionFrame.Kind.PRIORITY
                    && frame.status == DecisionFrame.Status.SUPPORTED
                    && frame.actorPlayerId.equals(playerId)
                    && isMainPhase(session)) {
                return;
            }
            if (frame.kind == DecisionFrame.Kind.MULLIGAN) {
                BridgeTestSupport.submitKeep(session, frame);
            } else if (frame.status == DecisionFrame.Status.SUPPORTED) {
                BridgeTestSupport.submitPass(session, frame);
            } else {
                throw new IllegalStateException("blocked by " + frame.kind);
            }
        }
    }

    private static boolean isMainPhase(BridgeSession session) {
        try {
            final String phase = session.getGame().getPhaseHandler().getPhase().name();
            return phase.equals("MAIN1") || phase.equals("MAIN2");
        } catch (Throwable t) {
            return false;
        }
    }

    private static DecisionFrame awaitNext(BridgeSession session, long lastRevision) {
        final long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            final DecisionFrame frame = session.getCurrentFrame();
            if (frame != null && frame.revision != lastRevision) {
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

    private static void record(long seed, String gameId, Path recordPath,
            String coreSha) throws Exception {
        final BridgeTestSupport.ConstructedGame constructed = scryFixture(gameId, seed);
        final BridgeSession session = constructed.session;
        final JsonObject tape = new JsonObject();
        tape.addProperty("tape_contract", SemanticReplay.TAPE_CONTRACT);
        tape.addProperty("semantic_replay_version", SemanticReplay.SEMANTIC_REPLAY_VERSION);
        tape.addProperty("decision_protocol_version",
                SemanticReplay.DECISION_PROTOCOL_VERSION);
        tape.addProperty("core_sha", coreSha);
        tape.addProperty("provider", "forge");
        tape.addProperty("fixture", "scry-search");
        tape.addProperty("seed", seed);
        tape.addProperty("game_id", gameId);
        final JsonArray steps = new JsonArray();
        try {
            long lastRevision = session.getCurrentFrame() == null ? -1
                    : session.getCurrentFrame().revision;
            // Phase 1: drive to the Opt cast, recording every step.
            boolean cast = false;
            for (int i = 0; i < 80 && !cast; i++) {
                final DecisionFrame parked = awaitNext(session, lastRevision);
                if (parked == null) {
                    throw new IllegalStateException("no frame while recording cast");
                }
                lastRevision = parked.revision;
                if (parked.kind == DecisionFrame.Kind.PRIORITY
                        && parked.actorPlayerId.equals("p1")) {
                    DecisionFrame.Option opt = null;
                    for (DecisionFrame.Option option : parked.options) {
                        if ("cast_spell".equals(option.actionType)
                                && "Opt".equals(option.sourceCardName)) {
                            opt = option;
                            break;
                        }
                    }
                    if (opt != null) {
                        steps.add(recordEnumerated(session, parked, opt));
                        final BridgeSession.SubmitOutcome outcome = session.submit(
                                parked.actorPlayerId, opt.optionId, opt.actionType,
                                parked.revision);
                        if (!outcome.applied) {
                            throw new IllegalStateException("record cast failed");
                        }
                        fillAfter(session, steps);
                        cast = true;
                        break;
                    }
                }
                steps.add(recordAuto(session, parked));
            }
            if (!cast) {
                throw new IllegalStateException("Opt never offered in record");
            }
            // Phase 2: scry choice (first enumerated subset, deterministic).
            boolean scryed = false;
            for (int i = 0; i < 30 && !scryed; i++) {
                final DecisionFrame parked = awaitNext(session, lastRevision);
                if (parked == null) {
                    throw new IllegalStateException("no frame awaiting scry");
                }
                lastRevision = parked.revision;
                if (parked.kind == DecisionFrame.Kind.GENERIC_SELECTION
                        && parked.actorPlayerId.equals("p1")) {
                    final DecisionFrame.Option first = parked.options.get(0);
                    steps.add(recordEnumerated(session, parked, first));
                    final BridgeSession.SubmitOutcome outcome = session.submit(
                            parked.actorPlayerId, first.optionId, first.actionType,
                            parked.revision);
                    if (!outcome.applied) {
                        throw new IllegalStateException("record scry failed");
                    }
                    fillAfter(session, steps);
                    scryed = true;
                    break;
                }
                steps.add(recordAuto(session, parked));
            }
            if (!scryed) {
                throw new IllegalStateException("scry never offered in record");
            }
            // Phase 3: activate Evolving Wilds.
            boolean activated = false;
            for (int i = 0; i < 60 && !activated; i++) {
                final DecisionFrame parked = awaitNext(session, lastRevision);
                if (parked == null) {
                    throw new IllegalStateException("no frame awaiting activation");
                }
                lastRevision = parked.revision;
                if (parked.kind == DecisionFrame.Kind.PRIORITY
                        && parked.actorPlayerId.equals("p1")) {
                    DecisionFrame.Option wilds = null;
                    for (DecisionFrame.Option option : parked.options) {
                        if ("activate_ability".equals(option.actionType)
                                && "Evolving Wilds".equals(option.sourceCardName)) {
                            wilds = option;
                            break;
                        }
                    }
                    if (wilds != null) {
                        steps.add(recordEnumerated(session, parked, wilds));
                        final BridgeSession.SubmitOutcome outcome = session.submit(
                                parked.actorPlayerId, wilds.optionId, wilds.actionType,
                                parked.revision);
                        if (!outcome.applied) {
                            throw new IllegalStateException("record activation failed");
                        }
                        fillAfter(session, steps);
                        activated = true;
                        break;
                    }
                }
                steps.add(recordAuto(session, parked));
            }
            if (!activated) {
                throw new IllegalStateException("Wilds never offered in record");
            }
            // Phase 4: search choice (first enumerated basic, deterministic).
            boolean searched = false;
            for (int i = 0; i < 40 && !searched; i++) {
                final DecisionFrame parked = awaitNext(session, lastRevision);
                if (parked == null) {
                    throw new IllegalStateException("no frame awaiting search");
                }
                lastRevision = parked.revision;
                if (parked.kind == DecisionFrame.Kind.SEARCH_SELECTION
                        && parked.actorPlayerId.equals("p1")) {
                    final DecisionFrame.Option first = parked.options.get(0);
                    steps.add(recordEnumerated(session, parked, first));
                    final BridgeSession.SubmitOutcome outcome = session.submit(
                            parked.actorPlayerId, first.optionId, first.actionType,
                            parked.revision);
                    if (!outcome.applied) {
                        throw new IllegalStateException("record search failed");
                    }
                    fillAfter(session, steps);
                    searched = true;
                    break;
                }
                if (parked.kind == DecisionFrame.Kind.COST_SELECTION) {
                    final DecisionFrame.Option first = parked.options.get(0);
                    steps.add(recordEnumerated(session, parked, first));
                    final BridgeSession.SubmitOutcome outcome = session.submit(
                            parked.actorPlayerId, first.optionId, first.actionType,
                            parked.revision);
                    if (!outcome.applied) {
                        throw new IllegalStateException("record cost failed");
                    }
                    fillAfter(session, steps);
                    continue;
                }
                steps.add(recordAuto(session, parked));
            }
            if (!searched) {
                throw new IllegalStateException("search never offered in record");
            }
            drain(session);
            // Native outcome proof (privileged evidence, not replay input).
            String foundLand = null;
            for (Card card : constructed.game.getPlayers().get(0)
                    .getCardsIn(ZoneType.Battlefield)) {
                final String name = card.getName();
                if ("Plains".equals(name) || "Mountain".equals(name) || "Forest".equals(name)) {
                    foundLand = name;
                }
            }
            final int librarySize = constructed.game.getPlayers().get(0)
                    .getZone(ZoneType.Library).getCards().size();
            tape.add("steps", steps);
            tape.addProperty("found_land", foundLand == null ? "none" : foundLand);
            tape.addProperty("p1_library", librarySize);
            tape.addProperty("public_post", SemanticReplay.publicStateDigest(session));
            tape.add("terminal_outcomes", SemanticReplay.terminalOutcomes(session));
            Files.write(recordPath, GSON.toJson(tape).getBytes(StandardCharsets.UTF_8));
            System.out.println("RECORD_PASS steps=" + steps.size() + " found=" + foundLand);
        } finally {
            session.shutdown(5000);
            forge.util.MyRandom.clearBinding();
        }
    }

    private static void fillAfter(BridgeSession session, JsonArray steps) {
        final JsonObject last = steps.get(steps.size() - 1).getAsJsonObject();
        last.addProperty("rng_after", forge.util.MyRandom.getCallCount());
        last.addProperty("event_after", SemanticReplay.eventOffset(session));
        last.addProperty("post_digest", SemanticReplay.publicStateDigest(session));
    }

    private static void drain(BridgeSession session) {
        for (int i = 0; i < 40; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 5000);
            if (parked == null) {
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                DecisionFrame.Option pass = null;
                for (DecisionFrame.Option option : parked.options) {
                    if (option.isPass) {
                        pass = option;
                        break;
                    }
                }
                if (pass == null) {
                    break;
                }
                session.submit(parked.actorPlayerId, pass.optionId, pass.actionType,
                        parked.revision);
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                final DecisionFrame.Option first = parked.options.get(0);
                session.submit(parked.actorPlayerId, first.optionId, first.actionType,
                        parked.revision);
            } else if (parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                boolean answered = false;
                for (DecisionFrame.Option option : parked.options) {
                    if (option.label != null && (option.label.contains("No attack")
                            || option.label.contains("No block"))) {
                        session.submit(parked.actorPlayerId, option.optionId,
                                option.actionType, parked.revision);
                        answered = true;
                        break;
                    }
                }
                if (!answered) {
                    break;
                }
            } else {
                break;
            }
        }
    }

    private static JsonObject recordEnumerated(BridgeSession session, DecisionFrame frame,
            DecisionFrame.Option selected) {
        final JsonObject step = new JsonObject();
        step.addProperty("decision_class", SemanticReplay.decisionClass(frame.kind));
        step.addProperty("kind", frame.kind.name());
        step.addProperty("actor", frame.actorPlayerId);
        step.addProperty("revision", frame.revision);
        step.addProperty("legal_set_digest", SemanticReplay.legalSetDigest(frame, session));
        step.addProperty("legal_set_size", SemanticReplay.legalSetSize(frame));
        step.addProperty("selected_fingerprint",
                SemanticReplay.optionFingerprint(frame, selected, session));
        step.addProperty("selected_key",
                SemanticReplay.semanticKey(frame, selected, session));
        step.addProperty("rng_before", forge.util.MyRandom.getCallCount());
        step.addProperty("event_before", SemanticReplay.eventOffset(session));
        step.addProperty("observation_digest",
                SemanticReplay.principalObservationDigest(session, frame.actorPlayerId));
        step.addProperty("public_digest", SemanticReplay.publicStateDigest(session));
        return step;
    }

    private static JsonObject recordAuto(BridgeSession session, DecisionFrame frame) {
        final JsonObject step;
        final BridgeSession.SubmitOutcome outcome;
        if (frame.kind == DecisionFrame.Kind.PRIORITY) {
            DecisionFrame.Option pass = null;
            for (DecisionFrame.Option option : frame.options) {
                if (option.isPass) {
                    pass = option;
                    break;
                }
            }
            if (pass == null) {
                throw new IllegalStateException("pass missing in record auto");
            }
            step = recordEnumerated(session, frame, pass);
            outcome = session.submit(frame.actorPlayerId, pass.optionId, pass.actionType,
                    frame.revision);
        } else if (frame.kind == DecisionFrame.Kind.MANA_PAYMENT) {
            final DecisionFrame.Option first = frame.options.get(0);
            step = recordEnumerated(session, frame, first);
            outcome = session.submit(frame.actorPlayerId, first.optionId, first.actionType,
                    frame.revision);
        } else if (frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                || frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
            DecisionFrame.Option decline = null;
            for (DecisionFrame.Option option : frame.options) {
                if (option.label != null && (option.label.contains("No attack")
                        || option.label.contains("No block"))) {
                    decline = option;
                    break;
                }
            }
            if (decline == null) {
                throw new IllegalStateException("decline missing in record auto");
            }
            step = recordEnumerated(session, frame, decline);
            outcome = session.submit(frame.actorPlayerId, decline.optionId,
                    decline.actionType, frame.revision);
        } else {
            throw new IllegalStateException("unexpected auto " + frame.kind);
        }
        if (!outcome.applied) {
            throw new IllegalStateException("record auto failed: " + outcome.errorCode);
        }
        step.addProperty("rng_after", forge.util.MyRandom.getCallCount());
        step.addProperty("event_after", SemanticReplay.eventOffset(session));
        step.addProperty("post_digest", SemanticReplay.publicStateDigest(session));
        return step;
    }

    private static void replay(long seed, String gameId, Path recordPath,
            String coreSha) throws Exception {
        final String raw = new String(Files.readAllBytes(recordPath), StandardCharsets.UTF_8);
        final JsonObject tape = JsonParser.parseString(raw).getAsJsonObject();
        if (!coreSha.equals(tape.get("core_sha").getAsString())) {
            System.err.println("[wsr24child] FIRST_DIVERGENCE step=-1 field=core_sha");
            System.exit(4);
            return;
        }
        if (!"scry-search".equals(tape.get("fixture").getAsString())) {
            System.err.println("[wsr24child] FIRST_DIVERGENCE step=-1 field=fixture");
            System.exit(4);
            return;
        }
        if (tape.get("seed").getAsLong() != seed) {
            System.err.println("[wsr24child] FIRST_DIVERGENCE step=-1 field=seed");
            System.exit(4);
            return;
        }
        final JsonArray steps = tape.getAsJsonArray("steps");
        final BridgeTestSupport.ConstructedGame constructed = scryFixture(gameId, seed);
        final BridgeSession session = constructed.session;
        try {
            long lastRevision = session.getCurrentFrame() == null ? -1
                    : session.getCurrentFrame().revision;
            int index = 0;
            for (int i = 0; i < 200 && index < steps.size(); i++) {
                final DecisionFrame parked = awaitNext(session, lastRevision);
                if (parked == null) {
                    System.err.println("[wsr24child] FIRST_DIVERGENCE step=" + index
                            + " field=frame_presence actual=none");
                    System.exit(5);
                    return;
                }
                lastRevision = parked.revision;
                final JsonObject expected = steps.get(index).getAsJsonObject();
                if (!checkStep(session, parked, expected, index)) {
                    return;
                }
                final String recordedPrint =
                        expected.get("selected_fingerprint").getAsString();
                final DecisionFrame.Option current;
                try {
                    current = SemanticReplay.resolveExactlyOnce(
                            parked, session, recordedPrint);
                } catch (SemanticReplay.SemanticReplayDivergence e) {
                    System.err.println("[wsr24child] FIRST_DIVERGENCE step=" + index
                            + " field=resolve_exactly_once actual=" + e.code());
                    System.exit(6);
                    return;
                }
                final BridgeSession.SubmitOutcome outcome = session.submit(
                        parked.actorPlayerId, current.optionId, current.actionType,
                        parked.revision);
                if (!outcome.applied) {
                    System.err.println("[wsr24child] FIRST_DIVERGENCE step=" + index
                            + " field=submit actual=" + outcome.errorCode);
                    System.exit(6);
                    return;
                }
                if (forge.util.MyRandom.getCallCount()
                        != expected.get("rng_after").getAsLong()) {
                    System.err.println("[wsr24child] FIRST_DIVERGENCE step=" + index
                            + " field=rng_after");
                    System.exit(6);
                    return;
                }
                if (SemanticReplay.eventOffset(session)
                        != expected.get("event_after").getAsLong()) {
                    System.err.println("[wsr24child] FIRST_DIVERGENCE step=" + index
                            + " field=event_after");
                    System.exit(6);
                    return;
                }
                if (!SemanticReplay.publicStateDigest(session)
                        .equals(expected.get("post_digest").getAsString())) {
                    System.err.println("[wsr24child] FIRST_DIVERGENCE step=" + index
                            + " field=post_digest");
                    System.exit(6);
                    return;
                }
                index++;
            }
            if (index != steps.size()) {
                System.err.println("[wsr24child] FIRST_DIVERGENCE step=" + index
                        + " field=step_count expected=" + steps.size() + " actual=" + index);
                System.exit(5);
                return;
            }
            drain(session);
            String foundLand = null;
            for (Card card : constructed.game.getPlayers().get(0)
                    .getCardsIn(ZoneType.Battlefield)) {
                final String name = card.getName();
                if ("Plains".equals(name) || "Mountain".equals(name) || "Forest".equals(name)) {
                    foundLand = name;
                }
            }
            if (!tape.get("found_land").getAsString()
                    .equals(foundLand == null ? "none" : foundLand)) {
                System.err.println("[wsr24child] FIRST_DIVERGENCE step=end field=found_land");
                System.exit(7);
                return;
            }
            final int librarySize = constructed.game.getPlayers().get(0)
                    .getZone(ZoneType.Library).getCards().size();
            if (librarySize != tape.get("p1_library").getAsInt()) {
                System.err.println("[wsr24child] FIRST_DIVERGENCE step=end field=p1_library");
                System.exit(7);
                return;
            }
            if (!SemanticReplay.publicStateDigest(session)
                    .equals(tape.get("public_post").getAsString())) {
                System.err.println("[wsr24child] FIRST_DIVERGENCE step=end field=public_post");
                System.exit(7);
                return;
            }
            System.out.println("REPLAY_PASS steps=" + index + " found=" + foundLand);
        } finally {
            session.shutdown(5000);
            forge.util.MyRandom.clearBinding();
        }
    }

    private static boolean checkStep(BridgeSession session, DecisionFrame parked,
            JsonObject expected, int index) {
        final String expClass = expected.get("decision_class").getAsString();
        if (!SemanticReplay.decisionClass(parked.kind).equals(expClass)) {
            System.err.println("[wsr24child] FIRST_DIVERGENCE step=" + index
                    + " field=decision_class expected=" + expClass
                    + " actual=" + SemanticReplay.decisionClass(parked.kind));
            System.exit(6);
            return false;
        }
        if (!parked.actorPlayerId.equals(expected.get("actor").getAsString())) {
            System.err.println("[wsr24child] FIRST_DIVERGENCE step=" + index
                    + " field=actor");
            System.exit(6);
            return false;
        }
        if (parked.revision != expected.get("revision").getAsLong()) {
            System.err.println("[wsr24child] FIRST_DIVERGENCE step=" + index
                    + " field=revision");
            System.exit(6);
            return false;
        }
        if (!SemanticReplay.legalSetDigest(parked, session)
                .equals(expected.get("legal_set_digest").getAsString())) {
            System.err.println("[wsr24child] FIRST_DIVERGENCE step=" + index
                    + " field=legal_set_digest");
            System.exit(6);
            return false;
        }
        if (!SemanticReplay.principalObservationDigest(session, parked.actorPlayerId)
                .equals(expected.get("observation_digest").getAsString())) {
            System.err.println("[wsr24child] FIRST_DIVERGENCE step=" + index
                    + " field=observation_digest");
            System.exit(6);
            return false;
        }
        if (forge.util.MyRandom.getCallCount() != expected.get("rng_before").getAsLong()) {
            System.err.println("[wsr24child] FIRST_DIVERGENCE step=" + index
                    + " field=rng_before");
            System.exit(6);
            return false;
        }
        return true;
    }
}
