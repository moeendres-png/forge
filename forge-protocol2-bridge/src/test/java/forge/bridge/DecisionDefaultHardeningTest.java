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
    public void unexpectedPaymentFailuresCannotBecomeOrdinaryDeclines() throws Exception {
        final String text = new String(Files.readAllBytes(productionSource()), StandardCharsets.UTF_8);
        assertFailClosedMethod(text, "public boolean applyManaToCost",
                "public CostDecisionMakerBase getCostDecisionMaker", "applyManaToCost");
        assertFailClosedMethod(text, "public boolean payManaCost",
                "private boolean exileDelved", "payManaCost");
        assertFailClosedMethod(text, "private boolean exileDelved",
                "private boolean payFromPoolWithTaps", "exileDelved");
    }

    @Test
    public void optionalCategoryDeclineDoesNotSkipLaterCategories() throws Exception {
        final String text = new String(Files.readAllBytes(productionSource()), StandardCharsets.UTF_8);
        final int start = text.indexOf("public CardCollection chooseCardsForEffectMultiple");
        final int end = text.indexOf("public <T extends GameEntity> T chooseSingleEntityForEffect", start);
        Assert.assertTrue(start >= 0 && end > start,
                "chooseCardsForEffectMultiple source not found");
        final String method = text.substring(start, end);
        Assert.assertFalse(method.contains("if (picked == null) {\n                return result;"),
                "declining one optional category must not silently skip later categories");
        Assert.assertTrue(method.contains("if (picked != null)"),
                "each category must be processed independently");
    }

    private static void assertFailClosedMethod(String text, String startNeedle,
            String endNeedle, String callback) {
        final int start = text.indexOf(startNeedle);
        final int end = text.indexOf(endNeedle, start);
        Assert.assertTrue(start >= 0 && end > start, callback + " source not found");
        final String method = text.substring(start, end);
        Assert.assertTrue(method.contains("throw unsupported(\"" + callback + "\""),
                callback + " unexpected failures must fail closed explicitly");
        Assert.assertFalse(method.contains("catch (Throwable t) {\n            return false;"),
                callback + " unexpected failures must not look like an ordinary unpaid cost");
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
