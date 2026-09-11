package forge.bridge;

/** Stable wire error codes. Every failure is explicit; nothing fails silent. */
public final class BridgeErrors {
    private BridgeErrors() { }

    public static final String PROTOCOL_VERSION_MISMATCH = "protocol_version_mismatch";
    public static final String MALFORMED_REQUEST = "malformed_request";
    public static final String UNKNOWN_MESSAGE = "unknown_message";
    public static final String ENGINE_NOT_STARTED = "engine_not_started";
    public static final String ENGINE_SHUT_DOWN = "engine_shut_down";
    public static final String UNKNOWN_DECK_HANDLE = "unknown_deck_handle";
    public static final String UNKNOWN_GAME = "unknown_game";
    public static final String SESSION_FAILED = "session_failed";
    public static final String SESSION_CLOSED = "session_closed";
    public static final String GAME_OVER = "game_over";
    public static final String NO_PENDING_DECISION = "no_pending_decision";
    public static final String STALE_REVISION = "stale_revision";
    public static final String UNKNOWN_OPTION = "unknown_option";
    public static final String WRONG_ACTOR = "wrong_actor";
    public static final String UNSUPPORTED_DECISION = "unsupported_decision";
    public static final String EXECUTION_FAILED = "execution_failed";
    public static final String SUBMIT_TIMEOUT = "submit_timeout";
    public static final String DECK_IMPORT_FAILED = "deck_import_failed";
    public static final String GAME_CREATION_FAILED = "game_creation_failed";
    public static final String SEED_UNSUPPORTED = "seed_unsupported";
    public static final String PLAYER_COUNT_UNSUPPORTED = "player_count_unsupported";
    public static final String INTERNAL_ERROR = "internal_error";
}
