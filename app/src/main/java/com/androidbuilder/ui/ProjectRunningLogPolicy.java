package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

/**
 * While a task-execution job is live, the build-log card should show the TAIL of the log (the most
 * recent narration — "✍️ writing batch 15/25", "🔍 reviewing", ...) rather than the failure-triage
 * preview, which surfaces the log HEAD + compile diagnostics and scrolls the current step out of view.
 */
public final class ProjectRunningLogPolicy {
    private ProjectRunningLogPolicy() {
    }

    public static boolean isLiveJob(BuildJobRecord job) {
        if (job == null || job.status == null) {
            return false;
        }
        String status = job.status.trim().toLowerCase(java.util.Locale.ROOT);
        return "generating".equals(status)
                || "building".equals(status)
                || "queued".equals(status)
                || "coding".equals(status);
    }

    public static String tail(String logs, int maxChars, boolean chinese) {
        if (logs == null) {
            return "";
        }
        if (maxChars <= 0 || logs.length() <= maxChars) {
            return logs;
        }
        String cut = logs.substring(logs.length() - maxChars);
        // Start at a line boundary so the first shown line is not a fragment.
        int newline = cut.indexOf('\n');
        if (newline >= 0 && newline < cut.length() - 1) {
            cut = cut.substring(newline + 1);
        }
        String prefix = chinese ? "…（更早的日志见上方）\n" : "…(earlier log above)\n";
        return prefix + cut;
    }
}
