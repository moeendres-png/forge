package forge.d17.witness;

import java.io.File;
import java.io.FilePermission;
import java.io.IOException;
import java.lang.reflect.ReflectPermission;
import java.lang.management.ManagementPermission;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.Permission;
import java.security.SecurityPermission;
import java.util.Collection;
import java.util.Hashtable;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.PropertyPermission;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * In-JVM containment for hostile candidate production bytecode.
 *
 * <p>The qualification JVM separates the trusted driver/listener and pinned
 * TestNG on the system loader, trusted comparison-base tests on
 * {@link TrustedTestLoader}, pinned production dependencies on
 * {@link DependencyLoader}, and candidate production bytecode on
 * {@link CandidateCodeLoader}. Candidate and dependency domains are both
 * authority-tainted.
 *
 * <p>The candidate loader cannot resolve the witness package or TestNG authority
 * APIs.  The security manager additionally denies untrusted authority contexts the
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
        private volatile Guard guard;

        DependencyLoader(URL[] urls) {
            // Deliberately do NOT parent this domain to the system loader.
            // Otherwise ClassLoader.getSystemClassLoader() can be returned to a
            // descendant without a getClassLoader permission check because the
            // system loader is its ancestor. The platform loader keeps only JDK
            // APIs available; trusted TestNG/witness authority is not referenced
            // by this loader at all.
            super(urls, ClassLoader.getPlatformClassLoader());
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
            if (name.startsWith("forge.d17.witness.") || name.startsWith("org.testng.")) {
                denyAuthority("dependency attempted to load trusted authority class " + name);
                throw new ClassNotFoundException(
                        "D17 trusted authority package is not dependency-visible: " + name);
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
        private volatile Guard guard;

        CandidateCodeLoader(URL[] urls, DependencyLoader dependencies) {
            // The actual parent is platform-only.  DependencyLoader is a private
            // delegate, not an ancestor: candidate code may obtain its own
            // ClassLoader.getParent() without permission when that parent is an
            // ancestor, so making the dependency/TestNG bridge the real parent
            // would expose trusted TestNG authority.
            super(urls, ClassLoader.getPlatformClassLoader());
            this.dependencies = dependencies;
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
     * Sticky containment guard. Candidate production and its pinned dependency
     * domain are both treated as hostile authority-wise. This prevents a malicious
     * candidate from turning a trusted dependency into an asynchronous confused
     * deputy after the original candidate frame has returned. Comparison-base
     * tests remain trusted policy.
     */
    @SuppressWarnings("removal")
    static final class Guard extends SecurityManager {
        private static final int CONTEXT_HOSTILE = 1;
        private static final int CONTEXT_TRUSTED_AUTHORITY = 2;
        private static final int CONTEXT_UNTRUSTED_ASYNC = 3;

        private final CandidateCodeLoader candidate;
        private final DependencyLoader dependencies;
        private final ClassLoader trusted;
        private final Path protectedRoot;
        private final ThreadLocal<Boolean> resolvingPath =
                ThreadLocal.withInitial(() -> Boolean.FALSE);
        private final ThreadLocal<Integer> invocationDepth =
                ThreadLocal.withInitial(() -> Integer.valueOf(0));
        private volatile String violation;

        Guard(CandidateCodeLoader candidate, DependencyLoader dependencies,
                ClassLoader trusted, Path protectedRoot) {
            this.candidate = candidate;
            this.dependencies = dependencies;
            this.trusted = trusted;
            this.protectedRoot = protectedRoot.toAbsolutePath().normalize();
        }

        private int authorityContext() {
            boolean trustedAuthority = false;
            boolean activeCounter = false;
            for (Class<?> frame : getClassContext()) {
                ClassLoader loader = frame.getClassLoader();
                if (loader == candidate || loader == dependencies) {
                    // Hostile always wins even though TestNG appears lower on the
                    // normal invocation stack.
                    return CONTEXT_HOSTILE;
                }
                if (loader == trusted) {
                    String name = frame.getName();
                    if (name.equals("forge.d17.witness.QualifiedExecutionCounter")) {
                        activeCounter = true;
                        trustedAuthority = true;
                    } else if (name.equals("forge.d17.witness.TrustedTestNGDriver")
                            || name.startsWith("org.testng.")) {
                        trustedAuthority = true;
                    }
                }
            }
            // TrustedTestNGDriver.main and TestNG remain deep on the stack for
            // the whole test run. They must NOT lend authority to a pure-JDK
            // callback returned by hostile production and invoked by a trusted
            // test. During an active invocation only an actually executing
            // trusted counter callback may perform sensitive authority work.
            if (invocationDepth.get().intValue() > 0) {
                return activeCounter ? CONTEXT_TRUSTED_AUTHORITY : CONTEXT_UNTRUSTED_ASYNC;
            }
            // A JDK-only asynchronous task has neither a hostile frame nor an
            // explicit trusted authority frame. Treat it as untrusted so a
            // candidate cannot shed its taint through a configured JDK deputy.
            return trustedAuthority ? CONTEXT_TRUSTED_AUTHORITY : CONTEXT_UNTRUSTED_ASYNC;
        }

        void enterInvocation() {
            invocationDepth.set(Integer.valueOf(invocationDepth.get().intValue() + 1));
        }

        void exitInvocation() {
            int depth = invocationDepth.get().intValue();
            if (depth <= 1) {
                invocationDepth.remove();
            } else {
                invocationDepth.set(Integer.valueOf(depth - 1));
            }
        }

        boolean candidateInContext() {
            return authorityContext() == CONTEXT_HOSTILE;
        }

        boolean authorityRestrictedContext() {
            return authorityContext() != CONTEXT_TRUSTED_AUTHORITY;
        }

        private String authorityLabel() {
            return authorityContext() == CONTEXT_HOSTILE
                    ? "candidate/dependency" : "untrusted asynchronous context";
        }

        private String canonicalFilePath(String name) {
            if ("<<ALL FILES>>".equals(name)) {
                return name;
            }
            try {
                // Canonicalization itself performs filesystem queries that can
                // re-enter SecurityManager. Temporarily authorize only this
                // trusted synchronous JDK operation; no candidate callback runs
                // inside File.getCanonicalPath().
                resolvingPath.set(Boolean.TRUE);
                return new File(name).getCanonicalPath().replace('\\', '/');
            } catch (IOException | RuntimeException error) {
                deny(authorityLabel() + " path canonicalization failed for " + name);
                return ""; // unreachable: deny always throws
            } finally {
                resolvingPath.remove();
            }
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
            if (Boolean.TRUE.equals(resolvingPath.get())) {
                return;
            }
            if (!authorityRestrictedContext()) {
                return;
            }
            String actor = authorityLabel();
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
                    deny(actor + " RuntimePermission " + name);
                }
            } else if (permission instanceof ReflectPermission
                    && "suppressAccessChecks".equals(name)) {
                deny(actor + " reflective access suppression");
            } else if (permission instanceof SecurityPermission) {
                deny(actor + " SecurityPermission " + name);
            } else if (permission instanceof ManagementPermission
                    && "control".equals(name)) {
                deny(actor + " management control");
            } else if (permission.getClass().getName().startsWith("javax.management.")
                    || permission.getClass().getName().equals("jdk.jfr.FlightRecorderPermission")
                    || permission.getClass().getName().equals("com.sun.tools.attach.AttachPermission")) {
                deny(actor + " VM-introspection permission " + permission.getClass().getName());
            } else if (permission.getClass().getName().equals("java.nio.file.LinkPermission")) {
                deny(actor + " filesystem link permission " + name);
            } else if (permission instanceof PropertyPermission
                    && permission.getActions().contains("write")) {
                // Process-global properties are trusted-runtime state.  Even a
                // property not presently consumed by TestNG can change class
                // loading, provider selection or a future witness dependency.
                deny(actor + " property mutation " + name);
            } else if (permission instanceof FilePermission) {
                String actions = permission.getActions();
                String unix = name.replace('\\', '/');
                String canonicalUnix = canonicalFilePath(name);
                // procfs exposes process memory, fd tables, task aliases and
                // root-based aliases. Check both the lexical permission path and
                // the canonical target so pre-existing symlinks planted by the
                // untrusted build UID cannot route around the boundary.
                if (unix.equals("/proc") || unix.startsWith("/proc/")
                        || canonicalUnix.equals("/proc") || canonicalUnix.startsWith("/proc/")) {
                    deny(actor + " procfs access " + actions + " " + unix
                            + " -> " + canonicalUnix);
                }
                if (actions.contains("execute")) {
                    deny(actor + " process execution " + name);
                }
                if (actions.contains("write") || actions.contains("delete")) {
                    if ("<<ALL FILES>>".equals(name)) {
                        deny(actor + " all-files mutation permission");
                    }
                    if (!"<<ALL FILES>>".equals(canonicalUnix)
                            && Path.of(canonicalUnix).startsWith(protectedRoot)) {
                        deny(actor + " mutation of protected witness path " + canonicalUnix);
                    }
                }
            }
        }

        @Override
        public void checkPackageAccess(String pkg) {
            if (authorityRestrictedContext()
                    && (pkg.startsWith("forge.d17.witness")
                    || pkg.startsWith("org.testng")
                    || pkg.startsWith("sun.")
                    || pkg.startsWith("jdk.internal"))) {
                deny(authorityLabel() + " package access " + pkg);
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
            if (guard.authorityRestrictedContext()) {
                // The Properties object is process-global trusted runtime state.
                // Candidate production code has no authority to mutate *any*
                // entry, even one not currently consumed by TestNG: otherwise a
                // future provider/loader/library property could become a bypass.
                guard.deny("untrusted direct Properties mutation " + key);
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
            if (guard.authorityRestrictedContext()) {
                for (Object key : values.keySet()) {
                    checkKey(key);
                }
            }
            super.putAll(values);
        }

        @Override
        public synchronized void clear() {
            if (guard.authorityRestrictedContext()) {
                guard.deny("untrusted Properties.clear");
            }
            super.clear();
        }

        @Override
        public Set<Map.Entry<Object, Object>> entrySet() {
            if (guard.authorityRestrictedContext()) {
                guard.deny("untrusted mutable Properties.entrySet view");
            }
            return super.entrySet();
        }

        @Override
        public Set<Object> keySet() {
            if (guard.authorityRestrictedContext()) {
                guard.deny("untrusted mutable Properties.keySet view");
            }
            return super.keySet();
        }

        @Override
        public Collection<Object> values() {
            if (guard.authorityRestrictedContext()) {
                guard.deny("untrusted mutable Properties.values view");
            }
            return super.values();
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
            if (guard.authorityRestrictedContext()) {
                guard.deny("untrusted Properties.replaceAll");
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
        DependencyLoader deps = new DependencyLoader(dependencies);
        CandidateCodeLoader candidate = new CandidateCodeLoader(candidateCode, deps);
        TrustedTestLoader tests = new TrustedTestLoader(trustedTests, candidate, system);
        Guard guard = new Guard(candidate, deps, system, protectedRoot);
        deps.bindGuard(guard);
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
