package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ProjectPlanRecord;
import com.androidbuilder.model.ProjectTaskRecord;

import java.util.List;

public final class ProjectTabPolicy {
    public static final int TAB_DESIGN = 0;
    public static final int TAB_EXECUTE = 1;
    public static final int TAB_BUILD = 2;

    private ProjectTabPolicy() {
    }

    public static int initialTab(int savedTab, ProjectPlanRecord plan, List<ProjectTaskRecord> tasks, BuildJobRecord latestJob) {
        if (isValidTab(savedTab)) {
            return savedTab;
        }
        if (latestJob != null && isRunningJob(latestJob)) {
            return TAB_BUILD;
        }
        if (plan != null && "coding".equals(plan.status)) {
            return TAB_EXECUTE;
        }
        if (hasRunningTask(tasks)) {
            return TAB_EXECUTE;
        }
        return TAB_DESIGN;
    }

    public static boolean isValidTab(int tab) {
        return tab == TAB_DESIGN || tab == TAB_EXECUTE || tab == TAB_BUILD;
    }

    private static boolean hasRunningTask(List<ProjectTaskRecord> tasks) {
        if (tasks == null) {
            return false;
        }
        for (ProjectTaskRecord task : tasks) {
            if ("running".equals(task.status)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRunningJob(BuildJobRecord job) {
        if (job.status == null) {
            return false;
        }
        return "queued".equals(job.status) || "generating".equals(job.status) || "building".equals(job.status);
    }
}
