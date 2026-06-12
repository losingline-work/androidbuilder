package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

public final class ProjectBuildLogExpansionPolicy {
    private ProjectBuildLogExpansionPolicy() {
    }

    public static boolean shouldShowContent(BuildJobRecord job, boolean userExpanded) {
        if (job == null) {
            return true;
        }
        if ("building".equals(job.status) || "queued".equals(job.status) || "generating".equals(job.status)) {
            return true;
        }
        return userExpanded;
    }
}
