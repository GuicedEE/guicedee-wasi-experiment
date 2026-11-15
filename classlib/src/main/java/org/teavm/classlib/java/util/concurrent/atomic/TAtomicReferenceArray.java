package org.teavm.classlib.java.util.concurrent.atomic;

import java.io.Serializable;
import java.util.Arrays;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

/** Reference-identity atomics for a cooperative single-host-thread runtime.
 * No operation yields between checking a reference and storing its replacement. */
public class TAtomicReferenceArray<E> implements Serializable {
    private final Object[] values;
    public TAtomicReferenceArray(int length) { values = new Object[length]; }
    public TAtomicReferenceArray(E[] source) { values = Arrays.copyOf(source, source.length, Object[].class); }
    public final int length() { return values.length; }
    @SuppressWarnings("unchecked") public final E get(int index) { return (E) values[index]; }
    public final void set(int index, E value) { values[index] = value; }
    public final void lazySet(int index, E value) { set(index, value); }
    public final E getAndSet(int index, E value) { E previous = get(index); set(index, value); return previous; }
    public final boolean compareAndSet(int index, E expected, E value) {
        if (values[index] != expected) return false;
        values[index] = value;
        return true;
    }
    public final boolean weakCompareAndSet(int index, E expected, E value) { return compareAndSet(index, expected, value); }
    public final E getAndUpdate(int index, UnaryOperator<E> function) {
        E previous;
        E next;
        do { previous = get(index); next = function.apply(previous); } while (!compareAndSet(index, previous, next));
        return previous;
    }
    public final E updateAndGet(int index, UnaryOperator<E> function) {
        E previous;
        E next;
        do { previous = get(index); next = function.apply(previous); } while (!compareAndSet(index, previous, next));
        return next;
    }
    public final E getAndAccumulate(int index, E value, BinaryOperator<E> function) {
        return getAndUpdate(index, previous -> function.apply(previous, value));
    }
    public final E accumulateAndGet(int index, E value, BinaryOperator<E> function) {
        return updateAndGet(index, previous -> function.apply(previous, value));
    }
    @Override public String toString() { return Arrays.toString(values); }
}
