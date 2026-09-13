package forge.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * Principal-visible observation identity.
 *
 * <p>A digest exposed to a pilot may depend ONLY on data in that principal's
 * authorized observation and authoritative legal decision envelope. This utility
 * therefore hashes only already-sanitized projection output: the exact
 * principal-scoped game state plus that principal's decision metadata, with the
 * digest field itself excluded (no recursion).
 *
 * <p>It performs NO visibility, legality or rules decisions: no card
 * visibility checks, no legal-action enumeration, no zone legality, no costs.
 * All redaction is done beforehand by {@link StateProjection}; this class only
 * canonicalizes and hashes. It must never become a second Rules engine.
 *
 * <p>Canonical form: JSON object keys sorted lexicographically (recursively);
 * array order preserved exactly (semantic ordering is never sorted away);
 * nulls explicit. SHA-256 over UTF-8 bytes, lowercase hex.
 *
 * <p>Failure is always explicit: any hashing failure throws
 * {@link BridgeProjectionException} so callers fail closed with
 * {@code projection_failed}. A plausible fixed string is never returned.
 */
public final class ObservationDigest {
    private static final Gson PLAIN_GSON = new Gson();

    private ObservationDigest() { }

    /**
     * Digests one principal's sanitized observation.
     *
     * @param gameState the exact principal-scoped game state just emitted
     *                  (already redacted by {@link StateProjection})
     * @param metaMinusHash that principal's bridge metadata WITHOUT the
     *                  {@code state_hash} entry (revision, pending decision,
     *                  session status, bound execution error)
     * @return 64 lowercase hex characters; never a sentinel
     * @throws BridgeProjectionException if canonicalization or hashing fails
     */
    public static String digestOf(JsonObject gameState, JsonObject metaMinusHash) {
        try {
            if (gameState == null) {
                throw new BridgeProjectionException("observation_digest", "missing game state");
            }
            if (metaMinusHash == null) {
                throw new BridgeProjectionException("observation_digest", "missing bridge metadata");
            }
            if (metaMinusHash.has("state_hash")) {
                throw new BridgeProjectionException("observation_digest",
                        "digest field must be excluded from its own preimage");
            }
            final JsonObject preimage = new JsonObject();
            preimage.add("state", gameState.deepCopy());
            preimage.add("bridge", metaMinusHash.deepCopy());
            return sha256Hex(canonical(preimage));
        } catch (BridgeProjectionException e) {
            throw e;
        } catch (Throwable t) {
            throw new BridgeProjectionException("observation_digest", t);
        }
    }

    /**
     * Deterministic canonical form of a sanitized JSON tree. Object keys are
     * sorted; arrays keep their order; hidden content must already have been
     * removed by the authoritative projection.
     */
    public static String canonical(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonArray()) {
            final StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (JsonElement item : element.getAsJsonArray()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(canonical(item));
            }
            return sb.append(']').toString();
        }
        if (element.isJsonObject()) {
            final Map<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                sorted.put(entry.getKey(), entry.getValue());
            }
            final StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, JsonElement> entry : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(PLAIN_GSON.toJson(entry.getKey()));
                sb.append(':');
                sb.append(canonical(entry.getValue()));
            }
            return sb.append('}').toString();
        }
        return PLAIN_GSON.toJson(element);
    }

    /**
     * SHA-256 hex of UTF-8 text. Throws instead of returning a placeholder:
     * a digest that cannot be computed must fail closed, never masquerade.
     */
    public static String sha256Hex(String text) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Throwable e) {
            throw new IllegalStateException("SHA-256 unavailable for observation digest", e);
        }
    }

    /** Wire gate: only a real 64-hex digest may cross to a pilot. */
    public static boolean isHexDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
