package forge.d17.witness;

import java.io.FilePermission;
import java.lang.reflect.ReflectPermission;
import java.lang.management.ManagementPermission;
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
            // Deliberately do NOT parent this domain to the system loader.
            // Otherwise ClassLoader.getSystemClassLoader() can be returned to a
            // descendant without a getClassLoader permission check because the
            // system loader is its ancestor.  The platform loader keeps JDK APIs
            // available while TestNG is bridged explicitly below.
            super(urls, ClassLoader.getPlatformClassLoader());
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
        private final DependencyLoader dependencies;
        private final ClassLoader trusted;
        private volatile Guard guard;

        CandidateCodeLoader(URL[] urls, DependencyLoader dependencies, ClassLoader trusted) {
            // The actual parent is platform-only.  DependencyLoader is a private
            // delegate, not an ancestor: candidate code may obtain its own
            // ClassLoader.getParent() without permission when that parent is an
            // ancestor, so making the dependency/TestNG bridge the real parent
            // would expose trusted TestNG authority.
            super(urls, ClassLoader.getPlatformClassLoader());
            this.dependencies = dependencies;
            this.trusted = trusted;
        }

        void bindGuard(Guard value) {
            this.guard = value;
        }

        private void denyAuthority(String detail) {
            Guard current = guard;
            if (current != null) {
                current.deny(detail);
            }
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (name.startsWith("forge.d17.witness.")) {
                denyAuthority("candidate attempted to load witness class " + name);
                throw new ClassNotFoundException("D17 witness package is not candidate-visible");
            }
            if (name.startsWith("org.testng.")) {
                denyAuthority("candidate attempted to load TestNG class " + name);
                throw new ClassNotFoundException("D17 TestNG is not candidate-visible: " + name);
            }
            if (isPlatform(name)) {
                return super.loadClass(name, resolve);
            }
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    // Trusted dependencies are logically parent-first but are a
                    // private delegate, never a ClassLoader ancestor visible to
                    // hostile candidate code.
                    loaded = dependencies.loadClass(name);
                } catch (ClassNotFoundException missingDependency) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException missingCandidate) {
                        loaded = super.loadClass(name, false);
                    }
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
        private final CandidateCodeLoader candidate;
        private final ClassLoader trusted;

        TrustedTestLoader(URL[] urls, CandidateCodeLoader candidate, ClassLoader trusted) {
            // CandidateCodeLoader is deliberately a delegate, not the parent.
            // Otherwise hostile candidate code is an ancestor of the context
            // loader and can obtain that loader without a getClassLoader check.
            super(urls, ClassLoader.getPlatformClassLoader());
            this.candidate = candidate;
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
                    // The trusted comparison-base test tree is child-first.
                    loaded = findClass(name);
                } catch (ClassNotFoundException missing) {
                    // Product behavior comes only from the candidate domain.
                    loaded = candidate.loadClass(name);
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
                        || name.equals("defineClass")
                        || name.equals("getClassLoader")
                        || name.equals("setContextClassLoader")
                        || name.equals("enableContextClassLoaderOverride")
                        || name.equals("setIO")
                        || name.equals("shutdownHooks")
                        || name.equals("getStackWalkerWithClassReference")
                        || name.equals("getStackTrace")
                        || name.equals("manageProcess")
                        || name.equals("modifyThread")
                        || name.equals("modifyThreadGroup")
                        || name.equals("stopThread")
                        || name.equals("setDefaultUncaughtExceptionHandler")
                        || name.equals("accessDeclaredMembers")
                        || name.startsWith("accessClassInPackage.forge.d17.witness")
                        || name.startsWith("accessClassInPackage.org.testng")
                        || name.startsWith("accessClassInPackage.sun.")
                        || name.startsWith("accessClassInPackage.jdk.internal.")
                        || name.startsWith("exitVM")
                        || name.startsWith("loadLibrary")) {
                    deny("candidate RuntimePermission " + name);
                }
            } else if (permission instanceof ReflectPermission
                    && "suppressAccessChecks".equals(name)) {
                deny("candidate reflective access suppression");
            } else if (permission instanceof SecurityPermission) {
                deny("candidate SecurityPermission " + name);
            } else if (permission instanceof ManagementPermission
                    && "control".equals(name)) {
                deny("candidate management control");
            } else if (permission.getClass().getName().startsWith("javax.management.")
                    || permission.getClass().getName().equals("jdk.jfr.FlightRecorderPermission")
                    || permission.getClass().getName().equals("com.sun.tools.attach.AttachPermission")) {
                deny("candidate VM-introspection permission " + permission.getClass().getName());
            } else if (permission instanceof PropertyPermission
                    && permission.getActions().contains("write")) {
                // Process-global properties are trusted-runtime state.  Even a
                // property not presently consumed by TestNG can change class
                // loading, provider selection or a future witness dependency.
                deny("candidate property mutation " + name);
            } else if (permission instanceof FilePermission) {
                String actions = permission.getActions();
                if (actions.contains("execute")) {
                    deny("candidate process execution " + name);
                }
                if (actions.contains("read")) {
                    String unix = name.replace('\\', '/');
                    if (unix.matches("^/proc/(self|[0-9]+)/(mem|maps|pagemap|fd|fdinfo)(/.*)?$")) {
                        deny("candidate process-memory/fd discovery read " + unix);
                    }
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
                    || pkg.startsWith("org.testng")
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
            if (guard.candidateInContext()) {
                // The Properties object is process-global trusted runtime state.
                // Candidate production code has no authority to mutate *any*
                // entry, even one not currently consumed by TestNG: otherwise a
                // future provider/loader/library property could become a bypass.
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
        public synchronized boolean remove(Object key, Object value) {
            checkKey(key);
            return super.remove(key, value);
        }

        @Override
        public synchronized Object putIfAbsent(Object key, Object value) {
            checkKey(key);
            return super.putIfAbsent(key, value);
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
        candidate.bindGuard(guard);
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

    private static boolean isPlatform(String name) {
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.")
                || name.startsWith("com.sun.");
    }
}
