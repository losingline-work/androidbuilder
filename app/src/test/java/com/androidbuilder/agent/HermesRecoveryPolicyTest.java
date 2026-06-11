package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HermesRecoveryPolicyTest {
    @Test
    public void runningCodingJobCanRecoverToPlannedWithFailedTask() {
        HermesRecoveryPolicy.Decision decision = HermesRecoveryPolicy.decide("coding", "generating", true);

        assertEquals(HermesRecoveryPolicy.Action.MARK_TASK_FAILED_AND_ALLOW_RESUME, decision.action);
    }

    @Test
    public void buildingJobRequiresUserRetryInsteadOfSilentResume() {
        HermesRecoveryPolicy.Decision decision = HermesRecoveryPolicy.decide("generated", "building", false);

        assertEquals(HermesRecoveryPolicy.Action.SHOW_REBUILD_PROMPT, decision.action);
    }

    @Test
    public void repairingJobShowsRepairPrompt() {
        HermesRecoveryPolicy.Decision decision = HermesRecoveryPolicy.decide("generated", "generating", false);

        assertEquals(HermesRecoveryPolicy.Action.SHOW_REPAIR_PROMPT, decision.action);
    }
}
