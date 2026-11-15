package com.guicedee.wasi.experiment;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** The same assertions run against the JDK and the WASI class-library substitutions. */
public final class ConcurrencyProbe {
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public static String verify() throws InterruptedException {
        var queue = new ConcurrentLinkedQueue<>(List.of("first", "second", "third"));
        var iterator = queue.iterator();
        require(iterator.next().equals("first"), "FIFO iteration");
        iterator.remove();
        queue.offer("fourth");
        require(iterator.next().equals("second"), "Weakly consistent iteration");
        require(queue.size() == 3 && queue.peek().equals("second"), "Iterator removal");
        require(queue.poll().equals("second") && queue.poll().equals("third")
                && queue.poll().equals("fourth") && queue.poll() == null, "FIFO polling");
        try { queue.offer(null); throw new IllegalStateException("Accepted null"); }
        catch (NullPointerException expected) { }
        queue.offer("reused");
        require(queue.poll().equals("reused") && queue.isEmpty(), "Empty queue reuse");

        String original = new String("same");
        String equalButDifferent = new String("same");
        String[] source = {original};
        var references = new AtomicReferenceArray<>(source);
        source[0] = "changed";
        require(references.get(0) == original, "Array constructor must copy");
        require(!references.compareAndSet(0, equalButDifferent, "wrong"), "CAS must use identity");
        require(references.compareAndSet(0, original, "updated"), "Successful CAS");
        require(references.getAndSet(0, "next").equals("updated"), "getAndSet previous value");
        // The callback mutates the array: the outer CAS must retry with the new value.
        int[] attempts = {0};
        String updated = references.updateAndGet(0, value -> {
            if (attempts[0]++ == 0) references.set(0, "callback");
            return value + "!";
        });
        require(updated.equals("callback!") && attempts[0] == 2, "Retry after callback mutation");
        try { references.get(1); throw new IllegalStateException("Missing bounds check"); }
        catch (IndexOutOfBoundsException expected) { }

        var lock = new ReentrantLock();
        lock.lock();
        lock.lock();
        require(lock.getHoldCount() == 2 && lock.isHeldByCurrentThread(), "Reentrant hold count");
        boolean[] checked = {false};
        Throwable[] failure = {null};
        Thread contender = new Thread(() -> {
            try {
                require(!lock.isHeldByCurrentThread() && lock.getHoldCount() == 0, "Different thread owner");
                require(!lock.tryLock(), "Contended tryLock must fail");
                try { lock.unlock(); throw new IllegalStateException("Non-owner unlock accepted"); }
                catch (IllegalMonitorStateException expected) { }
                checked[0] = true;
            } catch (Throwable error) { failure[0] = error; }
        });
        contender.start();
        contender.join();
        if (failure[0] != null) throw new IllegalStateException("Lock contender failed", failure[0]);
        require(checked[0] && lock.getHoldCount() == 2, "Owner retained across thread scheduling");
        lock.unlock();
        require(lock.isLocked() && lock.getHoldCount() == 1, "Partial unlock");
        lock.unlock();
        require(!lock.isLocked() && lock.tryLock(), "Release and reacquire");
        lock.unlock();
        try { lock.unlock(); throw new IllegalStateException("Unowned unlock accepted"); }
        catch (IllegalMonitorStateException expected) { }
        Lock viaInterface = lock;
        require(viaInterface.tryLock(0, TimeUnit.NANOSECONDS), "Lock interface dispatch");
        viaInterface.unlock();
        Thread.currentThread().interrupt();
        try { lock.lockInterruptibly(); throw new IllegalStateException("Ignored interrupt"); }
        catch (InterruptedException expected) { }
        require(!Thread.currentThread().isInterrupted() && !lock.isLocked(), "Interrupt must clear without acquiring");
        return "WASI_CONCURRENCY_OK queue identity-cas update-retry lock-owner cooperative-thread";
    }

    /** WASI-specific negative checks, deliberately not part of the JDK control. */
    private static void verifyWasiLimits() throws InterruptedException {
        try { new ReentrantLock(true); throw new IllegalStateException("Fair lock accepted"); }
        catch (UnsupportedOperationException expected) { }
        var lock = new ReentrantLock();
        try { lock.newCondition(); throw new IllegalStateException("Condition accepted"); }
        catch (UnsupportedOperationException expected) { }
        lock.lock();
        Throwable[] failure = {null};
        boolean[] rejected = {false};
        Thread contender = new Thread(() -> {
            try {
                try { lock.lock(); throw new IllegalStateException("Contended blocking lock accepted"); }
                catch (UnsupportedOperationException expected) { }
                require(!lock.tryLock(0, TimeUnit.NANOSECONDS), "Zero timeout must fail immediately");
                try { lock.tryLock(1, TimeUnit.SECONDS); throw new IllegalStateException("Contended timed lock accepted"); }
                catch (UnsupportedOperationException expected) { }
                rejected[0] = true;
            } catch (Throwable error) { failure[0] = error; }
        });
        contender.start();
        contender.join();
        lock.unlock();
        if (failure[0] != null) throw new IllegalStateException("WASI limits failed", failure[0]);
        require(rejected[0], "Missing WASI rejection checks");
        System.out.println("WASI_CONCURRENCY_LIMITS_OK blocking timed condition fair rejected");
    }
    public static void main(String[] args) throws InterruptedException {
        String result = verify();
        if (args.length == 1 && args[0].equals("--wasi-limits")) verifyWasiLimits();
        System.out.println(result);
    }
}
