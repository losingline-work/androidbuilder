package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HermesParallelExecutionPolicyTest {
    @Test
    public void rateLimitErrorRecommendsSerialRetry() {
        String message = HermesParallelExecutionPolicy.userMessageForBatchFailure(
                "HTTP 429 rate limit exceeded", false);

        assertTrue(message.contains("serial"));
    }

    @Test
    public void chineseRateLimitErrorRecommendsSerialRetry() {
        String message = HermesParallelExecutionPolicy.userMessageForBatchFailure(
                "too many requests", true);

        assertTrue(message.contains("串行"));
    }

    @Test
    public void ordinaryErrorsDoNotDowngrade() {
        assertFalse(HermesParallelExecutionPolicy.shouldDowngradeToSerial("javac failed"));
    }
}
