package forge.bridge;

/**
 * Thrown by {@link ExternalPlayerController} for every discretionary engine callback
 * that the external bridge does not (yet) represent.
 *
 * <p>This is the fail-closed mechanism: an unrepresented decision aborts the session
 * loudly instead of being answered by AI, GUI defaults, first-option, random choice,
 * or silent pass. It must never be caught and answered inside the bridge.
 */
public final class BridgeUnsupportedDecision extends RuntimeException {
    private final String callback;
    private final String actorPlayerId;

    public BridgeUnsupportedDecision(String callback, String actorPlayerId, String detail) {
        super("unsupported external decision: " + callback + " actor=" + actorPlayerId + " " + detail);
        this.callback = callback;
        this.actorPlayerId = actorPlayerId;
    }

    public String getCallback() {
        return callback;
    }

    public String getActorPlayerId() {
        return actorPlayerId;
    }
}
