package com.androidbuilder.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Outcome of a lenient {@link FileOperationsWriter#applyLenient} pass on the repair path: which operations
 * committed and which were skipped (e.g. a stale edit anchor). Lets a repair round make NET PROGRESS by
 * committing the ops that matched instead of discarding the whole batch when one edit cannot be anchored.
 */
final class FileOperationsApplyReport {
    /** A single operation that could not be applied, with the reason (the anchor-failure message). */
    static final class FailedOperation {
        final String action;
        final String path;
        final String reason;

        FailedOperation(String action, String path, String reason) {
            this.action = action == null ? "" : action;
            this.path = path == null ? "" : path;
            this.reason = reason == null ? "" : reason;
        }
    }

    private final List<String> appliedPaths = new ArrayList<>();
    private final List<FailedOperation> failedOps = new ArrayList<>();

    void recordApplied(String path) {
        appliedPaths.add(path == null ? "" : path);
    }

    void recordFailed(String action, String path, String reason) {
        failedOps.add(new FailedOperation(action, path, reason));
    }

    List<String> appliedPaths() {
        return Collections.unmodifiableList(appliedPaths);
    }

    List<FailedOperation> failedOps() {
        return Collections.unmodifiableList(failedOps);
    }

    boolean anyApplied() {
        return !appliedPaths.isEmpty();
    }

    boolean anyFailed() {
        return !failedOps.isEmpty();
    }

    /** The paths that were skipped, for a concise "deferred" narration line. */
    List<String> failedPaths() {
        List<String> paths = new ArrayList<>();
        for (FailedOperation op : failedOps) {
            paths.add(op.path);
        }
        return paths;
    }
}
