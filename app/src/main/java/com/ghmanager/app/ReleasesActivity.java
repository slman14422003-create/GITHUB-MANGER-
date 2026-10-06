package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Releases of a repository: list and create (with auto-generated notes). */
public class ReleasesActivity extends BaseRepoActivity {
    private static final int PER_PAGE = 30;
    private final List<JSONObject> items = new ArrayList<>();
    private int page = 1;
    private boolean hasMore = false;
    private boolean firstLoad = true;
    private int gen = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        bindHeader(getString(R.string.releases), repo);
        initList();
        btnRefresh.setOnClickListener(v -> load(true));
        action(btnA1, R.drawable.ic_add, R.string.new_release, v -> createDialog());
        action(btnA2, R.drawable.ic_package, R.string.br_title, v -> buildReleaseDialog());
        action(btnA3, R.drawable.ic_check_circle, R.string.select_items, v -> selectReleasesForDelete());
        listView.setOnItemClickListener((p, v, pos, id) -> {
            if (pos == items.size()) {
                page++;
                load(false);
                return;
            }
            if (pos < 0 || pos > items.size()) return;
            Intent i = repoIntent(ReleaseDetailActivity.class);
            i.putExtra("releaseId", items.get(pos).optLong("id"));
            startActivity(i);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load(true);
        firstLoad = false;
    }

    private void load(final boolean reset) {
        if (reset) page = 1;
        final int pg = page;
        final int myGen = ++gen;
        loading(firstLoad || !reset);
        io.execute(() -> {
            try {
                JSONArray arr = api.listReleases(owner, repo, pg, PER_PAGE);
                final List<JSONObject> tmp = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) tmp.add(arr.getJSONObject(i));
                post(() -> {
                    if (myGen != gen) return;
                    loading(false);
                    showStatus(null);
                    if (pg == 1) items.clear();
                    items.addAll(tmp);
                    hasMore = tmp.size() >= PER_PAGE;
                    render();
                });
            } catch (Exception e) {
                if (myGen == gen) fail(e);
            }
        });
    }

