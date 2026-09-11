package forge.bridge;

import forge.game.Game;
import forge.game.Match;
import forge.game.player.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One externally driven Forge game: session state, parked decision frames, audit trail.
 *
 * <p>Locking discipline: short synchronized blocks for validation and bookkeeping only.
 * The game thread blocks in handoff queues; the protocol thread never holds the session
 * monitor while waiting. All negative controls (unknown option, wrong actor, stale
 * revision, unsupported frame) are enforced here before the engine is touched.
 */
public final class BridgeSession {
    public enum Status {
        CREATED,
        RUNNING,
        OVER,
        FAILED,
        CLOSED
    }

    /** Answer delivered to a parked engine callback. */
    public static final class FrameAnswer {
        public final DecisionFrame.Option selected;
        public final boolean aborted;

        private FrameAnswer(DecisionFrame.Option selected, boolean aborted) {
            this.selected = selected;
            this.aborted = aborted;
        }

        public static FrameAnswer select(DecisionFrame.Option selected) {
            return new FrameAnswer(selected, false);
        }

        public static FrameAnswer abort() {
            return new FrameAnswer(null, true);
        }
    }

    /** Outcome of a validated submission once the engine has settled. */
    public static final class SubmitOutcome {
        public final boolean applied;
        public final String errorCode;
        public final String errorMessage;
        public final String preStateHash;
        public final String postStateHash;
        public final boolean executionOk;

        private SubmitOutcome(boolean applied, String errorCode, String errorMessage,
                String preStateHash, String postStateHash, boolean executionOk) {
            this.applied = applied;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.preStateHash = preStateHash;
            this.postStateHash = postStateHash;
            this.executionOk = executionOk;
        }

        public static SubmitOutcome applied(String pre, String post, boolean executionOk) {
            return new SubmitOutcome(true, null, null, pre, post, executionOk);
        }

        public static SubmitOutcome rejected(String code, String message, String pre) {
            return new SubmitOutcome(false, code, message, pre, pre, false);
        }
    }

    public static final class AuditEvent {
        public final long sequence;
        public final String type;
        public final Map<String, String> details;

        AuditEvent(long sequence, String type, Map<String, String> details) {
            this.sequence = sequence;
            this.type = type;
            this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
        }
    }

    /** Thrown on the game thread when the session is aborted (shutdown) mid-park. */
    public static final class SessionAbortedException extends RuntimeException {
        SessionAbortedException(String message) {
            super(message);
        }
    }

    private final String gameId;
    private final Map<String, String> deckHandleToDeckId;
    private final List<List<String>> seatCommanderNames = new ArrayList<>();
    /**
     * R11 immutable principal registry: native Player -&gt; seat, bound once from
     * Forge's registered roster (all participants regardless of outcome) after real
     * Game construction and before execution. Never recomputed from the shrinking
     * ingame list; never falls back to display names.
     */
    private final Map<Player, Integer> seatRegistry =
            Collections.synchronizedMap(new IdentityHashMap<Player, Integer>());
    private final List<Player> registryOrder = new ArrayList<>();

    private volatile Match match;
    private volatile Game game;
    private volatile Thread gameThread;
    private volatile Status status = Status.CREATED;
    private volatile String failReason = "";
    private volatile boolean closeRequested;

    private final AtomicLong frameSeq = new AtomicLong(0);
    private final AtomicLong auditSeq = new AtomicLong(0);
    private volatile DecisionFrame currentFrame;
    private volatile BlockingQueue<FrameAnswer> currentHandoff;
    private volatile long currentHandoffRevision = -1;
    private final CountDownLatch terminalLatch = new CountDownLatch(1);

    private final List<AuditEvent> audit = new ArrayList<>();
    private volatile String lastExecutionError = "";

    public BridgeSession(String gameId, Map<String, String> deckHandleToDeckId) {
        this.gameId = gameId;
        this.deckHandleToDeckId = Collections.unmodifiableMap(new LinkedHashMap<>(deckHandleToDeckId));
    }

    public String getGameId() {
        return gameId;
    }

    public synchronized void attach(Match match, Game game) {
        this.match = match;
        this.game = game;
        seatRegistry.clear();
        registryOrder.clear();
        int seat = 0;
        for (Player player : game.getRegisteredPlayers()) {
            seatRegistry.put(player, seat);
            registryOrder.add(player);
            seat++;
        }
    }

