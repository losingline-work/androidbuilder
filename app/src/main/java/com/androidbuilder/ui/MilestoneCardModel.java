package com.androidbuilder.ui;

import com.androidbuilder.model.MilestoneTaskSnapshot;

import java.util.List;

/**
 * View model for one milestone card: header info, its task list (with statuses), and a CONSOLIDATED build
 * summary (all the milestone's build attempts + repair rounds rolled into one — not one row per build).
 */
final class MilestoneCardModel {
    final long milestoneId;
    final int orderIndex;
    final String title;
    final String status;
    final List<MilestoneTaskSnapshot> tasks;
    /** True when the milestone has been built at least once (so the build summary is meaningful). */
    final boolean hasBuild;
    /** Total build attempts for the milestone = repair rounds + 1. */
    final int buildAttempts;
    /** The latest build's status: "success" / "failed" / "building" / "". */
    final String buildResult;
    /** A short excerpt of the latest build's failure, or "". */
    final String buildError;

    MilestoneCardModel(long milestoneId, int orderIndex, String title, String status,
                       List<MilestoneTaskSnapshot> tasks,
                       boolean hasBuild, int buildAttempts, String buildResult, String buildError) {
        this.milestoneId = milestoneId;
        this.orderIndex = orderIndex;
        this.title = title == null ? "" : title;
        this.status = status == null ? "" : status;
        this.tasks = tasks;
        this.hasBuild = hasBuild;
        this.buildAttempts = buildAttempts;
        this.buildResult = buildResult == null ? "" : buildResult;
        this.buildError = buildError == null ? "" : buildError;
    }

    int totalTasks() {
        return tasks == null ? 0 : tasks.size();
    }

    int doneTasks() {
        if (tasks == null) {
            return 0;
        }
        int done = 0;
        for (MilestoneTaskSnapshot task : tasks) {
            if ("done".equals(task.status)) {
                done++;
            }
        }
        return done;
    }
}
