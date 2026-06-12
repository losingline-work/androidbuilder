package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

import java.util.Locale;

final class ProjectJobStatePolicy {
    private ProjectJobStatePolicy() {
    }

    static boolean isTaskExecutionFailure(BuildJobRecord job) {
        if (job == null) {
            return false;
        }
        String status = job.status == null ? "" : job.status.toLowerCase(Locale.ROOT);
        String phase = job.phase == null ? "" : job.phase.toLowerCase(Locale.ROOT);
        return "failed".equals(status) && phase.contains("coding_failed");
    }
}
