package com.guicedee.wasi.compiler;

/** Private runtime FIFO for TeaVM monitor waiters. Deliberately has no Collection
 * interface: unrelated suspendable collection implementations must not infect
 * the monitor's synchronous async-callback setup with coroutine split points. */
public final class MonitorQueue {
    private Object[] entries = new Object[8];
    private int head;
    private int count;

    public boolean add(Object value) {
        if (value == null) throw new NullPointerException();
        if (count == entries.length) {
            Object[] larger = new Object[entries.length * 2];
            for (int i = 0; i < count; i++) larger[i] = entries[(head + i) % entries.length];
            entries = larger; head = 0;
        }
        entries[(head + count) % entries.length] = value;
        count++;
        return true;
    }
    public Object remove() {
        if (count == 0) throw new java.util.NoSuchElementException();
        Object value = entries[head];
        entries[head] = null;
        head = (head + 1) % entries.length;
        count--;
        return value;
    }
    public boolean isEmpty() { return count == 0; }
}
