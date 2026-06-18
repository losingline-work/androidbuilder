package com.androidbuilder.agent;

public final class TaskOperationErrorPolicy {
    private TaskOperationErrorPolicy() {
    }

    public static boolean shouldRequestRewrite(IllegalArgumentException error) {
        String message = error == null ? null : error.getMessage();
        if (message == null) {
            return false;
        }
        return message.startsWith("Dependency policy blocked") ||
                message.startsWith("Generated source policy blocked") ||
                message.startsWith("Task operation response did not contain a JSON object") ||
                message.startsWith("Task operation response JSON could not be parsed") ||
                message.startsWith("Task operation list is empty") ||
                message.startsWith("Unsupported file operation action") ||
                message.startsWith("Unsafe generated file path") ||
                // A stale/ambiguous/empty edit anchor is recoverable: the next rewrite attempt resends the
                // file as a full write. Without these, the merge-path throw (TaskOperationsMergePolicy ->
                // EditOperationPolicy) rethrew immediately and froze the repair loop (project-134: the same
                // javac "15 errors" for 22 rounds because one stale anchor discarded every batch).
                message.startsWith("edit target not found") ||
                message.startsWith("edit target is ambiguous") ||
                message.startsWith("edit operation has empty find");
    }
}
