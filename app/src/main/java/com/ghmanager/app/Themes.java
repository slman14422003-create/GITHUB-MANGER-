package com.ghmanager.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

import java.util.WeakHashMap;

final class Themes {
    static final int DARK = 0;
    static final int LIGHT = 1;
    static final int AMOLED = 2;
    static final int SYSTEM = 3;

    private static final WeakHashMap<Activity, Integer> APPLIED = new WeakHashMap<>();

    private Themes() {
    }

    static int resolve(Context c) {
        int mode = Store.theme(c);
        if (mode != SYSTEM) return mode;
        int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_NO ? LIGHT : DARK;
    }

    static void apply(Activity a) {
        int t = resolve(a);
        APPLIED.put(a, t);
        if (t == LIGHT) a.getTheme().applyStyle(R.style.Overlay_Light, true);
        else if (t == AMOLED) a.getTheme().applyStyle(R.style.Overlay_Amoled, true);
    }

    static void refresh(Activity a) {
        Integer cur = APPLIED.get(a);
        if (cur != null && cur != resolve(a)) a.recreate();
    }
}
