package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TaskOperationsMergePolicy {
    private TaskOperationsMergePolicy() {
    }

    static TaskOperations merge(TaskOperations previousDraft, TaskOperations correction) {
        if (previousDraft == null) {
            return normalizedCopy(correction);
        }
        if (correction == null) {
            return normalizedCopy(previousDraft);
        }
        Map<String, FileOperation> byPath = new LinkedHashMap<>();
        for (FileOperation operation : previousDraft.operations) {
            FileOperation normalized = normalized(operation);
            if ("drop".equals(normalized.action)) {
                byPath.remove(normalized.path);
            } else {
                byPath.put(normalized.path, normalized);
            }
        }
        for (FileOperation operation : correction.operations) {
            FileOperation normalized = normalized(operation);
            if ("drop".equals(normalized.action)) {
                byPath.remove(normalized.path);
            } else {
                byPath.put(normalized.path, normalized);
            }
        }
        String correctionSummary = correction.summary == null ? "" : correction.summary.trim();
        String previousSummary = previousDraft.summary == null ? "" : previousDraft.summary.trim();
        return new TaskOperations(correctionSummary.isEmpty() ? previousSummary : correctionSummary, new ArrayList<>(byPath.values()));
    }

    static TaskOperations stripDrops(TaskOperations operations) {
        if (operations == null) {
            return new TaskOperations("", new ArrayList<FileOperation>());
        }
        List<FileOperation> stripped = new ArrayList<>();
        for (FileOperation operation : operations.operations) {
            FileOperation normalized = normalized(operation);
            if (!"drop".equals(normalized.action)) {
                stripped.add(normalized);
            }
        }
        return new TaskOperations(
                operations.summary == null ? "" : operations.summary.trim(),
                stripped,
                operations.blocked,
                operations.blockedReason,
                operations.prerequisiteWork);
    }

    private static TaskOperations normalizedCopy(TaskOperations operations) {
        if (operations == null) {
            return new TaskOperations("", new ArrayList<FileOperation>());
        }
        List<FileOperation> normalized = new ArrayList<>();
        for (FileOperation operation : operations.operations) {
            FileOperation item = normalized(operation);
            if (!"drop".equals(item.action)) {
                normalized.add(item);
            }
        }
        return new TaskOperations(operations.summary == null ? "" : operations.summary.trim(), normalized);
    }

    private static FileOperation normalized(FileOperation operation) {
        if (operation == null) {
            throw new IllegalArgumentException("Task operation is missing.");
        }
        String action = operation.action == null ? "" : operation.action.trim();
        if (!"write".equals(action) && !"delete".equals(action) && !"drop".equals(action)) {
            throw new IllegalArgumentException("Unsupported file operation action: " + action);
        }
        String path = PathValidator.normalizeGeneratedPath(operation.path);
        String content = "delete".equals(action) || "drop".equals(action) ? "" : (operation.content == null ? "" : operation.content);
        return new FileOperation(action, path, content);
    }
}
