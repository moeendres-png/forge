package forge.d17.witness;

import java.io.FilePermission;
import java.lang.reflect.ReflectPermission;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.Permission;
import java.security.SecurityPermission;
import java.util.Hashtable;
import java.util.Map;
import java.util.Properties;
import java.util.PropertyPermission;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * In-JVM containment for hostile candidate production bytecode.
 *
 * <p>The qualification JVM has three code domains:
 * <ol>
 *   <li>the trusted driver/listener and pinned TestNG on the system loader;</li>
 *   <li>the trusted comparison-base tests on {@link TrustedTestLoader}; and</li>
 *   <li>candidate production bytecode on {@link CandidateCodeLoader}.</li>
 * </ol>
 *
 * <p>The candidate loader cannot resolve the witness package or TestNG authority
 * APIs.  The security manager additionally denies the candidate domain the
 * process-global operations that could bypass that loader boundary or corrupt
 * the witness.  A denied operation is sticky: even when candidate code catches
 * {@link SecurityException}, the run is marked violated and cannot earn PASS.
 *
 * <p>This uses the JDK 17/21 SecurityManager compatibility path.  The driver
 * refuses to run when that mechanism is unavailable; absence is UNKNOWN, never
 * a fall-through to uncontained execution.
 */
public final class Containment {
    public static final String ENFORCED = "SECURITY_MANAGER_ENFORCED";
    public static final String VIOLATED = "SECURITY_MANAGER_VIOLATED";

    private Containment() {
    }

    static final class DependencyLoader extends URLClassLoader {
        private final ClassLoader trusted;

