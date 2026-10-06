package forge.bridge;

import forge.game.Game;
import forge.game.Match;
import com.google.common.eventbus.Subscribe;
import forge.game.card.Card;
import forge.game.event.GameEventPlayerPriority;
import forge.game.event.GameEventShuffle;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
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
        public final Long inputValue;
        public final Map<forge.game.GameEntity, Integer> dividedAllocations;

        private FrameAnswer(DecisionFrame.Option selected, boolean aborted, Long inputValue,
                Map<forge.game.GameEntity, Integer> dividedAllocations) {
            this.selected = selected;
            this.aborted = aborted;
            this.inputValue = inputValue;
            this.dividedAllocations = dividedAllocations;
        }

        public static FrameAnswer select(DecisionFrame.Option selected) {
            return new FrameAnswer(selected, false, null, null);
        }

        public static FrameAnswer abort() {
            return new FrameAnswer(null, true, null, null);
        }

        public static FrameAnswer input(DecisionFrame.Option sentinel, long value) {
            return new FrameAnswer(sentinel, false, Long.valueOf(value), null);
        }

        public static FrameAnswer divided(Map<forge.game.GameEntity, Integer> allocations) {
            return new FrameAnswer(null, false, null, allocations);
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
    // WS202: qualification seed binding (explicit native RNG control) and scenario
    // plan (native hook placement). Both set once at creation, read at launch.
    private volatile Long seedBinding;
    private volatile ScenarioBootstrap.Plan scenarioPlan;

    /** WSR30 test-only fault injection for the creation-time seed install. */
    static volatile String seedInstallFaultForTests;

    /**
     * Commander-Lab #561 G1-R1 test-only fault injection for the scenario
     * bootstrap controls. {@code null} is production. Named mutants:
     * {@code "never"} the subscriber never does the placement;
     * {@code "throw"} the subscriber's placement throws;
     * {@code "double"} the turn-one event is handled twice;
     * {@code "late"} placement stays at the retained post-untap hook (the
     * pre-G1-R1 point); {@code "tapped_at_begin"} requested tapped state is
     * applied before the untap step; {@code "counters_twice"} counters are
     * re-applied in the hook; {@code "sick_active"} the active seat's placed
     * permanents are re-marked summoning-sick after the readiness sweep;
     * {@code "ready_all"} every placed permanent is marked not summoning-sick;
     * {@code "ready_casts"} the active seat's sickness is cleared at every
     * turn-one priority. Each is
     * deterministic, set before launch and cleared by the test.
     */
    static volatile String scenarioBootstrapFaultForTests;

    /**
     * G1-R1 fail-closed latch. Guava's EventBus logs and swallows subscriber
     * exceptions, so the subscriber records failure on the session and the
     * retained start-game hook refuses to run unless the bootstrap ran exactly
     * once, for the intended game's turn one, and without error.
     */
    private final java.util.concurrent.atomic.AtomicInteger scenarioBootstrapInvocations =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile String scenarioBootstrapFailure;
    private volatile boolean scenarioBootstrapPlaced;
    private volatile List<forge.game.card.Card> scenarioPlacedCards = new ArrayList<>();

    /** WSR30: engine-readback of an accepted seed binding (never a request echo). */
    public static final class SeedAcknowledgement {
        public final long acceptedSeed;
        public final boolean explicit;

        SeedAcknowledgement(long acceptedSeed, boolean explicit) {
            this.acceptedSeed = acceptedSeed;
            this.explicit = explicit;
        }
    }

    private final AtomicLong frameSeq = new AtomicLong(0);
    private final AtomicLong auditSeq = new AtomicLong(0);
    private volatile DecisionFrame currentFrame;
    private volatile BlockingQueue<FrameAnswer> currentHandoff;
    private volatile long currentHandoffRevision = -1;
    private final CountDownLatch terminalLatch = new CountDownLatch(1);

    private final List<AuditEvent> audit = new ArrayList<>();
    private volatile String lastExecutionError = "";
    /**
     * R14B binding: which parked frame's execution produced the current error text.
     * An execution error is only ever exposed in that same frame's context to its
     * actor; anywhere else (including other actors' polls) it stays internal.
     */
    private volatile long errorFrameRevision = -1;
    private volatile String errorFrameActor;

    public BridgeSession(String gameId, Map<String, String> deckHandleToDeckId) {
        this.gameId = gameId;
        this.deckHandleToDeckId = Collections.unmodifiableMap(new LinkedHashMap<>(deckHandleToDeckId));
    }

    public String getGameId() {
        return gameId;
    }

    /** WS202: explicit seed binding for native RNG (null when uncontrolled). */
    // Commander-Lab #441 decision (c): the engine's own library shuffles per
    // player (GameEventShuffle, fired by Player.shuffle after the Rules RNG
    // shuffled), read by the orchestration channel's constructed state.
    private final Map<Integer, Integer> libraryShuffles = new ConcurrentHashMap<>();

    /** Subscribed to the game's event bus before the game thread starts. */
    public final class ShuffleCounter {
        @Subscribe
        public void onShuffle(GameEventShuffle event) {
            if (event.player() != null) {
                libraryShuffles.merge(event.player().getId(), 1, Integer::sum);
            }
        }
    }

    /** The engine's library shuffles of {@code player} so far. */
    public int libraryShuffles(Player player) {
        return libraryShuffles.getOrDefault(PlayerView.get(player).getId(), 0);
    }

    // ---- G1-R1 (#561): TurnBegan scenario bootstrap, fail-closed latch ----

    /**
     * Subscribed to the game's event bus before the game thread starts. Runs
     * synchronously at {@code PhaseHandler}'s turn-began event, before the
     * first-turn readiness sweep and before the untap step.
     */
    public final class ScenarioTurnBeganSubscriber {
        @Subscribe
        public void onTurnBegan(GameEventTurnBegan event) {
            handleScenarioTurnBegan(event);
        }
    }

    /** Test-only mutant: clears the active seat's sickness at each turn-one priority. */
    private final class ScenarioCastReadyFaultSubscriber {
        @Subscribe
        public void onPriority(GameEventPlayerPriority event) {
            try {
                if (event == null) {
                    return;
                }
                final Game current = game;
                if (current == null || current.getPhaseHandler().getTurn() != 1) {
                    return;
                }
                final Player active = current.getPhaseHandler().getPlayerTurn();
                if (active == null) {
                    return;
                }
                for (Card card : active.getCardsIn(ZoneType.Battlefield)) {
                    card.setSickness(false);
                }
            } catch (Throwable t) {
                // Deterministic mutant simulation only; it never affects production.
            }
        }
    }

    /**
     * Registers the scenario TurnBegan subscriber before the game thread
     * starts. The one-shot latch lives on this session, not in the event bus:
     * Guava logs and swallows subscriber exceptions, so only an explicit,
     * session-owned record of "exactly one successful invocation" can stop a
     * silently failed bootstrap from producing a usable game.
     */
    synchronized void installScenarioBootstrap(final Game game, final ScenarioBootstrap.Plan plan) {
        if (game == null || plan == null) {
            return;
        }
        if ("never".equals(scenarioBootstrapFaultForTests)) {
            audit("scenario_bootstrap_subscriber_skipped", detail("fault", "never"));
            return;
        }
        game.subscribeToEvents(new ScenarioTurnBeganSubscriber());
        if ("ready_casts".equals(scenarioBootstrapFaultForTests)) {
            game.subscribeToEvents(new ScenarioCastReadyFaultSubscriber());
        }
        audit("scenario_bootstrap_subscribed", detail("game_id", gameId));
    }

    /**
     * The one-shot, game-and-turn-bound bootstrap handler. Duplicate
     * invocation for the intended game's turn one fails closed; an event for a
     * different game or turn one never satisfies the latch. Every
     * {@code Throwable} is recorded on the session rather than escaping into
     * Guava's swallowing dispatcher.
     */
    void handleScenarioTurnBegan(final GameEventTurnBegan event) {
        final ScenarioBootstrap.Plan plan = scenarioPlan;
        if (event == null || plan == null) {
            return;
        }
        final String fault = scenarioBootstrapFaultForTests;
        if ("never".equals(fault)) {
            return;
        }
        try {
            // Later turns are legitimate native events, never bootstrap attempts.
            if (event.turnNumber() != 1) {
                return;
            }
            final Game intended = game;
            final Player active = intended == null ? null
                    : intended.getPhaseHandler().getPlayerTurn();
            // TrackableObject equality is (class, id), which two different games can
            // satisfy; the intended game binds by the identity of its own player view.
            if (active == null || event.turnOwner() == null
                    || PlayerView.get(active) != event.turnOwner()) {
                recordScenarioBootstrapFailure(
                        "scenario bootstrap turn-began event does not belong to the intended game");
                return;
            }
            if (scenarioBootstrapInvocations.incrementAndGet() > 1) {
                recordScenarioBootstrapFailure(
                        "scenario bootstrap invoked more than once for the intended game");
                return;
            }
            if ("throw".equals(fault)) {
                throw new IllegalStateException("injected scenario bootstrap failure");
            }
            if ("late".equals(fault)) {
                // Pre-G1-R1 mutant: the retained hook places instead.
                return;
            }
            final List<Card> placed = ScenarioBootstrap.placeBattlefield(this, intended, plan);
            scenarioPlacedCards = placed;
            if ("tapped_at_begin".equals(fault)) {
                ScenarioBootstrap.applyRequestedTapped(this, plan, placed);
            }
            if ("counters_twice".equals(fault)) {
                ScenarioBootstrap.addRequestedCounters(this, plan, placed);
            }
            if ("ready_all".equals(fault)) {
                for (Card card : placed) {
                    card.setSickness(false);
                }
            }
            scenarioBootstrapPlaced = true;
            audit("scenario_bootstrap_placed",
                    detail("turn", Integer.toString(event.turnNumber())));
            if ("double".equals(fault)) {
                handleScenarioTurnBegan(event);
            }
        } catch (Throwable t) {
            recordScenarioBootstrapFailure("scenario bootstrap failed: " + t);
        }
    }

    private void recordScenarioBootstrapFailure(final String reason) {
        if (scenarioBootstrapFailure == null) {
            scenarioBootstrapFailure = reason;
        }
        audit("scenario_bootstrap_failed", detail("reason", reason));
    }

    /**
     * Refuses continuation unless the bootstrap ran exactly once, for the
     * intended game's turn one, with no recorded failure. This is the retained
     * start-game hook's guard: a missing, duplicated or failed invocation can
     * never lead to a usable game.
     */
    void requireScenarioBootstrapCompleted() {
        if (scenarioBootstrapFailure != null) {
            throw new IllegalStateException(scenarioBootstrapFailure);
        }
        if (!scenarioBootstrapPlaced || scenarioBootstrapInvocations.get() != 1) {
            throw new IllegalStateException(
                    "scenario bootstrap did not run exactly once before the start-game hook");
        }
    }

    /**
     * The retained start-game hook. Runs after the first-turn untap step with
     * priority still withheld: it refuses continuation unless the TurnBegan
     * bootstrap is complete, then applies requested tapped state silently and
     * the non-battlefield plan (hands, life, commander damage).
     */
    Runnable scenarioStartGameHook(final Game game, final ScenarioBootstrap.Plan plan) {
        final BridgeSession self = this;
        return () -> {
            if ("late".equals(scenarioBootstrapFaultForTests)) {
                // Pre-G1-R1 mutant: placement happens here instead of at TurnBegan.
                scenarioPlacedCards = ScenarioBootstrap.placeBattlefield(self, game, plan);
            } else {
                requireScenarioBootstrapCompleted();
            }
            ScenarioBootstrap.applyPostUntap(self, game, plan, scenarioPlacedCards);
            if ("sick_active".equals(scenarioBootstrapFaultForTests)) {
                // Mutant: the active seat's placed permanents are re-marked sick
                // after the engine's readiness sweep.
                final Player active = game == null ? null
                        : game.getPhaseHandler().getPlayerTurn();
                for (Card card : scenarioPlacedCards) {
                    if (active != null && active.equals(card.getController())) {
                        card.setSickness(true);
                    }
                }
            }
        };
    }

    /** Latch state for the fail-closed controls (test-only accessor). */
    int scenarioBootstrapInvocationsForTests() {
        return scenarioBootstrapInvocations.get();
    }

    /** Recorded failure for the fail-closed controls (test-only accessor). */
    String scenarioBootstrapFailureForTests() {
        return scenarioBootstrapFailure;
    }

    /** Placed battlefield cards from the TurnBegan handler (test-only accessor). */
    List<Card> scenarioPlacedCardsForTests() {
        return scenarioPlacedCards;
    }

    public Long getSeedBinding() {
        return seedBinding;
    }

    public synchronized void setSeedBinding(Long seed) {
        this.seedBinding = seed;
    }

    /**
     * WSR30: install the requested seed into the engine at creation and read the
     * engine's own accepted state back.
     *
     * <p>The acknowledgement a create response carries is derived from
     * {@code MyRandom.getRootSeed()/isExplicitSeed()}, never from the request
     * value: a field populated only from what the caller sent proves what was
     * asked, not what the Rules Core accepted. A readback that is not explicit,
     * null, or different from the requested seed fails closed by throwing, so
     * the create transaction cannot report a binding the engine did not take.
     *
     * <p>The authoritative pre-shuffle install for the running game still happens
     * in {@link #launch()}; this creation-time install exists so the creation
     * transaction can be acknowledged, and it is why {@link #launch()} clears a
     * stale explicit binding for unseeded sessions.
     */
    public synchronized SeedAcknowledgement installSeedBindingForCreation() {
        final Long requested = seedBinding;
        if (requested == null) {
            return null;
        }
        if ("reject".equals(seedInstallFaultForTests)) {
            throw new IllegalStateException("injected seed rejection");
        }
        final long installed = "mismatch".equals(seedInstallFaultForTests)
                ? requested.longValue() + 1
                : requested.longValue();
        try {
            forge.util.MyRandom.bindSeed(installed);
        } catch (Throwable t) {
            throw new IllegalStateException("seed install failed", t);
        }
        final Long accepted = forge.util.MyRandom.getRootSeed();
        final boolean explicit = forge.util.MyRandom.isExplicitSeed();
        if (!explicit || accepted == null || accepted.longValue() != requested.longValue()) {
            throw new IllegalStateException("engine did not accept the requested seed");
        }
        audit("seed_accepted_at_creation", detail("seed", accepted.toString()));
        return new SeedAcknowledgement(accepted.longValue(), true);
    }

    /** WS202: validated scenario plan for native hook placement (null when none). */
    public ScenarioBootstrap.Plan getScenarioPlan() {
        return scenarioPlan;
    }

    public synchronized void setScenarioPlan(ScenarioBootstrap.Plan plan) {
        this.scenarioPlan = plan;
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

    /**
     * Records an execution diagnostic, bound to the currently parked frame (the only
     * execution that can be in flight). Called from the game thread during real
     * pipeline execution; never carries anything the current frame didn't produce.
     */
    public void setLastExecutionError(String message) {
        this.lastExecutionError = message == null ? "" : message;
        final DecisionFrame frame = currentFrame;
        if (frame == null) {
            this.errorFrameRevision = -1;
            this.errorFrameActor = null;
        } else {
            this.errorFrameRevision = frame.revision;
            this.errorFrameActor = frame.actorPlayerId;
        }
    }

    /** Clears execution diagnostics for a fresh decision. Called on submit. */
    private void clearExecutionError() {
        this.lastExecutionError = "";
        this.errorFrameRevision = -1;
        this.errorFrameActor = null;
    }

    /**
     * Whether the recorded execution diagnostic belongs to the given actor's frame.
     * Only then may protocol output carry it.
     */
    public boolean isExecutionErrorBoundTo(String actorId, long revision) {
        return !lastExecutionError.isEmpty() && actorId != null
                && actorId.equals(errorFrameActor) && revision == errorFrameRevision;
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
        final Long capturedSeed = seedBinding;
        final ScenarioBootstrap.Plan capturedPlan = scenarioPlan;
        // WS202: explicit native RNG control. Installed on the protocol thread
        // before the game thread shuffles/rolls. Global MyRandom scope requires
        // single-flight seeded execution for twin determinism; concurrent seeded
        // games share the global and must be serialized by the orchestrator.
        // WS227: Core-owned explicit binding with call coordinates
        // (regenerate-not-inject; no bridge RNG, no prediction).
        if (capturedSeed != null) {
            try {
                forge.util.MyRandom.bindSeed(capturedSeed.longValue());
            } catch (Throwable t) {
                throw new IllegalStateException("seed install failed");
            }
            audit("seed_bound", detail("seed", capturedSeed.toString()));
        } else {
            // WSR30: an unseeded session must not run under a stale explicit
            // binding left by an earlier creation. Without this, an abandoned
            // seeded create would make a later "unseeded" game secretly
            // seeded while its evidence still claimed UNCONTROLLED_ENGINE_RNG.
            // Clearing makes that classification true rather than merely
            // recorded. Seeded execution stays single-flight by contract.
            forge.util.MyRandom.clearBinding();
        }
        if (capturedGame != null) {
            capturedGame.subscribeToEvents(new ShuffleCounter());
        }
        if (capturedPlan == null) {
            launchStarter(() -> capturedMatch.startGame(capturedGame));
        } else {
            // G1-R1: battlefield placement happens from the TurnBegan
            // subscriber (registered before the game thread starts); the
            // retained start-game hook only refuses-on-failure and applies the
            // post-untap plan.
            installScenarioBootstrap(capturedGame, capturedPlan);
            final Runnable scenarioHook = scenarioStartGameHook(capturedGame, capturedPlan);
            launchStarter(() -> capturedMatch.startGame(capturedGame, scenarioHook));
        }
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

    /**
     * Parks a Core-owned divided-allocation vector frame (CR 601.2d): authoritative
     * targets as opaque options plus Core-calculated total/min/UpTo constraints.
     * The pilot submits an exact vector via {@link #submitDividedAllocation};
     * native Rules/Core validates before mutation. Same revision/actor discipline.
     */
    public FrameAnswer parkDividedAllocation(Player actor,
            List<DecisionFrame.Option> targetOptions, int total, int minPerTarget,
            boolean dividedUpTo) {
        final long revision = frameSeq.incrementAndGet();
        final String actorId = playerIdOf(actor);
        final int seat = seatOf(actor);
        final String preHash = StateHash.ofGame(game, this);
        final DecisionFrame frame = new DecisionFrame(revision,
                DecisionFrame.Kind.DIVIDED_ALLOCATION, DecisionFrame.Status.SUPPORTED, "",
                actorId, seat, targetOptions, preHash, false, 0L, 0L,
                total, minPerTarget, dividedUpTo);
        final BlockingQueue<FrameAnswer> handoff = new ArrayBlockingQueue<>(1);
        synchronized (this) {
            currentFrame = frame;
            currentHandoff = handoff;
            currentHandoffRevision = revision;
        }
        audit(frameParkedEvent(DecisionFrame.Kind.DIVIDED_ALLOCATION), frameDetails(frame));
        try {
            final FrameAnswer answer = handoff.take();
            if (answer.aborted || answer.dividedAllocations == null) {
                throw new SessionAbortedException("frame " + revision + " aborted");
            }
            return answer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionAbortedException("frame " + revision + " interrupted");
        }
    }

    /**
     * Parks a validated free-integer input frame for the calling game thread:
     * unbounded engine ranges the pilot answers with any integer inside native
     * [min,max]. Same revision/actor/handoff discipline as {@link #parkFrame}.
     */
    public FrameAnswer parkFreeInput(DecisionFrame.Kind kind, Player actor, String actionType,
            String title, long min, long max, List<DecisionFrame.Option> sentinel) {
        final long revision = frameSeq.incrementAndGet();
        final String actorId = playerIdOf(actor);
        final int seat = seatOf(actor);
        final String preHash = StateHash.ofGame(game, this);
        final String reason = "integer input [" + min + ".." + max + "]";
        final DecisionFrame frame = new DecisionFrame(revision, kind, DecisionFrame.Status.SUPPORTED,
                reason, actorId, seat, sentinel, preHash, true, min, max);
        final BlockingQueue<FrameAnswer> handoff = new ArrayBlockingQueue<>(1);
        synchronized (this) {
            currentFrame = frame;
            currentHandoff = handoff;
            currentHandoffRevision = revision;
        }
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("revision", Long.toString(revision));
        details.put("kind", kind.name());
        details.put("status", DecisionFrame.Status.SUPPORTED.name());
        details.put("actor", actorId);
        details.put("options", "free-input");
        details.put("pre_hash", preHash);
        details.put("reason", reason);
        audit(frameParkedEvent(kind), details);
        try {
            final FrameAnswer answer = handoff.take();
            if (answer.aborted || (answer.selected == null && answer.inputValue == null)) {
                throw new SessionAbortedException("frame " + revision + " aborted");
            }
            return answer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionAbortedException("frame " + revision + " interrupted");
        }
    }

    private static String frameParkedEvent(DecisionFrame.Kind kind) {        switch (kind) {
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
     * before the engine is touched. R16 ordering protects private frame metadata:
     * after syntactic checks and lifecycle checks, the caller must prove actor and
     * revision BEFORE any frame-specific status, reason, count or option data is
     * exposed. Only the authenticated current actor ever sees UNSUPPORTED reasons.
     * Actor, option, action type and revision are all mandatory (defense in depth:
     * this boundary rejects nulls even if an upstream parser failed to enforce them).
     */
    public SubmitOutcome submit(String actorId, String optionId, String actionType, Long revision) {
        return submit(actorId, optionId, actionType, revision, null);
    }

    /**
     * Validates and delivers a proposal selection. All negative controls fire here,
     * before the engine is touched. R16 ordering protects private frame metadata:
     * after syntactic checks and lifecycle checks, the caller must prove actor and
     * revision BEFORE any frame-specific status, reason, count or option data is
     * exposed. Only the authenticated current actor ever sees UNSUPPORTED reasons.
     * Actor, option, action type and revision are all mandatory (defense in depth:
     * this boundary rejects nulls even if an upstream parser failed to enforce them).
     *
     * <p>Validated free-integer input frames (unbounded X / numeric ranges) take
     * a pilot-supplied {@code value} bound by native engine bounds instead of an
     * enumerated option: the value must be present and inside
     * [{@code inputMin},{@code inputMax}], and the frame answers exactly once.
     * Any {@code value} on an enumerated frame is malformed.
     */
    public SubmitOutcome submit(String actorId, String optionId, String actionType, Long revision,
            Long value) {
        final DecisionFrame frame;
        final BlockingQueue<FrameAnswer> handoff;
        final DecisionFrame.Option option;
        final String preHash;
        final FrameAnswer answer;
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
                // R14B: generic external signal only; the detailed reason stays in the
                // internal audit, stderr and test-visible session state.
                return SubmitOutcome.rejected(BridgeErrors.SESSION_FAILED,
                        "session failed", currentHash());
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
            if (!actorId.equals(frame.actorPlayerId)) {
                return SubmitOutcome.rejected(BridgeErrors.WRONG_ACTOR,
                        "option belongs to " + frame.actorPlayerId, currentHash());
            }
            if (revision.longValue() != frame.revision) {
                return SubmitOutcome.rejected(BridgeErrors.STALE_REVISION,
                        "frame revision " + frame.revision + " expected, got " + revision, currentHash());
            }
            if (frame.status != DecisionFrame.Status.SUPPORTED) {
                return SubmitOutcome.rejected(BridgeErrors.UNSUPPORTED_DECISION,
                        "parked decision is not representable: " + frame.reason, currentHash());
            }
            if (frame.kind == DecisionFrame.Kind.DIVIDED_ALLOCATION) {
                return SubmitOutcome.rejected(BridgeErrors.MALFORMED_REQUEST,
                        "divided allocation requires an exact vector for revision "
                                + frame.revision, currentHash());
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
            if (frame.freeInput) {
                if (value == null) {
                    return SubmitOutcome.rejected(BridgeErrors.MALFORMED_REQUEST,
                            "value is required for revision " + frame.revision, currentHash());
                }
                if (value.longValue() < frame.inputMin || value.longValue() > frame.inputMax) {
                    return SubmitOutcome.rejected(BridgeErrors.UNKNOWN_OPTION,
                            "value out of range for revision " + frame.revision, currentHash());
                }
                if (!frame.markAnswered()) {
                    return SubmitOutcome.rejected(BridgeErrors.UNKNOWN_OPTION,
                            "option was already consumed", currentHash());
                }
                preHash = StateHash.ofGame(game, this);
                audit("decision_submitted", submitDetails(frame, option, actorId));
                clearExecutionError();
                answer = FrameAnswer.input(option, value.longValue());
                handoff.offer(answer);
            } else {
                if (value != null) {
                    return SubmitOutcome.rejected(BridgeErrors.MALFORMED_REQUEST,
                            "value is not accepted for revision " + frame.revision, currentHash());
                }
                if (!option.consume()) {
                    return SubmitOutcome.rejected(BridgeErrors.UNKNOWN_OPTION,
                            "option was already consumed", currentHash());
                }
                preHash = StateHash.ofGame(game, this);
                audit("decision_submitted", submitDetails(frame, option, actorId));
                clearExecutionError();
                answer = FrameAnswer.select(option);
                handoff.offer(answer);
            }
        }
        return waitForSettle(frame.revision, preHash, actorId);
    }

    /**
     * Validates and delivers an exact divided-allocation vector (CR 601.2d).
     * R16 ordering matches {@link #submit}: actor/revision prove before any
     * frame-specific data. Session checks only syntactic identity (known opaque
     * target option IDs, present integer amounts); all legality (totals, minima,
     * membership, staleness of the target set) stays in Rules/Core validation
     * before mutation. Single-option submits on divided frames stay malformed.
     */
    public SubmitOutcome submitDividedAllocation(String actorId, Long revision,
            Map<String, Integer> allocationsByOptionId) {
        final DecisionFrame frame;
        final BlockingQueue<FrameAnswer> handoff;
        final String preHash;
        final FrameAnswer answer;
        synchronized (this) {
            if (actorId == null || actorId.isEmpty()) {
                return SubmitOutcome.rejected(BridgeErrors.MALFORMED_REQUEST,
                        "actor_id is required", currentHash());
            }
            if (revision == null) {
                return SubmitOutcome.rejected(BridgeErrors.MALFORMED_REQUEST,
                        "revision is required", currentHash());
            }
            if (allocationsByOptionId == null) {
                return SubmitOutcome.rejected(BridgeErrors.MALFORMED_REQUEST,
                        "allocations are required", currentHash());
            }
            if (status == Status.CLOSED) {
                return SubmitOutcome.rejected(BridgeErrors.SESSION_CLOSED, "session is closed", currentHash());
            }
            if (status == Status.FAILED) {
                return SubmitOutcome.rejected(BridgeErrors.SESSION_FAILED,
                        "session failed", currentHash());
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
            if (!actorId.equals(frame.actorPlayerId)) {
                return SubmitOutcome.rejected(BridgeErrors.WRONG_ACTOR,
                        "option belongs to " + frame.actorPlayerId, currentHash());
            }
            if (revision.longValue() != frame.revision) {
                return SubmitOutcome.rejected(BridgeErrors.STALE_REVISION,
                        "frame revision " + frame.revision + " expected, got " + revision, currentHash());
            }
            if (frame.status != DecisionFrame.Status.SUPPORTED) {
                return SubmitOutcome.rejected(BridgeErrors.UNSUPPORTED_DECISION,
                        "parked decision is not representable: " + frame.reason, currentHash());
            }
            if (frame.kind != DecisionFrame.Kind.DIVIDED_ALLOCATION) {
                return SubmitOutcome.rejected(BridgeErrors.MALFORMED_REQUEST,
                        "frame revision " + frame.revision + " is not a divided allocation",
                        currentHash());
            }
            final Map<forge.game.GameEntity, Integer> resolved = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : allocationsByOptionId.entrySet()) {
                final String targetId = entry.getKey();
                final Integer amount = entry.getValue();
                if (targetId == null || targetId.isEmpty()) {
                    return SubmitOutcome.rejected(BridgeErrors.MALFORMED_REQUEST,
                            "target identity is required for revision " + frame.revision,
                            currentHash());
                }
                if (amount == null) {
                    return SubmitOutcome.rejected(BridgeErrors.MALFORMED_REQUEST,
                            "amount is required for revision " + frame.revision, currentHash());
                }
                final DecisionFrame.Option target = frame.find(targetId);
                if (target == null) {
                    return SubmitOutcome.rejected(BridgeErrors.UNKNOWN_OPTION,
                            "unknown target for revision " + frame.revision, currentHash());
                }
                if (!"divided_allocation_target".equals(target.actionType)) {
                    return SubmitOutcome.rejected(BridgeErrors.UNKNOWN_OPTION,
                            "unknown target for revision " + frame.revision, currentHash());
                }
                if (!(target.nativePayload instanceof forge.game.GameEntity)) {
                    return SubmitOutcome.rejected(BridgeErrors.INTERNAL_ERROR,
                            "target without native binding for revision " + frame.revision,
                            currentHash());
                }
                if (resolved.containsKey(target.nativePayload)) {
                    return SubmitOutcome.rejected(BridgeErrors.MALFORMED_REQUEST,
                            "duplicate target for revision " + frame.revision, currentHash());
                }
                resolved.put((forge.game.GameEntity) target.nativePayload, amount);
            }
            if (!frame.markAnswered()) {
                return SubmitOutcome.rejected(BridgeErrors.UNKNOWN_OPTION,
                        "option was already consumed", currentHash());
            }
            preHash = StateHash.ofGame(game, this);
            final Map<String, String> details = new LinkedHashMap<>();
            details.put("revision", Long.toString(frame.revision));
            details.put("actor", actorId);
            details.put("targets", Integer.toString(resolved.size()));
            audit("divided_allocation_submitted", details);
            clearExecutionError();
            answer = FrameAnswer.divided(Collections.unmodifiableMap(resolved));
            handoff.offer(answer);
        }
        return waitForSettle(frame.revision, preHash, actorId);
    }

    /**
     * R14B deterministic settlement. Status is snapshotted per iteration and FAILED /
     * CLOSED never route through the successful terminal path: a submission whose
     * execution drove the session into FAILED is rejected SESSION_FAILED (generic
     * message; the diagnostic stays internal), and CLOSED is rejected
     * SESSION_CLOSED. Only OVER / genuine game-over settle as applied-terminal, and
     * only when neither FAILED nor CLOSED won the race. No timing dependence.
     */
    private SubmitOutcome waitForSettle(long answeredRevision, String preHash, String actorId) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            final DecisionFrame frame = currentFrame;
            if (frame != null && frame.revision != answeredRevision) {
                final String postHash = StateHash.ofGame(game, this);
                final boolean executionOk = !isExecutionErrorBoundTo(actorId, answeredRevision);
                audit("decision_settled", settleDetails(answeredRevision, frame.revision, preHash, postHash));
                return SubmitOutcome.applied(preHash, postHash, executionOk);
            }
            final Status observed = status;
            if (observed == Status.FAILED) {
                audit("decision_failed", settleDetails(answeredRevision, -1, preHash, preHash));
                return SubmitOutcome.rejected(BridgeErrors.SESSION_FAILED,
                        "session failed", preHash);
            }
            if (observed == Status.CLOSED) {
                audit("decision_closed", settleDetails(answeredRevision, -1, preHash, preHash));
                return SubmitOutcome.rejected(BridgeErrors.SESSION_CLOSED,
                        "session is closed", preHash);
            }
            if (observed == Status.OVER || (game != null && game.isGameOver())) {
                final String postHash = StateHash.ofGame(game, this);
                audit("decision_terminal", settleDetails(answeredRevision, -1, preHash, postHash));
                return SubmitOutcome.applied(preHash, postHash,
                        !isExecutionErrorBoundTo(actorId, answeredRevision));
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
