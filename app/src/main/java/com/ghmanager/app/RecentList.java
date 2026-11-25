package com.ghmanager.app;

import java.util.ArrayList;
import java.util.List;

final class RecentList {
    private RecentList() {
    }

    static List<String> push(List<String> list, String item, int max) {
        List<String> out = new ArrayList<>();
        if (item == null) return list == null ? out : new ArrayList<>(list);
        String t = item.trim();
        if (t.isEmpty()) return list == null ? out : new ArrayList<>(list);
        out.add(t);
        if (list != null) {
            for (String s : list) {
                if (s == null) continue;
                if (s.trim().equalsIgnoreCase(t)) continue;
                out.add(s);
                if (out.size() >= max) break;
            }
        }
        while (out.size() > max) out.remove(out.size() - 1);
        return out;
    }

    static List<String> remove(List<String> list, String item) {
        List<String> out = new ArrayList<>();
        if (list == null) return out;
        for (String s : list) {
            if (s != null && !s.equalsIgnoreCase(item)) out.add(s);
        }
        return out;
    }

    static List<String> prefixMatches(List<String> list, String typed, int max) {
        List<String> out = new ArrayList<>();
        if (list == null) return out;
        String t = typed == null ? "" : typed.trim().toLowerCase(java.util.Locale.ROOT);
        for (String s : list) {
            if (s == null) continue;
            if (t.isEmpty() || s.toLowerCase(java.util.Locale.ROOT).contains(t)) out.add(s);
            if (out.size() >= max) break;
        }
        return out;
    }
}
