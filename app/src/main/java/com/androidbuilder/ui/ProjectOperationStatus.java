package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

public final class ProjectOperationStatus {
    private ProjectOperationStatus() {
    }

    public static boolean shouldShow(String message, boolean busy, boolean autoExecutingPlan, BuildJobRecord latestJob) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        return busy || autoExecutingPlan || isRunningJob(latestJob);
    }

    public static String displayText(String message, String elapsedText) {
        String text = message == null ? "" : message.trim();
        String elapsed = elapsedText == null ? "" : elapsedText.trim();
        if (elapsed.isEmpty()) {
            return text;
        }
        return text + " · " + elapsed;
    }

    private static boolean isRunningJob(BuildJobRecord job) {
        if (job == null || job.status == null) {
            return false;
        }
        return "queued".equals(job.status) || "generating".equals(job.status) || "building".equals(job.status);
    }
}
