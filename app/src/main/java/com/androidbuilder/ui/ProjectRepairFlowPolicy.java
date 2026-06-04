package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

final class ProjectRepairFlowPolicy {
    private ProjectRepairFlowPolicy() {
    }

    static BuildJobRecord repairTargetJob(BuildJobRecord latestJob, BuildJobRecord latestFailedBuildWithLog) {
        if (isFailedWithLog(latestJob)) {
            return latestJob;
        }
        if (latestJob != null && "repair_failed".equals(latestJob.phase) && isFailedWithLog(latestFailedBuildWithLog)) {
            return latestFailedBuildWithLog;
        }
        return null;
    }

    private static boolean isFailedWithLog(BuildJobRecord job) {
        return hasLog(job) && "failed".equals(job.status);
    }

    private static boolean hasLog(BuildJobRecord job) {
        return job != null && job.logsPath != null;
    }
}
