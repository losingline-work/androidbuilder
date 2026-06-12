package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

final class ProjectBuildLogContentPolicy {
    private ProjectBuildLogContentPolicy() {
    }

    static Content content(BuildJobRecord job, boolean showLogContent, String logPreview, String noLogText, boolean chinese) {
        String failure = failureSummary(job, chinese);
        if (!failure.isEmpty()) {
            return new Content(true, failure);
        }
        if (!showLogContent) {
            return new Content(false, "");
        }
        String text = clean(logPreview);
        return new Content(true, text.isEmpty() ? clean(noLogText) : text);
    }

    static boolean hasFailureSummary(BuildJobRecord job) {
        return !failureSummary(job, true).isEmpty();
    }

    private static String failureSummary(BuildJobRecord job, boolean chinese) {
        if (job == null) {
            return "";
        }
        String summary = clean(job.errorSummary);
        if (!ProjectJobStatePolicy.isTaskExecutionFailure(job) || summary.isEmpty()) {
            return "";
        }
        if (summary.startsWith("执行计划失败：") || summary.startsWith("Execute plan failed: ")) {
            return summary;
        }
        return (chinese ? "执行计划失败：" : "Execute plan failed: ") + summary;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    static final class Content {
        final boolean visible;
        final String text;

        Content(boolean visible, String text) {
            this.visible = visible;
            this.text = text == null ? "" : text;
        }
    }
}
