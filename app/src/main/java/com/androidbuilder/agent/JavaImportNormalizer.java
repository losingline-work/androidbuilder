package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JavaImportNormalizer {
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("namespace\\s*(?:=\\s*)?[\"']([^\"']+)[\"']");
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z_][\\w]*(?:\\.[a-zA-Z_][\\w]*)*)\\s*;");
    private static final Pattern BARE_R_USAGE = Pattern.compile("(?<![A-Za-z0-9_.])R(?:\\.|\\.class\\b)");

    private JavaImportNormalizer() {
    }

    static TaskOperations normalize(TaskOperations operations, String sourceSnapshot) {
        if (operations == null || operations.operations == null || operations.operations.isEmpty()) {
            return operations;
        }
        String namespace = namespaceFor(operations, sourceSnapshot);
        if (namespace.isEmpty()) {
            return operations;
        }
        List<FileOperation> normalized = new ArrayList<>();
        boolean changed = false;
        for (FileOperation operation : operations.operations) {
            FileOperation next = normalizeOperation(operation, namespace);
            normalized.add(next);
            changed = changed || next != operation;
        }
        if (!changed) {
            return operations;
        }
        return new TaskOperations(
                operations.summary,
                normalized,
                operations.blocked,
                operations.blockedReason,
                operations.prerequisiteWork,
                operations.manifestJson,
                operations.acceptedPaths);
    }

    private static FileOperation normalizeOperation(FileOperation operation, String namespace) {
        if (operation == null
                || !"write".equals(operation.action)
                || operation.path == null
                || !operation.path.endsWith(".java")) {
            return operation;
        }
        String content = operation.content == null ? "" : operation.content;
        if (!BARE_R_USAGE.matcher(content).find()) {
            return operation;
        }
        Matcher packageMatcher = PACKAGE_PATTERN.matcher(content);
        if (!packageMatcher.find()) {
            return operation;
        }
        String packageName = packageMatcher.group(1);
        if (!packageName.startsWith(namespace + ".")) {
            return operation;
        }
        String importLine = "import " + namespace + ".R;";
        if (content.contains(importLine)) {
            return operation;
        }
        String updated = content.substring(0, packageMatcher.end())
                + "\n"
                + importLine
                + content.substring(packageMatcher.end());
        return new FileOperation(operation.action, operation.path, updated, operation.find, operation.replace);
    }

    private static String namespaceFor(TaskOperations operations, String sourceSnapshot) {
        for (FileOperation operation : operations.operations) {
            if (operation != null && "write".equals(operation.action) && "app/build.gradle".equals(operation.path)) {
                String namespace = namespaceFromText(operation.content);
                if (!namespace.isEmpty()) {
                    return namespace;
                }
            }
        }
        return namespaceFromText(sourceSnapshot);
    }

    private static String namespaceFromText(String text) {
        Matcher matcher = NAMESPACE_PATTERN.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : "";
    }
}
