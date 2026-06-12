package com.androidbuilder.agent;

import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.ProjectTaskRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SequentialFailureGate {
    private SequentialFailureGate() {
    }

    static List<ProjectTaskRecord> filter(
            List<ProjectTaskRecord> allTasks,
            List<ProjectTaskRecord> allowed,
            Map<Long, Integer> dispatchCounts,
            Set<String> doneProduces) {
        if (allowed == null || allowed.isEmpty()) {
            return Collections.emptyList();
        }
        ProjectTaskRecord exhaustedFailure = firstExhaustedFailure(allTasks, dispatchCounts);
        if (exhaustedFailure == null) {
            return allowed;
        }
        Set<String> normalizedDoneProduces = normalizeSet(doneProduces);
        List<ProjectTaskRecord> filtered = new ArrayList<>();
        for (ProjectTaskRecord task : allowed) {
            if (task == null) {
                continue;
            }
            if (compare(task, exhaustedFailure) <= 0 || dependenciesExplicitlySatisfied(task, normalizedDoneProduces)) {
                filtered.add(task);
            }
        }
        return filtered;
    }

    static ProjectTaskRecord firstExhaustedFailure(List<ProjectTaskRecord> allTasks, Map<Long, Integer> dispatchCounts) {
        ProjectTaskRecord first = null;
        if (allTasks == null) {
            return null;
        }
        for (ProjectTaskRecord task : allTasks) {
            if (task == null || !HermesTaskGraph.isFailed(task) || HermesDispatchBudget.allows(dispatchCounts, task.id)) {
                continue;
            }
            if (first == null || compare(task, first) < 0) {
                first = task;
            }
        }
        return first;
    }

    static Set<String> doneProducesForTasks(List<ProjectTaskRecord> tasks) {
        Set<String> produces = new HashSet<>();
        if (tasks == null) {
            return produces;
        }
        for (ProjectTaskRecord task : tasks) {
            if (!HermesTaskGraph.isDone(task)) {
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
        return produces;
    }

    private static boolean dependenciesExplicitlySatisfied(ProjectTaskRecord task, Set<String> doneProduces) {
        HermesTaskContract contract = contractFor(task);
        if (contract.dependsOn.isEmpty()) {
            return false;
        }
        for (String dependency : contract.dependsOn) {
            String token = token(dependency);
            if (!token.isEmpty() && !doneProduces.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> normalizeSet(Set<String> values) {
        Set<String> normalized = new HashSet<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            String token = token(value);
            if (!token.isEmpty()) {
                normalized.add(token);
            }
        }
        return normalized;
    }

    private static HermesTaskContract contractFor(ProjectTaskRecord task) {
        if (task == null) {
            return HermesTaskContract.empty();
        }
        return HermesTaskContractCodec.extractFromInstruction(task.instruction);
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
}
