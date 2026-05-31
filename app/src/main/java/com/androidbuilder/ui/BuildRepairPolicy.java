package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

final class BuildRepairPolicy {
    static final int MAX_AUTO_REPAIR_ATTEMPTS = 3;

    private BuildRepairPolicy() {
    }

    static boolean canAutoRepair(BuildJobRecord job, boolean alreadyRepairing, boolean repairable) {
        return job != null &&
                "failed".equals(job.status) &&
                !alreadyRepairing &&
                repairable &&
                !reachedAutoRepairLimit(job);
    }

    static boolean reachedAutoRepairLimit(BuildJobRecord job) {
        return job != null && job.retryCount >= MAX_AUTO_REPAIR_ATTEMPTS;
    }

    static int retryCountForManualBuild(BuildJobRecord ignored) {
        return 0;
    }

    static int nextRetryCount(BuildJobRecord failedJob) {
        return failedJob == null ? 1 : failedJob.retryCount + 1;
    }
}
