package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

final class ProjectExecutePlanErrorMessagePolicy {
    private ProjectExecutePlanErrorMessagePolicy() {
    }

    static boolean shouldAddStandaloneMessage(BuildJobRecord latestJob, String errorMessage) {
        return !isCoveredByFailedCodingJob(latestJob, errorMessage);
    }

    static boolean isCoveredStandaloneMessage(String role, String content, BuildJobRecord latestJob) {
        if (!"assistant".equals(role)) {
            return false;
        }
        return isCoveredByFailedCodingJob(latestJob, stripExecutePlanFailurePrefix(content));
    }

    private static boolean isCoveredByFailedCodingJob(BuildJobRecord latestJob, String errorMessage) {
        String error = clean(errorMessage);
        if (latestJob == null || error.isEmpty()) {
            return false;
        }
        String summary = clean(latestJob.errorSummary);
        return ProjectJobStatePolicy.isTaskExecutionFailure(latestJob) && error.equals(summary);
    }

    private static String stripExecutePlanFailurePrefix(String value) {
        String text = clean(value);
        String chinesePrefix = "执行计划失败：";
        String englishPrefix = "Execute plan failed: ";
        if (text.startsWith(chinesePrefix)) {
            return text.substring(chinesePrefix.length()).trim();
        }
        if (text.startsWith(englishPrefix)) {
            return text.substring(englishPrefix.length()).trim();
        }
        return text;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
