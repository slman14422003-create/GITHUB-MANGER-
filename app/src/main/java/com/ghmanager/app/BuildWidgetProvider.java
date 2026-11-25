package com.ghmanager.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.Locale;

public class BuildWidgetProvider extends AppWidgetProvider {
    static final String ACTION_REFRESH = "com.ghmanager.app.WIDGET_REFRESH";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("gh", Context.MODE_PRIVATE);
    }

    static String cfgKey(int id) {
        return "wdg_" + id;
    }

    static String cacheKey(int id) {
        return "wdgc_" + id;
    }

    static String flagKey(int id) {
        return "wdgx_" + id;
    }

    static int[] ids(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        return m.getAppWidgetIds(new ComponentName(c, BuildWidgetProvider.class));
    }

    static JSONObject config(Context c, int id) {
        String raw = sp(c).getString(cfgKey(id), "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    static void saveConfig(Context c, int id, PinnedWorkflows.Item it) {
        try {
            JSONObject o = new JSONObject();
            o.put("owner", it.owner);
            o.put("repo", it.repo);
            o.put("id", it.id);
            o.put("name", it.name);
            sp(c).edit().putString(cfgKey(id), o.toString()).remove(cacheKey(id)).remove(flagKey(id)).apply();
        } catch (Exception ignored) {
        }
    }

    static JSONObject cache(Context c, int id) {
        String raw = sp(c).getString(cacheKey(id), "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    static void saveCache(Context c, int id, JSONObject data) {
        sp(c).edit().putString(cacheKey(id), data.toString()).apply();
    }

    static void setFlag(Context c, int id, String flag) {
        SharedPreferences.Editor e = sp(c).edit();
        if (flag == null || flag.isEmpty()) e.remove(flagKey(id));
        else e.putString(flagKey(id), flag);
        e.apply();
    }

    static String flag(Context c, int id) {
        String v = sp(c).getString(flagKey(id), "");
        return v == null ? "" : v;
    }

    private static int tone(String s, String conc) {
        switch (Status.state(s, conc)) {
            case "success":
                return 0xFF4ADE80;
            case "failure":
            case "timed_out":
            case "startup_failure":
                return 0xFFFF6B6B;
            case "in_progress":
                return 0xFF6AA6FF;
            case "queued":
            case "waiting":
            case "pending":
            case "requested":
            case "action_required":
                return 0xFFF5B94A;
            default:
                return 0xFFB7B7C2;
        }
    }

    static Context localized(Context c) {
        Configuration conf = new Configuration(c.getResources().getConfiguration());
        conf.setLocale(Lang.isAr() ? new Locale("ar") : Locale.ENGLISH);
        return c.createConfigurationContext(conf);
    }

    private static PendingIntent open(Context c, int id, Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, id, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void render(Context base, AppWidgetManager mgr, int id) {
        Context c = localized(base.getApplicationContext());
        RemoteViews v = new RemoteViews(base.getPackageName(), R.layout.widget_build);
        Intent refresh = new Intent(base, BuildWidgetProvider.class).setAction(ACTION_REFRESH);
        v.setOnClickPendingIntent(R.id.wRefresh, PendingIntent.getBroadcast(base, 0, refresh,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        v.setViewVisibility(R.id.wStatus, View.GONE);
        v.setViewVisibility(R.id.wMeta, View.GONE);
        v.setViewVisibility(R.id.wIcon, View.GONE);
        v.setViewVisibility(R.id.wNote, View.GONE);
        v.setViewVisibility(R.id.wRefresh, View.VISIBLE);
        v.setTextViewText(R.id.wTitle, c.getString(R.string.wdg_name));
        boolean signedOut = Accounts.active(base) == null;
        JSONObject cfg = signedOut ? null : config(base, id);
        if (signedOut) {
            v.setViewVisibility(R.id.wRefresh, View.GONE);
            v.setTextViewText(R.id.wNote, c.getString(R.string.wdg_signed_out));
            v.setViewVisibility(R.id.wNote, View.VISIBLE);
            v.setOnClickPendingIntent(R.id.wRoot, open(base, id, new Intent(base, LoginActivity.class)));
        } else if (cfg == null) {
            v.setTextViewText(R.id.wNote, c.getString(R.string.wdg_config_needed));
            v.setViewVisibility(R.id.wNote, View.VISIBLE);
            Intent ci = new Intent(base, WidgetConfigActivity.class)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            v.setOnClickPendingIntent(R.id.wRoot, open(base, id, ci));
        } else {
            fillRun(base, c, v, id, cfg);
        }
        try {
            mgr.updateAppWidget(id, v);
        } catch (Exception ignored) {
        }
    }

    private static void fillRun(Context base, Context c, RemoteViews v, int id, JSONObject cfg) {
        String owner = cfg.optString("owner");
        String repo = cfg.optString("repo");
        v.setTextViewText(R.id.wTitle, cfg.optString("name"));
        JSONObject d = cache(base, id);
        String flag = flag(base, id);
        if (d == null) {
            v.setTextViewText(R.id.wNote, c.getString(R.string.wdg_no_data));
            v.setViewVisibility(R.id.wNote, View.VISIBLE);
            v.setOnClickPendingIntent(R.id.wRoot, open(base, id, new Intent(base, PinnedWorkflowsActivity.class)));
            return;
        }
        long at = d.optLong("at");
        if (!d.optBoolean("has")) {
            v.setTextViewText(R.id.wNote, c.getString(R.string.pw_no_runs));
            v.setViewVisibility(R.id.wNote, View.VISIBLE);
            v.setOnClickPendingIntent(R.id.wRoot, open(base, id, new Intent(base, PinnedWorkflowsActivity.class)));
            return;
        }
        String s = d.optString("status");
        String conc = d.optString("conclusion");
        int color = tone(s, conc);
        v.setViewVisibility(R.id.wIcon, View.VISIBLE);
        v.setImageViewResource(R.id.wIcon, Status.icon(s, conc));
        v.setInt(R.id.wIcon, "setColorFilter", color);
        v.setViewVisibility(R.id.wStatus, View.VISIBLE);
        v.setTextViewText(R.id.wStatus, Status.label(s, conc));
        v.setTextColor(R.id.wStatus, color);
        StringBuilder meta = new StringBuilder();
        meta.append("#").append(d.optInt("number"));
        String br = d.optString("branch");
        if (!br.isEmpty()) meta.append(" · ").append(br);
        long ms = d.optLong("ms");
        if (ms > 0) meta.append(" · ").append(Fmt.duration(ms));
        long created = d.optLong("created");
        if (created > 0) meta.append(" · ").append(Fmt.agoMs(created));
        v.setTextViewText(R.id.wMeta, meta.toString());
        v.setViewVisibility(R.id.wMeta, View.VISIBLE);
        String note = null;
        if ("expired".equals(flag)) note = c.getString(R.string.wdg_expired);
        else if ("offline".equals(flag)) note = c.getString(R.string.wdg_offline, Fmt.agoMs(at));
        else if (at > 0) note = c.getString(R.string.wdg_updated, Fmt.agoMs(at));
        if (note != null) {
            v.setTextViewText(R.id.wNote, note);
            v.setViewVisibility(R.id.wNote, View.VISIBLE);
        }
        Intent target;
        if ("expired".equals(flag)) {
            target = new Intent(base, LoginActivity.class).putExtra("reauth", true);
        } else {
            target = new Intent(base, RunDetailActivity.class)
                    .putExtra("owner", owner)
                    .putExtra("repo", repo)
                    .putExtra("runId", d.optLong("runId"));
            if (!br.isEmpty()) target.putExtra("branch", br);
        }
        v.setOnClickPendingIntent(R.id.wRoot, open(base, id, target));
    }

    static void renderAll(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            for (int id : ids(c)) render(c, m, id);
        } catch (Exception ignored) {
        }
    }

    static void onSignedOut(Context c) {
        if (c == null) return;
        try {
            WidgetRefreshJob.cancelAll(c);
            renderAll(c);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] appWidgetIds) {
        for (int id : appWidgetIds) render(c, mgr, id);
        if (Accounts.active(c) != null) WidgetRefreshJob.schedule(c);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (i != null && ACTION_REFRESH.equals(i.getAction())) {
            if (Accounts.active(c) != null) WidgetRefreshJob.runOnce(c);
            return;
        }
        super.onReceive(c, i);
    }

    @Override
    public void onDeleted(Context c, int[] appWidgetIds) {
        SharedPreferences.Editor e = sp(c).edit();
        for (int id : appWidgetIds) e.remove(cfgKey(id)).remove(cacheKey(id)).remove(flagKey(id));
        e.apply();
    }

    @Override
    public void onEnabled(Context c) {
        if (Accounts.active(c) != null) WidgetRefreshJob.schedule(c);
    }

    @Override
    public void onDisabled(Context c) {
        WidgetRefreshJob.cancelAll(c);
    }
}
