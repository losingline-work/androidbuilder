package com.androidbuilder.agent;

import com.androidbuilder.model.HermesTaskContract;

final class HermesTaskScheduler {
    private HermesTaskScheduler() {
    }

    static HermesTaskDecision decide(HermesTaskContract contract, String previousFailure, int failureCount, boolean repairFlow) {
        boolean requiresContextScout = repairFlow || isHighRisk(contract);
        boolean requiresBuildAfter = contract != null && contract.buildRequiredAfter;
        String retryMode = failureCount >= 2 ? "narrow_scope" : "normal";
        String reason = reason(contract, previousFailure, failureCount, repairFlow);
        return new HermesTaskDecision(
                HermesTaskDecision.Action.CODE,
                reason,
                requiresContextScout,
                requiresBuildAfter,
                retryMode);
    }

    private static boolean isHighRisk(HermesTaskContract contract) {
        if (contract == null) {
            return false;
        }
        return "high".equalsIgnoreCase(contract.riskLevel)
                || !contract.riskNotes.isEmpty()
                || !contract.forbiddenPaths.isEmpty();
    }

    private static String reason(HermesTaskContract contract, String previousFailure, int failureCount, boolean repairFlow) {
        StringBuilder builder = new StringBuilder();
        if (repairFlow) {
            builder.append("Repair flow requires focused source context.");
        } else if (isHighRisk(contract)) {
            builder.append("High-risk task contract requires context scout before coding.");
        } else {
            builder.append("Task contract can proceed directly to coding.");
        }
        if (contract != null && contract.buildRequiredAfter) {
            builder.append(" Build verification is required after this task.");
        }
        if (failureCount >= 2) {
            builder.append(" Repeated task failure detected; use narrow_scope retry mode.");
        } else if (previousFailure != null && !previousFailure.trim().isEmpty()) {
            builder.append(" Previous task failure is present.");
        }
        return builder.toString().trim();
    }
}
