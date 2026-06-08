package com.androidbuilder.agent;

final class LocalGuardInstructionComposer {
    private LocalGuardInstructionComposer() {
    }

    static String forPreflightRewrite(String originalInstruction, String localHint) {
        if (isBlank(localHint)) {
            return originalInstruction == null ? "" : originalInstruction;
        }
        return (originalInstruction == null ? "" : originalInstruction)
                + "\n\nLocal guard preflight found a likely source/API mismatch before writing files:\n"
                + localHint.trim()
                + "\nRewrite the task operations using this hint. Keep the response as valid task operations JSON.";
    }

    static String forBuildTriage(String baseInstruction, String localHint) {
        if (isBlank(localHint)) {
            return baseInstruction == null ? "" : baseInstruction;
        }
        return (baseInstruction == null ? "" : baseInstruction)
                + "\n\nLocal build-failure triage (focus the fix on this; the full log is above):\n"
                + localHint.trim()
                + "\nApply the smallest change that resolves this root cause. The deterministic source guard is still the final authority.";
    }

    static String forPolicyRewrite(String policyInstruction, String localHint) {
        if (isBlank(localHint)) {
            return policyInstruction == null ? "" : policyInstruction;
        }
        return (policyInstruction == null ? "" : policyInstruction)
                + "\n\nLocal guard policy-error hint:\n"
                + localHint.trim()
                + "\nUse this hint only to make the next cloud response match the real source APIs. The deterministic local source guard is still the final authority.";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
