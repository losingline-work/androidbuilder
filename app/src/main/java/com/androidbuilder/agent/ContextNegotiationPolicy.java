package com.androidbuilder.agent;

import com.androidbuilder.model.ContextNegotiation;

import java.util.List;

final class ContextNegotiationPolicy {
    static final int MAX_NEGOTIATION_ROUNDS = 1;

    private ContextNegotiationPolicy() {
    }

    static boolean shouldNegotiate(boolean retryLikeFlow, int attempt, String previousFailure, String policyError) {
        // Only scout for context on a repair/retry with a real failure, or after a policy error.
        // Fresh-task retries must not pay an extra cloud round-trip just because attempt > 1.
        return (retryLikeFlow && hasText(previousFailure)) || hasText(policyError);
    }

    static boolean shouldContinueNegotiation(ContextNegotiation result, int completedRounds) {
        return result != null && !result.ready && completedRounds < MAX_NEGOTIATION_ROUNDS;
    }

    static String focusText(ContextNegotiation result, String failureText) {
        StringBuilder builder = new StringBuilder();
        if (hasText(failureText)) {
            builder.append(failureText.trim()).append('\n');
        }
        if (result != null) {
            for (String path : result.neededFiles) {
                builder.append(path).append('\n');
            }
            for (String term : result.focusTerms) {
                builder.append(term).append('\n');
            }
        }
        return builder.toString().trim();
    }

    static String retryContext(String previousFailure, ContextNegotiation result) {
        return retryContext(previousFailure, result, null);
    }

    static String retryContext(String previousFailure, ContextNegotiation result, List<String> missingNeededFiles) {
        StringBuilder builder = new StringBuilder();
        builder.append("This is a retry or repair of an existing source tree.\n");
        builder.append("Do not recreate the project.\n");
        builder.append("Modify only the files needed for the current task.\n");
        builder.append("Use the shown source as authoritative.\n");
        builder.append("If a file is listed as omitted or digest-only, do not invent its API beyond what the digest shows.\n");
        builder.append("If a file appears in NO part of the snapshot inventory, it does not exist yet; creating it is part of the task when the task requires it - never return blocked because a nonexistent file is \"missing\".\n");
        if (hasText(previousFailure)) {
            builder.append("\nPrevious failure summary (the snapshot has changed since this failure; re-verify every visibility claim against the current snapshot and resource index before acting on it):\n")
                    .append(previousFailure.trim()).append('\n');
        }
        if (result != null) {
            if (missingNeededFiles != null && !missingNeededFiles.isEmpty()) {
                builder.append("\nFile existence verdict for the files you requested:\n");
                for (String path : missingNeededFiles) {
                    builder.append("- ").append(path).append(": does NOT exist in the project. It cannot be shown; create it yourself if the task requires it.\n");
                }
            }
            if (hasText(result.patchIntent)) {
                builder.append("\nNegotiated patch intent (advisory; it cannot forbid creating files that do not exist or work the task instruction requires):\n")
                        .append(result.patchIntent.trim()).append('\n');
            }
            if (!result.riskNotes.isEmpty()) {
                builder.append("\nNegotiated risk notes:\n");
                for (String note : result.riskNotes) {
                    builder.append("- ").append(note).append('\n');
                }
            }
        }
        return builder.toString().trim();
    }

    static String summary(ContextNegotiation result) {
        if (result == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("ready=").append(result.ready);
        if (!result.neededFiles.isEmpty()) {
            builder.append("\nneededFiles=").append(result.neededFiles);
        }
        if (!result.focusTerms.isEmpty()) {
            builder.append("\nfocusTerms=").append(result.focusTerms);
        }
        if (!result.riskNotes.isEmpty()) {
            builder.append("\nriskNotes=").append(result.riskNotes);
        }
        if (hasText(result.patchIntent)) {
            builder.append("\npatchIntent=").append(result.patchIntent.trim());
        }
        return builder.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
