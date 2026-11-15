package org.teavm.classlib.java.util.concurrent.locks;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/** Owner-aware nonblocking subset for TeaVM cooperative threads. Contended blocking
 * acquisition is rejected explicitly until a proper wait/interrupt queue is ported.
 * Condition waits, contended timed acquisition and fair queuing are not implemented. */
public class TReentrantLock implements Lock, Serializable {
    private transient Thread owner;
    private transient int holds;
    public TReentrantLock() { }
    public TReentrantLock(boolean fair) {
        if (fair) throw new UnsupportedOperationException("Fair lock queuing is not implemented for WASI");
    }
    public void lock() {
        if (!tryLock()) throw new UnsupportedOperationException("Contended blocking locks are not implemented for WASI");
    }
    public boolean tryLock() {
        Thread thread = Thread.currentThread();
        if (owner != null && owner != thread) return false;
        if (holds == Integer.MAX_VALUE) throw new Error("Maximum lock count exceeded");
        owner = thread;
        holds++;
        return true;
    }
    public void lockInterruptibly() throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        lock();
    }
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        long nanos = unit.toNanos(time);
        if (tryLock()) return true;
        if (nanos <= 0) return false;
        throw new UnsupportedOperationException("Contended timed locks are not implemented for WASI");
    }
    public Condition newCondition() {
        throw new UnsupportedOperationException("Condition waits are not implemented for WASI");
    }
    public void unlock() {
        if (owner != Thread.currentThread()) throw new IllegalMonitorStateException();
        if (--holds == 0) owner = null;
    }
    public boolean isHeldByCurrentThread() { return owner == Thread.currentThread(); }
    public boolean isLocked() { return owner != null; }
    public int getHoldCount() { return isHeldByCurrentThread() ? holds : 0; }
    public boolean isFair() { return false; }
}
