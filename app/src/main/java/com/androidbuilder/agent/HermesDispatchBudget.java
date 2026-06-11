package com.androidbuilder.agent;

import com.androidbuilder.model.ProjectTaskRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class HermesDispatchBudget {
    static final int MAX_DISPATCHES_PER_EXECUTION = 2;

    private HermesDispatchBudget() {
    }

    static boolean allows(Map<Long, Integer> dispatchCounts, long taskId) {
        if (dispatchCounts == null) {
            return true;
        }
        Integer count = dispatchCounts.get(taskId);
        return count == null || count < MAX_DISPATCHES_PER_EXECUTION;
    }

    static void markDispatched(Map<Long, Integer> dispatchCounts, long taskId) {
        if (dispatchCounts == null) {
            return;
        }
        Integer count = dispatchCounts.get(taskId);
        dispatchCounts.put(taskId, count == null ? 1 : count + 1);
    }

    static List<ProjectTaskRecord> allowedTasks(List<ProjectTaskRecord> tasks, Map<Long, Integer> dispatchCounts) {
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }
        List<ProjectTaskRecord> allowed = new ArrayList<>();
        for (ProjectTaskRecord task : tasks) {
            if (task == null) {
                continue;
            }
            if (isFailed(task) && !allows(dispatchCounts, task.id)) {
                continue;
            }
            allowed.add(task);
        }
        return allowed;
    }

    private static boolean isFailed(ProjectTaskRecord task) {
        return task.status != null && "failed".equalsIgnoreCase(task.status.trim());
    }
}
