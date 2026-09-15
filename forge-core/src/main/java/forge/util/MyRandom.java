/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.util;

import java.security.SecureRandom;
import java.util.Random;

/**
 * <p>
 * MyRandom class.<br>
 * Preferably all Random numbers should be retrieved using this wrapper class
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public class MyRandom {
    /** Constant <code>random</code>. */
    private static Random random = new CountingSecureRandom();

    /** WS227: authoritative Rules-RNG root seed binding (null when uncontrolled). */
    private static volatile Long rootSeed;

    /** WS227: true only after an explicit bindSeed (regenerate-not-inject contract). */
    private static volatile boolean explicitSeed;

    /** WS227: authoritative Rules-RNG call coordinates (monotonic per binding). */
    private static final java.util.concurrent.atomic.AtomicLong callCount =
            new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * WS227: counting Rules Random for an explicit seed binding. Every
     * {@code next(bits)} is one authoritative Rules-RNG call; all
     * {@code nextInt/nextLong/shuffle} paths funnel through it. Shares the
     * global call counter so coordinates survive across wrapper instances.
     */
    private static final class CountingRandom extends Random {
        private static final long serialVersionUID = 1L;

        CountingRandom(final long seed) {
            super(seed);
        }

        @Override
        protected int next(final int bits) {
            callCount.incrementAndGet();
            return super.next(bits);
        }
    }

    /**
     * WS227: counting SecureRandom for the uncontrolled path. Counts
     * {@code nextBytes} as authoritative calls when no explicit seed is bound
     * (binding remains non-explicit, coordinates are observational only).
     * {@code SecureRandom.next(int)} is final and funnels through
     * {@code nextBytes}, so overriding {@code nextBytes} captures the
     * uncontrolled path without replacing the engine RNG.
     */
    private static final class CountingSecureRandom extends SecureRandom {
        private static final long serialVersionUID = 1L;

        @Override
        public void nextBytes(final byte[] bytes) {
            callCount.incrementAndGet();
            super.nextBytes(bytes);
        }
    }

    /**
     * <p>
     * percentTrue.<br>
     * If percent is like 30, then 30% of the time it will be true.
     * </p>
     * 
     * @param percent an int.
     * @return a boolean.
     */
    public static boolean percentTrue(final int percent) {
        return percent > MyRandom.getRandom().nextInt(100);
    }

    /**
     * Gets the random.
     * 
     * @return the random
     */
    public static Random getRandom() {
        return MyRandom.random;
    }

    /**
     * Sets the random provider. Used for deterministic simulation.
     * WS227: clears any explicit seed binding; call coordinates restart and
     * remain non-authoritative until {@link #bindSeed(long)}. Preserved for
     * backward compatibility; replay paths must use {@link #bindSeed(long)}.
     * @param random the random
     */
    public static synchronized void setRandom(Random random) {
        if (random == null) {
            throw new IllegalArgumentException("random is required");
        }
        MyRandom.random = random;
        MyRandom.rootSeed = null;
        MyRandom.explicitSeed = false;
        MyRandom.callCount.set(0);
    }

    /**
     * WS227: binds an explicit Rules root seed for clean-process semantic replay.
     * Installs a counting Rules Random over the seed and resets call coordinates.
     * The engine regenerates all random results natively; recorded results are
     * never injected. Core-owned; the bridge must call this instead of
     * installing its own RNG.
     * @param seed the explicit root Rules seed
     */
    public static synchronized void bindSeed(final long seed) {
        MyRandom.rootSeed = Long.valueOf(seed);
        MyRandom.explicitSeed = true;
        MyRandom.callCount.set(0);
        MyRandom.random = new CountingRandom(seed);
    }

    /**
     * WS227: clears an explicit seed binding (tests only). Restores the
     * uncontrolled counting SecureRandom path with fresh coordinates.
     */
    public static synchronized void clearBinding() {
        MyRandom.rootSeed = null;
        MyRandom.explicitSeed = false;
        MyRandom.callCount.set(0);
        MyRandom.random = new CountingSecureRandom();
    }

    /**
     * WS227: explicit root Rules seed, or null when uncontrolled.
     * @return the bound seed or null
     */
    public static Long getRootSeed() {
        return MyRandom.rootSeed;
    }

    /**
     * WS227: whether an explicit seed binding is active.
     * @return true after bindSeed until setRandom/clearBinding
     */
    public static boolean isExplicitSeed() {
        return MyRandom.explicitSeed;
    }

    /**
     * WS227: authoritative Rules-RNG call coordinates (calls since binding).
     * Authoritative only when {@link #isExplicitSeed()} is true; otherwise
     * observational (legacy setRandom path installs a non-counting Random).
     * @return monotonic call count
     */
    public static long getCallCount() {
        return MyRandom.callCount.get();
    }

    public static int[] splitIntoRandomGroups(final int value, final int numGroups) {
        int[] groups = new int[numGroups];

        for (int i = 0; i < value; i++) {
            groups[random.nextInt(numGroups)]++;
        }

        return groups;
    }
}
