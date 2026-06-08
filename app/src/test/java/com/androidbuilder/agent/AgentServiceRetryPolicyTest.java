package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class AgentServiceRetryPolicyTest {
    @Test
    public void operationGenerationHasEnoughAttemptsForPreflightAndPolicyRetries() {
        assertTrue(AgentService.policyRewriteAttemptsForTest() >= 5);
    }
}
