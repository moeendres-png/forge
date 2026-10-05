package forge.d21;

import org.testng.annotations.Test;

/**
 * D21 shadow-only red control.
 *
 * Models code that explicitly clears a dynamically installed SecurityManager.
 * With java.security.manager unset this call is permitted by JDK 17, while
 * JDK 21 throws UnsupportedOperationException by default.
 *
 * The source is copied into the test tree only by the D21 shadow workflow.
 */
@SuppressWarnings({"removal", "deprecation"})
public final class D21SecurityManagerCompatibilityRedControlTest {
    @Test
    public void dynamicSecurityManagerChangeRemainsAvailable() {
        System.setSecurityManager(null);
    }
}
