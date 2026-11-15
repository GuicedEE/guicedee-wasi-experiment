package org.teavm.classlib.java.util.concurrent;

import java.io.Serializable;
import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Queue for TeaVM's cooperative, single-host-thread runtime. Operations never yield.
 * This implementation is not safe for shared-memory Wasm threads. */
public class TConcurrentLinkedQueue<E> extends AbstractQueue<E> implements Serializable {
    private static final class Node<E> {
        E value;
        Node<E> next;
        Node(E value) { this.value = value; }
    }
    private Node<E> head = new Node<>(null);
    private Node<E> tail = head;

    public TConcurrentLinkedQueue() { }
    public TConcurrentLinkedQueue(Collection<? extends E> values) { addAll(values); }

    @Override public boolean offer(E value) {
        Node<E> node = new Node<>(Objects.requireNonNull(value));
        tail.next = node;
        tail = node;
        return true;
    }

    @Override public E peek() {
        Node<E> node = head.next;
        while (node != null && node.value == null) {
            head = node;
            node = node.next;
        }
        return node == null ? null : node.value;
    }

    @Override public E poll() {
        E value = peek();
        if (value != null) {
            head = head.next;
            head.value = null;
        }
        return value;
    }

    @Override public int size() {
        int size = 0;
        for (Node<E> node = head.next; node != null; node = node.next) {
            if (node.value != null && size != Integer.MAX_VALUE) size++;
        }
        return size;
    }

    @Override public Iterator<E> iterator() {
        return new Iterator<>() {
            private Node<E> next = head.next;
            private Node<E> last;
            private E value;
            private void advance() {
                while (next != null && value == null) {
                    value = next.value;
                    if (value == null) next = next.next;
                }
            }
            @Override public boolean hasNext() { advance(); return value != null; }
            @Override public E next() {
                if (!hasNext()) throw new NoSuchElementException();
                E result = value;
                last = next;
                next = next.next;
                value = null;
                return result;
            }
            @Override public void remove() {
                if (last == null) throw new IllegalStateException();
                last.value = null;
                last = null;
            }
        };
    }
}
