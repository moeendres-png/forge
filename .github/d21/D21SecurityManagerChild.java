package forge.d21;

/**
 * Child-JVM payload for the D21 red control.
 * The child exits naturally after attempting a real dynamic SecurityManager install.
 */
@SuppressWarnings({"removal", "deprecation"})
public final class D21SecurityManagerChild {
    private D21SecurityManagerChild() {
    }

    public static void main(String[] args) {
        System.setSecurityManager(new SecurityManager());
    }
}
