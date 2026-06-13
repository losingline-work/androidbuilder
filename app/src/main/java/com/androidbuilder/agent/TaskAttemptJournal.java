package com.androidbuilder.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory accumulator for one task's execution flow. Every attempt's terminal outcome is kept
 * (not overwritten) so a failed task can be diagnosed from a single summary line + per-attempt
 * records instead of a multi-megabyte log.
 */
final class TaskAttemptJournal {
    static final int MAX_REASON_CHARS = 500;

    private final long startedAtMs = System.currentTimeMillis();
    private final List<String> terminations = new ArrayList<>();
    private int attempts;
    private int manifests;
    private int batchesDone;
    private int batchesTotal;
    private int rewrites;
    private int reviews;

    /** Records one attempt's terminal outcome and returns the structured one-line record. */
    String recordAttempt(int attempt, String phase, String reason) {
        attempts++;
        String line = "attempt=" + attempt
                + " phase=" + safe(phase)
                + " reason=" + truncate(reason);
        terminations.add(line);
        return line;
    }

    void recordManifest() {
        manifests++;
    }

    void recordBatchProgress(int done, int total) {
        batchesDone = Math.max(batchesDone, Math.max(0, done));
        batchesTotal = Math.max(batchesTotal, Math.max(0, total));
    }

    void recordRewrite() {
        rewrites++;
    }

    void recordReview() {
        reviews++;
    }

    String renderSummary(String reason) {
        long wallSeconds = Math.max(0, (System.currentTimeMillis() - startedAtMs) / 1000);
        StringBuilder builder = new StringBuilder();
        builder.append("attempts=").append(attempts)
                .append(", manifests=").append(manifests)
                .append(", batches=").append(batchesDone).append('/').append(batchesTotal)
                .append(", rewrites=").append(rewrites)
                .append(", reviews=").append(reviews)
                .append(", wallSeconds=").append(wallSeconds)
                .append(", reason=").append(safe(reason));
        if (!terminations.isEmpty()) {
            builder.append(", last=").append(terminations.get(terminations.size() - 1));
        }
        return builder.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String truncate(String value) {
        String trimmed = safe(value).replace('\n', ' ');
        if (trimmed.length() <= MAX_REASON_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_REASON_CHARS) + "…";
    }
}
