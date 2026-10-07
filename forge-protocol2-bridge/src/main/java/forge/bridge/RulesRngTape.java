package forge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The G4-K keyed Rules-RNG tape (Commander-Lab #561, decision record
 * 6005365186 and batch 6 B2b decision 6035022676): the results of the
 * engine's own library shuffles, for the orchestration channel
 * {@code get_rules_rng_tape}. Library shuffle results only; coin and die
 * values would need a forge-game Rules-Core decision and are not recorded.
 *
 * <p>A shuffle's result is the permutation it applied: for each position of
 * the shuffled library, the position that card held immediately before the
 * shuffle (the Lab's XMage counterpart, {@code XmageRulesRngResultTape}). That
 * depends only on the Rules RNG and the library size, never on which of
 * several identical cards stood where, so the same seed reproduces it in every
 * process and a different seed changes it. It leaves only as an HMAC under the
 * launch's orchestration key, whose input binds the game id, the stream, the
 * seat and the sequence number; no card name and no native id is ever in the
 * payload.
 *
 * <p><b>Hooks.</b> {@code Player.shuffle} hands the shuffled order to the
 * player's controller ({@code cheatShuffle}) after the Rules RNG has shuffled
 * and before the zone is rewritten, so the bridge controller sees both orders
 * and the Rules-RNG call count when the shuffle's randomness was consumed
 * ({@code after}). The shuffle's {@code GameEventShuffle} then confirms the
 * entry through the session's {@code ShuffleCounter}. {@code before_lower_bound} is the
 * call count at the last engine event dispatched before the shuffle: a lower
 * bound of the shuffle's first call (no engine event fires inside
 * {@code Player.shuffle} before its RNG use).
 *
 * <p><b>Fail closed.</b> Guava's {@code EventBus} logs and swallows subscriber
 * exceptions, so every hook catches {@code Throwable} and poisons the tape. A
 * poisoned tape, an unconfirmed shuffle (a controller result without its
 * event, or an event without its result) and a coordinate pair that cannot
 * contain the shuffle all make the channel refuse with a code.
 */
final class RulesRngTape {

    static final String SCHEMA = "forge-rules-rng-tape/2";
    /**
     * Schema /2 names the lower coordinate {@code before_lower_bound}: it is the
     * Rules-RNG call count at the last engine event before the shuffle, never an
     * exact call index of the shuffle's first call.
     */
    static final String BEFORE_SEMANTICS =
            "rules_rng_calls_at_last_engine_event_before_the_shuffle_lower_bound_not_exact";
    static final String AFTER_SEMANTICS =
            "rules_rng_calls_when_the_shuffled_order_reached_the_controller_exact";
    /**
     * {@code sequence} is one global, dense, zero-based counter in engine order
     * across all seats and streams, the convention of the Lab's XMage tape
     * ({@code XmageRulesRngResultTape}: {@code results.size()} over one game list).
     */
    static final String SEQUENCE_SCOPE = "global_engine_order_across_seats";
    static final String OPERATION = "LIBRARY_SHUFFLE";

    /** The stream of a seat's library shuffles; seats are named as the Lab's records name them. */
    static String stream(int seat) {
        return "library_shuffle:P" + (seat + 1);
    }

    private static final class Pending {
        final int seat;
        final long before;
        final long after;
        final List<String> permutation;

        Pending(int seat, long before, long after, List<String> permutation) {
            this.seat = seat;
            this.before = before;
            this.after = after;
            this.permutation = permutation;
        }
    }

    private static final class Entry {
        final String stream;
        final int seat;
        final int sequence;
        final long before;
        final long after;
        final List<String> permutation;

        Entry(String stream, int seat, int sequence, long before, long after, List<String> permutation) {
            this.stream = stream;
            this.seat = seat;
            this.sequence = sequence;
            this.before = before;
            this.after = after;
            this.permutation = permutation;
        }
    }

