package com.guicedee.wasi.metadata;
import java.util.concurrent.ConcurrentHashMap;
import org.teavm.classlib.java.util.concurrent.TConcurrentHashMap$KeySetView;
public final class KeySets {
    public static <K> ConcurrentHashMap.KeySetView<K, Boolean> create() { return create(16); }
    @SuppressWarnings("unchecked") public static <K> ConcurrentHashMap.KeySetView<K, Boolean> create(int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("Negative key-set capacity");
        return (ConcurrentHashMap.KeySetView<K, Boolean>) (Object)
                new TConcurrentHashMap$KeySetView<K, Boolean>(new ConcurrentHashMap<>(capacity), Boolean.TRUE);
    }
}
