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
            } else if ("edit".equals(normalized.action)) {
                throw new IllegalArgumentException("edit target not found in " + normalized.path + " (the file may have changed); resend the full file with action write");
            } else {
                byPath.put(normalized.path, normalized);
            }
        }
        for (FileOperation operation : correction.operations) {
            FileOperation normalized = normalized(operation);
            if ("drop".equals(normalized.action)) {
                byPath.remove(normalized.path);
            } else if ("edit".equals(normalized.action)) {
                FileOperation previous = byPath.get(normalized.path);
                String existingContent = previous == null || !"write".equals(previous.action) ? "" : previous.content;
                String updated = EditOperationPolicy.apply(existingContent, normalized.find, normalized.replace, normalized.path);
                byPath.put(normalized.path, new FileOperation("write", normalized.path, updated));
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
                if ("edit".equals(item.action)) {
                    throw new IllegalArgumentException("edit target not found in " + item.path + " (the file may have changed); resend the full file with action write");
                }
                normalized.add(item);
            }
        }
        return new TaskOperations(operations.summary == null ? "" : operations.summary.trim(), normalized);
    }

    private static FileOperation normalized(FileOperation operation) {
        return CanonicalPathPolicy.canonicalOperation(operation);
    }
}
