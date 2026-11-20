package com.ghmanager.app;

import android.content.Context;
import android.content.res.Resources;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import java.util.Locale;

/** App language (Arabic / English / follow system) and a cheap check used by the formatters. */
public final class Lang {
    private Lang() {
    }

    private static volatile boolean arabic = true;

    public static boolean isAr() {
        return arabic;
    }

    /** Applies the saved choice. Called at startup and whenever the user changes the language. */
    public static void apply(Context c) {
        String v = Store.language(c);
        if ("ar".equals(v) || "en".equals(v)) {
            arabic = "ar".equals(v);
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(v));
        } else {
            Locale sys = Resources.getSystem().getConfiguration().getLocales().get(0);
            arabic = !"en".equals(sys.getLanguage());
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
        }
    }

    /** Sets the flag only (no activity recreation) - used from Application.onCreate. */
    public static void init(Context c) {
        String v = Store.language(c);
        if ("ar".equals(v)) arabic = true;
        else if ("en".equals(v)) arabic = false;
        else arabic = !"en".equals(Resources.getSystem().getConfiguration().getLocales().get(0).getLanguage());
    }
}
