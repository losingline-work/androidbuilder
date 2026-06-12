package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.HermesReview;
import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.TaskOperations;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class HermesTaskContractGuard {
    private HermesTaskContractGuard() {
    }

    static HermesReview review(HermesTaskContract contract, TaskOperations operations) {
        if (contract == null || !contract.hasSignals() || operations == null || operations.operations == null) {
            return ok();
        }
        Set<String> touchedPaths = operationPaths(operations.operations, false);
        for (String forbiddenPath : normalizedSet(contract.forbiddenPaths)) {
            if (touchedPaths.contains(forbiddenPath)) {
                return rewrite(
                        "Generated operations touched forbiddenPaths: " + forbiddenPath + ".",
                        "Rewrite the operations without touching " + forbiddenPath + ". Keep the task scoped to allowed or expected files.");
            }
        }

        Set<String> writtenPaths = operationPaths(operations.operations, true);
        Set<String> expectedFiles = normalizedSet(contract.expectedFiles);
        Set<String> missingExpected = new HashSet<>();
        for (String expectedPath : expectedFiles) {
            if (!writtenPaths.contains(expectedPath)) {
                missingExpected.add(expectedPath);
            }
        }
        if (isSeverelyUnderDelivered(expectedFiles.size(), missingExpected.size())) {
            String missing = join(missingExpected);
            return rewrite(
                    "Generated operations under-delivered expectedFiles: " + missing + ".",
                    "Rewrite the operations to include the missing expected files or adjust the task contract if those files are no longer required: " + missing + ".");
        }
        return ok();
    }

    private static boolean isSeverelyUnderDelivered(int expectedCount, int missingCount) {
        if (expectedCount <= 0 || missingCount < 3) {
            return false;
        }
        int deliveredCount = expectedCount - missingCount;
        return deliveredCount * 2 < expectedCount;
    }

    private static Set<String> operationPaths(List<FileOperation> operations, boolean writesOnly) {
        Set<String> paths = new HashSet<>();
        if (operations == null) {
            return paths;
        }
        for (FileOperation operation : operations) {
            if (operation == null || operation.path == null) {
                continue;
            }
            if (writesOnly && !"write".equals(operation.action)) {
                continue;
            }
            paths.add(normalizePath(operation.path));
        }
        return paths;
    }

    private static Set<String> normalizedSet(List<String> values) {
        Set<String> paths = new HashSet<>();
        if (values == null) {
            return paths;
        }
        for (String value : values) {
            String path = normalizePath(value);
            if (!path.isEmpty()) {
                paths.add(path);
            }
        }
        return paths;
    }

    private static String normalizePath(String path) {
        try {
            return PathValidator.normalizeGeneratedPath(path);
        } catch (Exception ignored) {
            return path == null ? "" : path.trim();
        }
    }

    private static HermesReview ok() {
        return new HermesReview(HermesReview.Decision.OK, "Hermes task contract passed.", "");
    }

    private static HermesReview rewrite(String summary, String instruction) {
        return new HermesReview(HermesReview.Decision.REWRITE, summary, instruction);
    }

    private static String join(Set<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(value);
        }
        return builder.toString();
    }
}
