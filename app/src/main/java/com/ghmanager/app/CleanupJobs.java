package com.ghmanager.app;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Delete / archive many repositories as one background job, with the typed-confirmation dialog and the report. */
public final class CleanupJobs {
    private CleanupJobs() {
    }

    private static volatile String pendingReport;
    private static volatile boolean pendingOk = true;

    public static Transfers.Job start(Context c, final List<String[]> targets, final boolean delete) {
        final Context app = c.getApplicationContext();
        final GitHubApi api = new GitHubApi(app);
        final List<String[]> list = new ArrayList<>(targets);
        final String title = app.getString(delete ? R.string.cl_job_delete : R.string.cl_job_archive);
        return Transfers.start(app, title, "cleanup", job -> {
            int ok = 0;
            final List<String> failed = new ArrayList<>();
            final int n = list.size();
            try {
                for (int i = 0; i < n; i++) {
                    job.check();
                    String[] t = list.get(i);
                    job.progress(app.getString(R.string.cl_job_item, t[1], i + 1, n), i, n);
                    try {
                        if (delete) {
                            api.deleteRepo(t[0], t[1]);
                        } else {
                            JSONObject patch = new JSONObject();
                            patch.put("archived", true);
                            api.updateRepo(t[0], t[1], patch);
                        }
                        ok++;
                    } catch (Transfers.Cancelled e) {
                        throw e;
                    } catch (Exception e) {
                        failed.add(t[0] + "/" + t[1] + " — " + reason(app, e));
                    }
                }
            } finally {
                String head = app.getString(delete ? R.string.cl_report_del : R.string.cl_report_arc, ok, failed.size());
                StringBuilder sb = new StringBuilder(head);
                for (String f : failed) sb.append('\n').append(f);
                pendingReport = sb.toString();
                pendingOk = failed.isEmpty();
                job.message = head;
            }
        }, (job, ok, msg) -> {
            String first = msg == null ? "" : msg;
            Toast.makeText(job.app, first, Toast.LENGTH_LONG).show();
        });
    }

    private static String reason(Context app, Exception e) {
        if (e instanceof GitHubApi.ApiException) {
            int code = ((GitHubApi.ApiException) e).code;
            if (code == 403) return app.getString(R.string.cl_reason_403);
            if (code == 404) return app.getString(R.string.cl_reason_404);
            if (code == 422) return app.getString(R.string.cl_reason_422);
        }
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl) : m;
    }

    /** Shows the report of the last finished cleanup job once, if there is one waiting. */
    public static boolean showPending(Activity a) {
        final String r = pendingReport;
        if (r == null || a.isFinishing()) return false;
        final boolean ok = pendingOk;
        pendingReport = null;
        Dlg.result(a, ok, a.getString(R.string.cl_report_title), r);
        return true;
    }

    public static void confirmArchive(final Activity a, final List<String[]> targets, final Runnable started) {
        if (targets.isEmpty()) return;
        new Dlg(a)
                .setTitle(R.string.cl_archive_title)
                .setMessage(a.getString(R.string.cl_archive_msg, targets.size()))
                .setPositiveButton(R.string.cl_archive_go, (d, w) -> {
                    start(a, targets, false);
                    Toast.makeText(a, R.string.cl_started, Toast.LENGTH_LONG).show();
                    if (started != null) started.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Typed confirmation (the count or the first name) plus a 5 second wait before the button works. */
    public static void confirmDelete(final Activity a, final List<String[]> targets, final Runnable started) {
        if (targets.isEmpty()) return;
        final int n = targets.size();
        final String first = targets.get(0)[1];
        LinearLayout box = Ui.box(a);
        box.addView(Ui.label(a, a.getString(R.string.cl_confirm_msg, n, first)));
        final EditText input = Ui.edit(a, a.getString(R.string.cl_confirm_hint), null);
        box.addView(input);

        Dlg dlg = new Dlg(a);
        dlg.danger();
        dlg.setTitle(R.string.cl_confirm_title);
        dlg.setView(box);
        dlg.setPositiveButton(R.string.delete, (d, w) -> {
            String typed = input.getText().toString().trim();
            if (!typed.equals(String.valueOf(n)) && !typed.equals(first)) {
                Toast.makeText(a, R.string.cl_mismatch, Toast.LENGTH_SHORT).show();
                Dlg.stay(d);
                return;
            }
            start(a, targets, true);
            Toast.makeText(a, R.string.cl_started, Toast.LENGTH_LONG).show();
            if (started != null) started.run();
        });
        dlg.setNegativeButton(R.string.cancel, null);
        final AlertDialog shown = dlg.show();

        final Button pos = Dlg.getButton(shown, DialogInterface.BUTTON_POSITIVE);
        if (pos == null) return;
        final CharSequence label = pos.getText();
        pos.setEnabled(false);
        final Handler h = new Handler(Looper.getMainLooper());
        final int[] left = {5};
        h.post(new Runnable() {
            @Override
            public void run() {
                if (!shown.isShowing()) return;
                if (left[0] <= 0) {
                    pos.setText(label);
                    pos.setEnabled(true);
                    return;
                }
                pos.setText(a.getString(R.string.cl_confirm_wait, left[0]));
                left[0]--;
                h.postDelayed(this, 1000);
            }
        });
    }
}
