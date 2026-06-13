package com.androidbuilder.agent;

import com.androidbuilder.model.HermesReview;
import com.androidbuilder.model.ContextNegotiation;
import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

final class HermesReviewerPolicy {
    private HermesReviewerPolicy() {
    }

    static boolean shouldRetry(HermesReview review, int attempt, int maxAttempts) {
        return review != null
                && review.decision == HermesReview.Decision.REWRITE
                && hasText(review.rewriteInstruction)
                && attempt < maxAttempts;
    }

    static boolean shouldFallback(HermesReview review) {
        return review == null || review.decision == HermesReview.Decision.FALLBACK;
    }

    static boolean shouldReviewOperations(boolean retryOrRepairFlow, int attempt, ContextNegotiation negotiation, TaskOperations operations, int cloudReviewsUsed) {
        return shouldReviewOperations(retryOrRepairFlow, attempt, negotiation, operations, cloudReviewsUsed, Integer.MAX_VALUE, true);
    }

    static boolean shouldReviewOperations(boolean retryOrRepairFlow,
                                          int attempt,
                                          ContextNegotiation negotiation,
                                          TaskOperations operations,
                                          int cloudReviewsUsed,
                                          int maxAttempts,
                                          boolean rewriteBudgetAvailable) {
        if (cloudReviewsUsed > 0) {
            return false;
        }
        if (!rewriteBudgetAvailable || attempt >= maxAttempts) {
            return false;
        }
        if (retryOrRepairFlow || attempt > 1 || hasScoutSignal(negotiation)) {
            return true;
        }
        if (operations == null || operations.operations == null || operations.operations.isEmpty()) {
            return false;
        }
        boolean touchesJava = false;
        boolean touchesXml = false;
        if (operations.operations.size() > 2) {
            return true;
        }
        for (FileOperation operation : operations.operations) {
            if (operation == null) {
                continue;
            }
            String action = operation.action == null ? "" : operation.action;
            String path = operation.path == null ? "" : operation.path;
            if ("delete".equals(action) || isCriticalProjectFile(path)) {
                return true;
            }
            touchesJava = touchesJava || path.endsWith(".java");
            touchesXml = touchesXml || path.endsWith(".xml");
        }
        return touchesJava && touchesXml;
    }

    static boolean isStaleOrDuplicate(HermesReview review, boolean deterministicOkThisAttempt) {
        if (!deterministicOkThisAttempt
                || review == null
                || review.decision != HermesReview.Decision.REWRITE) {
            return false;
        }
        String text = ((review.summary == null ? "" : review.summary)
                + "\n"
                + (review.rewriteInstruction == null ? "" : review.rewriteInstruction)).toLowerCase();
        return text.contains("missing r import")
                || text.contains("uses r.* but is missing r import")
                || (text.contains("import ") && text.contains(".r;"));
    }

    static boolean isValidCloudDecision(HermesReview review) {
        return review != null && review.decision != HermesReview.Decision.FALLBACK;
    }

    static String contextScoutNotes(ContextNegotiation negotiation) {
        String notes = ContextNegotiationPolicy.summary(negotiation);
        return hasText(notes) ? notes : "(none)";
    }

    static String rewriteContext(HermesReview review) {
        StringBuilder builder = new StringBuilder();
        builder.append("HermesReviewer requested rewrite before applying generated operations.\n");
        if (review != null && hasText(review.summary)) {
            builder.append("\nReviewer summary:\n").append(review.summary.trim()).append('\n');
        }
        if (review != null && hasText(review.rewriteInstruction)) {
            builder.append("\nRewrite instruction:\n").append(review.rewriteInstruction.trim()).append('\n');
        }
        return builder.toString().trim();
    }

    private static boolean hasScoutSignal(ContextNegotiation negotiation) {
        return negotiation != null
                && (!negotiation.ready
                || !negotiation.neededFiles.isEmpty()
                || !negotiation.focusTerms.isEmpty()
                || !negotiation.riskNotes.isEmpty()
                || hasText(negotiation.patchIntent));
    }

    private static boolean isCriticalProjectFile(String path) {
        return "settings.gradle".equals(path)
                || "build.gradle".equals(path)
                || "app/build.gradle".equals(path)
                || path.endsWith("/AndroidManifest.xml")
                || "app/src/main/AndroidManifest.xml".equals(path);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
