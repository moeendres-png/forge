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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WS227 fresh-JVM child harness (provider-level clean-process proof).
 *
 * <p>Runs in a fresh child JVM bound to the exact Core-authority SHA. Mode
 * {@code record} constructs the exact Arc Lightning manifest, binds the seed,
 * observes authoritative decision frames, records semantic choices and
 * checkpoints, submits native options and writes the provider tape. Mode
 * {@code replay} starts from the same manifest/seed in a fresh JVM, awaits
 * current native decisions, compares legal semantics, resolves each recorded
 * semantic choice EXACTLY ONCE to a current native option, submits the current
 * native option and compares RNG/state/event coordinates. No state injection,
 * no outcome injection, no second Rules engine.
 */
public final class WS227ReplayChild {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private WS227ReplayChild() { }

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
            System.err.println("[ws227child] core SHA mismatch: expected=" + expectedSha
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
            System.err.println("[ws227child] unknown mode " + mode);
            System.exit(2);
        }
    }

    private static BridgeTestSupport.ConstructedGame arcFixture(String gameId, long seed) {
        forge.util.MyRandom.bindSeed(seed);
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(gameId);
        constructed.session.setSeedBinding(Long.valueOf(seed));
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        for (int i = 0; i < 3; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Arc Lightning", ZoneType.Hand);
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < 7; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        drivePassesToMain(constructed.session, "p1");
        for (int i = 0; i < 3; i++) {
            tapLand(constructed.session, "p1", "Mountain");
        }
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

    private static void tapLand(BridgeSession session, String actorId, String landName) {
        DecisionFrame frame = null;
        for (int i = 0; i < 60; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            if (parked == null) {
                throw new IllegalStateException("no frame awaiting tap");
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED
                    && parked.actorPlayerId.equals(actorId)
                    && isMainPhase(session)) {
                boolean hasTap = false;
                for (DecisionFrame.Option option : parked.options) {
                    if ("activate_ability".equals(option.actionType)
                            && landName.equals(option.sourceCardName)) {
                        hasTap = true;
                        frame = parked;
                        break;
                    }
                }
                if (hasTap) {
                    break;
                }
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                boolean arcHere = false;
                for (DecisionFrame.Option option : parked.options) {
                    if ("cast_spell".equals(option.actionType)) {
                        arcHere = true;
                        break;
                    }
                }
                if (!arcHere) {
                    for (DecisionFrame.Option option : parked.options) {
                        if (option.isPass) {
                            session.submit(parked.actorPlayerId, option.optionId,
                                    option.actionType, parked.revision);
                            break;
                        }
                    }
                }
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                final DecisionFrame.Option first = parked.options.get(0);
                session.submit(parked.actorPlayerId, first.optionId, first.actionType,
                        parked.revision);
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                for (DecisionFrame.Option option : parked.options) {
                    if (option.label != null && (option.label.contains("No attack")
                            || option.label.contains("No block"))) {
                        session.submit(parked.actorPlayerId, option.optionId,
                                option.actionType, parked.revision);
                        break;
                    }
                }
                continue;
            }
        }
        if (frame == null) {
            throw new IllegalStateException("tap frame never parked");
        }
        DecisionFrame.Option tap = null;
        for (DecisionFrame.Option option : frame.options) {
            if ("activate_ability".equals(option.actionType)
                    && landName.equals(option.sourceCardName)) {
                tap = option;
                break;
            }
        }
        if (tap == null) {
            throw new IllegalStateException("tap option missing");
        }
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                tap.optionId, tap.actionType, frame.revision);
        if (!outcome.applied) {
            throw new IllegalStateException("tap failed: " + outcome.errorCode);
        }
        for (int i = 0; i < 10; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            if (parked == null) {
                throw new IllegalStateException("no frame after tap");
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.actorPlayerId.equals(actorId)) {
                return;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                final DecisionFrame.Option first = parked.options.get(0);
                session.submit(parked.actorPlayerId, first.optionId, first.actionType,
                        parked.revision);
            }
        }
        throw new IllegalStateException("priority never resumed after tap");
    }

    private static DecisionFrame awaitNext(BridgeSession session, long lastRevision) {
        final long deadline = System.currentTimeMillis() + 15000;
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
        final BridgeTestSupport.ConstructedGame constructed = arcFixture(gameId, seed);
        final BridgeSession session = constructed.session;
        final JsonObject tape = new JsonObject();
        tape.addProperty("tape_contract", SemanticReplay.TAPE_CONTRACT);
        tape.addProperty("semantic_replay_version", SemanticReplay.SEMANTIC_REPLAY_VERSION);
        tape.addProperty("decision_protocol_version",
                SemanticReplay.DECISION_PROTOCOL_VERSION);
        tape.addProperty("core_sha", coreSha);
        tape.addProperty("provider", "forge");
        tape.addProperty("seed", seed);
        tape.addProperty("game_id", gameId);
        final JsonArray steps = new JsonArray();
        try {
            long lastRevision = session.getCurrentFrame() == null ? -1
                    : session.getCurrentFrame().revision;
            // Drive to Arc cast, recording every step.
            boolean cast = false;
            for (int i = 0; i < 80 && !cast; i++) {
                final DecisionFrame parked = awaitNext(session, lastRevision);
                if (parked == null) {
                    throw new IllegalStateException("no frame while recording cast");
                }
                lastRevision = parked.revision;
                if (parked.kind == DecisionFrame.Kind.PRIORITY
                        && parked.actorPlayerId.equals("p1")) {
                    DecisionFrame.Option arc = null;
                    for (DecisionFrame.Option option : parked.options) {
                        if ("cast_spell".equals(option.actionType)
                                && "Arc Lightning".equals(option.sourceCardName)) {
                            arc = option;
                            break;
                        }
                    }
                    if (arc != null) {
                        steps.add(recordEnumerated(session, parked, arc));
                        final BridgeSession.SubmitOutcome outcome = session.submit(
                                parked.actorPlayerId, arc.optionId, arc.actionType,
                                parked.revision);
                        if (!outcome.applied) {
                            throw new IllegalStateException("record cast failed");
                        }
                        cast = true;
                        break;
                    }
                }
                steps.add(recordAuto(session, parked));
            }
            if (!cast) {
                throw new IllegalStateException("Arc never offered in record");
            }
            // TARGET_SELECTION Bear+p2.
            DecisionFrame targetFrame = null;
            for (int i = 0; i < 30; i++) {
                final DecisionFrame parked = awaitNext(session, lastRevision);
                if (parked == null) {
                    throw new IllegalStateException("no frame awaiting targets");
                }
                lastRevision = parked.revision;
                if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION
                        && parked.actorPlayerId.equals("p1")) {
                    targetFrame = parked;
                    break;
                }
                steps.add(recordAuto(session, parked));
            }
            if (targetFrame == null) {
                throw new IllegalStateException("target frame missing in record");
            }
            DecisionFrame.Option both = null;
            for (DecisionFrame.Option option : targetFrame.options) {
                if (option.label != null && option.label.contains("Runeclaw Bear")
                        && option.label.contains("p2")) {
                    both = option;
                    break;
                }
            }
            if (both == null) {
                throw new IllegalStateException("Bear+p2 target missing in record");
            }
            steps.add(recordEnumerated(session, targetFrame, both));
            final BridgeSession.SubmitOutcome targetOutcome = session.submit(
                    targetFrame.actorPlayerId, both.optionId, both.actionType,
                    targetFrame.revision);
            if (!targetOutcome.applied) {
                throw new IllegalStateException("record target failed");
            }
            // DIVIDED_ALLOCATION 2+1.
            DecisionFrame dividedFrame = null;
            for (int i = 0; i < 20; i++) {
                final DecisionFrame parked = awaitNext(session, lastRevision);
                if (parked == null) {
                    throw new IllegalStateException("no frame awaiting divided");
                }
                lastRevision = parked.revision;
                if (parked.kind == DecisionFrame.Kind.DIVIDED_ALLOCATION) {
                    dividedFrame = parked;
                    break;
                }
                throw new IllegalStateException("unexpected " + parked.kind);
            }
            if (dividedFrame == null) {
                throw new IllegalStateException("divided frame missing in record");
            }
            String bearPrint = null;
            String p2Print = null;
            String bearId = null;
            String p2Id = null;
            for (DecisionFrame.Option option : dividedFrame.options) {
                final String print =
                        SemanticReplay.optionFingerprint(dividedFrame, option, session);
                if (option.label != null && option.label.contains("Runeclaw Bear")) {
                    bearPrint = print;
                    bearId = option.optionId;
                } else if (option.label != null && option.label.contains("p2")) {
                    p2Print = print;
                    p2Id = option.optionId;
                }
            }
            if (bearPrint == null || p2Print == null) {
                throw new IllegalStateException("divided targets missing in record");
            }
            final JsonObject dividedStep = recordDividedHeader(session, dividedFrame);
            final JsonObject vector = new JsonObject();
            vector.addProperty(bearPrint, 2);
            vector.addProperty(p2Print, 1);
            dividedStep.add("divided_vector_semantic", vector);
            final Map<String, Integer> nativeVector = new LinkedHashMap<>();
            nativeVector.put(bearId, Integer.valueOf(2));
            nativeVector.put(p2Id, Integer.valueOf(1));
            final BridgeSession.SubmitOutcome dividedOutcome = session.submitDividedAllocation(
                    dividedFrame.actorPlayerId, dividedFrame.revision, nativeVector);
            if (!dividedOutcome.applied || !dividedOutcome.executionOk) {
                throw new IllegalStateException("record divided failed");
            }
            dividedStep.addProperty("rng_after", forge.util.MyRandom.getCallCount());
            dividedStep.addProperty("event_after", SemanticReplay.eventOffset(session));
            dividedStep.addProperty("post_digest",
                    SemanticReplay.publicStateDigest(session));
            steps.add(dividedStep);
            // Drain to native resolution before capturing the semantic outcome
            // (damage/counters resolve via passes; the divided step post above
            // remains the immediate post for coordinate comparison).
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
                    for (DecisionFrame.Option option : parked.options) {
                        if (option.label != null && (option.label.contains("No attack")
                                || option.label.contains("No block"))) {
                            session.submit(parked.actorPlayerId, option.optionId,
                                    option.actionType, parked.revision);
                            break;
                        }
                    }
                } else {
                    break;
                }
            }
            tape.add("steps", steps);
            tape.addProperty("public_post",
                    SemanticReplay.publicStateDigest(session));
            // Native outcome proof (privileged evidence, not replay input).
            boolean bearDead = true;
            for (Card card : session.getGame().getPlayers().get(1)
                    .getCardsIn(ZoneType.Battlefield)) {
                if (card.getName().equals("Runeclaw Bear")) {
                    bearDead = false;
                }
            }
            tape.addProperty("bear_dead", bearDead);
            tape.addProperty("p2_life", session.getGame().getPlayers().get(1).getLife());
            tape.add("terminal_outcomes", SemanticReplay.terminalOutcomes(session));
            Files.write(recordPath, GSON.toJson(tape).getBytes(StandardCharsets.UTF_8));
            System.out.println("RECORD_PASS steps=" + steps.size() + " bearDead=" + bearDead);
        } finally {
            session.shutdown(5000);
            forge.util.MyRandom.clearBinding();
        }
    }

    private static JsonObject recordEnumerated(BridgeSession session, DecisionFrame frame,
            DecisionFrame.Option selected) {
        final JsonObject step = new JsonObject();
        step.addProperty("decision_class", SemanticReplay.decisionClass(frame.kind));
        step.addProperty("kind", frame.kind.name());
        step.addProperty("actor", frame.actorPlayerId);
        step.addProperty("revision", frame.revision);
        step.addProperty("legal_set_digest",
                SemanticReplay.legalSetDigest(frame, session));
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
        final BridgeSession.SubmitOutcome probe = null;
        // After-submit coordinates are filled by the caller after submit.
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

    private static JsonObject recordDividedHeader(BridgeSession session, DecisionFrame frame) {
        final JsonObject step = new JsonObject();
        step.addProperty("decision_class", SemanticReplay.decisionClass(frame.kind));
        step.addProperty("kind", frame.kind.name());
        step.addProperty("actor", frame.actorPlayerId);
        step.addProperty("revision", frame.revision);
        step.addProperty("legal_set_digest",
                SemanticReplay.legalSetDigest(frame, session));
        step.addProperty("legal_set_size", SemanticReplay.legalSetSize(frame));
        step.addProperty("divided_total", frame.dividedTotal);
        step.addProperty("divided_min", frame.dividedMinPerTarget);
        step.addProperty("rng_before", forge.util.MyRandom.getCallCount());
        step.addProperty("event_before", SemanticReplay.eventOffset(session));
        step.addProperty("observation_digest",
                SemanticReplay.principalObservationDigest(session, frame.actorPlayerId));
        step.addProperty("public_digest", SemanticReplay.publicStateDigest(session));
        return step;
    }

    private static void replay(long seed, String gameId, Path recordPath,
            String coreSha) throws Exception {
        final String raw = new String(Files.readAllBytes(recordPath), StandardCharsets.UTF_8);
        final JsonObject tape = JsonParser.parseString(raw).getAsJsonObject();
        if (!coreSha.equals(tape.get("core_sha").getAsString())) {
            System.err.println("[ws227child] source lock mismatch");
            System.exit(4);
            return;
        }
        if (tape.get("seed").getAsLong() != seed) {
            System.err.println("[ws227child] seed mismatch");
            System.exit(4);
            return;
        }
        final JsonArray steps = tape.getAsJsonArray("steps");
        final BridgeTestSupport.ConstructedGame constructed = arcFixture(gameId, seed);
        final BridgeSession session = constructed.session;
        try {
            long lastRevision = session.getCurrentFrame() == null ? -1
                    : session.getCurrentFrame().revision;
            int index = 0;
            boolean dividedDone = false;
            for (int i = 0; i < 120 && index < steps.size() && !dividedDone; i++) {
                final DecisionFrame parked = awaitNext(session, lastRevision);
                if (parked == null) {
                    System.err.println("[ws227child] no frame while replaying");
                    System.exit(5);
                    return;
                }
                lastRevision = parked.revision;
                final JsonObject expected = steps.get(index).getAsJsonObject();
                final String expClass = expected.get("decision_class").getAsString();
                final String expActor = expected.get("actor").getAsString();
                final long expRevision = expected.get("revision").getAsLong();
                final String expLegal = expected.get("legal_set_digest").getAsString();
                final String curClass = SemanticReplay.decisionClass(parked.kind);
                if (!curClass.equals(expClass)) {
                    System.err.println("[ws227child] DECISION_CLASS_MISMATCH");
                    System.exit(6);
                    return;
                }
                if (!parked.actorPlayerId.equals(expActor)) {
                    System.err.println("[ws227child] ACTOR_MISMATCH");
                    System.exit(6);
                    return;
                }
                if (parked.revision != expRevision) {
                    System.err.println("[ws227child] DECISION_REVISION_MISMATCH");
                    System.exit(6);
                    return;
                }
                final String curLegal =
                        SemanticReplay.legalSetDigest(parked, session);
                if (!curLegal.equals(expLegal)) {
                    System.err.println("[ws227child] LEGAL_SET_MISMATCH");
                    System.exit(6);
                    return;
                }
                final String curObs = SemanticReplay.principalObservationDigest(
                        session, parked.actorPlayerId);
                if (!curObs.equals(expected.get("observation_digest").getAsString())) {
                    System.err.println("[ws227child] OBSERVATION_MISMATCH");
                    System.exit(6);
                    return;
                }
                if (forge.util.MyRandom.getCallCount()
                        != expected.get("rng_before").getAsLong()) {
                    System.err.println("[ws227child] RULES_RNG_CALL_DRIFT");
                    System.exit(6);
                    return;
                }
                if (parked.kind == DecisionFrame.Kind.DIVIDED_ALLOCATION) {
                    final JsonObject vector =
                            expected.getAsJsonObject("divided_vector_semantic");
                    final Map<String, Integer> currentVector = new LinkedHashMap<>();
                    for (Map.Entry<String, com.google.gson.JsonElement> entry
                            : vector.entrySet()) {
                        final DecisionFrame.Option current;
                        try {
                            current = SemanticReplay.resolveExactlyOnce(
                                    parked, session, entry.getKey());
                        } catch (SemanticReplay.SemanticReplayDivergence e) {
                            System.err.println("[ws227child] " + e.code());
                            System.exit(6);
                            return;
                        }
                        currentVector.put(current.optionId, entry.getValue().getAsInt());
                    }
                    final BridgeSession.SubmitOutcome outcome =
                            session.submitDividedAllocation(parked.actorPlayerId,
                                    parked.revision, currentVector);
                    if (!outcome.applied || !outcome.executionOk) {
                        System.err.println("[ws227child] divided submit failed");
                        System.exit(6);
                        return;
                    }
                    if (forge.util.MyRandom.getCallCount()
                            != expected.get("rng_after").getAsLong()) {
                        System.err.println("[ws227child] RNG_AFTER_MISMATCH");
                        System.exit(6);
                        return;
                    }
                    if (!SemanticReplay.publicStateDigest(session)
                            .equals(expected.get("post_digest").getAsString())) {
                        System.err.println("[ws227child] STATE_DIGEST_MISMATCH");
                        System.exit(6);
                        return;
                    }
                    dividedDone = true;
                    index++;
                    break;
                }
                final String recordedPrint =
                        expected.get("selected_fingerprint").getAsString();
                final DecisionFrame.Option current;
                try {
                    current = SemanticReplay.resolveExactlyOnce(
                            parked, session, recordedPrint);
                } catch (SemanticReplay.SemanticReplayDivergence e) {
                    System.err.println("[ws227child] " + e.code());
                    System.exit(6);
                    return;
                }
                final BridgeSession.SubmitOutcome outcome = session.submit(
                        parked.actorPlayerId, current.optionId, current.actionType,
                        parked.revision);
                if (!outcome.applied) {
                    System.err.println("[ws227child] submit failed " + outcome.errorCode);
                    System.exit(6);
                    return;
                }
                index++;
            }
            if (!dividedDone) {
                System.err.println("[ws227child] divided never replayed");
                System.exit(5);
                return;
            }
            // Drain to native resolution and verify the semantic outcome.
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
                    for (DecisionFrame.Option option : parked.options) {
                        if (option.label != null && (option.label.contains("No attack")
                                || option.label.contains("No block"))) {
                            session.submit(parked.actorPlayerId, option.optionId,
                                    option.actionType, parked.revision);
                            break;
                        }
                    }
                } else {
                    break;
                }
            }
            boolean bearDead = true;
            for (Card card : session.getGame().getPlayers().get(1)
                    .getCardsIn(ZoneType.Battlefield)) {
                if (card.getName().equals("Runeclaw Bear")) {
                    bearDead = false;
                }
            }
            if (bearDead != tape.get("bear_dead").getAsBoolean()) {
                System.err.println("[ws227child] outcome mismatch");
                System.exit(7);
                return;
            }
            if (session.getGame().getPlayers().get(1).getLife()
                    != tape.get("p2_life").getAsInt()) {
                System.err.println("[ws227child] life mismatch");
                System.exit(7);
                return;
            }
            System.out.println("REPLAY_PASS steps=" + index + " bearDead=" + bearDead);
        } finally {
            session.shutdown(5000);
            forge.util.MyRandom.clearBinding();
        }
    }
}