    public synchronized void setSeatCommanderNames(List<List<String>> names) {
        seatCommanderNames.clear();
        seatCommanderNames.addAll(names);
    }

    public synchronized List<String> commanderNames(int seat) {
        if (seat < 0 || seat >= seatCommanderNames.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(seatCommanderNames.get(seat));
    }

    public Game getGame() {
        return game;
    }

    public Status getStatus() {
        return status;
    }

    public String getFailReason() {
        return failReason;
    }

    public String getLastExecutionError() {
        return lastExecutionError;
    }

    public void setLastExecutionError(String message) {
        this.lastExecutionError = message == null ? "" : message;
    }

    // ---- player identity (immutable registry) ----

    /** Stable seat from the immutable registry. Unknown players fail explicitly. */
    public int seatOf(Player player) {
        if (player == null) {
            throw new BridgeUnknownPlayerException("null player");
        }
        final Integer seat = seatRegistry.get(player);
        if (seat == null) {
            throw new BridgeUnknownPlayerException("player not in registered roster");
        }
        return seat.intValue();
    }

    /** Stable principal id. Never a display name. */
    public String playerIdOf(Player player) {
        return "p" + (seatOf(player) + 1);
    }

    public Player playerById(String playerId) {
        if (playerId == null) {
            return null;
        }
        synchronized (this) {
            for (int i = 0; i < registryOrder.size(); i++) {
                if (("p" + (i + 1)).equals(playerId)) {
                    return registryOrder.get(i);
                }
            }
        }
        return null;
    }

    /** Immutable registered roster in registration order (survives player loss). */
    public List<Player> registryPlayers() {
        synchronized (this) {
            return new ArrayList<>(registryOrder);
        }
    }

    // ---- game thread lifecycle ----

    public synchronized void launch() {
        final Match capturedMatch = match;
        final Game capturedGame = game;
        launchStarter(() -> capturedMatch.startGame(capturedGame));
    }

    /**
     * Launches the game thread with a custom starter (tests only: constructed games
     * that bypass shuffle/mulligan while keeping the real engine, cards and pipeline).
     */
    public synchronized void launchStarter(Runnable starter) {
        if (status != Status.CREATED) {
            throw new IllegalStateException("session already launched: " + status);
        }
        status = Status.RUNNING;
        gameThread = new Thread(() -> runStarter(starter), "bridge-game-" + gameId);
        gameThread.setDaemon(true);
        gameThread.start();
        audit("session_started", detail("game_id", gameId));
    }

    private void runStarter(Runnable starter) {
        try {
            starter.run();
            synchronized (this) {
                if (status == Status.RUNNING) {
                    status = Status.OVER;
                }
            }
            audit("session_over", detail("game_id", gameId));
        } catch (SessionAbortedException e) {
            synchronized (this) {
                status = Status.CLOSED;
            }
            audit("session_aborted", detail("reason", e.getMessage()));
        } catch (BridgeUnsupportedDecision e) {
            synchronized (this) {
                status = Status.FAILED;
                failReason = e.getMessage();
            }
            audit("session_failed_unsupported", detail("reason", e.getMessage()));
        } catch (Throwable e) {
            synchronized (this) {
                status = Status.FAILED;
                failReason = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            audit("session_failed", detail("reason", failReason));
        } finally {
            abortParkedFrame();
            terminalLatch.countDown();
        }
    }

    /** Interrupts the game thread and waits for termination. Never fabricates an outcome. */
    public void shutdown(long joinMillis) {
        closeRequested = true;
        abortParkedFrame();
        final Thread thread = gameThread;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            try {
                thread.join(joinMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            if (status == Status.RUNNING || status == Status.CREATED) {
                status = Status.CLOSED;
            }
        }
        audit("session_shut_down", detail("game_id", gameId));
    }

    public boolean awaitTerminal(long millis) {
        try {
            return terminalLatch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean isTerminal() {
        final Status s = status;
        return s == Status.OVER || s == Status.FAILED || s == Status.CLOSED;
    }

    // ---- decision frames ----

    /**
     * Parks a frame for the calling game thread and blocks until the protocol thread
     * delivers a validated selection or the session ends.
     */
    public FrameAnswer parkFrame(DecisionFrame.Kind kind, Player actor,
            DecisionFrame.Status frameStatus, String reason, List<DecisionFrame.Option> options) {
        final long revision = frameSeq.incrementAndGet();
        final String actorId = playerIdOf(actor);
        final int seat = seatOf(actor);
        final String preHash = StateHash.ofGame(game, this);
        final DecisionFrame frame = new DecisionFrame(revision, kind, frameStatus, reason,
                actorId, seat, options, preHash);
        final BlockingQueue<FrameAnswer> handoff = new ArrayBlockingQueue<>(1);
        synchronized (this) {
            currentFrame = frame;
            currentHandoff = handoff;
            currentHandoffRevision = revision;
        }
        audit(frameParkedEvent(kind), frameDetails(frame));
        try {
            final FrameAnswer answer = handoff.take();
            if (answer.aborted || answer.selected == null) {
                throw new SessionAbortedException("frame " + revision + " aborted");
            }
            return answer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionAbortedException("frame " + revision + " interrupted");
        }
    }

    private static String frameParkedEvent(DecisionFrame.Kind kind) {
        switch (kind) {
            case PRIORITY:
                return "priority_frame_parked";
            case MULLIGAN:
                return "mulligan_frame_parked";
            case STARTING_PLAYER:
                return "starting_player_frame_parked";
            default:
                return "frame_parked";
        }
    }

    private void abortParkedFrame() {
        final BlockingQueue<FrameAnswer> handoff;
        synchronized (this) {
            handoff = currentHandoff;
        }
        if (handoff != null) {
            handoff.offer(FrameAnswer.abort());
        }
    }

    public DecisionFrame getCurrentFrame() {
        return currentFrame;
    }

    /**
     * Validates and delivers a proposal selection. All negative controls fire here,
     * before the engine is touched: unknown game/session state, unsupported frame,
     * missing or wrong actor, missing or stale revision, unknown or consumed option.
     * Actor, option, action type and revision are all mandatory (defense in depth:
     * this boundary rejects nulls even if an upstream parser failed to enforce them).
     */
    public SubmitOutcome submit(String actorId, String optionId, String actionType, Long revision) {
        final DecisionFrame frame;
        final BlockingQueue<FrameAnswer> handoff;
        final DecisionFrame.Option option;
        final String preHash;
        // Validation and handoff under the monitor; the settle wait runs WITHOUT the
        // monitor so the game thread can park its next frame (else self-deadlock).
        synchronized (this) {
            if (actorId == null || actorId.isEmpty()) {
                return SubmitOutcome.rejected(BridgeErrors.MALFORMED_REQUEST,
                        "actor_id is required", currentHash());
            }
            if (optionId == null || optionId.isEmpty()) {
                return SubmitOutcome.rejected(BridgeErrors.UNKNOWN_OPTION,
                        "legal_action_id is required", currentHash());
            }
            if (actionType == null || actionType.isEmpty()) {
                return SubmitOutcome.rejected(BridgeErrors.MALFORMED_REQUEST,
                        "action_type is required", currentHash());
            }
            if (revision == null) {
                return SubmitOutcome.rejected(BridgeErrors.MALFORMED_REQUEST,
                        "revision is required", currentHash());
            }
            if (status == Status.CLOSED) {
                return SubmitOutcome.rejected(BridgeErrors.SESSION_CLOSED, "session is closed", currentHash());
            }
            if (status == Status.FAILED) {
                return SubmitOutcome.rejected(BridgeErrors.SESSION_FAILED,
                        "session failed: " + failReason, currentHash());
            }
            if (status == Status.OVER || (game != null && game.isGameOver())) {
                return SubmitOutcome.rejected(BridgeErrors.GAME_OVER, "game is over", currentHash());
            }
            frame = currentFrame;
            handoff = currentHandoff;
            if (frame == null || handoff == null) {
                return SubmitOutcome.rejected(BridgeErrors.NO_PENDING_DECISION,
                        "engine is not awaiting an external decision", currentHash());
            }
            if (frame.status != DecisionFrame.Status.SUPPORTED) {
                return SubmitOutcome.rejected(BridgeErrors.UNSUPPORTED_DECISION,
                        "parked decision is not representable: " + frame.reason, currentHash());
            }
            if (!actorId.equals(frame.actorPlayerId)) {
                return SubmitOutcome.rejected(BridgeErrors.WRONG_ACTOR,
                        "option belongs to " + frame.actorPlayerId, currentHash());
            }
            if (revision.longValue() != frame.revision) {
                return SubmitOutcome.rejected(BridgeErrors.STALE_REVISION,
                        "frame revision " + frame.revision + " expected, got " + revision, currentHash());
            }
            option = frame.find(optionId);
            if (option == null) {
                return SubmitOutcome.rejected(BridgeErrors.UNKNOWN_OPTION,
                        "unknown option for revision " + frame.revision, currentHash());
            }
            if (!actionType.equals(option.actionType)) {
                return SubmitOutcome.rejected(BridgeErrors.UNKNOWN_OPTION,
                        "action type " + actionType + " does not match option", currentHash());
            }
            if (!option.consume()) {
                return SubmitOutcome.rejected(BridgeErrors.UNKNOWN_OPTION,
                        "option was already consumed", currentHash());
            }
            preHash = StateHash.ofGame(game, this);
            audit("decision_submitted", submitDetails(frame, option, actorId));
            handoff.offer(FrameAnswer.select(option));
        }
        return waitForSettle(frame.revision, preHash);
    }

    private SubmitOutcome waitForSettle(long answeredRevision, String preHash) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            final DecisionFrame frame = currentFrame;
            if (frame != null && frame.revision != answeredRevision) {
                final String postHash = StateHash.ofGame(game, this);
                final boolean executionOk = lastExecutionError.isEmpty();
                audit("decision_settled", settleDetails(answeredRevision, frame.revision, preHash, postHash));
                return SubmitOutcome.applied(preHash, postHash, executionOk);
            }
            if (isTerminal() || (game != null && game.isGameOver())) {
                final String postHash = StateHash.ofGame(game, this);
                audit("decision_terminal", settleDetails(answeredRevision, -1, preHash, postHash));
                return SubmitOutcome.applied(preHash, postHash, lastExecutionError.isEmpty());
            }
            final Status s = status;
            if (s == Status.FAILED) {
                return SubmitOutcome.rejected(BridgeErrors.SESSION_FAILED,
                        "session failed: " + failReason, preHash);
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return SubmitOutcome.rejected(BridgeErrors.SUBMIT_TIMEOUT, "submit wait interrupted", preHash);
            }
        }
        return SubmitOutcome.rejected(BridgeErrors.SUBMIT_TIMEOUT,
                "engine did not reach the next decision within 15s", preHash);
    }

    private String currentHash() {
        try {
            return StateHash.ofGame(game, this);
        } catch (Throwable e) {
            return "unavailable";
        }
    }

    // ---- audit ----

    public synchronized void audit(String type, Map<String, String> details) {
        audit.add(new AuditEvent(auditSeq.incrementAndGet(), type, details));
    }

    public synchronized List<AuditEvent> auditSnapshot() {
        return new ArrayList<>(audit);
    }

    public synchronized long auditSize() {
        return auditSeq.get();
    }

    public static Map<String, String> detail(String key, String value) {
        final Map<String, String> details = new LinkedHashMap<>();
        details.put(key, value == null ? "" : value);
        return details;
    }

    private Map<String, String> frameDetails(DecisionFrame frame) {
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("revision", Long.toString(frame.revision));
        details.put("kind", frame.kind.name());
        details.put("status", frame.status.name());
        details.put("actor", frame.actorPlayerId);
        details.put("options", Integer.toString(frame.options.size()));
        details.put("pre_hash", frame.preStateHash);
        if (!frame.reason.isEmpty()) {
            details.put("reason", frame.reason);
        }
        return details;
    }

    private Map<String, String> submitDetails(DecisionFrame frame, DecisionFrame.Option option, String actorId) {
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("revision", Long.toString(frame.revision));
        details.put("actor", actorId == null ? frame.actorPlayerId : actorId);
        details.put("option", option.optionId);
        details.put("action_type", option.actionType);
        details.put("label", option.label);
        return details;
    }

    private Map<String, String> settleDetails(long answered, long next, String pre, String post) {
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("answered_revision", Long.toString(answered));
        details.put("next_revision", Long.toString(next));
        details.put("pre_hash", pre);
        details.put("post_hash", post);
        return details;
    }
}