    /** A refused read; the message is a code. */
    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String code) {
            super(code);
        }
    }

    private final String gameId;
    private final List<Entry> entries = new ArrayList<>();
    private final Deque<Pending> pending = new ArrayDeque<>();
    private long lastEventCalls;
    private String poison;

    RulesRngTape(String gameId) {
        this.gameId = gameId;
    }

    /** Rules-RNG call count at an engine event (lower bound for the next shuffle's start). */
    synchronized void observeEvent(long calls) {
        if (calls > lastEventCalls) {
            lastEventCalls = calls;
        }
    }

    /**
     * The controller's view of one shuffle: the library before (the zone's
     * current order) and after (the shuffled order), by object identity.
     */
    synchronized <T> void onControllerShuffle(int seat, List<T> before, List<T> after, long callsAfter) {
        final Map<T, Integer> positions = new IdentityHashMap<>();
        for (int index = 0; index < before.size(); index++) {
            positions.put(before.get(index), index);
        }
        final List<String> permutation = new ArrayList<>(after.size());
        for (T card : after) {
            final Integer index = positions.get(card);
            if (index == null) {
                poisonLocked("SHUFFLE_ORDER_NOT_A_PERMUTATION");
                permutation.add("?");
            } else {
                permutation.add(Integer.toString(index));
            }
        }
        if (before.size() != after.size()) {
            poisonLocked("SHUFFLE_ORDER_NOT_A_PERMUTATION");
        }
        final long start = Math.min(lastEventCalls, callsAfter);
        // A shuffle of n cards consumes at least n - 1 Rules-RNG calls; a
        // narrower pair cannot contain it.
        if (callsAfter - start < Math.max(0, after.size() - 1)) {
            poisonLocked("RNG_COORDINATE_INCONSISTENT");
        }
        pending.addLast(new Pending(seat, start, callsAfter, permutation));
        lastEventCalls = callsAfter;
    }

    /** The shuffle's {@code GameEventShuffle}: confirms the oldest unconfirmed controller result. */
    synchronized void onShuffleEvent(int seat) {
        final Pending result = pending.pollFirst();
        if (result == null) {
            poisonLocked("SHUFFLE_WITHOUT_RESULT");
            return;
        }
        if (result.seat != seat) {
            poisonLocked("SHUFFLE_SEAT_MISMATCH");
            return;
        }
        entries.add(new Entry(stream(seat), seat, entries.size(), result.before, result.after,
                result.permutation));
    }

    synchronized void poison(String code) {
        poisonLocked(code);
    }

    private void poisonLocked(String code) {
        if (poison == null) {
            poison = code == null ? "RULES_RNG_TAPE_ERROR" : code;
        }
    }

    synchronized String poisonForTests() {
        return poison;
    }

    /** HMAC binding game id, stream, seat and sequence to one shuffle result. */
    static String resultDigest(String gameId, String stream, int seat, int sequence,
            int librarySize, List<String> permutation) {
        final List<String> tokens = new ArrayList<>(permutation.size() + 12);
        tokens.add(SCHEMA);
        tokens.add("game_id");
        tokens.add(gameId);
        tokens.add("stream");
        tokens.add(stream);
        tokens.add("seat");
        tokens.add(Integer.toString(seat));
        tokens.add("sequence");
        tokens.add(Integer.toString(sequence));
        tokens.add("library_size");
        tokens.add(Integer.toString(librarySize));
        tokens.add("permutation");
        tokens.addAll(permutation);
        return OrchestrationKey.digest(tokens);
    }

    /**
     * Every confirmed shuffle result, in engine order. Refuses with a code if
     * the tape is poisoned or holds an unconfirmed shuffle.
     */
    synchronized JsonArray results() {
        if (poison != null) {
            throw new Refused("RULES_RNG_TAPE_POISONED:" + poison);
        }
        if (!pending.isEmpty()) {
            throw new Refused("RULES_RNG_TAPE_INCOMPLETE");
        }
        final JsonArray array = new JsonArray();
        for (Entry entry : entries) {
            final JsonObject json = new JsonObject();
            json.addProperty("operation", OPERATION);
            json.addProperty("stream", entry.stream);
            json.addProperty("sequence", entry.sequence);
            json.addProperty("seat", entry.seat);
            json.addProperty("before_lower_bound", entry.before);
            json.addProperty("after", entry.after);
            json.addProperty("library_size", entry.permutation.size());
            json.addProperty("result_digest", resultDigest(gameId, entry.stream, entry.seat,
                    entry.sequence, entry.permutation.size(), entry.permutation));
            array.add(json);
        }
        return array;
    }

    synchronized int sizeForTests() {
        return entries.size();
    }
}