    private void render() {
        List<Row> rows = new ArrayList<>();
        for (JSONObject o : items) {
            String name = Fmt.s(o, "name");
            String tag = Fmt.s(o, "tag_name");
            StringBuilder sub = new StringBuilder(tag);
            String target = Fmt.s(o, "target_commitish");
            if (!target.isEmpty() && !target.equals(tag)) sub.append(" · ").append(target);
            String when = Fmt.ago(Fmt.s(o, o.isNull("published_at") ? "created_at" : "published_at"));
            if (!when.isEmpty()) sub.append(" · ").append(when);
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null && assets.length() > 0) {
                sub.append(" · ").append(getString(R.string.files_count, assets.length()));
            }
            Row r = new Row(R.drawable.ic_tag, true, name.isEmpty() ? tag : name, sub.toString(), false, true);
            if (o.optBoolean("draft")) r.badge(getString(R.string.draft), Ui.color(this, R.color.warn));
            else if (o.optBoolean("prerelease")) r.badge(getString(R.string.prerelease), Ui.color(this, R.color.info));
            rows.add(r);
        }
        if (hasMore) rows.add(new Row(R.drawable.ic_refresh, false, getString(R.string.load_more), null, false, false));
        adapter.setRows(rows);
        showEmpty(items.isEmpty(), R.string.no_releases);
    }

    private void selectReleasesForDelete() {
        if(items.isEmpty()){toast(R.string.no_releases);return;}
        final List<CheckBox> checks=new ArrayList<>(); LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,8));
        for(JSONObject o:items){CheckBox cb=Ui.check(this, Fmt.s(o,"name") + " · " + Fmt.s(o,"tag_name"), false);checks.add(cb);box.addView(cb);}
        ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.addView(box);
        AlertDialog dialog=new Dlg(this).setTitle(R.string.select_items).setView(sv)
          .setNeutralButton(R.string.select_all,null)
          .setPositiveButton(R.string.delete_selected,(d,w)->{List<Long> ids=new ArrayList<>();for(int i=0;i<checks.size();i++)if(checks.get(i).isChecked())ids.add(items.get(i).optLong("id"));deleteSelectedReleases(ids);})
          .setNegativeButton(R.string.cancel,null).show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{boolean all=true;for(CheckBox c:checks)if(!c.isChecked()){all=false;break;}for(CheckBox c:checks)c.setChecked(!all);});
    }

    private void deleteSelectedReleases(final List<Long> ids){
        if(ids.isEmpty()){toast(R.string.nothing_selected);return;}
        confirm(getString(R.string.delete_selected),getString(R.string.delete_selected_msg,ids.size()),R.string.delete,()->{
            if(busy)return;busy=true;showProgress(getString(R.string.working));
            bg(()->{int ok=0;for(long id:ids){try{api.deleteRelease(owner,repo,id);ok++;}catch(Exception ignored){}}final int done=ok;post(()->{busy=false;hideProgress();toast(getString(R.string.deleted_count,done));load(true);});});
        });
    }

    private void createDialog() {
        LinearLayout box = Ui.box(this);
        final EditText tag = Ui.edit(this, getString(R.string.tag_name), null);
        final EditText target = Ui.edit(this, getString(R.string.target_branch), branch);
        final EditText title = Ui.edit(this, getString(R.string.release_title), null);
        final EditText notes = Ui.editMulti(this, getString(R.string.release_notes), null, 4);
        final CheckBox draft = Ui.check(this, R.string.draft, false);
        final CheckBox pre = Ui.check(this, R.string.prerelease, false);
        Button gen = Ui.button(this, R.string.generate_notes, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 10);
        gen.setLayoutParams(lp);
        gen.setOnClickListener(v -> {
            final String t = tag.getText().toString().trim();
            if (t.isEmpty()) {
                toast(R.string.tag_required);
                return;
            }
            final String tg = target.getText().toString().trim();
            bg(() -> {
                final JSONObject g = api.generateNotes(owner, repo, t, tg);
                post(() -> {
                    notes.setText(g.optString("body"));
                    if (title.getText().toString().trim().isEmpty()) title.setText(g.optString("name"));
                });
            });
        });
        box.addView(tag);
        box.addView(target);
        box.addView(title);
        box.addView(notes);
        box.addView(gen);
        box.addView(draft);
        box.addView(pre);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new Dlg(this)
                .setTitle(R.string.new_release)
                .setView(sv)
                .setPositiveButton(R.string.create, (d, w) -> {
                    final String t = tag.getText().toString().trim();
                    if (t.isEmpty()) {
                        toast(R.string.tag_required);
                        return;
                    }
                    final String tg = target.getText().toString().trim();
                    final String ti = title.getText().toString().trim();
                    final String no = notes.getText().toString();
                    final boolean isDraft = draft.isChecked();
                    final boolean isPre = pre.isChecked();
                    bg(() -> {
                        JSONObject b = new JSONObject();
                        b.put("tag_name", t);
                        if (!tg.isEmpty()) b.put("target_commitish", tg);
                        if (!ti.isEmpty()) b.put("name", ti);
                        b.put("body", no);
                        b.put("draft", isDraft);
                        b.put("prerelease", isPre);
                        api.createRelease(owner, repo, b);
                        post(() -> {
                            toast(R.string.release_created);
                            load(true);
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ build + publish a release

    /** Suggests the next tag by incrementing the last number of the newest published tag. */
    private String nextVersion() {
        for (JSONObject o : items) {
            if (o.optBoolean("draft")) continue;
            String tag = Fmt.s(o, "tag_name");
            if (tag.isEmpty()) continue;
            Matcher m = Pattern.compile("(\\d+)(?!.*\\d)").matcher(tag);
            if (m.find()) {
                try {
                    long n = Long.parseLong(m.group(1)) + 1;
                    return tag.substring(0, m.start(1)) + n + tag.substring(m.end(1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return "v1.0.0";
    }

    private void buildReleaseDialog() {
        if (busy) return;
        busy = true;
        showProgress(getString(R.string.br_loading));
        bg(() -> {
            final List<JSONObject> list = new ArrayList<>();
            JSONArray wfs = api.listWorkflows(owner, repo);
            for (int i = 0; wfs != null && i < wfs.length(); i++) {
                JSONObject w = wfs.getJSONObject(i);
                if ("active".equals(w.optString("state"))) list.add(w);
            }
            boolean hasKey = true;
            try {
                JSONArray sec = api.listSecrets(owner, repo);
                hasKey = false;
                for (int i = 0; sec != null && i < sec.length(); i++) {
                    if ("KEYSTORE_BASE64".equals(sec.getJSONObject(i).optString("name"))) hasKey = true;
                }
            } catch (Exception ignored) {
                hasKey = true; // secrets not readable: do not warn
            }
            final boolean fKey = hasKey;
            post(() -> {
                hideProgress();
                if (list.isEmpty()) {
                    info(getString(R.string.br_title), getString(R.string.br_no_workflow));
                } else {
                    showBuildDialog(list, fKey);
                }
            });
        });
    }

    private void showBuildDialog(final List<JSONObject> list, boolean hasKey) {
        List<String> names = new ArrayList<>();
        int def = 0;
        for (int i = 0; i < list.size(); i++) {
            JSONObject w = list.get(i);
            names.add(w.optString("name"));
            String key = (w.optString("name") + " " + w.optString("path")).toLowerCase(Locale.ROOT);
            if (def == 0 && (key.contains("build") || key.contains("apk") || key.contains("release"))) def = i;
        }
        final List<String> types = Arrays.asList("release", "debug");
        LinearLayout box = Ui.box(this);
        final Spinner wfSpinner = Ui.spinner(this, names, def);
        final EditText ref = Ui.edit(this, getString(R.string.br_branch), branch);
        final EditText ver = Ui.edit(this, getString(R.string.br_version_hint), nextVersion());
        final Spinner typeSpinner = Ui.spinner(this, types, 0);
        final CheckBox pub = Ui.check(this, R.string.br_publish, true);
        final CheckBox pre = Ui.check(this, R.string.prerelease, false);
        box.addView(Ui.label(this, getString(R.string.br_workflow)));
        box.addView(wfSpinner);
        box.addView(Ui.label(this, getString(R.string.br_branch)));
        box.addView(ref);
        box.addView(Ui.label(this, getString(R.string.br_version)));
        box.addView(ver);
        box.addView(Ui.label(this, getString(R.string.br_type)));
        box.addView(typeSpinner);
        box.addView(pub);
        box.addView(pre);
        if (!hasKey) box.addView(Ui.body(this, getString(R.string.br_no_key_warn), 12, R.color.warn));
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new Dlg(this)
                .setTitle(R.string.br_title)
                .setView(sv)
                .setPositiveButton(R.string.br_start, (d, w) -> {
                    final JSONObject wf = list.get(wfSpinner.getSelectedItemPosition());
                    String r = ref.getText().toString().trim();
                    final String useRef = r.isEmpty() ? branch : r;
                    final String v = ver.getText().toString().trim();
                    final String bt = types.get(typeSpinner.getSelectedItemPosition());
                    final boolean doPub = pub.isChecked();
                    final boolean isPre = pre.isChecked();
                    startBuild(wf, useRef, v, bt, doPub, isPre);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void startBuild(final JSONObject wf, final String useRef, final String version,
                            final String buildType, final boolean publish, final boolean prerelease) {
        bg(() -> {
            String yaml = "";
            byte[] raw = api.getFileBytes(owner, repo, wf.optString("path"), useRef, 512 * 1024);
            if (raw != null) yaml = new String(raw, StandardCharsets.UTF_8);
            WorkflowInputs.Result r = WorkflowInputs.parse(yaml);
            if (!r.dispatch) {
                post(() -> info(getString(R.string.br_title), getString(R.string.br_no_dispatch)));
                return;
            }
            JSONObject in = new JSONObject();
            if (r.has("build_type")) in.put("build_type", buildType);
            final String tagKey = r.tagInput();
            if (tagKey != null && !version.isEmpty()) {
                if (!Versions.validTag(version)) {
                    post(() -> toast(R.string.tag_invalid));
                    return;
                }
                in.put(tagKey, version);
            }
            final boolean tagIgnored = tagKey == null && !version.isEmpty();
            if (r.has("publish_release")) in.put("publish_release", String.valueOf(publish));
            if (r.has("prerelease")) in.put("prerelease", String.valueOf(prerelease));
            final boolean noReleaseInputs = publish && !r.has("publish_release");
            api.dispatchWorkflow(owner, repo, wf.optLong("id"), useRef, in);
            post(() -> {
                if (tagIgnored) info(getString(R.string.br_title), getString(R.string.tag_none_declared));
                else if (noReleaseInputs) info(getString(R.string.br_title), getString(R.string.br_no_release_inputs));
                else toast(R.string.br_started);
                Intent i = repoIntent(ActionsActivity.class);
                i.putExtra("workflowId", wf.optLong("id"));
                i.putExtra("workflowName", wf.optString("name"));
                startActivity(i);
            });
        });
    }
}
