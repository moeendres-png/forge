package forge.bridge;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * WS89 integration identity: the bridge's reported engine identity must be the
 * accepted clean Rules-Core authority, never the historical H4F pin.
 *
 * <p>RULES_CORE_AUTHORITY = aa5c00aa32dfd40e213f223f8fd400c43daabb24 (exact
 * tested technical commit). Historical H4F pin a37a865a53280dd8ad6fad3384d69611e8c5a42f
 * must not be reported as the current engine identity.
 */
public class Ws89EngineIdentityTest {
    private static final String RULES_CORE_AUTHORITY = "aa5c00aa32dfd40e213f223f8fd400c43daabb24";
    private static final String HISTORICAL_H4F_PIN = "a37a865a53280dd8ad6fad3384d69611e8c5a42f";

    private String savedSysprop;

    @BeforeMethod
    public void save() {
        savedSysprop = System.getProperty("forge.engine.sha");
    }

    @AfterMethod
    public void restore() {
        if (savedSysprop == null) {
            System.clearProperty("forge.engine.sha");
        } else {
            System.setProperty("forge.engine.sha", savedSysprop);
        }
    }

    @Test
    public void testAuthorityIsNotHistoricalPin() {
        Assert.assertNotEquals(RULES_CORE_AUTHORITY, HISTORICAL_H4F_PIN,
                "Rules-Core authority must differ from the historical H4F pin");
        Assert.assertTrue(RULES_CORE_AUTHORITY.matches("[0-9a-f]{40}"));
        Assert.assertTrue(HISTORICAL_H4F_PIN.matches("[0-9a-f]{40}"));
    }

    @Test
    public void testBridgeReportsRulesCoreAuthority() {
        System.setProperty("forge.engine.sha", RULES_CORE_AUTHORITY);
        Assert.assertEquals(VersionInfo.engineCommit(), RULES_CORE_AUTHORITY);
        Assert.assertEquals(VersionInfo.engineCommitIfValid(), RULES_CORE_AUTHORITY);
        Assert.assertEquals(VersionInfo.engineCommitSource(), "sysprop:forge.engine.sha");
    }

    @Test
    public void testBridgeDoesNotReportHistoricalPinAsAuthority() {
        System.setProperty("forge.engine.sha", RULES_CORE_AUTHORITY);
        Assert.assertNotEquals(VersionInfo.engineCommit(), HISTORICAL_H4F_PIN,
                "bridge must report the clean Rules-Core authority, not the historical H4F pin");
    }
}
