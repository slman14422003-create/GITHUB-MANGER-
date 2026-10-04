package com.ghmanager.app;

import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * Advanced repository settings: merge options, features, Actions permissions, security features and
 * branch protection of the default branch. Only values that were changed are sent.
 */
public class AdvancedSettingsActivity extends BaseRepoActivity {
    private static final String[] ALLOWED = {"all", "local_only", "selected"};
    private static final String[] WF_PERM = {"read", "write"};

    private LinearLayout content;
    private JSONObject data;
    private JSONObject actionsPerm;
    private JSONObject workflowPerm;
    private Boolean alertsOn;
    private Boolean fixesOn;
    private JSONObject protection;
    private boolean protectionKnown;
    private String defBranch = "main";

    private CheckBox cSquash;
    private CheckBox cMergeCommit;
    private CheckBox cRebase;
    private CheckBox cAuto;
    private CheckBox cDelBranch;
    private CheckBox cUpdateBranch;
    private CheckBox cSignoff;
    private CheckBox cProjects;
    private CheckBox cDiscussions;
    private CheckBox cTemplate;
    private CheckBox cActions;
    private CheckBox cApprove;
    private Spinner spAllowed;
    private Spinner spWfPerm;
    private CheckBox cAlerts;
    private CheckBox cFixes;
    private CheckBox cSecretScan;
    private CheckBox cPushProtect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        bindHeader(getString(R.string.adv_title), repo);
        content = findViewById(R.id.content);
        btnRefresh.setOnClickListener(v -> load());
        action(btnA1, R.drawable.ic_check, R.string.save, v -> save());
        load();
    }

    // ------------------------------------------------------------------ load

    private void load() {
        loading(true);
        io.execute(() -> {
            try {
                final JSONObject r = api.getRepo(owner, repo);
                JSONObject ap = null;
                JSONObject wp = null;
                Boolean al = null;
                Boolean fx = null;
                JSONObject prot = null;
                boolean known = false;
                try {
                    ap = api.actionsPermissions(owner, repo);
                } catch (Exception ignored) {
                }
                try {
                    wp = api.workflowPermissions(owner, repo);
                } catch (Exception ignored) {
                }
                try {
                    al = api.vulnerabilityAlerts(owner, repo);
                } catch (Exception ignored) {
                }
                try {
                    fx = api.automatedSecurityFixes(owner, repo);
                } catch (Exception ignored) {
                }
                final String db = r.optString("default_branch", branch);
                try {
                    prot = api.getBranchProtection(owner, repo, db);
                    known = true;
                } catch (Exception ignored) {
                }
                final JSONObject fAp = ap;
                final JSONObject fWp = wp;
                final Boolean fAl = al;
                final Boolean fFx = fx;
                final JSONObject fProt = prot;
                final boolean fKnown = known;
                post(() -> {
                    loading(false);
                    data = r;
                    actionsPerm = fAp;
                    workflowPerm = fWp;
                    alertsOn = fAl;
                    fixesOn = fFx;
                    protection = fProt;
                    protectionKnown = fKnown;
                    defBranch = db;
                    render();
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    // ------------------------------------------------------------------ render

    private CheckBox add(LinearLayout box, int textRes, boolean checked) {
        CheckBox c = Ui.check(this, textRes, checked);
        box.addView(c);
        return c;
    }

    private void render() {
        content.removeAllViews();
        cAlerts = null;
        cFixes = null;
        cSecretScan = null;
        cPushProtect = null;
        cActions = null;
        cApprove = null;
        spAllowed = null;
        spWfPerm = null;

        // merge options
        content.addView(Ui.sectionTitle(this, getString(R.string.adv_merge)));
        LinearLayout merge = Ui.box(this);
        cSquash = add(merge, R.string.adv_squash, data.optBoolean("allow_squash_merge", true));
        cMergeCommit = add(merge, R.string.adv_mergecommit, data.optBoolean("allow_merge_commit", true));
        cRebase = add(merge, R.string.adv_rebase, data.optBoolean("allow_rebase_merge", true));
        cAuto = add(merge, R.string.adv_auto_merge, data.optBoolean("allow_auto_merge", false));
        cDelBranch = add(merge, R.string.adv_del_branch, data.optBoolean("delete_branch_on_merge", false));
        cUpdateBranch = add(merge, R.string.adv_update_branch, data.optBoolean("allow_update_branch", false));
        cSignoff = add(merge, R.string.adv_signoff, data.optBoolean("web_commit_signoff_required", false));
        content.addView(merge);

        // features
        content.addView(Ui.sectionTitle(this, getString(R.string.adv_features)));
        LinearLayout feat = Ui.box(this);
        cProjects = add(feat, R.string.adv_projects, data.optBoolean("has_projects", true));
        cDiscussions = add(feat, R.string.adv_discussions, data.optBoolean("has_discussions", false));
        cTemplate = add(feat, R.string.adv_template, data.optBoolean("is_template", false));
        content.addView(feat);

        // Actions
        if (actionsPerm != null || workflowPerm != null) {
            content.addView(Ui.sectionTitle(this, getString(R.string.adv_actions)));
            LinearLayout act = Ui.box(this);
            if (actionsPerm != null) {
                cActions = add(act, R.string.adv_actions_enabled, actionsPerm.optBoolean("enabled", true));
                act.addView(Ui.label(this, getString(R.string.adv_allowed)));
                List<String> labels = Arrays.asList(getString(R.string.adv_allowed_all),
                        getString(R.string.adv_allowed_local), getString(R.string.adv_allowed_selected));
                int sel = Math.max(0, Arrays.asList(ALLOWED).indexOf(actionsPerm.optString("allowed_actions", "all")));
                spAllowed = Ui.spinner(this, labels, sel);
                act.addView(spAllowed);
            }
            if (workflowPerm != null) {
                act.addView(Ui.label(this, getString(R.string.adv_wf_perm)));
                List<String> labels = Arrays.asList(getString(R.string.adv_wf_read), getString(R.string.adv_wf_write));
                int sel = Math.max(0, Arrays.asList(WF_PERM).indexOf(workflowPerm.optString("default_workflow_permissions", "read")));
                spWfPerm = Ui.spinner(this, labels, sel);
                act.addView(spWfPerm);
                cApprove = add(act, R.string.adv_approve_pr, workflowPerm.optBoolean("can_approve_pull_request_reviews", false));
            }
            content.addView(act);
        }

        // security
        JSONObject sa = data.optJSONObject("security_and_analysis");
        if (alertsOn != null || fixesOn != null || sa != null) {
            content.addView(Ui.sectionTitle(this, getString(R.string.adv_security)));
            LinearLayout sec = Ui.box(this);
            if (alertsOn != null) cAlerts = add(sec, R.string.adv_alerts, alertsOn);
            if (fixesOn != null) cFixes = add(sec, R.string.adv_fixes, fixesOn);
            if (sa != null && sa.optJSONObject("secret_scanning") != null) {
                cSecretScan = add(sec, R.string.adv_secret_scan,
                        "enabled".equals(sa.optJSONObject("secret_scanning").optString("status")));
            }
            if (sa != null && sa.optJSONObject("secret_scanning_push_protection") != null) {
                cPushProtect = add(sec, R.string.adv_push_protect,
                        "enabled".equals(sa.optJSONObject("secret_scanning_push_protection").optString("status")));
            }
            content.addView(sec);
        }

        Button save = Ui.button(this, R.string.save, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 14);
        save.setLayoutParams(lp);
        save.setOnClickListener(v -> save());
        content.addView(save);

        // branch protection
        content.addView(Ui.sectionTitle(this, getString(R.string.adv_protection)));
        Row pr = new Row(R.drawable.ic_shield, false, getString(R.string.adv_protect_row), defBranch, false, true);
        if (!protectionKnown) pr.badge(getString(R.string.adv_protect_unknown), Ui.color(this, R.color.text_secondary));
        else if (protection != null) pr.badge(getString(R.string.adv_protect_on), Ui.color(this, R.color.ok));
        else pr.badge(getString(R.string.adv_protect_off), Ui.color(this, R.color.warn));
        content.addView(Ui.rowView(this, content, pr, v -> protectionDialog()));
        content.addView(Ui.body(this, getString(R.string.adv_prot_hint), 12, R.color.text_secondary));

        content.addView(Ui.sectionTitle(this, getString(R.string.adv_tools)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_share, false,
                getString(R.string.col_title), getString(R.string.set_collab_sub), false, true),
                v -> startActivity(repoIntent(CollaboratorsActivity.class))));
    }

    // ------------------------------------------------------------------ save

    private static void diff(JSONObject patch, JSONObject data, String key, CheckBox c, boolean def) throws JSONException {
        if (c == null) return;
        if (c.isChecked() != data.optBoolean(key, def)) patch.put(key, c.isChecked());
    }

    private static boolean unauthorized(Exception e) {
        return e instanceof GitHubApi.ApiException && ((GitHubApi.ApiException) e).code == 401;
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private void save() {
        if (data == null) return;
        if (!cSquash.isChecked() && !cMergeCommit.isChecked() && !cRebase.isChecked()) {
            toast(R.string.adv_need_merge);
            return;
        }
        final JSONObject patch = new JSONObject();
        try {
            diff(patch, data, "allow_squash_merge", cSquash, true);
            diff(patch, data, "allow_merge_commit", cMergeCommit, true);
            diff(patch, data, "allow_rebase_merge", cRebase, true);
            diff(patch, data, "allow_auto_merge", cAuto, false);
            diff(patch, data, "delete_branch_on_merge", cDelBranch, false);
            diff(patch, data, "allow_update_branch", cUpdateBranch, false);
            diff(patch, data, "web_commit_signoff_required", cSignoff, false);
            diff(patch, data, "has_projects", cProjects, true);
            diff(patch, data, "has_discussions", cDiscussions, false);
            diff(patch, data, "is_template", cTemplate, false);
            JSONObject sa = data.optJSONObject("security_and_analysis");
            JSONObject saPatch = new JSONObject();
            if (cSecretScan != null && sa != null) {
                boolean old = "enabled".equals(sa.optJSONObject("secret_scanning").optString("status"));
                if (cSecretScan.isChecked() != old) {
                    saPatch.put("secret_scanning", new JSONObject().put("status", cSecretScan.isChecked() ? "enabled" : "disabled"));
                }
            }
            if (cPushProtect != null && sa != null) {
                boolean old = "enabled".equals(sa.optJSONObject("secret_scanning_push_protection").optString("status"));
                if (cPushProtect.isChecked() != old) {
                    saPatch.put("secret_scanning_push_protection",
                            new JSONObject().put("status", cPushProtect.isChecked() ? "enabled" : "disabled"));
                }
            }
            if (saPatch.length() > 0) patch.put("security_and_analysis", saPatch);
        } catch (JSONException e) {
            showError(e);
            return;
        }

        final boolean actEnabled = cActions == null || cActions.isChecked();
        final String allowed = spAllowed == null ? null : ALLOWED[spAllowed.getSelectedItemPosition()];
        final String wfPerm = spWfPerm == null ? null : WF_PERM[spWfPerm.getSelectedItemPosition()];
        final boolean approve = cApprove != null && cApprove.isChecked();
        final Boolean wantAlerts = cAlerts == null ? null : cAlerts.isChecked();
        final Boolean wantFixes = cFixes == null ? null : cFixes.isChecked();

        bg(() -> {
            StringBuilder errs = new StringBuilder();
            if (patch.length() > 0) {
                try {
                    api.updateRepo(owner, repo, patch);
                } catch (Exception e) {
                    if (unauthorized(e)) {
                        showError(e);
                        return;
                    }
                    errs.append("• ").append(msg(e)).append('\n');
                }
            }
            if (actionsPerm != null) {
                boolean oldEn = actionsPerm.optBoolean("enabled", true);
                String oldAllowed = actionsPerm.optString("allowed_actions", "all");
                if (actEnabled != oldEn || (actEnabled && allowed != null && !allowed.equals(oldAllowed))) {
                    try {
                        api.setActionsPermissions(owner, repo, actEnabled, allowed);
                    } catch (Exception e) {
                        if (unauthorized(e)) {
                            showError(e);
                            return;
                        }
                        errs.append("• ").append(msg(e)).append('\n');
                    }
                }
            }
            if (workflowPerm != null && wfPerm != null) {
                String oldPerm = workflowPerm.optString("default_workflow_permissions", "read");
                boolean oldApprove = workflowPerm.optBoolean("can_approve_pull_request_reviews", false);
                if (!wfPerm.equals(oldPerm) || approve != oldApprove) {
                    try {
                        api.setWorkflowPermissions(owner, repo, wfPerm, approve);
                    } catch (Exception e) {
                        if (unauthorized(e)) {
                            showError(e);
                            return;
                        }
                        errs.append("• ").append(msg(e)).append('\n');
                    }
                }
            }
            if (wantAlerts != null && alertsOn != null && wantAlerts != alertsOn.booleanValue()) {
                try {
                    api.setVulnerabilityAlerts(owner, repo, wantAlerts);
                } catch (Exception e) {
                    errs.append("• ").append(msg(e)).append('\n');
                }
            }
            if (wantFixes != null && fixesOn != null && wantFixes != fixesOn.booleanValue()) {
                try {
                    api.setAutomatedSecurityFixes(owner, repo, wantFixes);
                } catch (Exception e) {
                    errs.append("• ").append(msg(e)).append('\n');
                }
            }
            final String problems = errs.toString().trim();
            post(() -> {
                if (problems.isEmpty()) toast(R.string.saved);
                else Dlg.result(AdvancedSettingsActivity.this, false, getString(R.string.adv_partial), problems);
                load();
            });
        });
    }

    // ------------------------------------------------------------------ branch protection

    private void protectionDialog() {
        JSONObject prot = protection;
        boolean hasPr = prot != null && prot.optJSONObject("required_pull_request_reviews") != null;
        int reviews = hasPr ? prot.optJSONObject("required_pull_request_reviews").optInt("required_approving_review_count", 1) : 1;
        boolean admins = prot != null && prot.optJSONObject("enforce_admins") != null
                && prot.optJSONObject("enforce_admins").optBoolean("enabled");
        boolean force = prot != null && prot.optJSONObject("allow_force_pushes") != null
                && prot.optJSONObject("allow_force_pushes").optBoolean("enabled");
        boolean del = prot != null && prot.optJSONObject("allow_deletions") != null
                && prot.optJSONObject("allow_deletions").optBoolean("enabled");

        LinearLayout box = Ui.box(this);
        final CheckBox cPr = Ui.check(this, R.string.adv_prot_pr, hasPr);
        final EditText count = Ui.edit(this, getString(R.string.adv_prot_reviews), String.valueOf(reviews));
        count.setInputType(InputType.TYPE_CLASS_NUMBER);
        final CheckBox cAdm = Ui.check(this, R.string.adv_prot_admins, admins);
        final CheckBox cForce = Ui.check(this, R.string.adv_prot_force, !force);
        final CheckBox cDel = Ui.check(this, R.string.adv_prot_delete, !del);
        box.addView(cPr);
        box.addView(count);
        box.addView(cAdm);
        box.addView(cForce);
        box.addView(cDel);
        Dlg dlg = new Dlg(this);
        dlg.setTitle(getString(R.string.adv_protect_row) + ": " + defBranch)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    int n = 1;
                    try {
                        n = Math.max(1, Math.min(6, Integer.parseInt(count.getText().toString().trim())));
                    } catch (NumberFormatException ignored) {
                    }
                    saveProtection(cPr.isChecked(), n, cAdm.isChecked(), !cForce.isChecked(), !cDel.isChecked());
                })
                .setNegativeButton(R.string.cancel, null);
        if (protection != null) {
            dlg.setNeutralButton(R.string.adv_prot_remove, (d, w) -> bg(() -> {
                api.deleteBranchProtection(owner, repo, defBranch);
                post(() -> {
                    toast(R.string.adv_prot_removed);
                    load();
                });
            }));
        }
        dlg.show();
    }

    private void saveProtection(final boolean requirePr, final int reviews, final boolean admins,
                                final boolean allowForce, final boolean allowDelete) {
        bg(() -> {
            JSONObject body = new JSONObject();
            // keep existing required status checks instead of wiping them
            JSONObject rsc = protection == null ? null : protection.optJSONObject("required_status_checks");
            if (rsc != null) {
                JSONObject n = new JSONObject();
                n.put("strict", rsc.optBoolean("strict"));
                JSONArray out = new JSONArray();
                JSONArray checks = rsc.optJSONArray("checks");
                for (int i = 0; checks != null && i < checks.length(); i++) {
                    JSONObject c = checks.getJSONObject(i);
                    JSONObject o = new JSONObject();
                    o.put("context", c.optString("context"));
                    if (c.has("app_id") && !c.isNull("app_id")) o.put("app_id", c.optInt("app_id"));
                    out.put(o);
                }
                n.put("checks", out);
                body.put("required_status_checks", n);
            } else {
                body.put("required_status_checks", JSONObject.NULL);
            }
            body.put("enforce_admins", admins);
            if (requirePr) {
                JSONObject rv = new JSONObject();
                rv.put("required_approving_review_count", reviews);
                JSONObject old = protection == null ? null : protection.optJSONObject("required_pull_request_reviews");
                if (old != null) {
                    rv.put("dismiss_stale_reviews", old.optBoolean("dismiss_stale_reviews"));
                    rv.put("require_code_owner_reviews", old.optBoolean("require_code_owner_reviews"));
                    rv.put("require_last_push_approval", old.optBoolean("require_last_push_approval"));
                }
                body.put("required_pull_request_reviews", rv);
            } else {
                body.put("required_pull_request_reviews", JSONObject.NULL);
            }
            body.put("restrictions", JSONObject.NULL);
            body.put("allow_force_pushes", allowForce);
            body.put("allow_deletions", allowDelete);
            api.putBranchProtection(owner, repo, defBranch, body);
            post(() -> {
                toast(R.string.adv_prot_saved);
                load();
            });
        });
    }
}
