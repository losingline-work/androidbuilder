package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

final class CanonicalPathPolicy {
    private CanonicalPathPolicy() {
    }

    static String canonicalize(String rawPath) {
        String path = PathValidator.normalizeGeneratedPath(rawPath);
        if (path.equals("AndroidManifest.xml")
                || path.equals("src/main/AndroidManifest.xml")
                || path.equals("app/AndroidManifest.xml")) {
            return "app/src/main/AndroidManifest.xml";
        }
        if (path.startsWith("res/")) {
            return "app/src/main/" + path;
        }
        if (path.startsWith("app/res/")) {
            return "app/src/main/" + path.substring("app/".length());
        }
        if (path.startsWith("src/main/res/")) {
            return "app/" + path;
        }
        if (path.startsWith("java/")) {
            return "app/src/main/" + path;
        }
        if (path.startsWith("app/java/")) {
            return "app/src/main/" + path.substring("app/".length());
        }
        if (path.startsWith("src/main/java/")) {
            return "app/" + path;
        }
        if (path.startsWith("assets/")) {
            return "app/src/main/" + path;
        }
        if (path.startsWith("app/assets/")) {
            return "app/src/main/" + path.substring("app/".length());
        }
        if (path.startsWith("src/main/assets/")) {
            return "app/" + path;
        }
        return path;
    }

    static TaskOperations canonicalizeAll(TaskOperations operations) {
        if (operations == null) {
            return new TaskOperations("", new ArrayList<FileOperation>());
        }
        Map<String, FileOperation> byPath = new LinkedHashMap<>();
        for (FileOperation operation : operations.operations) {
            FileOperation canonical = canonicalOperation(operation);
            byPath.remove(canonical.path);
            byPath.put(canonical.path, canonical);
        }
        return new TaskOperations(
                operations.summary,
                new ArrayList<>(byPath.values()),
                operations.blocked,
                operations.blockedReason,
                operations.prerequisiteWork);
    }

    static FileOperation canonicalOperation(FileOperation operation) {
        if (operation == null) {
            throw new IllegalArgumentException("Task operation is missing.");
        }
        String action = operation.action == null ? "" : operation.action.trim();
        if (!"write".equals(action) && !"delete".equals(action) && !"drop".equals(action) && !"edit".equals(action)) {
            throw new IllegalArgumentException("Unsupported file operation action: " + action);
        }
        String path = canonicalize(operation.path);
        String content = "delete".equals(action) || "drop".equals(action) || "edit".equals(action) ? "" : (operation.content == null ? "" : operation.content);
        return new FileOperation(action, path, content, operation.find, operation.replace);
    }
}
