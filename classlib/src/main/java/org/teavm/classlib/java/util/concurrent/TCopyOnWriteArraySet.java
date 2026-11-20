package org.teavm.classlib.java.util.concurrent;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Predicate;

/** Snapshot iterators and atomic copy-on-write mutations for the single-threaded
 * WASI profile. No claim of native multi-threaded WASM support. */
public class TCopyOnWriteArraySet<E> extends AbstractSet<E> implements java.io.Serializable {
    private volatile Object[] elements = new Object[0];
    public TCopyOnWriteArraySet() { }
    public TCopyOnWriteArraySet(Collection<? extends E> values) { addAll(values); }
    public int size() { return elements.length; }
    public boolean contains(Object value) {
        for(Object element:elements) if(Objects.equals(value,element)) return true;
        return false;
    }
    @SuppressWarnings("unchecked") public Iterator<E> iterator() {
        return (Iterator<E>)(Iterator<?>) Arrays.asList(elements).iterator();
    }
    public synchronized boolean add(E value) {
        if(contains(value)) return false;
        Object[] copy=Arrays.copyOf(elements,elements.length+1); copy[copy.length-1]=value; elements=copy; return true;
    }
    public synchronized boolean remove(Object value) { return removeIf(e->Objects.equals(value,e)); }
    public synchronized void clear() { elements=new Object[0]; }
    public synchronized boolean addAll(Collection<? extends E> values) {
        ArrayList<Object> copy=new ArrayList<>(Arrays.asList(elements));
        for(E value:values) if(!copy.contains(value)) copy.add(value);
        if(copy.size()==elements.length) return false;
        elements=copy.toArray(); return true;
    }
    @SuppressWarnings("unchecked") public synchronized boolean removeIf(Predicate<? super E> predicate) {
        Objects.requireNonNull(predicate);
        ArrayList<Object> copy=new ArrayList<>(); Object[] snapshot=elements;
        for(Object element:snapshot) if(!predicate.test((E)element)) copy.add(element);
        if(copy.size()==snapshot.length) return false;
        elements=copy.toArray(); return true;
    }
    public synchronized boolean removeAll(Collection<?> values) {
        Objects.requireNonNull(values); return removeIf(values::contains);
    }
    public synchronized boolean retainAll(Collection<?> values) {
        Objects.requireNonNull(values); return removeIf(v->!values.contains(v));
    }
}
