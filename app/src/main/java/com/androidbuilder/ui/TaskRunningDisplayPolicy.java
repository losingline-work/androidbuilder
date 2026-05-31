package com.androidbuilder.ui;

import com.androidbuilder.model.ProjectTaskRecord;

import java.util.List;

public final class TaskRunningDisplayPolicy {
    private TaskRunningDisplayPolicy() {
    }

    public static boolean shouldShowPredictedRunning(boolean busy, int position, String taskStatus, List<ProjectTaskRecord> tasks) {
        if (!busy || !("pending".equals(taskStatus) || "failed".equals(taskStatus)) || hasRunningTask(tasks)) {
            return false;
        }
        return position == firstActionableTaskIndex(tasks);
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

    private static int firstActionableTaskIndex(List<ProjectTaskRecord> tasks) {
        if (tasks == null) {
            return -1;
        }
        for (int i = 0; i < tasks.size(); i++) {
            String status = tasks.get(i).status == null ? "pending" : tasks.get(i).status;
            if ("failed".equals(status)) {
                return i;
            }
        }
        for (int i = 0; i < tasks.size(); i++) {
            String status = tasks.get(i).status == null ? "pending" : tasks.get(i).status;
            if ("pending".equals(status)) {
                return i;
            }
        }
        return -1;
    }
}
