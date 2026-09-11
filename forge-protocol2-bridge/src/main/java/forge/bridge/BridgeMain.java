package forge.bridge;

import forge.model.FModel;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Separate-process Protocol-2.0.0 bridge entry point.
 *
 * <p>stdout carries exactly one JSON response object per line. The pinned engine prints
 * diagnostics to {@code System.out} in several places, so the original stdout is captured
 * first and {@code System.out} is then pointed at stderr: engine chatter can never
 * pollute the protocol stream (hard gate F2). All bridge logging uses stderr explicitly.
 */
public final class BridgeMain {
    private static PrintStream protocolOut = System.out;

    private BridgeMain() { }

    public static void main(String[] args) {
        protocolOut = System.out;
        System.setOut(System.err);
        final long bootStart = System.nanoTime();
        System.err.println("[bridge] starting " + VersionInfo.BRIDGE_NAME + "/"
                + VersionInfo.BRIDGE_VERSION + " protocol=" + BridgeProtocol.PROTOCOL_VERSION);
        try {
            HeadlessBridgeGui.install();
            System.err.println("[bridge] assets dir: " + HeadlessBridgeGui.resolveAssetsDir());
            FModel.initialize(null, null);
        } catch (Throwable e) {
            System.err.println("[bridge] engine initialization failed: " + e);
            e.printStackTrace(System.err);
            System.exit(1);
            return;
        }
        final long bootMillis = (System.nanoTime() - bootStart) / 1_000_000L;
        System.err.println("[bridge] engine initialized in " + bootMillis + " ms");
        System.err.println("[bridge] engine_commit=" + VersionInfo.engineCommit()
                + " source=" + VersionInfo.engineCommitSource());

        final BridgeEngine engine = new BridgeEngine();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                final String response = handleLine(engine, line);
                protocolOut.println(response);
                protocolOut.flush();
                if (engine.isShutDown()) {
                    break;
                }
            }
        } catch (Throwable e) {
            System.err.println("[bridge] fatal io error: " + e);
            e.printStackTrace(System.err);
        }
        System.err.println("[bridge] exiting");
        System.exit(0);
    }

    private static String handleLine(BridgeEngine engine, String line) {
        final BridgeProtocol.Request request;
        try {
            request = BridgeProtocol.parse(line);
        } catch (BridgeProtocol.MalformedRequestException e) {
            return BridgeProtocol.error("", BridgeErrors.MALFORMED_REQUEST, e.getMessage(), 0);
        }
        try {
            return engine.dispatch(request);
        } catch (Throwable e) {
            System.err.println("[bridge] dispatch failed: " + e);
            e.printStackTrace(System.err);
            return BridgeProtocol.error(request.requestId, BridgeErrors.INTERNAL_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), 0);
        }
    }
}
