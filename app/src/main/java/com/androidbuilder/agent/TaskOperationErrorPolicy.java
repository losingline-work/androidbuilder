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
                message.startsWith("Unsafe generated file path");
    }
}
