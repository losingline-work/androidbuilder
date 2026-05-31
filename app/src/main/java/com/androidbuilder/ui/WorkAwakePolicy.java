package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ProjectTaskRecord;

import java.util.List;

public final class WorkAwakePolicy {
    private WorkAwakePolicy() {
    }

    public static boolean shouldKeepScreenOn(boolean busy, boolean autoExecutingPlan, List<ProjectTaskRecord> tasks, BuildJobRecord latestJob) {
        if (busy || autoExecutingPlan) {
            return true;
        }
        if (tasks != null) {
            for (ProjectTaskRecord task : tasks) {
                if ("running".equals(task.status)) {
                    return true;
                }
            }
        }
        return latestJob != null && isRunningJob(latestJob);
    }

    private static boolean isRunningJob(BuildJobRecord job) {
        if (job.status == null) {
            return false;
        }
        return "queued".equals(job.status) || "generating".equals(job.status) || "building".equals(job.status);
    }
}
