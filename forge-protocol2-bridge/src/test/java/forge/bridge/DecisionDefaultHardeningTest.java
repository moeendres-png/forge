package forge.bridge;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Regression for adversarial candidate review findings that previously turned
 * discretionary/exceptional bridge paths into implicit outcomes.
 */
public class DecisionDefaultHardeningTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    @Test
    public void assistContributionFailsClosedInsteadOfDefaultingNo() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("assist-fail-closed", 2);
        try {
            final ExternalPlayerController controller = (ExternalPlayerController)
                    constructed.game.getPlayers().get(0).getController();
            final BridgeUnsupportedDecision error = Assert.expectThrows(
                    BridgeUnsupportedDecision.class,
                    () -> controller.helpPayForAssistSpell(null, null, 0, 0));
            Assert.assertTrue(error.getMessage().contains("assist payment contribution"),
                    "unexpected Assist disposition: " + error.getMessage());
        } finally {
            constructed.session.shutdown(5000);
        }
    }

    @Test
    public void unexpectedManaPaymentFailureCannotBecomeOrdinaryDecline() throws Exception {
        final Path source = productionSource();
        final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        final int start = text.indexOf("public boolean applyManaToCost");
        final int end = text.indexOf("public CostDecisionMakerBase getCostDecisionMaker", start);
        Assert.assertTrue(start >= 0 && end > start, "applyManaToCost source not found");
        final String method = text.substring(start, end);
        Assert.assertTrue(method.contains("throw unsupported(\"applyManaToCost\""),
                "unexpected native payment failures must fail closed explicitly");
        Assert.assertFalse(method.contains("catch (Throwable t) {\n            return false;"),
                "unexpected native payment failures must not look like an ordinary unpaid cost");
    }

    private static Path productionSource() {
        final String relative =
                "src/main/java/forge/bridge/ExternalPlayerController.java";
        final String basedir = System.getProperty("basedir");
        if (basedir != null) {
            final Path local = Paths.get(basedir, relative);
            if (Files.isRegularFile(local)) {
                return local;
            }
        }
        final Path module = Paths.get(relative);
        if (Files.isRegularFile(module)) {
            return module;
        }
        final Path root = Paths.get("forge-protocol2-bridge", relative);
        if (Files.isRegularFile(root)) {
            return root;
        }
        throw new AssertionError("ExternalPlayerController source not found");
    }
}
