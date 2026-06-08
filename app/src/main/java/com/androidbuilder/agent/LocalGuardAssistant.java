package com.androidbuilder.agent;

import com.androidbuilder.model.TaskOperations;

public interface LocalGuardAssistant {
    LocalGuardResult reviewOperations(
            String plan,
            String taskTitle,
            String taskInstruction,
            String sourceSnapshot,
            TaskOperations operations);

    LocalGuardResult rewritePolicyFailure(
            String taskInstruction,
            String policyError,
            String focusedSnapshot,
            int attempt);

    /**
     * Turns a raw build-failure log into a precise, focused repair instruction for the cloud model.
     * Returns an unusable result to fall back to the deterministic repair instruction.
     */
    LocalGuardResult triageBuildFailure(String buildLog, String focusedSnapshot);

    /** Releases any cached native model/engine. Safe to call multiple times. */
    default void close() {
    }
}
