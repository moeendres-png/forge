package forge.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.util.Map;

/**
 * Protocol-2.0.0 JSONL envelopes.
 *
 * <p>Requests accept both the canonical field names ({@code message_type}/{@code payload})
 * and the Lab compatibility aliases ({@code method}/{@code params}). Responses carry both
 * the canonical names ({@code success}/{@code status}/{@code payload}) and the legacy
 * aliases ({@code ok}/{@code result}/{@code error}) so either Lab reader accepts them.
 * stdout carries responses only; diagnostics never touch stdout.
 */
public final class BridgeProtocol {
    public static final String PROTOCOL_VERSION = "2.0.0";

    // Canonical Protocol-2 message names (engine_runtime.EngineMessageType at Lab 950d6fd6).
    public static final String START_ENGINE = "start_engine";
    public static final String GET_CAPABILITIES = "get_capabilities";
    public static final String GET_PROVIDER_VERSION = "get_provider_version";
    public static final String IMPORT_DECK = "import_deck";
    public static final String CREATE_COMMANDER_GAME = "create_commander_game";
    public static final String ADD_PLAYER = "add_player";
    public static final String START_GAME = "start_game";
    public static final String GET_GAME_STATE = "get_game_state";
    public static final String GET_LEGAL_ACTIONS = "get_legal_actions";
    public static final String SUBMIT_ACTION = "submit_action";
    public static final String PASS_PRIORITY = "pass_priority";
    public static final String SELECT_TARGETS = "select_targets";
    public static final String CHOOSE_MODES = "choose_modes";
    public static final String ORDER_TRIGGERS = "order_triggers";
    public static final String RESOLVE_MULLIGAN = "resolve_mulligan";
    public static final String CONCEDE = "concede";
    public static final String EXPORT_EVENT_LOG = "export_event_log";
    public static final String EXPORT_REPLAY = "export_replay";
    public static final String SHUTDOWN_GAME = "shutdown_game";
    public static final String SHUTDOWN_ENGINE = "shutdown_engine";

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private BridgeProtocol() { }

    public static Gson gson() {
        return GSON;
    }

    /** Parsed inbound request. Never null fields except where the wire omits them. */
    public static final class Request {
        public final String requestId;
        public final String protocolVersion;
        public final String messageType;
        public final String gameId;
        public final JsonObject payload;

        Request(String requestId, String protocolVersion, String messageType, String gameId, JsonObject payload) {
            this.requestId = requestId;
            this.protocolVersion = protocolVersion;
            this.messageType = messageType;
            this.gameId = gameId;
            this.payload = payload;
        }
    }

    /** Parses one JSONL line. Throws {@link MalformedRequestException} when not an object. */
    public static Request parse(String line) throws MalformedRequestException {
        final JsonElement element;
        try {
            element = JsonParser.parseString(line);
        } catch (Exception e) {
            throw new MalformedRequestException("line is not valid JSON: " + e.getMessage());
        }
        if (element == null || !element.isJsonObject()) {
            throw new MalformedRequestException("line is not a JSON object");
        }
        final JsonObject obj = element.getAsJsonObject();
        final String requestId = optString(obj, "request_id", "");
        if (requestId == null || requestId.isEmpty()) {
            throw new MalformedRequestException("request_id is required and must be non-empty");
        }
        final String protocolVersion = optString(obj, "protocol_version", null);
        final boolean hasType = obj.has("message_type") && !obj.get("message_type").isJsonNull();
        final boolean hasMethod = obj.has("method") && !obj.get("method").isJsonNull();
        String messageType = optString(obj, "message_type", null);
        if (messageType == null) {
            messageType = optString(obj, "method", null);
        }
        if (hasType && hasMethod && !obj.get("message_type").equals(obj.get("method"))) {
            throw new MalformedRequestException("message_type and method disagree");
        }
        final String gameId = optString(obj, "game_id", null);
        final boolean hasPayload = obj.has("payload") && !obj.get("payload").isJsonNull();
        final boolean hasParams = obj.has("params") && !obj.get("params").isJsonNull();
        JsonObject payload = optObject(obj, "payload");
        if (payload == null) {
            payload = optObject(obj, "params");
        }
        if (payload == null) {
            payload = new JsonObject();
        }
        if (hasPayload && hasParams && !obj.get("payload").equals(obj.get("params"))) {
            throw new MalformedRequestException("payload and params disagree");
        }
        return new Request(requestId, protocolVersion, messageType, gameId, payload);
    }

    public static final class MalformedRequestException extends Exception {
        MalformedRequestException(String message) {
            super(message);
        }
    }

    public static String optString(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return fallback;
        }
    }

    public static JsonObject optObject(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
            return null;
        }
        return obj.get(key).getAsJsonObject();
    }

    public static String ok(String requestId, JsonObject payload, int engineEventOffset) {
        final JsonObject response = base(requestId, true, "ok", payload, engineEventOffset);
        response.addProperty("ok", true);
        response.add("result", payload);
        return GSON.toJson(response);
    }

    public static String fail(String requestId, String status, String code, String message,
            boolean retryable, Map<String, String> details, int engineEventOffset) {
        final JsonObject payload = new JsonObject();
        final JsonObject response = base(requestId, false, status, payload, engineEventOffset);
        final JsonObject errorDetail = new JsonObject();
        errorDetail.addProperty("code", code);
        errorDetail.addProperty("message", message);
        errorDetail.addProperty("retryable", retryable);
        final JsonObject detailObj = new JsonObject();
        if (details != null) {
            for (Map.Entry<String, String> entry : details.entrySet()) {
                detailObj.addProperty(entry.getKey(), entry.getValue());
            }
        }
        errorDetail.add("details", detailObj);
        final JsonArray errors = new JsonArray();
        errors.add(errorDetail);
        response.add("errors", errors);
        response.addProperty("ok", false);
        final JsonObject legacyError = new JsonObject();
        legacyError.addProperty("code", code);
        legacyError.addProperty("message", message);
        response.add("error", legacyError);
        return GSON.toJson(response);
    }

    public static String unsupported(String requestId, String code, String message, int engineEventOffset) {
        return fail(requestId, "unsupported", code, message, false, null, engineEventOffset);
    }

    public static String error(String requestId, String code, String message, int engineEventOffset) {
        return fail(requestId, "error", code, message, false, null, engineEventOffset);
    }

    private static JsonObject base(String requestId, boolean success, String status,
            JsonObject payload, int engineEventOffset) {
        final JsonObject response = new JsonObject();
        response.addProperty("protocol_version", PROTOCOL_VERSION);
        response.addProperty("request_id", requestId == null ? "" : requestId);
        response.addProperty("timestamp", Instant.now().toString());
        response.addProperty("success", success);
        response.addProperty("status", status);
        response.add("payload", payload);
        response.add("warnings", new JsonArray());
        response.addProperty("engine_event_offset", Math.max(0, engineEventOffset));
        return response;
    }
}
