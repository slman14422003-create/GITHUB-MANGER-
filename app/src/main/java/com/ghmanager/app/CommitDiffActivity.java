package com.ghmanager.app;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommitDiffActivity extends BaseRepoActivity {
    static final String MODE_COMMIT = "commit";
    static final String MODE_COMPARE = "compare";
    static final String MODE_PULL = "pull";

    private String mode = MODE_COMMIT;
    private String sha = "";
    private long number;
    private String base = "";
    private String head = "";
    private String fileFilter = "";
    private LinearLayout content;
    private JSONObject data;
    private JSONObject pull;
    private JSONArray files = new JSONArray();
    private JSONArray commits = new JSONArray();
    private static final int MAX_LINES = 1500;
    private JSONArray serverComments = new JSONArray();
    private final Map<String, List<JSONObject>> serverByKey = new HashMap<>();
    private PendingReviews.Review review = new PendingReviews.Review();
    private Button reviewBtn;
    private boolean headAsked;
    private final List<View> bodies = new ArrayList<>();
    private final List<View> arrows = new ArrayList<>();

    static Intent commit(BaseRepoActivity from, String sha, String file) {
        Intent i = from.repoIntent(CommitDiffActivity.class);
        i.putExtra("mode", MODE_COMMIT);
        i.putExtra("sha", sha);
        if (file != null) i.putExtra("file", file);
        return i;
    }

    static Intent compare(BaseRepoActivity from, String base, String head) {
        Intent i = from.repoIntent(CommitDiffActivity.class);
        i.putExtra("mode", MODE_COMPARE);
        i.putExtra("base", base);
        i.putExtra("head", head);
        return i;
    }

    static Intent pull(BaseRepoActivity from, long number) {
        Intent i = from.repoIntent(CommitDiffActivity.class);
        i.putExtra("mode", MODE_PULL);
        i.putExtra("number", number);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        Intent in = getIntent();
        String m = in.getStringExtra("mode");
        if (m != null) mode = m;
        String s = in.getStringExtra("sha");
        sha = s == null ? "" : s;
        number = in.getLongExtra("number", 0);
        String b = in.getStringExtra("base");
        base = b == null ? "" : b;
        String h = in.getStringExtra("head");
        head = h == null ? "" : h;
        String f = in.getStringExtra("file");
        fileFilter = f == null ? "" : f;
        String title;
        if (MODE_COMPARE.equals(mode)) title = getString(R.string.df_compare);
        else if (MODE_PULL.equals(mode)) title = getString(R.string.df_pr_files, number);
        else title = Fmt.shortSha(sha);
        bindHeader(title, repo);
        wireRootNav();
        content = findViewById(R.id.content);
        btnRefresh.setOnClickListener(v -> load());
        action(btnA1, R.drawable.ic_sparkles, R.string.sm_btn, v -> summarize());
        action(btnA2, R.drawable.ic_more, R.string.more, v -> moreMenu());
        load();
    }

    private void load() {
        loading(true);
        io.execute(() -> {
            try {
                JSONObject d = null;
                JSONObject pr = null;
                JSONArray fs = new JSONArray();
                JSONArray cs = new JSONArray();
                JSONArray pcs = new JSONArray();
                if (MODE_COMPARE.equals(mode)) {
                    d = api.compare(owner, repo, base, head);
                    JSONArray a = d.optJSONArray("files");
                    if (a != null) fs = a;
                    JSONArray c = d.optJSONArray("commits");
                    if (c != null) cs = c;
                } else if (MODE_PULL.equals(mode)) {
                    pr = api.getPull(owner, repo, number);
                    for (int pg = 1; pg <= 3; pg++) {
                        JSONArray p = api.listPullFiles(owner, repo, number, pg);
                        for (int i = 0; i < p.length(); i++) fs.put(p.get(i));
                        if (p.length() < 100) break;
                    }
                    cs = api.listPullCommits(owner, repo, number);
                    try {
                        for (int pg = 1; pg <= 3; pg++) {
                            JSONArray p = api.listPullComments(owner, repo, number, pg);
                            for (int i = 0; i < p.length(); i++) pcs.put(p.get(i));
                            if (p.length() < 100) break;
                        }
                    } catch (GitHubApi.ApiException e) {
                        if (e.code == 401) throw e;
                    } catch (java.io.IOException ignored) {
                    }
                } else {
                    d = api.getCommit(owner, repo, sha);
                    JSONArray a = d.optJSONArray("files");
                    if (a != null) fs = a;
                }
                final JSONObject fd = d;
                final JSONObject fpr = pr;
                final JSONArray ffs = fs;
                final JSONArray fcs = cs;
                final JSONArray fpcs = pcs;
                post(() -> {
                    loading(false);
                    showStatus(null);
                    data = fd;
                    pull = fpr;
                    files = ffs;
                    commits = fcs;
                    if (MODE_PULL.equals(mode)) {
                        serverComments = fpcs;
                        indexComments();
                        review = PendingReviews.load(this, owner, repo, number);
                    }
                    render();
                    checkHeadMoved();
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private TextView pill(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.radius(this, R.dimen.radius_pill));
        g.setColor((color & 0x00FFFFFF) | 0x26000000);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 8));
        t.setLayoutParams(lp);
        return t;
    }

    private int totalAdd() {
        int n = 0;
        for (int i = 0; i < files.length(); i++) n += files.optJSONObject(i).optInt("additions");
        return n;
    }

    private int totalDel() {
        int n = 0;
        for (int i = 0; i < files.length(); i++) n += files.optJSONObject(i).optInt("deletions");
        return n;
    }

    private void render() {
        content.removeAllViews();
        bodies.clear();
        arrows.clear();
        content.addView(hero());
        reviewBtn = null;
        if (MODE_PULL.equals(mode) && pull != null) content.addView(reviewBar());

        if (commits.length() > 0 && !MODE_COMMIT.equals(mode)) {
            content.addView(Ui.sectionTitle(this, getString(R.string.df_commits_n, commits.length())));
            int shown = Math.min(commits.length(), 30);
            for (int i = 0; i < shown; i++) {
                final JSONObject co = commits.optJSONObject(i);
                if (co == null) continue;
                JSONObject cm = co.optJSONObject("commit");
                String msg = cm == null ? "" : Fmt.firstLine(cm.optString("message"));
                JSONObject au = cm == null ? null : cm.optJSONObject("author");
                String sub = Fmt.shortSha(co.optString("sha"))
                        + (au == null ? "" : " · " + au.optString("name") + " · " + Fmt.ago(Fmt.s(au, "date")));
                content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_commit, true, msg, sub, false, true),
                        v -> startActivity(commit(this, co.optString("sha"), null))));
            }
            if (commits.length() > shown) {
                content.addView(Ui.body(this, getString(R.string.df_more_commits, commits.length() - shown), 12,
                        R.color.text_hint));
            }
        }

        int visible = 0;
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            if (!fileFilter.isEmpty() && !fileFilter.equals(f.optString("filename"))) continue;
            visible++;
        }
        content.addView(Ui.sectionTitle(this, getString(R.string.df_files_n, visible)));
        if (visible > 1) content.addView(expandBar());
        if (visible == 0) {
            content.addView(Ui.body(this, getString(R.string.df_no_files), 14, R.color.text_hint));
            return;
        }
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            if (!fileFilter.isEmpty() && !fileFilter.equals(f.optString("filename"))) continue;
            View card = fileCard(f, visible == 1);
            content.addView(card);
        }
    }

    private View expandBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPaddingRelative(Ui.dp(this, 22), 0, Ui.dp(this, 16), Ui.dp(this, 6));
        TextView open = Ui.chip(this, getString(R.string.log_expand_all), false);
        open.setOnClickListener(v -> setAll(true));
        TextView close = Ui.chip(this, getString(R.string.log_collapse_all), false);
        close.setOnClickListener(v -> setAll(false));
        row.addView(open);
        row.addView(close);
        return row;
    }

    private void setAll(boolean open) {
        TransitionManager.beginDelayedTransition(content, Motion.layout(this));
        for (int i = 0; i < bodies.size(); i++) {
            View b = bodies.get(i);
            if (open) ensureBody(b);
            b.setVisibility(open ? View.VISIBLE : View.GONE);
            arrows.get(i).animate().rotation(open ? 90f : 0f).setDuration(Motion.base(this)).start();
        }
    }

    private View hero() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = Ui.dp(this, 18);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 4));
        card.setLayoutParams(clp);

        String title = "";
        String rest = "";
        String meta = "";
        if (MODE_COMMIT.equals(mode) && data != null) {
            JSONObject cm = data.optJSONObject("commit");
            String msg = cm == null ? "" : cm.optString("message");
            title = Fmt.firstLine(msg);
            int nl = msg.indexOf('\n');
            if (nl > 0) rest = msg.substring(nl + 1).trim();
            JSONObject au = cm == null ? null : cm.optJSONObject("author");
            if (au != null) meta = au.optString("name") + " · " + Fmt.ago(Fmt.s(au, "date"));
            meta = Fmt.shortSha(sha) + (meta.isEmpty() ? "" : " · " + meta);
        } else if (MODE_PULL.equals(mode) && pull != null) {
            title = pull.optString("title");
            JSONObject h = pull.optJSONObject("head");
            JSONObject b = pull.optJSONObject("base");
            if (h != null && b != null) meta = h.optString("ref") + " → " + b.optString("ref");
        } else {
            title = head + " → " + base;
            meta = base + "..." + head;
        }
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Ui.color(this, R.color.text_primary));
        t.setTextSize(18);
        t.setTypeface(Typeface.SERIF, Typeface.BOLD);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setTextIsSelectable(true);
        card.addView(t);
        if (!meta.isEmpty()) {
            TextView m = new TextView(this);
            m.setText(meta);
            m.setTextColor(Ui.color(this, R.color.text_secondary));
            m.setTextSize(13);
            m.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            m.setPadding(0, Ui.dp(this, 4), 0, 0);
            card.addView(m);
        }
        if (!rest.isEmpty()) {
            TextView r = new TextView(this);
            r.setText(rest);
            r.setTextColor(Ui.color(this, R.color.text_secondary));
            r.setTextSize(13);
            r.setLineSpacing(0, 1.15f);
            r.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            r.setPadding(0, Ui.dp(this, 10), 0, 0);
            r.setTextIsSelectable(true);
            card.addView(r);
        }
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, Ui.dp(this, 14), 0, 0);
        int add = totalAdd();
        int del = totalDel();
        if (MODE_COMPARE.equals(mode) && data != null) {
            chips.addView(pill(getString(R.string.df_ahead, data.optInt("ahead_by")), Ui.color(this, R.color.ok)));
            chips.addView(pill(getString(R.string.df_behind, data.optInt("behind_by")), Ui.color(this, R.color.warn)));
        }
        final TextView plus = pill("+" + add, Ui.color(this, R.color.ok));
        chips.addView(plus);
        chips.addView(pill("\u2212" + del, Ui.color(this, R.color.bad)));
        chips.addView(pill(getString(R.string.df_files_chip, files.length()), Ui.color(this, R.color.text_secondary)));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        card.addView(hs);

        int total = Math.max(1, add + del);
        card.addView(Ui.bar(this, add * 100.0 / total, R.color.ok), barLp());
        Ui.enter(card, 0);
        return card;
    }

    private LinearLayout.LayoutParams barLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 4));
        lp.topMargin = Ui.dp(this, 12);
        return lp;
    }

    private int statusColor(String st) {
        if ("added".equals(st)) return Ui.color(this, R.color.ok);
        if ("removed".equals(st)) return Ui.color(this, R.color.bad);
        if ("renamed".equals(st)) return Ui.color(this, R.color.info);
        return Ui.color(this, R.color.warn);
    }

    private View fileCard(final JSONObject f, boolean openNow) {
        final String name = f.optString("filename");
        String st = f.optString("status");
        int color = statusColor(st);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4));
        card.setLayoutParams(clp);
        card.setClipToOutline(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPaddingRelative(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12));
        header.setBackgroundResource(R.drawable.bg_card_ripple);
        Ui.press(this, header);

        View stripe = new View(this);
        GradientDrawable sg = new GradientDrawable();
        sg.setCornerRadius(Ui.dp(this, 2));
        sg.setColor(color);
        stripe.setBackground(sg);
        header.addView(stripe, new LinearLayout.LayoutParams(Ui.dp(this, 4), Ui.dp(this, 30)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.setMarginStart(Ui.dp(this, 12));
        nlp.setMarginEnd(Ui.dp(this, 8));
        TextView n = new TextView(this);
        int slash = name.lastIndexOf('/');
        n.setText(slash < 0 ? name : name.substring(slash + 1));
        n.setTextColor(Ui.color(this, R.color.text_primary));
        n.setTextSize(15);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        n.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        n.setTextDirection(View.TEXT_DIRECTION_LTR);
        names.addView(n);
        TextView dir = new TextView(this);
        String prev = f.optString("previous_filename");
        String sub = !prev.isEmpty() ? prev + " \u2192" : (slash < 0 ? "" : name.substring(0, slash));
        dir.setText(sub);
        dir.setTextColor(Ui.color(this, R.color.text_hint));
        dir.setTextSize(11);
        dir.setSingleLine(true);
        dir.setEllipsize(TextUtils.TruncateAt.START);
        dir.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        dir.setTextDirection(View.TEXT_DIRECTION_LTR);
        dir.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
        names.addView(dir);
        header.addView(names, nlp);

        TextView counts = new TextView(this);
        android.text.SpannableStringBuilder cs = new android.text.SpannableStringBuilder();
        String ad = "+" + f.optInt("additions");
        String de = " \u2212" + f.optInt("deletions");
        cs.append(ad).append(de);
        cs.setSpan(new android.text.style.ForegroundColorSpan(Ui.color(this, R.color.ok)), 0, ad.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        cs.setSpan(new android.text.style.ForegroundColorSpan(Ui.color(this, R.color.bad)), ad.length(), cs.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        counts.setText(cs);
        counts.setTextSize(12);
        counts.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        counts.setTextDirection(View.TEXT_DIRECTION_LTR);
        header.addView(counts);

        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_chevron);
        arrow.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.text_hint)));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20));
        alp.setMarginStart(Ui.dp(this, 6));
        header.addView(arrow, alp);
        card.addView(header);

        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        body.setTag(f);
        card.addView(body);
        bodies.add(body);
        arrows.add(arrow);

        header.setOnClickListener(v -> {
            TransitionManager.beginDelayedTransition(content, Motion.layout(this));
            boolean open = body.getVisibility() != View.VISIBLE;
            if (open) ensureBody(body);
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            arrow.animate().rotation(open ? 90f : 0f).setDuration(Motion.base(this)).start();
        });
        header.setOnLongClickListener(v -> {
            fileMenu(f);
            return true;
        });
        if (openNow) {
            ensureBody(body);
            body.setVisibility(View.VISIBLE);
            arrow.setRotation(90f);
        }
        Ui.enter(card, 2);
        return card;
    }

    private void ensureBody(View b) {
        if (!(b instanceof LinearLayout)) return;
        LinearLayout body = (LinearLayout) b;
        if (body.getChildCount() > 0) return;
        Object tag = body.getTag();
        if (!(tag instanceof JSONObject)) return;
        JSONObject f = (JSONObject) tag;
        String patch = f.optString("patch");
        if (MODE_PULL.equals(mode) && !patch.isEmpty()) {
            lineBody(body, f.optString("filename"), patch);
            return;
        }
        if (patch.isEmpty()) {
            TextView none = new TextView(this);
            none.setText(R.string.df_no_patch);
            none.setTextColor(Ui.color(this, R.color.text_hint));
            none.setTextSize(13);
            none.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 14));
            body.addView(none);
            return;
        }
        List<CharSequence> chunks = LineDiff.patchChunks(this, patch);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPaddingRelative(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 12));
        for (CharSequence cs : chunks) {
            TextView t = new TextView(this);
            t.setText(cs);
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextSize(12);
            t.setLineSpacing(Ui.dp(this, 2), 1f);
            t.setTextColor(Ui.color(this, R.color.text_primary));
            t.setTextDirection(View.TEXT_DIRECTION_LTR);
            t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            t.setHorizontallyScrolling(true);
            inner.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(inner);
        body.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private String refForBlob() {
        if (MODE_COMMIT.equals(mode)) return sha;
        if (MODE_COMPARE.equals(mode)) return head;
        if (pull != null) {
            JSONObject h = pull.optJSONObject("head");
            if (h != null) return h.optString("sha");
        }
        return branch;
    }

    private void fileMenu(final JSONObject f) {
        final String name = f.optString("filename");
        String[] items = {getString(R.string.copy_path), getString(R.string.df_history),
                getString(R.string.open_in_github)};
        choose(name, items, (d, which) -> {
            if (which == 0) {
                copy("path", name);
            } else if (which == 1) {
                Intent i = repoIntent(CommitsActivity.class);
                i.putExtra("path", name);
                startActivity(i);
            } else {
                String raw = f.optString("blob_url");
                if (!raw.isEmpty()) openUrl(raw);
                else openUrl(webUrl("/blob/" + refForBlob() + "/" + name));
            }
        });
    }

    private JSONObject synthetic() {
        try {
            JSONObject s = new JSONObject();
            JSONObject cm = new JSONObject();
            if (MODE_COMMIT.equals(mode) && data != null) return data;
            if (MODE_PULL.equals(mode) && pull != null) {
                cm.put("message", pull.optString("title"));
                JSONObject u = pull.optJSONObject("user");
                JSONObject au = new JSONObject();
                au.put("name", u == null ? "" : u.optString("login"));
                au.put("date", pull.optString("created_at"));
                cm.put("author", au);
            } else {
                JSONObject lastCm = null;
                if (commits.length() > 0) {
                    JSONObject last = commits.optJSONObject(commits.length() - 1);
                    if (last != null) lastCm = last.optJSONObject("commit");
                }
                if (lastCm != null) cm = lastCm;
                else cm.put("message", head + " \u2192 " + base);
            }
            s.put("commit", cm);
            s.put("files", files);
            return s;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void summarize() {
        if (files.length() == 0 && data == null && pull == null) return;
        SemoExplain.showSummary(this, synthetic());
    }

    private void moreMenu() {
        final boolean discard = MODE_PULL.equals(mode) && !review.comments.isEmpty();
        String[] items = discard
                ? new String[]{getString(R.string.open_in_github), getString(R.string.copy_link),
                getString(R.string.rv_discard)}
                : new String[]{getString(R.string.open_in_github), getString(R.string.copy_link)};
        choose(getString(R.string.more), items, (d, which) -> {
            if (which == 2) {
                confirm(getString(R.string.rv_discard), getString(R.string.rv_discard_msg), R.string.rv_discard, () -> {
                    PendingReviews.clear(this, owner, repo, number);
                    review = new PendingReviews.Review();
                    updateReviewBtn();
                    refreshBodies();
                });
                return;
            }
            String url;
            if (MODE_COMMIT.equals(mode)) url = webUrl("/commit/" + sha);
            else if (MODE_PULL.equals(mode)) url = webUrl("/pull/" + number + "/files");
            else url = webUrl("/compare/" + base + "..." + head);
            if (which == 0) openUrl(url);
            else copy("link", url);
        });
    }

    private String headSha() {
        JSONObject h = pull == null ? null : pull.optJSONObject("head");
        return h == null ? "" : h.optString("sha");
    }

    private void indexComments() {
        serverByKey.clear();
        for (int i = 0; i < serverComments.length(); i++) {
            JSONObject c = serverComments.optJSONObject(i);
            if (c == null || c.isNull("line")) continue;
            int line = c.optInt("line");
            if (line <= 0) continue;
            String side = "LEFT".equals(c.optString("side")) ? "LEFT" : "RIGHT";
            String k = PatchLines.key(c.optString("path"), side, line);
            List<JSONObject> l = serverByKey.get(k);
            if (l == null) {
                l = new ArrayList<>();
                serverByKey.put(k, l);
            }
            l.add(c);
        }
    }

    private void checkHeadMoved() {
        if (!MODE_PULL.equals(mode) || headAsked || review.comments.isEmpty() || review.commitId.isEmpty()) return;
        final String now = headSha();
        if (now.isEmpty() || review.commitId.equals(now)) return;
        headAsked = true;
        new Dlg(this)
                .setTitle(R.string.rv_moved_title)
                .setMessage(getString(R.string.rv_moved_msg, Fmt.shortSha(review.commitId), Fmt.shortSha(now)))
                .setPositiveButton(R.string.rv_moved_restart, (d, w) -> {
                    PendingReviews.clear(this, owner, repo, number);
                    review = new PendingReviews.Review();
                    updateReviewBtn();
                    refreshBodies();
                })
                .setNegativeButton(R.string.rv_moved_keep, null)
                .show();
    }

    private View reviewBar() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        reviewBtn = Ui.button(this, R.string.pr_review, true);
        updateReviewBtn();
        reviewBtn.setOnClickListener(v -> ReviewDialog.show(this, number, headSha(), () -> {
            review = PendingReviews.load(this, owner, repo, number);
            load();
        }));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), 0);
        col.addView(reviewBtn, lp);
        col.addView(Ui.body(this, getString(R.string.rv_hint), 12, R.color.text_hint));
        return col;
    }

    private void updateReviewBtn() {
        if (reviewBtn == null) return;
        int n = review.comments.size();
        reviewBtn.setText(n > 0 ? getString(R.string.rv_btn, n) : getString(R.string.pr_review));
    }

    private void refreshBodies() {
        for (View b : bodies) {
            if (b instanceof LinearLayout && ((LinearLayout) b).getChildCount() > 0) {
                ((LinearLayout) b).removeAllViews();
                ensureBody(b);
            }
        }
    }

    private static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private void lineBody(LinearLayout body, String path, String patch) {
        List<PatchLines.Line> ls = PatchLines.parse(patch);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPaddingRelative(0, Ui.dp(this, 4), 0, Ui.dp(this, 10));
        int limit = Math.min(ls.size(), MAX_LINES);
        for (int i = 0; i < limit; i++) addLine(inner, path, ls.get(i));
        if (ls.size() > limit) inner.addView(Ui.body(this, getString(R.string.df_truncated), 12, R.color.text_hint));
        body.addView(inner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addLine(LinearLayout inner, final String path, final PatchLines.Line l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (l.type == '+') row.setBackgroundColor(alpha(Ui.color(this, R.color.ok), 44));
        else if (l.type == '-') row.setBackgroundColor(alpha(Ui.color(this, R.color.bad), 44));
        else if (l.type == '@') row.setBackgroundColor(alpha(Ui.color(this, R.color.info), 30));
        boolean code = l.type == '+' || l.type == '-' || l.type == ' ';
        final boolean can = l.commentable();
        PendingReviews.Comment mine = can ? review.find(path, l.side(), l.number()) : null;

        TextView gutter = new TextView(this);
        gutter.setText(can ? String.valueOf(l.number()) : "");
        gutter.setTypeface(Typeface.MONOSPACE, mine != null ? Typeface.BOLD : Typeface.NORMAL);
        gutter.setTextSize(11);
        gutter.setGravity(Gravity.END);
        gutter.setTextDirection(View.TEXT_DIRECTION_LTR);
        gutter.setTextColor(Ui.color(this, mine != null ? R.color.accent : R.color.text_hint));
        gutter.setPaddingRelative(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2));
        row.addView(gutter, new LinearLayout.LayoutParams(Ui.dp(this, 52), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView t = new TextView(this);
        String prefix = l.type == '+' ? "+ " : l.type == '-' ? "\u2212 " : code ? "  " : "";
        t.setText(prefix + l.text);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(12);
        t.setTextColor(Ui.color(this, code ? R.color.text_primary : R.color.text_hint));
        t.setTextDirection(View.TEXT_DIRECTION_LTR);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setPaddingRelative(0, Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2));
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (can) row.setOnClickListener(v -> commentDialog(path, l));
        inner.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!can) return;
        List<JSONObject> server = serverByKey.get(PatchLines.key(path, l.side(), l.number()));
        if (server != null) {
            for (JSONObject c : server) {
                JSONObject u = c.optJSONObject("user");
                String head = (u == null ? "" : u.optString("login")) + " \u00B7 " + Fmt.ago(Fmt.s(c, "created_at"));
                inner.addView(Ui.noteCard(this, head + "\n" + c.optString("body"), R.color.text_secondary));
            }
        }
        if (mine != null) {
            TextView n = Ui.noteCard(this, getString(R.string.rv_pending_badge) + "\n" + mine.body,
                    R.color.text_primary);
            n.setTextIsSelectable(false);
            n.setOnClickListener(v -> commentDialog(path, l));
            inner.addView(n);
        }
    }

    private void commentDialog(final String path, final PatchLines.Line l) {
        final String side = l.side();
        final int line = l.number();
        final PendingReviews.Comment ex = review.find(path, side, line);
        LinearLayout box = Ui.box(this);
        final EditText e = Ui.editMulti(this, getString(R.string.rv_comment_hint), ex == null ? null : ex.body, 4);
        box.addView(e);
        int slash = path.lastIndexOf('/');
        String name = (slash < 0 ? path : path.substring(slash + 1)) + ":" + line
                + ("LEFT".equals(side) ? " (\u2212)" : "");
        androidx.appcompat.app.AlertDialog.Builder b = new Dlg(this).setTitle(name).setView(box);
        b.setPositiveButton(R.string.save, (d, w) -> {
            String txt = e.getText().toString().trim();
            if (txt.isEmpty()) {
                Dlg.stay(d);
                return;
            }
            if (review.commitId.isEmpty()) review.commitId = headSha();
            review.upsert(new PendingReviews.Comment(path, side, line, txt));
            PendingReviews.save(this, owner, repo, number, review);
            updateReviewBtn();
            refreshBodies();
        });
        if (ex != null) {
            b.setNeutralButton(R.string.delete, (d, w) -> {
                review.remove(path, side, line);
                if (review.comments.isEmpty()) review.commitId = "";
                PendingReviews.save(this, owner, repo, number, review);
                updateReviewBtn();
                refreshBodies();
            });
        }
        b.setNegativeButton(R.string.cancel, null);
        b.show();
    }
}
