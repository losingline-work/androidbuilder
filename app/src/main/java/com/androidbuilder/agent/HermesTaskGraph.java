package com.androidbuilder.agent;

import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.ProjectTaskRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class HermesTaskGraph {
    private final List<ProjectTaskRecord> tasks;
    private final Set<String> doneProduces;

    private HermesTaskGraph(List<ProjectTaskRecord> tasks, Set<String> doneProduces) {
        this.tasks = tasks;
        this.doneProduces = doneProduces;
    }

    public static HermesTaskGraph fromTasks(List<ProjectTaskRecord> tasks) {
        List<ProjectTaskRecord> ordered = new ArrayList<>();
        if (tasks != null) {
            ordered.addAll(tasks);
        }
        Collections.sort(ordered, TASK_ORDER);
        Set<String> produces = new HashSet<>();
        for (ProjectTaskRecord task : ordered) {
            if (!isDone(task)) {
                continue;
            }
            HermesTaskContract contract = contractFor(task);
            for (String produced : contract.produces) {
                String token = token(produced);
                if (!token.isEmpty()) {
                    produces.add(token);
                }
            }
        }
        return new HermesTaskGraph(Collections.unmodifiableList(ordered), produces);
    }

    public boolean isReady(ProjectTaskRecord task) {
        if (task == null || isDone(task)) {
            return false;
        }
        if (isFailed(task)) {
            return true;
        }
        if (!isPending(task)) {
            return false;
        }
        HermesTaskContract contract = contractFor(task);
        if (!contract.dependsOn.isEmpty()) {
            return dependenciesSatisfied(contract);
        }
        return !hasEarlierUnfinishedBarrier(task);
    }

    public List<ProjectTaskRecord> readyTasks() {
        ProjectTaskRecord failed = firstFailedTask();
        if (failed != null) {
            return Collections.singletonList(failed);
        }
        List<ProjectTaskRecord> ready = new ArrayList<>();
        for (ProjectTaskRecord task : tasks) {
            if (isReady(task)) {
                ready.add(task);
            }
        }
        return ready;
    }

    private boolean dependenciesSatisfied(HermesTaskContract contract) {
        for (String dependency : contract.dependsOn) {
            String token = token(dependency);
            if (!token.isEmpty() && !doneProduces.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasEarlierUnfinishedBarrier(ProjectTaskRecord task) {
        for (ProjectTaskRecord candidate : tasks) {
            if (candidate == task || compare(candidate, task) >= 0) {
                continue;
            }
            if (isDone(candidate)) {
                continue;
            }
            HermesTaskContract contract = contractFor(candidate);
            if (HermesFileLockPolicy.isExclusiveBarrier(candidate.title, candidate.instruction, contract)) {
                return true;
            }
        }
        return false;
    }

    private ProjectTaskRecord firstFailedTask() {
        for (ProjectTaskRecord task : tasks) {
            if (isFailed(task)) {
                return task;
            }
        }
        return null;
    }

    private static HermesTaskContract contractFor(ProjectTaskRecord task) {
        if (task == null) {
            return HermesTaskContract.empty();
        }
        return HermesTaskContractCodec.extractFromInstruction(task.instruction);
    }

    static boolean isDone(ProjectTaskRecord task) {
        return hasStatus(task, "done");
    }

    static boolean isFailed(ProjectTaskRecord task) {
        return hasStatus(task, "failed");
    }

    static boolean isPending(ProjectTaskRecord task) {
        return hasStatus(task, "pending");
    }

    private static boolean hasStatus(ProjectTaskRecord task, String expected) {
        return task != null && task.status != null && expected.equalsIgnoreCase(task.status.trim());
    }

    private static String token(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static int compare(ProjectTaskRecord left, ProjectTaskRecord right) {
        int order = Integer.compare(left.sortOrder, right.sortOrder);
        if (order != 0) {
            return order;
        }
        return Long.compare(left.id, right.id);
    }

    private static final Comparator<ProjectTaskRecord> TASK_ORDER = new Comparator<ProjectTaskRecord>() {
        @Override
        public int compare(ProjectTaskRecord left, ProjectTaskRecord right) {
            return HermesTaskGraph.compare(left, right);
        }
    };
}
