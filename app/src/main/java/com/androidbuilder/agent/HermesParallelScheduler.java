package com.androidbuilder.agent;

import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.HermesAgentRunRecord;
import com.androidbuilder.model.ProjectTaskRecord;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class HermesParallelScheduler {
    private HermesParallelScheduler() {
    }

    public static HermesParallelBatch nextBatch(
            List<ProjectTaskRecord> tasks,
            List<HermesAgentRunRecord> activeRuns,
            int maxParallel) {
        HermesTaskGraph graph = HermesTaskGraph.fromTasks(tasks);
        List<ProjectTaskRecord> ready = graph.readyTasks();
        if (ready.isEmpty()) {
            return HermesParallelBatch.empty();
        }
        ProjectTaskRecord first = ready.get(0);
        if (HermesTaskGraph.isFailed(first)) {
            return single(first, "failed_retry");
        }

        List<String> activeLocks = activeLocks(activeRuns);
        if (maxParallel <= 1) {
            ProjectTaskRecord serialTask = firstSchedulable(ready, activeLocks);
            if (serialTask == null) {
                return HermesParallelBatch.empty();
            }
            return single(serialTask, "serial");
        }

        if (isBarrier(first) && !conflictsWithActive(first, activeLocks)) {
            return single(first, "exclusive_barrier");
        } else if (isBarrier(first)) {
            return HermesParallelBatch.empty();
        }

        List<ProjectTaskRecord> selected = new ArrayList<>();
        List<String> selectedLocks = new ArrayList<>();
        for (ProjectTaskRecord task : ready) {
            if (isBarrier(task)) {
                if (selected.isEmpty() && !conflictsWithActive(task, activeLocks)) {
                    return single(task, "exclusive_barrier");
                }
                break;
            }
            List<String> locks = locksFor(task);
            if (HermesFileLockPolicy.conflicts(locks, activeLocks)
                    || HermesFileLockPolicy.conflicts(locks, selectedLocks)) {
                continue;
            }
            selected.add(task);
            selectedLocks.addAll(locks);
            if (selected.size() >= maxParallel) {
                break;
            }
        }
        if (selected.isEmpty()) {
            return HermesParallelBatch.empty();
        }
        return new HermesParallelBatch(0, selected, "");
    }

    private static HermesParallelBatch single(ProjectTaskRecord task, String reason) {
        return new HermesParallelBatch(0, Collections.singletonList(task), reason);
    }

    private static ProjectTaskRecord firstSchedulable(List<ProjectTaskRecord> ready, List<String> activeLocks) {
        for (ProjectTaskRecord task : ready) {
            if (!conflictsWithActive(task, activeLocks)) {
                return task;
            }
        }
        return null;
    }

    private static boolean conflictsWithActive(ProjectTaskRecord task, List<String> activeLocks) {
        return HermesFileLockPolicy.conflicts(locksFor(task), activeLocks);
    }

    private static boolean isBarrier(ProjectTaskRecord task) {
        HermesTaskContract contract = contractFor(task);
        return HermesFileLockPolicy.isExclusiveBarrier(task.title, task.instruction, contract);
    }

    private static List<String> locksFor(ProjectTaskRecord task) {
        HermesTaskContract contract = contractFor(task);
        return HermesFileLockPolicy.locksFor(task.title, task.instruction, contract);
    }

    private static HermesTaskContract contractFor(ProjectTaskRecord task) {
        if (task == null) {
            return HermesTaskContract.empty();
        }
        return HermesTaskContractCodec.extractFromInstruction(task.instruction);
    }

    private static List<String> activeLocks(List<HermesAgentRunRecord> activeRuns) {
        if (activeRuns == null || activeRuns.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> locks = new ArrayList<>();
        for (HermesAgentRunRecord run : activeRuns) {
            if (run == null || !isActive(run.status)) {
                continue;
            }
            locks.addAll(parseLocks(run.lockedPathsJson));
        }
        return locks;
    }

    private static boolean isActive(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase(Locale.US);
        return "running".equals(normalized) || "merge_pending".equals(normalized);
    }

    private static List<String> parseLocks(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            JSONArray array = new JSONArray(json);
            List<String> locks = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                String lock = array.optString(i, "").trim();
                if (!lock.isEmpty()) {
                    locks.add(lock);
                }
            }
            return locks;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

}
