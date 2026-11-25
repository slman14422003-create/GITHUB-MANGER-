package com.ghmanager.app;

import java.util.LinkedHashMap;
import java.util.Map;

final class ApiEtags {
    private ApiEtags() {
    }

    private static final int MAX = 80;
    private static final Map<String, String[]> CACHE = new LinkedHashMap<String, String[]>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String[]> eldest) {
            return size() > MAX;
        }
    };

    static String[] get(String key) {
        synchronized (CACHE) {
            return CACHE.get(key);
        }
    }

    static void put(String key, String[] value) {
        synchronized (CACHE) {
            CACHE.put(key, value);
        }
    }

    static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }
}
