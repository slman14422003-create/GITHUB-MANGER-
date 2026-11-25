package com.ghmanager.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class PinnedWorkflows {
    private PinnedWorkflows() {
    }

    static final int LIMIT = 20;
    private static final String PREFIX = "wfp_";

    static final class Item {
        String owner = "";
        String repo = "";
        long id;
        String name = "";
        long at;

        String full() {
            return owner + "/" + repo;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("gh", Context.MODE_PRIVATE);
    }

    static String key(String owner, String repo, long id) {
        return PREFIX + owner + "/" + repo + "#" + id;
    }

    static boolean isPinned(Context c, String owner, String repo, long id) {
        return sp(c).contains(key(owner, repo, id));
    }

    static List<Item> all(Context c) {
        List<Item> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : sp(c).getAll().entrySet()) {
            if (!e.getKey().startsWith(PREFIX) || !(e.getValue() instanceof String)) continue;
            try {
                JSONObject o = new JSONObject((String) e.getValue());
                Item it = new Item();
                it.owner = o.optString("owner");
                it.repo = o.optString("repo");
                it.id = o.optLong("id");
                it.name = o.optString("name");
                it.at = o.optLong("at");
                if (!it.owner.isEmpty() && !it.repo.isEmpty() && it.id > 0) out.add(it);
            } catch (Exception ignored) {
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.at, b.at));
        return out;
    }

    static int count(Context c) {
        return all(c).size();
    }

    static boolean pin(Context c, String owner, String repo, long id, String name) {
        if (owner == null || repo == null || id <= 0) return false;
        if (!isPinned(c, owner, repo, id) && count(c) >= LIMIT) return false;
        try {
            JSONObject o = new JSONObject();
            o.put("owner", owner);
            o.put("repo", repo);
            o.put("id", id);
            o.put("name", name == null ? "" : name);
            o.put("at", System.currentTimeMillis());
            sp(c).edit().putString(key(owner, repo, id), o.toString()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void unpin(Context c, String owner, String repo, long id) {
        sp(c).edit().remove(key(owner, repo, id)).apply();
    }

    static Item find(Context c, String owner, String repo, long id) {
        for (Item it : all(c)) if (it.owner.equals(owner) && it.repo.equals(repo) && it.id == id) return it;
        return null;
    }
}
