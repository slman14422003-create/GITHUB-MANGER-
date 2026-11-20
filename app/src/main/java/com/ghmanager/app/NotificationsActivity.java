package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The GitHub notification inbox: unread / all, open the item, mark one or everything as read. */
public class NotificationsActivity extends BaseRepoActivity {
    private static final int PER_PAGE = 40;
    private final List<JSONObject> all = new ArrayList<>();
    private boolean showAll = false;
    private int page = 1;
    private boolean hasMore = false;
    private int gen = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        bindHeader(getString(R.string.nt_title), getString(R.string.nt_sub));
        initList();
        btnRefresh.setOnClickListener(v -> load(true));
        action(btnA1, R.drawable.ic_check_circle, R.string.nt_mark_all, v -> confirm(getString(R.string.nt_mark_all),
                getString(R.string.nt_mark_all_msg), R.string.nt_mark_all_go, this::markAll));
        listView.setOnItemClickListener((p, v, pos, id) -> {
            if (pos == all.size()) {
                page++;
                load(false);
                return;
            }
            if (pos < 0 || pos > all.size()) return;
            openItem(all.get(pos));
        });
        buildChips();
        load(true);
    }

    private void buildChips() {
        chipRow.removeAllViews();
        String[] names = {getString(R.string.nt_unread), getString(R.string.filter_all)};
        for (int i = 0; i < names.length; i++) {
            final boolean wantAll = i == 1;
            TextView c = Ui.chip(this, names[i], wantAll == showAll);
            c.setOnClickListener(v -> {
                showAll = wantAll;
                buildChips();
                load(true);
            });
            chipRow.addView(c);
        }
        filterScroll.setVisibility(View.VISIBLE);
    }

    private void load(final boolean reset) {
        if (reset) page = 1;
        final int pg = page;
        final boolean wantAll = showAll;
        final int myGen = ++gen;
        loading(true);
        io.execute(() -> {
            try {
                JSONArray arr = api.listNotifications(wantAll, pg, PER_PAGE);
                final List<JSONObject> tmp = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) tmp.add(arr.getJSONObject(i));
                post(() -> {
                    if (myGen != gen) return;
                    loading(false);
                    showStatus(null);
                    if (pg == 1) all.clear();
                    all.addAll(tmp);
                    hasMore = tmp.size() >= PER_PAGE;
                    render();
                });
            } catch (Exception e) {
                if (myGen == gen) fail(e);
            }
        });
    }

    private static int iconFor(String type) {
        if ("PullRequest".equals(type)) return R.drawable.ic_pr;
        if ("Issue".equals(type)) return R.drawable.ic_issue;
        if ("Commit".equals(type)) return R.drawable.ic_commit;
        if ("Release".equals(type)) return R.drawable.ic_tag;
        return R.drawable.ic_info;
    }

    private void render() {
        List<Row> rows = new ArrayList<>();
        for (JSONObject o : all) {
            JSONObject subject = o.optJSONObject("subject");
            JSONObject repoObj = o.optJSONObject("repository");
            String type = subject == null ? "" : subject.optString("type");
            StringBuilder sub = new StringBuilder();
            if (repoObj != null) sub.append(repoObj.optString("full_name"));
            String reason = o.optString("reason").replace('_', ' ');
            if (!reason.isEmpty()) sub.append(" · ").append(reason);
            String ago = Fmt.ago(Fmt.s(o, "updated_at"));
            if (!ago.isEmpty()) sub.append(" · ").append(ago);
            boolean unread = o.optBoolean("unread");
            Row r = new Row(iconFor(type), false, subject == null ? "" : subject.optString("title"),
                    sub.toString(), false, true);
            if (unread) r.badge(getString(R.string.nt_new), Ui.color(this, R.color.accent_text));
            rows.add(r);
        }
        if (hasMore) rows.add(new Row(R.drawable.ic_refresh, false, getString(R.string.load_more), null, false, false));
        adapter.setRows(rows);
        showEmpty(all.isEmpty(), R.string.nt_none);
    }

    private void openItem(final JSONObject o) {
        final String id = o.optString("id");
        if (o.optBoolean("unread")) {
            io.execute(() -> {
                try {
                    api.markNotificationRead(id);
                } catch (Exception ignored) {
                }
            });
            try {
                o.put("unread", false);
            } catch (Exception ignored) {
            }
            render();
        }
        JSONObject subject = o.optJSONObject("subject");
        JSONObject repoObj = o.optJSONObject("repository");
        if (repoObj == null) return;
        String full = repoObj.optString("full_name");
        String url = subject == null ? "" : subject.optString("url");
        String type = subject == null ? "" : subject.optString("type");
        int slash = full.indexOf('/');
        long number = 0;
        int cut = url.lastIndexOf('/');
        if (cut >= 0) {
            try {
                number = Long.parseLong(url.substring(cut + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        if (slash > 0 && number > 0 && ("Issue".equals(type) || "PullRequest".equals(type))) {
            Intent i = new Intent(this, IssueDetailActivity.class);
            i.putExtra("owner", full.substring(0, slash));
            i.putExtra("repo", full.substring(slash + 1));
            i.putExtra("number", number);
            startActivity(i);
        } else {
            openUrl(repoObj.optString("html_url"));
        }
    }

    private void markAll() {
        bg(() -> {
            api.markAllNotificationsRead();
            post(() -> {
                toast(R.string.nt_marked);
                load(true);
            });
        });
    }
}
