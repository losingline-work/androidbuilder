package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TaskDraftContextPolicy {
    static final int DRAFT_SECTION_LIMIT = 12000;
    private static final String TRUNCATED = "\n...[truncated]";

    private TaskDraftContextPolicy() {
    }

    static String correctionSection(TaskOperations previousDraft, String error, int maxChars) {
        return section(
                "Previous draft available for correction.",
                "Previous draft selected operations (full content for files/types named by the latest error):",
                previousDraft,
                error,
                maxChars);
    }

    static String advisorySection(TaskOperations previousDraft, String error, int maxChars) {
        return section(
                "Previous rejected draft advisory. The last failed execution produced a valid draft; use this to avoid repeating blind rewrites.",
                "Previous rejected draft selected operations:",
                previousDraft,
                error,
                maxChars);
    }

    private static String section(String header, String selectedHeader, TaskOperations previousDraft, String error, int maxChars) {
        if (previousDraft == null || previousDraft.operations == null || previousDraft.operations.isEmpty()) {
            return "";
        }
        int limit = normalizedLimit(maxChars);
        StringBuilder builder = new StringBuilder();
        builder.append(header).append('\n');
        if (previousDraft.summary != null && !previousDraft.summary.trim().isEmpty()) {
            builder.append("Previous draft summary: ").append(previousDraft.summary.trim()).append('\n');
        }
        builder.append("Previous draft manifest:\n");
        List<FileOperation> normalized = normalizedOperations(previousDraft);
        for (FileOperation operation : normalized) {
            builder.append("- ").append(operation.action).append(' ').append(operation.path).append('\n');
        }
        List<FileOperation> selected = selectedOperations(normalized, error);
        if (!selected.isEmpty()) {
            builder.append('\n').append(selectedHeader).append('\n');
            for (FileOperation operation : selected) {
                builder.append("--- operation ").append(operation.action).append(' ').append(operation.path).append(" ---\n");
                if ("delete".equals(operation.action)) {
                    builder.append("(delete operation)\n");
                } else {
                    builder.append(operation.content == null ? "" : operation.content);
                    if (builder.length() == 0 || builder.charAt(builder.length() - 1) != '\n') {
                        builder.append('\n');
                    }
                }
            }
        }
        return trimToLimit(builder.toString().trim(), limit);
    }

    private static int normalizedLimit(int maxChars) {
        if (maxChars <= 0) {
            return DRAFT_SECTION_LIMIT;
        }
        return Math.min(maxChars, DRAFT_SECTION_LIMIT);
    }

    private static List<FileOperation> normalizedOperations(TaskOperations draft) {
        List<FileOperation> operations = new ArrayList<>();
        for (FileOperation operation : draft.operations) {
            String action = operation.action == null ? "" : operation.action.trim();
            String path = PathValidator.normalizeGeneratedPath(operation.path);
            operations.add(new FileOperation(action, path, operation.content == null ? "" : operation.content));
        }
        return operations;
    }

    private static List<FileOperation> selectedOperations(List<FileOperation> operations, String error) {
        Set<String> fileNames = referencedFileNames(error);
        Set<String> typeNames = referencedTypeNames(error);
        String lowerError = error == null ? "" : error.toLowerCase(Locale.ROOT);
        List<FileOperation> selected = new ArrayList<>();
        for (FileOperation operation : operations) {
            String path = operation.path;
            String fileName = fileName(path);
            String typeName = typeName(fileName);
            if (lowerError.contains(path.toLowerCase(Locale.ROOT))
                    || fileNames.contains(fileName)
                    || (!typeName.isEmpty() && typeNames.contains(typeName))) {
                selected.add(operation);
            }
        }
        return selected;
    }

    private static Set<String> referencedFileNames(String error) {
        Set<String> names = new HashSet<>();
        if (error == null || error.trim().isEmpty()) {
            return names;
        }
        Matcher matcher = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*\\.(?:java|xml|gradle|properties))\\b").matcher(error);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static Set<String> referencedTypeNames(String error) {
        Set<String> names = new HashSet<>(BuildLogContextExtractor.referencedJavaTypes(error));
        if (error == null || error.trim().isEmpty()) {
            return names;
        }
        Matcher matcher = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\b").matcher(error);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static String fileName(String path) {
        int slash = path == null ? -1 : path.lastIndexOf('/');
        return slash < 0 ? (path == null ? "" : path) : path.substring(slash + 1);
    }

    private static String typeName(String fileName) {
        if (fileName == null || !fileName.endsWith(".java")) {
            return "";
        }
        return fileName.substring(0, fileName.length() - ".java".length());
    }

    private static String trimToLimit(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        if (limit <= TRUNCATED.length()) {
            return text.substring(0, Math.max(0, limit));
        }
        return text.substring(0, limit - TRUNCATED.length()).trim() + TRUNCATED;
    }
}
