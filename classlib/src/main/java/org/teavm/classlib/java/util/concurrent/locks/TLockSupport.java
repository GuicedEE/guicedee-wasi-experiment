package org.teavm.classlib.java.util.concurrent.locks;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Permit-based parking for TeaVM's cooperative, single-host-thread scheduler.
 * A one-millisecond sleep yields to its event queue. This is not a shared-memory
 * atomic implementation and does not promise sub-millisecond wake-up precision. */
public final class TLockSupport {
    private static final Map<Thread, State> STATES = new IdentityHashMap<>();
    private static final class State {
        boolean permit;
        boolean parked;
        Object blocker;
    }
    private TLockSupport() { }

    public static void unpark(Thread thread) {
        if (thread == null || !thread.isAlive()) return;
        STATES.computeIfAbsent(thread, ignored -> new State()).permit = true;
    }
    public static Object getBlocker(Thread thread) {
        State state = STATES.get(Objects.requireNonNull(thread));
        return state == null ? null : state.blocker;
    }
    public static void setCurrentBlocker(Object blocker) {
        Thread thread = Thread.currentThread();
        State state = STATES.computeIfAbsent(thread, ignored -> new State());
        state.blocker = blocker;
        discardIdle(thread, state);
    }
    public static void park() { park(null); }
    public static void park(Object blocker) { parkInternal(blocker, 0, false, false); }
    public static void parkNanos(long nanos) { parkNanos(null, nanos); }
    public static void parkNanos(Object blocker, long nanos) {
        if (nanos > 0) parkInternal(blocker, nanos, true, false);
    }
    public static void parkUntil(long deadline) { parkUntil(null, deadline); }
    public static void parkUntil(Object blocker, long deadline) {
        parkInternal(blocker, deadline, true, true);
    }
    private static void parkInternal(Object blocker, long limit, boolean timed, boolean absolute) {
        Thread thread = Thread.currentThread();
        State state = STATES.computeIfAbsent(thread, ignored -> new State());
        state.parked = true;
        state.blocker = blocker;
        long start = System.nanoTime();
        try {
            while (true) {
                if (state.permit) {
                    state.permit = false;
                    return;
                }
                if (thread.isInterrupted()) return;
                if (timed && (absolute ? System.currentTimeMillis() >= limit : System.nanoTime() - start >= limit)) {
                    return;
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException interrupted) {
                    // LockSupport returns with the interrupt status still set.
                    thread.interrupt();
                    return;
                }
            }
        } finally {
            state.parked = false;
            state.blocker = null;
            discardIdle(thread, state);
        }
    }
    private static void discardIdle(Thread thread, State state) {
        if (!state.permit && !state.parked && state.blocker == null) STATES.remove(thread);
    }
}
