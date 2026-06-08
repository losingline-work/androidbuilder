package com.androidbuilder.agent;

import com.androidbuilder.model.TaskOperations;

final class NoOpLocalGuardAssistant implements LocalGuardAssistant {
    @Override
    public LocalGuardResult reviewOperations(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, TaskOperations operations) {
        return LocalGuardResult.unusable("");
    }

    @Override
    public LocalGuardResult rewritePolicyFailure(String taskInstruction, String policyError, String focusedSnapshot, int attempt) {
        return LocalGuardResult.unusable("");
    }

    @Override
    public LocalGuardResult triageBuildFailure(String buildLog, String focusedSnapshot) {
        return LocalGuardResult.unusable("");
    }
}
