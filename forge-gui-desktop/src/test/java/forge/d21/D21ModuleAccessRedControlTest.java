package forge.d21;

import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.net.URI;

import static org.testng.Assert.assertEquals;

/**
 * D21 SHADOW-ONLY red control.
 *
 * <p>This does not inspect Runtime.version() and does not fail intentionally. It exercises the
 * real JPMS strong-encapsulation failure mode by reflectively opening a private JDK field.
 * The experiment's Java 17 baseline profile grants java.net access; Java 21 intentionally
 * omits that opening. Never merge this fixture to production.
 */
public final class D21ModuleAccessRedControlTest {
    @Test
    public void reflectiveUriAccessRequiresModuleOpen() throws Exception {
        URI uri = URI.create("https://example.invalid/d21");
        Field cachedString = URI.class.getDeclaredField("string");
        cachedString.setAccessible(true);
        assertEquals(cachedString.get(uri), uri.toString());
    }
}
