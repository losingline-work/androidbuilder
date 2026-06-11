package com.androidbuilder.agent;

import com.androidbuilder.model.GuardFinding;
import com.androidbuilder.model.HermesReview;

import java.util.Collections;
import java.util.List;

final class GuardFindingPolicy {
    private GuardFindingPolicy() {
    }

    static List<GuardFinding> fromHermesReview(String source, HermesReview review) {
        if (review == null || review.decision != HermesReview.Decision.REWRITE) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new GuardFinding(
                source,
                "HERMES_REWRITE",
                GuardFinding.Severity.ERROR,
                "",
                "",
                review.summary,
                review.rewriteInstruction));
    }

    static List<GuardFinding> fromLocalGuardResult(String source, LocalGuardResult result) {
        if (result == null || !result.usable || result.decision != LocalGuardResult.Decision.REWRITE) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new GuardFinding(
                source,
                "LOCAL_GUARD_REWRITE",
                GuardFinding.Severity.ERROR,
                "",
                "",
                result.summary,
                result.additionalInstruction));
    }

    static String retryContext(List<GuardFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (GuardFinding finding : findings) {
            if (finding == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append("Guard finding [").append(finding.code).append("]");
            builder.append(" severity=").append(finding.severity.name().toLowerCase(java.util.Locale.ROOT));
            if (!finding.source.isEmpty()) {
                builder.append(" source=").append(finding.source);
            }
            if (!finding.path.isEmpty()) {
                builder.append("\nPath: ").append(finding.path);
            }
            if (!finding.symbol.isEmpty()) {
                builder.append("\nSymbol: ").append(finding.symbol);
            }
            if (!finding.message.isEmpty()) {
                builder.append("\nMessage: ").append(finding.message);
            }
            if (!finding.suggestion.isEmpty()) {
                builder.append("\nSuggested retry: ").append(finding.suggestion);
            }
        }
        return builder.toString().trim();
    }
}
