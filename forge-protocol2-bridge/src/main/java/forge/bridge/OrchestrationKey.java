package forge.bridge;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * The launch's orchestration key (Commander-Lab #441 decision (c), after the
 * AF09 precedent of the Lab's XMage Rules-RNG tape).
 *
 * <p>An orchestration channel is not an observation. It exists only when the
 * launch carries an orchestration key ({@value #KEY_VARIABLE}), which no
 * principal-facing launch carries; without one the bridge refuses the request.
 * Every hidden fact such a channel reports is an HMAC under that key, so
 * whoever does not hold the key can neither read a hidden card out of a digest
 * nor test a guess against it.</p>
 */
final class OrchestrationKey {

    static final String KEY_VARIABLE = "COMMANDER_LAB_ORCHESTRATION_KEY";

    // Loaded lazily, on the first use of a channel, never at class load: a
    // malformed variable must not affect an ordinary game.
    private static byte[] key;
    private static boolean loaded;
    private static String problem;

    private OrchestrationKey() {
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        final String hex = System.getenv(KEY_VARIABLE);
        if (hex == null || hex.trim().isEmpty()) {
            return;
        }
        final String trimmed = hex.trim();
        if (trimmed.length() % 2 != 0 || !trimmed.matches("[0-9a-fA-F]+")) {
            problem = KEY_VARIABLE + " is not hexadecimal";
            return;
        }
        final byte[] parsed = new byte[trimmed.length() / 2];
        for (int i = 0; i < parsed.length; i++) {
            parsed[i] = (byte) Integer.parseInt(trimmed.substring(2 * i, 2 * i + 2), 16);
        }
        if (parsed.length < 16) {
            problem = KEY_VARIABLE + " carries fewer than 128 bits";
            return;
        }
        key = parsed;
    }

    /** Test seam: the key a test JVM's launch would carry (never set in production code). */
    static synchronized void keyForTests(byte[] testKey) {
        loaded = true;
        problem = null;
        key = testKey == null ? null : testKey.clone();
    }

    /** True only when the launch carried a well-formed orchestration key. */
    static synchronized boolean enabled() {
        load();
        return key != null;
    }

    /** Why a key the launch carried was rejected, or null. */
    static synchronized String problem() {
        load();
        return problem;
    }

    /** HMAC-SHA-256 under the orchestration key, one token per line; refuses without a key. */
    static synchronized String digest(List<String> tokens) {
        load();
        if (key == null) {
            throw new IllegalStateException("orchestration channel not enabled for this launch");
        }
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (String token : tokens) {
                mac.update(token.getBytes(StandardCharsets.UTF_8));
                mac.update((byte) '\n');
            }
            final byte[] out = mac.doFinal();
            final StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
