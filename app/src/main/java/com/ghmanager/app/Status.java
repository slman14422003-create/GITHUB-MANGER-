package com.ghmanager.app;

import android.content.Context;

/** Maps GitHub Actions status/conclusion values to labels, colors and icons. */
public final class Status {
    private Status() {
    }

    public static boolean isActive(String status) {
        return "in_progress".equals(status) || "queued".equals(status) || "waiting".equals(status)
                || "pending".equals(status) || "requested".equals(status);
    }

    /** The effective state: the conclusion when completed, otherwise the status. */
    public static String state(String status, String conclusion) {
        if ("completed".equals(status) && conclusion != null && !conclusion.isEmpty() && !"null".equals(conclusion)) {
            return conclusion;
        }
        return status == null ? "" : status;
    }

    public static String label(String status, String conclusion) {
        switch (state(status, conclusion)) {
            case "success":
                return "نجاح";
            case "failure":
                return "فشل";
            case "cancelled":
                return "أُلغي";
            case "skipped":
                return "تم التخطي";
            case "timed_out":
                return "انتهت المهلة";
            case "neutral":
                return "محايد";
            case "action_required":
                return "يتطلب إجراء";
            case "stale":
                return "قديم";
            case "startup_failure":
                return "فشل البدء";
            case "in_progress":
                return "قيد التشغيل";
            case "queued":
                return "في الانتظار";
            case "waiting":
                return "بانتظار الموافقة";
            case "pending":
            case "requested":
                return "معلّق";
            case "completed":
                return "مكتمل";
            default:
                return status == null ? "" : status;
        }
    }

    public static int color(Context c, String status, String conclusion) {
        switch (state(status, conclusion)) {
            case "success":
                return Ui.color(c, R.color.ok);
            case "failure":
            case "timed_out":
            case "startup_failure":
                return Ui.color(c, R.color.bad);
            case "in_progress":
                return Ui.color(c, R.color.info);
            case "queued":
            case "waiting":
            case "pending":
            case "requested":
            case "action_required":
                return Ui.color(c, R.color.warn);
            default:
                return Ui.color(c, R.color.text_secondary);
        }
    }

    public static int icon(String status, String conclusion) {
        switch (state(status, conclusion)) {
            case "success":
                return R.drawable.ic_check_circle;
            case "failure":
            case "timed_out":
            case "startup_failure":
            case "cancelled":
                return R.drawable.ic_cancel;
            case "in_progress":
                return R.drawable.ic_play;
            case "queued":
            case "waiting":
            case "pending":
            case "requested":
            case "action_required":
                return R.drawable.ic_clock;
            default:
                return R.drawable.ic_clock;
        }
    }

    /** Single-glyph marker for plain text step lists. */
    public static String glyph(String status, String conclusion) {
        switch (state(status, conclusion)) {
            case "success":
                return "✓";
            case "failure":
            case "timed_out":
            case "startup_failure":
                return "✗";
            case "cancelled":
                return "⊘";
            case "skipped":
                return "↷";
            case "in_progress":
                return "▶";
            default:
                return "…";
        }
    }
}
