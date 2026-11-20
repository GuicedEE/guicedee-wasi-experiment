package org.teavm.classlib.java.util.concurrent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Backed key-set view for the experiment's cooperative, single-host-thread profile. */
public final class TConcurrentHashMap$KeySetView<K, V> extends AbstractSet<K> {
    private final ConcurrentHashMap<K, V> map;
    private final V mappedValue;
    public TConcurrentHashMap$KeySetView(ConcurrentHashMap<K, V> map, V mappedValue) {
        this.map = map; this.mappedValue = mappedValue;
    }
    public ConcurrentHashMap<K, V> getMap() { return map; }
    public V getMappedValue() { return mappedValue; }
    @Override public int size() { return map.size(); }
    @Override public void clear() { map.clear(); }
    @Override public boolean contains(Object key) { return map.containsKey(Objects.requireNonNull(key)); }
    @Override public boolean remove(Object key) { return map.remove(Objects.requireNonNull(key)) != null; }
    @Override public boolean add(K key) {
        if (mappedValue == null) throw new UnsupportedOperationException("This key view has no mapped value");
        return map.putIfAbsent(Objects.requireNonNull(key), mappedValue) == null;
    }
    @Override public Iterator<K> iterator() {
        // A snapshot is a valid weakly consistent traversal; removals still affect the map.
        // No suspension occurs while copying keys in this cooperative profile.
        Object[] keys = ((Map<K, V>) map).keySet().toArray();
        return new Iterator<>() {
            int position;
            int removable = -1;
            @Override public boolean hasNext() { return position < keys.length; }
            @SuppressWarnings("unchecked") @Override public K next() {
                if (!hasNext()) throw new NoSuchElementException();
                removable = position;
                return (K) keys[position++];
            }
            @Override public void remove() {
                if (removable < 0) throw new IllegalStateException();
                map.remove(keys[removable]); removable = -1;
            }
        };
    }
}
