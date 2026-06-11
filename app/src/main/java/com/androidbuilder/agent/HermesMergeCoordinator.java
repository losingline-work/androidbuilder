package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.HermesReview;
import com.androidbuilder.model.TaskOperations;
import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class HermesMergeCoordinator {
    private HermesMergeCoordinator() {
    }

    public static MergePlan plan(List<HermesAgentResult> results) {
        List<HermesAgentResult> successful = successfulResults(results);
        sortByTaskOrder(successful);
        List<HermesAgentResult> mergeable = new ArrayList<>();
        List<FailedResult> failed = new ArrayList<>();
        Map<String, HermesAgentResult> owners = new HashMap<>();
        for (HermesAgentResult result : successful) {
            List<String> normalizedPaths = new ArrayList<>();
            String failure = pathFailureReason(result, owners, normalizedPaths);
            if (!failure.isEmpty()) {
                failed.add(new FailedResult(result, failure));
                continue;
            }
            for (String path : normalizedPaths) {
                owners.put(path, result);
            }
            mergeable.add(result);
        }
        List<String> conflicts = reasons(failed);
        return new MergePlan(failed.isEmpty(), conflicts, mergeable, failed);
    }

    public static MergeResult merge(File canonicalSource, List<HermesAgentResult> results) throws Exception {
        MergePlan plan = plan(results);
        String snapshot = sourceSnapshot(canonicalSource);
        FileOperationsWriter writer = new FileOperationsWriter();
        List<HermesAgentResult> merged = new ArrayList<>();
        List<FailedResult> failed = new ArrayList<>(plan.failedResults);
        for (HermesAgentResult result : plan.mergeableResults) {
            HermesReview contractReview = HermesTaskContractGuard.review(result.contract, result.operations);
            if (contractReview.decision == HermesReview.Decision.REWRITE) {
                failed.add(new FailedResult(result, "Contract guard rejected task " + taskLabel(result) + ": "
                        + contractReview.summary));
                continue;
            }
            HermesReview preflightReview = TaskOperationsPreflight.review(result.operations, snapshot);
            if (preflightReview.decision == HermesReview.Decision.REWRITE) {
                failed.add(new FailedResult(result, "Preflight rejected task " + taskLabel(result) + ": "
                        + preflightReview.summary));
                continue;
            }
            try {
                writer.apply(canonicalSource, result.operations);
                merged.add(result);
            } catch (Exception error) {
                failed.add(new FailedResult(result, error.getMessage() == null ? error.toString() : error.getMessage()));
            }
        }
        return MergeResult.completed(merged, failed);
    }

    private static String pathFailureReason(HermesAgentResult result, Map<String, HermesAgentResult> owners, List<String> normalizedPaths) {
        if (result == null) {
            return "Missing merge result.";
        }
        for (String path : touchedPaths(result)) {
            if (path.contains("*")) {
                return "Task " + taskLabel(result) + " declared wildcard touched path: " + path;
            }
            String normalized;
            try {
                normalized = PathValidator.normalizeGeneratedPath(path);
            } catch (IllegalArgumentException error) {
                return "Task " + taskLabel(result) + " declared unsafe touched path: " + path;
            }
            HermesAgentResult owner = owners.get(normalized);
            if (owner != null && owner != result) {
                return "Path conflict on " + normalized + " between task "
                        + taskLabel(owner) + " and task " + taskLabel(result) + ".";
            }
            if (!normalizedPaths.contains(normalized)) {
                normalizedPaths.add(normalized);
            }
        }
        return "";
    }

    private static List<HermesAgentResult> successfulResults(List<HermesAgentResult> results) {
        List<HermesAgentResult> successful = new ArrayList<>();
        if (results == null) {
            return successful;
        }
        for (HermesAgentResult result : results) {
            if (result != null && result.success()) {
                successful.add(result);
            }
        }
        return successful;
    }

    private static List<String> touchedPaths(HermesAgentResult result) {
        if (result.touchedPaths != null && !result.touchedPaths.isEmpty()) {
            return result.touchedPaths;
        }
        List<String> paths = new ArrayList<>();
        TaskOperations operations = result.operations;
        if (operations == null || operations.operations == null) {
            return paths;
        }
        for (FileOperation operation : operations.operations) {
            if (operation != null && operation.path != null && !operation.path.trim().isEmpty()) {
                paths.add(operation.path.trim());
            }
        }
        return paths;
    }

    private static List<String> reasons(List<FailedResult> failed) {
        List<String> reasons = new ArrayList<>();
        if (failed == null) {
            return reasons;
        }
        for (FailedResult result : failed) {
            if (result != null && !result.reason.isEmpty()) {
                reasons.add(result.reason);
            }
        }
        return reasons;
    }

    private static void sortByTaskOrder(List<HermesAgentResult> results) {
        Collections.sort(results, new Comparator<HermesAgentResult>() {
            @Override
            public int compare(HermesAgentResult left, HermesAgentResult right) {
                int leftOrder = left.task == null ? Integer.MAX_VALUE : left.task.sortOrder;
                int rightOrder = right.task == null ? Integer.MAX_VALUE : right.task.sortOrder;
                if (leftOrder != rightOrder) {
                    return leftOrder < rightOrder ? -1 : 1;
                }
                long leftId = left.task == null ? Long.MAX_VALUE : left.task.id;
                long rightId = right.task == null ? Long.MAX_VALUE : right.task.id;
                return Long.compare(leftId, rightId);
            }
        });
    }

    private static String sourceSnapshot(File sourceDir) throws IOException {
        if (sourceDir == null || !sourceDir.exists()) {
            return "";
        }
        File root = sourceDir.getCanonicalFile();
        Map<String, File> files = new TreeMap<>();
        collectSnapshotFiles(root, root, files);
        StringBuilder snapshot = new StringBuilder();
        for (Map.Entry<String, File> entry : files.entrySet()) {
            snapshot.append("\n// ").append(entry.getKey()).append("\n");
            snapshot.append(FileUtils.readText(entry.getValue())).append('\n');
        }
        return snapshot.toString();
    }

    private static void collectSnapshotFiles(File root, File file, Map<String, File> files) throws IOException {
        if (file == null || !file.exists() || !isWithinRoot(root, file)) {
            return;
        }
        String relative = relativePath(root, file);
        if (isExcluded(relative)) {
            return;
        }
        if (file.isFile()) {
            files.put(relative, file.getCanonicalFile());
            return;
        }
        if (!file.isDirectory()) {
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectSnapshotFiles(root, child.getCanonicalFile(), files);
        }
    }

    private static boolean isExcluded(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return false;
        }
        String[] segments = relativePath.split("/");
        for (String segment : segments) {
            if (".gradle".equals(segment) || "build".equals(segment) || ".DS_Store".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static String relativePath(File root, File file) throws IOException {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (filePath.equals(rootPath)) {
            return "";
        }
        return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }

    private static boolean isWithinRoot(File root, File file) throws IOException {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
    }

    private static String taskLabel(HermesAgentResult result) {
        if (result == null || result.task == null) {
            return "unknown";
        }
        return result.task.id + "/" + result.task.sortOrder;
    }

    public static final class MergePlan {
        public final boolean canMergeAll;
        public final List<String> conflicts;
        public final List<HermesAgentResult> mergeableResults;
        public final List<FailedResult> failedResults;

        private MergePlan(boolean canMergeAll, List<String> conflicts,
                          List<HermesAgentResult> mergeableResults, List<FailedResult> failedResults) {
            this.canMergeAll = canMergeAll;
            this.conflicts = Collections.unmodifiableList(new ArrayList<>(conflicts));
            this.mergeableResults = Collections.unmodifiableList(new ArrayList<>(mergeableResults));
            this.failedResults = Collections.unmodifiableList(new ArrayList<>(failedResults));
        }
    }

    public static final class FailedResult {
        public final HermesAgentResult result;
        public final String reason;

        private FailedResult(HermesAgentResult result, String reason) {
            this.result = result;
            this.reason = reason == null ? "" : reason.trim();
        }
    }

    public static final class MergeResult {
        public final boolean success;
        public final boolean canMergeAll;
        public final List<String> conflicts;
        public final List<HermesAgentResult> mergedResults;
        public final List<FailedResult> failedResults;
        public final String summary;
        public final Exception error;

        private MergeResult(boolean success, boolean canMergeAll, List<String> conflicts,
                            List<HermesAgentResult> mergedResults, List<FailedResult> failedResults,
                            String summary, Exception error) {
            this.success = success;
            this.canMergeAll = canMergeAll;
            this.conflicts = Collections.unmodifiableList(new ArrayList<>(conflicts));
            this.mergedResults = Collections.unmodifiableList(new ArrayList<>(mergedResults));
            this.failedResults = Collections.unmodifiableList(new ArrayList<>(failedResults));
            this.summary = summary == null ? "" : summary;
            this.error = error;
        }

        private static MergeResult completed(List<HermesAgentResult> mergedResults, List<FailedResult> failedResults) {
            List<String> conflicts = reasons(failedResults);
            boolean hasAny = (mergedResults != null && !mergedResults.isEmpty())
                    || (failedResults != null && !failedResults.isEmpty());
            int mergedCount = mergedResults == null ? 0 : mergedResults.size();
            int failedCount = failedResults == null ? 0 : failedResults.size();
            return new MergeResult(hasAny, failedCount == 0, conflicts,
                    mergedResults == null ? Collections.<HermesAgentResult>emptyList() : mergedResults,
                    failedResults == null ? Collections.<FailedResult>emptyList() : failedResults,
                    "Merged " + mergedCount + " Hermes agent result(s), failed " + failedCount + ".",
                    null);
        }
    }
}
