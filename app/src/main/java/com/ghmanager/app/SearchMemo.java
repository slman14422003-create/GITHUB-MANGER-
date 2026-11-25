package com.ghmanager.app;

import java.util.LinkedHashMap;
import java.util.Map;

final class SearchMemo<V> {
    private static final class Slot<V> {
        final V value;
        final long at;

        Slot(V value, long at) {
            this.value = value;
            this.at = at;
        }
    }

    private final int cap;
    private final long ttlMs;
    private final LinkedHashMap<String, Slot<V>> map;

    SearchMemo(final int cap, long ttlMs) {
        this.cap = cap;
        this.ttlMs = ttlMs;
        this.map = new LinkedHashMap<String, Slot<V>>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Slot<V>> eldest) {
                return size() > cap;
            }
        };
    }

    synchronized V get(String key, long now) {
        Slot<V> e = map.get(key);
        if (e == null) return null;
        if (now - e.at > ttlMs) {
            map.remove(key);
            return null;
        }
        return e.value;
    }

    synchronized void put(String key, V value, long now) {
        map.put(key, new Slot<>(value, now));
    }

    synchronized void clear() {
        map.clear();
    }

    synchronized int size() {
        return map.size();
    }

    static String key(int kind, String q, String scope, int page) {
        return kind + "|" + page + "|" + scope + "|" + q;
    }
}
