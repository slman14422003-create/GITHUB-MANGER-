package com.ghmanager.app;

import android.content.ContentResolver;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** One release: notes, assets (download / upload / delete), publish, edit, delete. */
public class ReleaseDetailActivity extends BaseRepoActivity {
    private long releaseId;
    private JSONObject rel;
    private LinearLayout content;
    private ActivityResultLauncher<String[]> pickFiles;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        releaseId = getIntent().getLongExtra("releaseId", 0);
        bindHeader(getString(R.string.release), repo);
        content = findViewById(R.id.content);
        pickFiles = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
            if (uris != null && !uris.isEmpty()) uploadFiles(uris);
        });
        btnRefresh.setOnClickListener(v -> load());
        action(btnA1, R.drawable.ic_open, R.string.open_in_github, v -> {
            if (rel != null) openUrl(Fmt.s(rel, "html_url"));
        });
        action(btnA2, R.drawable.ic_more, R.string.more, v -> moreMenu());
        load();
    }

    private void load() {
        loading(true);
        io.execute(() -> {
            try {
                final JSONObject r = api.getRelease(owner, repo, releaseId);
                post(() -> {
                    loading(false);
                    rel = r;
                    render();
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void render() {
        if (rel == null) return;
        content.removeAllViews();
        String name = Fmt.s(rel, "name");
        final String tag = Fmt.s(rel, "tag_name");
        titleView.setText(name.isEmpty() ? tag : name);
        setSubtitle(tag);

        TextView t = Ui.body(this, name.isEmpty() ? tag : name, 18, R.color.text_primary);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(t);

        StringBuilder meta = new StringBuilder(tag);
        String target = Fmt.s(rel, "target_commitish");
        if (!target.isEmpty()) meta.append(" · ").append(target);
        JSONObject author = rel.optJSONObject("author");
        if (author != null) meta.append(" · ").append(author.optString("login"));
        String date = Fmt.date(Fmt.s(rel, rel.isNull("published_at") ? "created_at" : "published_at"));
        if (!date.isEmpty()) meta.append("\n").append(date);
        if (rel.optBoolean("draft")) meta.append("\n").append(getString(R.string.draft));
        else if (rel.optBoolean("prerelease")) meta.append("\n").append(getString(R.string.prerelease));
        content.addView(Ui.body(this, meta.toString(), 13, R.color.text_secondary));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        if (rel.optBoolean("draft")) {
            Button publish = Ui.button(this, R.string.publish, true);
            publish.setOnClickListener(v -> patch("draft", false));
            buttons.addView(publish, weighted());
        }
        Button edit = Ui.button(this, R.string.edit, false);
        edit.setOnClickListener(v -> editDialog());
        buttons.addView(edit, weighted());
        content.addView(buttons);

        String body = Fmt.s(rel, "body");
        content.addView(Ui.sectionTitle(this, getString(R.string.release_notes)));
        content.addView(Ui.body(this, body.isEmpty() ? getString(R.string.no_notes) : body, 14,
                body.isEmpty() ? R.color.text_hint : R.color.text_primary));

        JSONArray assets = rel.optJSONArray("assets");
        int n = assets == null ? 0 : assets.length();
        content.addView(Ui.sectionTitle(this, getString(R.string.assets_count, n)));
        for (int i = 0; i < n; i++) {
            final JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String sub = Fmt.size(a.optLong("size")) + " · " + getString(R.string.downloads_count, a.optInt("download_count"));
            content.addView(Ui.rowView(this, content,
                    new Row(R.drawable.ic_package, false, a.optString("name"), sub, false, true),
                    v -> assetMenu(a)));
        }
        LinearLayout up = new LinearLayout(this);
        up.setOrientation(LinearLayout.HORIZONTAL);
        up.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), 0);
        Button upload = Ui.button(this, R.string.upload_assets, true);
        upload.setOnClickListener(v -> pickFiles.launch(new String[]{"*/*"}));
        up.addView(upload, weighted());
        content.addView(up);

        content.addView(Ui.sectionTitle(this, getString(R.string.source_code)));
        content.addView(Ui.rowView(this, content,
                new Row(R.drawable.ic_download, false, "Source code (zip)", null, false, false),
                v -> saveAs(repo + "-" + tag.replace('/', '-') + ".zip", () -> api.openZipball(owner, repo, tag))));
        content.addView(Ui.rowView(this, content,
                new Row(R.drawable.ic_download, false, "Source code (tar.gz)", null, false, false),
                v -> saveAs(repo + "-" + tag.replace('/', '-') + ".tar.gz", () -> api.openTarball(owner, repo, tag))));
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        return lp;
    }

    private void patch(final String key, final Object value) {
        bg(() -> {
            JSONObject b = new JSONObject();
            b.put(key, value);
            api.updateRelease(owner, repo, releaseId, b);
            post(() -> {
                toast(R.string.done_ok);
                load();
            });
        });
    }

    private void assetMenu(final JSONObject a) {
        final long id = a.optLong("id");
        final String name = a.optString("name");
        String[] items = {getString(R.string.download), getString(R.string.copy_link), getString(R.string.delete)};
        choose(name, items, (d, which) -> {
            if (which == 0) {
                saveAs(name, () -> api.openDownload(api.assetPath(owner, repo, id), "application/octet-stream"));
            } else if (which == 1) {
                copy("asset", a.optString("browser_download_url"));
            } else {
                confirm(getString(R.string.delete), getString(R.string.delete_msg, name), R.string.delete, () ->
                        bg(() -> {
                            api.deleteAsset(owner, repo, id);
                            post(this::load);
                        }));
            }
        });
    }

    private void editDialog() {
        LinearLayout box = Ui.box(this);
        final EditText tag = Ui.edit(this, getString(R.string.tag_name), Fmt.s(rel, "tag_name"));
        final EditText title = Ui.edit(this, getString(R.string.release_title), Fmt.s(rel, "name"));
        final EditText notes = Ui.editMulti(this, getString(R.string.release_notes), Fmt.s(rel, "body"), 4);
        final CheckBox draft = Ui.check(this, R.string.draft, rel.optBoolean("draft"));
        final CheckBox pre = Ui.check(this, R.string.prerelease, rel.optBoolean("prerelease"));
        box.addView(tag);
        box.addView(title);
        box.addView(notes);
        box.addView(draft);
        box.addView(pre);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle(R.string.edit)
                .setView(sv)
                .setPositiveButton(R.string.save, (d, w) -> {
                    final String t = tag.getText().toString().trim();
                    final String ti = title.getText().toString().trim();
                    final String no = notes.getText().toString();
                    final boolean isDraft = draft.isChecked();
                    final boolean isPre = pre.isChecked();
                    bg(() -> {
                        JSONObject b = new JSONObject();
                        if (!t.isEmpty()) b.put("tag_name", t);
                        b.put("name", ti);
                        b.put("body", no);
                        b.put("draft", isDraft);
                        b.put("prerelease", isPre);
                        api.updateRelease(owner, repo, releaseId, b);
                        post(() -> {
                            toast(R.string.done_ok);
                            load();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void moreMenu() {
        String[] items = {getString(R.string.copy_link), getString(R.string.delete_release)};
        choose(getString(R.string.more), items, (d, which) -> {
            if (which == 0) {
                if (rel != null) copy("release", Fmt.s(rel, "html_url"));
            } else if (rel != null) {
                deleteDialog();
            }
        });
    }

    private void deleteDialog() {
        final String tag = Fmt.s(rel, "tag_name");
        final String name = Fmt.s(rel, "name").isEmpty() ? tag : Fmt.s(rel, "name");
        LinearLayout box = Ui.box(this);
        TextView msg = Ui.label(this, getString(R.string.delete_msg, name));
        final CheckBox delTag = Ui.check(this, R.string.delete_tag_too, false);
        box.addView(msg);
        box.addView(delTag);
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_release)
                .setView(box)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    final boolean alsoTag = delTag.isChecked();
                    bg(() -> {
                        api.deleteRelease(owner, repo, releaseId);
                        if (alsoTag) {
                            try {
                                api.deleteTag(owner, repo, tag);
                            } catch (GitHubApi.ApiException ignored) {
                            }
                        }
                        post(this::finish);
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void uploadFiles(final List<Uri> uris) {
        if (busy) return;
        busy = true;
        showProgress(getString(R.string.uploading));
        bg(() -> {
            ContentResolver cr = getContentResolver();
            final int total = uris.size();
            int okCount = 0;
            List<String> errors = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                final int cur = i;
                Uri u = uris.get(i);
                String n = FileScanner.displayName(cr, u);
                if (n == null) n = "file_" + System.currentTimeMillis();
                final String name = n;
                long size = FileScanner.size(cr, u);
                String type = cr.getType(u);
                post(() -> updateProgress(cur, total, name));
                InputStream in = cr.openInputStream(u);
                if (in == null) {
                    errors.add(name);
                    continue;
                }
                try {
                    api.uploadAsset(owner, repo, releaseId, name, type, in, size, (done, tot) -> post(() -> {
                        if (progressText != null) {
                            progressText.setText((cur + 1) + "/" + total + "\n" + name + "\n"
                                    + Fmt.size(done) + (tot > 0 ? " / " + Fmt.size(tot) : ""));
                        }
                    }));
                    okCount++;
                } catch (GitHubApi.ApiException e) {
                    errors.add(name + ": " + e.getMessage());
                } finally {
                    in.close();
                }
            }
            final int fOk = okCount;
            final StringBuilder sb = new StringBuilder(getString(R.string.uploaded_count, fOk));
            for (String err : errors) sb.append("\n").append(err);
            post(() -> {
                hideProgress();
                info(getString(R.string.done), sb.toString());
                load();
            });
        });
    }
}
