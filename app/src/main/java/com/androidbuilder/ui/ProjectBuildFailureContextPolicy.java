package com.androidbuilder.ui;

import com.androidbuilder.agent.BuildLogContextExtractor;
import com.androidbuilder.model.BuildJobRecord;

import java.util.Locale;

public final class ProjectBuildFailureContextPolicy {
    private static final int INLINE_LIMIT = 9000;
    private static final int CONTEXT_RADIUS = 2500;
    private static final int COPY_LIMIT = 18000;

    private ProjectBuildFailureContextPolicy() {
    }

    public static boolean canCopyFailureContext(BuildJobRecord job) {
        if (job == null) {
            return false;
        }
        String status = job.status == null ? "" : job.status.trim().toLowerCase(Locale.ROOT);
        return "failed".equals(status) && job.logsPath != null && !job.logsPath.trim().isEmpty();
    }

    public static String copyText(BuildJobRecord job, String logs) {
        StringBuilder result = new StringBuilder();
        if (job != null) {
            result.append("Job #").append(job.id).append('\n');
            appendLine(result, "Status", job.status);
            appendLine(result, "Phase", job.phase);
            appendLine(result, "Error summary", job.errorSummary);
            result.append('\n');
        }
        String context = previewText(logs);
        if (context.isEmpty()) {
            context = "No build log captured.";
        }
        result.append(context);
        return bound(result.toString().trim(), COPY_LIMIT);
    }

    public static String previewText(String logs) {
        if (logs == null || logs.trim().isEmpty()) {
            return "";
        }
        if (logs.length() <= INLINE_LIMIT) {
            return logs;
        }
        StringBuilder result = new StringBuilder();
        appendSnippet(result, "First log", logs, 0, Math.min(1600, logs.length()));
        String missingFieldHints = BuildLogContextExtractor.missingFieldHints(logs);
        if (!missingFieldHints.isEmpty()) {
            appendSnippet(result, "Java API consistency hints", missingFieldHints, 0, missingFieldHints.length());
        }
        String javaDiagnostics = BuildLogContextExtractor.javaCompileDiagnostics(logs, 9000);
        if (!javaDiagnostics.isEmpty()) {
            appendSnippet(result, "Java compile diagnostics", javaDiagnostics, 0, javaDiagnostics.length());
        }
        int[] anchors = failureAnchors(logs);
        for (int anchor : anchors) {
            if (anchor >= 0) {
                appendSnippet(result, "Failure context", logs,
                        Math.max(0, anchor - CONTEXT_RADIUS),
                        Math.min(logs.length(), anchor + CONTEXT_RADIUS));
            }
        }
        appendSnippet(result, "Last log", logs, Math.max(0, logs.length() - 3500), logs.length());
        return bound(result.toString().trim(), 14000);
    }

    private static void appendLine(StringBuilder result, String label, String value) {
        String text = value == null ? "" : value.trim();
        if (!text.isEmpty()) {
            result.append(label).append(": ").append(text).append('\n');
        }
    }

    private static int[] failureAnchors(String logs) {
        return new int[]{
                indexOfAny(logs, ".java:", "error: cannot find symbol", "has private access", "cannot be applied to given types", "actual and formal argument lists differ"),
                indexOfAny(logs, "Android resource linking failed", "error: resource", "error: failed linking", "AAPT: error"),
                indexOfAny(logs, "Namespace not specified", "Manifest merger failed", "package=\"", "> Task :app:processDebugResources FAILED", "Execution failed for task ':app:processDebugResources'", "* What went wrong:"),
                indexOfAny(logs, "BUILD FAILED", "Caused by:")
        };
    }

    private static int indexOfAny(String text, String... needles) {
        int best = -1;
        for (String needle : needles) {
            int index = text.indexOf(needle);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    private static void appendSnippet(StringBuilder result, String label, String text, int start, int end) {
        if (start >= end) {
            return;
        }
        String snippet = text.substring(start, end).trim();
        if (snippet.isEmpty() || result.indexOf(snippet) >= 0) {
            return;
        }
        if (result.length() > 0) {
            result.append("\n\n...\n\n");
        }
        result.append(label).append(":\n").append(snippet);
    }

    private static String bound(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        String suffix = "\n\n...[truncated]";
        return text.substring(0, Math.max(0, limit - suffix.length())).trim() + suffix;
    }
}
