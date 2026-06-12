package com.androidbuilder.agent;

import com.androidbuilder.model.TaskOperations;

final class BlockedTaskPolicy {
    static final String MODE_SCOPE_EXPANDED = "scope-expanded";

    private static final String SCOPE_EXPANDED_TEMPLATE =
            "The original task boundary is lifted by the orchestrator for this retry. "
                    + "First create the missing prerequisites described below, then complete the original task in the same response. "
                    + "Keep every new resource id consistent with the Java that references it.";

    private BlockedTaskPolicy() {
    }

    static boolean shouldExpandScope(TaskOperations operations, boolean alreadyExpanded) {
        return operations != null && operations.blocked && !alreadyExpanded;
    }

    static String scopeExpandedInstruction(String originalInstruction, TaskOperations blocked) {
        String prerequisite = prerequisiteText(blocked);
        String original = originalInstruction == null ? "" : originalInstruction.trim();
        StringBuilder instruction = new StringBuilder();
        instruction.append(SCOPE_EXPANDED_TEMPLATE)
                .append("\nPrerequisites: ")
                .append(prerequisite);
        if (!original.isEmpty()) {
            instruction.append("\n\nOriginal task instruction:\n")
                    .append(original);
        }
        return instruction.toString();
    }

    static String blockedSummary(TaskOperations blocked) {
        String reason = blocked == null ? "" : blocked.blockedReason.trim();
        if (reason.isEmpty() && blocked != null) {
            reason = blocked.summary.trim();
        }
        if (reason.isEmpty()) {
            reason = "Task prerequisites are missing.";
        }
        return "blocked: " + reason;
    }

    static String snapshotFocus(TaskOperations blocked) {
        String prerequisite = prerequisiteText(blocked);
        String reason = blocked == null ? "" : blocked.blockedReason.trim();
        if (reason.isEmpty()) {
            return prerequisite;
        }
        if (prerequisite.isEmpty() || prerequisite.equals(reason)) {
            return reason;
        }
        return reason + "\n" + prerequisite;
    }

    private static String prerequisiteText(TaskOperations blocked) {
        if (blocked == null) {
            return "";
        }
        String prerequisite = blocked.prerequisiteWork.trim();
        if (!prerequisite.isEmpty()) {
            return prerequisite;
        }
        return blocked.blockedReason.trim();
    }
}
