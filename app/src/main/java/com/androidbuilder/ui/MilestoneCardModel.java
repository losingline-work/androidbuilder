package com.androidbuilder.ui;

import com.androidbuilder.model.MilestoneTaskSnapshot;

import java.util.List;

/** View model for one milestone card in the timeline: header info + its task list (with statuses). */
final class MilestoneCardModel {
    final long milestoneId;
    final int orderIndex;
    final String title;
    final String status;
    final List<MilestoneTaskSnapshot> tasks;

    MilestoneCardModel(long milestoneId, int orderIndex, String title, String status, List<MilestoneTaskSnapshot> tasks) {
        this.milestoneId = milestoneId;
        this.orderIndex = orderIndex;
        this.title = title == null ? "" : title;
        this.status = status == null ? "" : status;
        this.tasks = tasks;
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
