package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides when a preflight failure should be repaired file-by-file instead of by re-rolling the whole batch,
 * and extracts the corrected file from the micro-fix reply.
 *
 * <p>The old behavior turned ONE malformed XML file into a whole-attempt rewrite — 9 good files re-rolled to
 * fix 1 bad one, and the re-roll can introduce fresh defects, so a weak model never converges. Micro-fix is
 * eligible only for a SMALL number of purely-structural defects (malformed XML / missing R import); anything
 * broader (contract/guard violations, the &gt;120-op cap) still takes the whole-batch path.
 */
final class PreflightMicroFixPolicy {
    static final int MAX_FILES = 3;
    static final int MAX_ATTEMPTS_PER_FILE = 2;

    private PreflightMicroFixPolicy() {
    }

    /** Eligible when there is at least one structural finding and they span at most {@link #MAX_FILES} files. */
    static boolean eligible(List<TaskOperationsPreflight.Finding> findings) {
        if (findings == null || findings.isEmpty()) {
            return false;
        }
        Set<String> paths = new LinkedHashSet<>();
        for (TaskOperationsPreflight.Finding finding : findings) {
            if (finding != null && finding.op != null && finding.op.path != null) {
                paths.add(finding.op.path);
            }
        }
        return !paths.isEmpty() && paths.size() <= MAX_FILES;
    }

    /** The distinct file paths that need a micro-fix, in first-seen order. */
    static List<String> paths(List<TaskOperationsPreflight.Finding> findings) {
        Set<String> paths = new LinkedHashSet<>();
        if (findings != null) {
            for (TaskOperationsPreflight.Finding finding : findings) {
                if (finding != null && finding.op != null && finding.op.path != null) {
                    paths.add(finding.op.path);
                }
            }
        }
        return new java.util.ArrayList<>(paths);
    }

    /**
     * Pull the corrected write operation for {@code path} out of a micro-fix reply (fenced or JSON, via the
     * codec). Falls back to the sole write operation when the model returned exactly one file under a slightly
     * different path. Returns null when nothing usable was produced.
     */
    static FileOperation extractFile(String modelText, String path) {
        TaskOperations parsed;
        try {
            parsed = TaskOperationsCodec.parse(modelText).operations;
        } catch (RuntimeException error) {
            return null;
        }
        if (parsed == null || parsed.operations == null || parsed.operations.isEmpty()) {
            return null;
        }
        FileOperation onlyWrite = null;
        int writeCount = 0;
        for (FileOperation op : parsed.operations) {
            if (op == null || !"write".equals(op.action)) {
                continue;
            }
            writeCount++;
            onlyWrite = op;
            if (op.path != null && op.path.equals(path)) {
                return op;
            }
        }
        return writeCount == 1 ? onlyWrite : null;
    }
}