        DependencyLoader(URL[] urls, ClassLoader trusted) {
            super(urls, trusted);
            this.trusted = trusted;
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (name.startsWith("forge.d17.witness.") || name.startsWith("org.testng.")) {
                return trusted.loadClass(name);
            }
            if (isPlatform(name)) {
                return super.loadClass(name, resolve);
            }
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException missing) {
                    loaded = super.loadClass(name, false);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    /**
     * Loads only candidate production output.  TestNG authority and the witness
     * package are not visible through this loader, even when a name is assembled
     * dynamically at runtime.
     */
    static final class CandidateCodeLoader extends URLClassLoader {
        private final ClassLoader trusted;

        CandidateCodeLoader(URL[] urls, ClassLoader parent, ClassLoader trusted) {
            super(urls, parent);
            this.trusted = trusted;
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (name.startsWith("forge.d17.witness.")) {
                throw new ClassNotFoundException("D17 witness package is not candidate-visible");
            }
            if (name.startsWith("org.testng.")) {
                if (!allowedTestNg(name)) {
                    throw new ClassNotFoundException("D17 TestNG authority is not candidate-visible: " + name);
                }
                return trusted.loadClass(name);
            }
            if (isPlatform(name)) {
                return super.loadClass(name, resolve);
            }
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException missing) {
                    loaded = super.loadClass(name, false);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    /**
     * Loads test bytecode compiled from the trusted comparison base.  It may use
     * TestNG normally, but production classes resolve through the candidate
     * loader beneath it.
     */
    static final class TrustedTestLoader extends URLClassLoader {
        private final ClassLoader trusted;

        TrustedTestLoader(URL[] urls, ClassLoader parent, ClassLoader trusted) {
            super(urls, parent);
            this.trusted = trusted;
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (name.startsWith("forge.d17.witness.")) {
                throw new ClassNotFoundException("D17 witness package is not test-visible");
            }
            if (name.startsWith("org.testng.")) {
                return trusted.loadClass(name);
            }
            if (isPlatform(name)) {
                return super.loadClass(name, resolve);
            }
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException missing) {
                    loaded = super.loadClass(name, false);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    /**
     * Sticky containment guard.  Only frames loaded from CandidateCodeLoader are
     * treated as hostile; comparison-base tests are trusted policy, not candidate
     * input.
     */
    @SuppressWarnings("removal")
    static final class Guard extends SecurityManager {
        private final CandidateCodeLoader candidate;
        private final Path protectedRoot;
        private volatile String violation;

        Guard(CandidateCodeLoader candidate, Path protectedRoot) {
            this.candidate = candidate;
            this.protectedRoot = protectedRoot.toAbsolutePath().normalize();
        }

        boolean candidateInContext() {
            for (Class<?> frame : getClassContext()) {
                if (frame.getClassLoader() == candidate) {
                    return true;
                }
            }
            return false;
        }

        void deny(String detail) {
            if (violation == null) {
                violation = detail;
            }
            throw new SecurityException("D17 containment: " + detail);
        }

        String violation() {
            return violation;
        }

        boolean intact() {
            return System.getSecurityManager() == this && violation == null;
        }

        @Override
        public void checkPermission(Permission permission) {
            if (!candidateInContext()) {
                return;
            }
            String name = permission.getName() == null ? "" : permission.getName();
            if (permission instanceof RuntimePermission) {
                if (name.equals("setSecurityManager")
                        || name.equals("createSecurityManager")
                        || name.equals("createClassLoader")
                        || name.equals("getClassLoader")
                        || name.equals("setContextClassLoader")
                        || name.equals("enableContextClassLoaderOverride")
                        || name.equals("setIO")
                        || name.equals("shutdownHooks")
                        || name.equals("getStackWalkerWithClassReference")
                        || name.startsWith("exitVM")
                        || name.startsWith("loadLibrary")) {
                    deny("candidate RuntimePermission " + name);
                }
            } else if (permission instanceof ReflectPermission
                    && "suppressAccessChecks".equals(name)) {
                deny("candidate reflective access suppression");
            } else if (permission instanceof SecurityPermission) {
                deny("candidate SecurityPermission " + name);
            } else if (permission instanceof PropertyPermission
                    && permission.getActions().contains("write")
                    && ("*".equals(name) || sensitiveProperty(name))) {
                deny("candidate property mutation " + name);
            } else if (permission instanceof FilePermission) {
                String actions = permission.getActions();
                if (actions.contains("execute")) {
                    deny("candidate process execution " + name);
                }
                if (actions.contains("write") || actions.contains("delete")) {
                    if ("<<ALL FILES>>".equals(name)) {
                        deny("candidate all-files mutation permission");
                    }
                    try {
                        Path target = Path.of(name).toAbsolutePath().normalize();
                        if (target.startsWith(protectedRoot)) {
                            deny("candidate mutation of protected witness path " + target);
                        }
                    } catch (RuntimeException badPath) {
                        deny("candidate mutation through non-normalizable path " + name);
                    }
                }
            }
        }

        @Override
        public void checkPackageAccess(String pkg) {
            if (candidateInContext()
                    && (pkg.startsWith("forge.d17.witness")
                    || pkg.startsWith("sun.misc")
                    || pkg.startsWith("jdk.internal"))) {
                deny("candidate package access " + pkg);
            }
        }
    }

    /**
     * Protect TestNG's process-global knobs even through System.getProperties().
     * System.setProperties itself is separately denied by the SecurityManager.
     */
    static final class GuardedProperties extends Properties {
        private static final long serialVersionUID = 1L;
        private final Guard guard;

        GuardedProperties(Properties source, Guard guard) {
            this.guard = guard;
            super.putAll(source);
        }

        private void checkKey(Object key) {
            if (guard.candidateInContext() && sensitiveProperty(String.valueOf(key))) {
                guard.deny("candidate direct Properties mutation " + key);
            }
        }

        @Override
        public synchronized Object put(Object key, Object value) {
            checkKey(key);
            return super.put(key, value);
        }

        @Override
        public synchronized Object remove(Object key) {
            checkKey(key);
            return super.remove(key);
        }

        @Override
        public synchronized void putAll(Map<?, ?> values) {
            if (guard.candidateInContext()) {
                for (Object key : values.keySet()) {
                    checkKey(key);
                }
            }
            super.putAll(values);
        }

        @Override
        public synchronized void clear() {
            if (guard.candidateInContext()) {
                guard.deny("candidate Properties.clear");
            }
            super.clear();
        }

        @Override
        public synchronized Object replace(Object key, Object value) {
            checkKey(key);
            return super.replace(key, value);
        }

        @Override
        public synchronized boolean replace(Object key, Object oldValue, Object newValue) {
            checkKey(key);
            return super.replace(key, oldValue, newValue);
        }

        @Override
        public synchronized void replaceAll(BiFunction<? super Object, ? super Object, ?> function) {
            if (guard.candidateInContext()) {
                guard.deny("candidate Properties.replaceAll");
            }
            super.replaceAll(function);
        }

        @Override
        public synchronized Object compute(Object key,
                BiFunction<? super Object, ? super Object, ?> function) {
            checkKey(key);
            return super.compute(key, function);
        }

        @Override
        public synchronized Object computeIfAbsent(Object key, Function<? super Object, ?> function) {
            checkKey(key);
            return super.computeIfAbsent(key, function);
        }

        @Override
        public synchronized Object computeIfPresent(Object key,
                BiFunction<? super Object, ? super Object, ?> function) {
            checkKey(key);
            return super.computeIfPresent(key, function);
        }

        @Override
        public synchronized Object merge(Object key, Object value,
                BiFunction<? super Object, ? super Object, ?> function) {
            checkKey(key);
            return super.merge(key, value, function);
        }
    }

    static final class Session {
        final Guard guard;
        final GuardedProperties properties;
        final TrustedTestLoader tests;

        Session(Guard guard, GuardedProperties properties, TrustedTestLoader tests) {
            this.guard = guard;
            this.properties = properties;
            this.tests = tests;
        }

        boolean intact() {
            return guard.intact() && System.getProperties() == properties;
        }
    }

    @SuppressWarnings("removal")
    static Session install(URL[] dependencies, URL[] candidateCode, URL[] trustedTests,
            Path protectedRoot) {
        if (!"allow".equals(System.getProperty("java.security.manager"))) {
            throw new UnsupportedOperationException(
                    "JDK SecurityManager compatibility mode is unavailable/not enabled");
        }
        ClassLoader system = Containment.class.getClassLoader();
        DependencyLoader deps = new DependencyLoader(dependencies, system);
        CandidateCodeLoader candidate = new CandidateCodeLoader(candidateCode, deps, system);
        TrustedTestLoader tests = new TrustedTestLoader(trustedTests, candidate, system);
        Guard guard = new Guard(candidate, protectedRoot);
        GuardedProperties guarded = new GuardedProperties(System.getProperties(), guard);
        System.setProperties(guarded);
        System.setSecurityManager(guard);
        if (System.getSecurityManager() != guard) {
            throw new UnsupportedOperationException("SecurityManager was not installed");
        }
        Thread.currentThread().setContextClassLoader(tests);
        return new Session(guard, guarded, tests);
    }

    private static boolean sensitiveProperty(String name) {
        return name.startsWith("testng.")
                || "java.security.manager".equals(name)
                || "java.class.path".equals(name);
    }

    private static boolean allowedTestNg(String name) {
        return name.startsWith("org.testng.annotations.")
                || name.equals("org.testng.Assert")
                || name.equals("org.testng.AssertJUnit")
                || name.equals("org.testng.SkipException")
                || name.startsWith("org.testng.asserts.")
                || name.startsWith("org.testng.collections.");
    }

    private static boolean isPlatform(String name) {
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.")
                || name.startsWith("com.sun.");
    }
}
