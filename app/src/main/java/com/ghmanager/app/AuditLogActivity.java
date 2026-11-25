package com.ghmanager.app;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

public class AuditLogActivity extends BaseRepoActivity {
    private final List<AuditLog.Entry> entries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        bindHeader(getString(R.string.audit_title), null);
        initList();
        btnRefresh.setOnClickListener(v -> load());
        action(btnA1, R.drawable.ic_delete, R.string.audit_clear, v -> askClear());
        listView.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < entries.size()) detail(entries.get(pos));
        });
        load();
    }

    private void load() {
        loading(true);
        io.execute(() -> {
            final List<AuditLog.Entry> all = AuditLog.read(this);
            post(() -> {
                loading(false);
                entries.clear();
                entries.addAll(all);
                render();
            });
        });
    }

    private int icon(AuditLog.Entry e) {
        if (e.kind.equals("event")) return R.drawable.ic_shield;
        switch (e.category) {
            case Verify.DELETE:
                return R.drawable.ic_delete;
            case Verify.FORCE:
                return R.drawable.ic_branch;
            case Verify.SECRETS:
                return R.drawable.ic_lock;
            case Verify.COLLAB:
                return R.drawable.ic_user;
            case Verify.PROTECTION:
                return R.drawable.ic_shield;
            case Verify.MERGE:
                return R.drawable.ic_merge;
            case Verify.ACTIONS:
                return R.drawable.ic_run;
            default:
                return R.drawable.ic_repo;
        }
    }

    private String title(AuditLog.Entry e) {
        if (e.kind.equals("event")) return eventName(e.category);
        return getString(VerifySettings.opLabel(e.category));
    }

    private String eventName(String key) {
        switch (key) {
            case "verify_ok_totp":
                return getString(R.string.audit_ev_ok_totp);
            case "verify_ok_recovery":
                return getString(R.string.audit_ev_ok_recovery);
            case "verify_ok_notify":
                return getString(R.string.audit_ev_ok_notify);
            case "verify_failed":
                return getString(R.string.audit_ev_failed);
            case "totp_on":
                return getString(R.string.audit_ev_totp_on);
            case "totp_off":
                return getString(R.string.audit_ev_totp_off);
            case "recovery_regen":
                return getString(R.string.audit_ev_regen);
            case "verify_settings":
                return getString(R.string.audit_ev_settings);
            case "log_cleared":
                return getString(R.string.audit_ev_cleared);
            default:
                return key;
        }
    }

    private String outcomeName(String o) {
        if ("ok".equals(o)) return getString(R.string.audit_ok);
        if ("denied".equals(o)) return getString(R.string.audit_denied);
        return getString(R.string.audit_failed);
    }

    private int outcomeColor(String o) {
        if ("ok".equals(o)) return Ui.color(this, R.color.ok);
        if ("denied".equals(o)) return Ui.color(this, R.color.warn);
        return Ui.color(this, R.color.bad);
    }

    private String sub(AuditLog.Entry e) {
        StringBuilder sb = new StringBuilder(Fmt.agoMs(e.time));
        if (!e.repo.isEmpty()) sb.append(" \u00B7 ").append(e.repo);
        if (!e.target.isEmpty()) sb.append(" \u00B7 ").append(e.target);
        return sb.toString();
    }

    private void render() {
        List<Row> rows = new ArrayList<>();
        for (AuditLog.Entry e : entries) {
            Row r = new Row(icon(e), false, title(e), sub(e), false, true);
            r.badge(outcomeName(e.outcome), outcomeColor(e.outcome));
            rows.add(r);
        }
        adapter.setRows(rows);
        showEmpty(entries.isEmpty(), R.string.audit_empty);
    }

    private void detail(AuditLog.Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(e.time))).append('\n');
        sb.append(getString(R.string.audit_f_result)).append(": ").append(outcomeName(e.outcome));
        if (e.code > 0 && !"ok".equals(e.outcome)) sb.append(" (").append(e.code).append(')');
        sb.append('\n');
        if (!e.account.isEmpty()) sb.append(getString(R.string.audit_f_account)).append(": ").append(e.account).append('\n');
        if (!e.method.isEmpty()) sb.append(getString(R.string.audit_f_method)).append(": ").append(e.method).append('\n');
        if (!e.repo.isEmpty()) sb.append(getString(R.string.audit_f_repo)).append(": ").append(e.repo).append('\n');
        if (!e.target.isEmpty()) sb.append(getString(R.string.audit_f_target)).append(": ").append(e.target).append('\n');
        info(title(e), sb.toString().trim());
    }

    private void askClear() {
        if (entries.isEmpty()) return;
        confirm(getString(R.string.audit_clear), getString(R.string.audit_clear_msg), R.string.delete, () ->
                io.execute(() -> {
                    try {
                        Verify.requireFresh();
                    } catch (Exception e) {
                        post(() -> toast(R.string.verify_failed));
                        return;
                    }
                    AuditLog.clear(this);
                    AuditLog.event(this, "log_cleared");
                    post(this::load);
                }));
    }
}
