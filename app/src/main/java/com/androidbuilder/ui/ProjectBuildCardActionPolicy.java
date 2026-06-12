package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

final class ProjectBuildCardActionPolicy {
    enum Action {
        NONE,
        INSTALL,
        REPAIR
    }

    private ProjectBuildCardActionPolicy() {
    }

    static Action action(BuildJobRecord job, boolean repairableByModel) {
        if (job == null) {
            return Action.NONE;
        }
        if ("success".equals(job.status) && job.apkPath != null && !job.apkPath.trim().isEmpty()) {
            return Action.INSTALL;
        }
        if (ProjectJobStatePolicy.isTaskExecutionFailure(job)) {
            return Action.NONE;
        }
        if ("failed".equals(job.status)
                && job.logsPath != null
                && !job.logsPath.trim().isEmpty()
                && repairableByModel) {
            return Action.REPAIR;
        }
        return Action.NONE;
    }
}
