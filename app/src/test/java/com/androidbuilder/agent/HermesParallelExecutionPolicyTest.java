package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HermesParallelExecutionPolicyTest {
    @Test
    public void rateLimitErrorRecommendsSerialRetryWhenParallel() {
        String message = HermesParallelExecutionPolicy.userMessageForBatchFailure(
                "HTTP 429 rate limit exceeded", false, 2);

        assertTrue(message.contains("serial"));
    }

    @Test
    public void chineseRateLimitErrorRecommendsSerialRetryWhenParallel() {
        String message = HermesParallelExecutionPolicy.userMessageForBatchFailure(
                "too many requests", true, 3);

        assertTrue(message.contains("调为串行"));
    }

    @Test
    public void rateLimitInSerialModeDoesNotSuggestSwitchingToSerial() {
        // Already serial: don't blame parallel sub-agents or tell the user to "switch to serial".
        String zh = HermesParallelExecutionPolicy.userMessageForBatchFailure("HTTP 429", true, 1);
        assertFalse(zh.contains("并行子 Agent"));
        assertFalse(zh.contains("调为串行"));
        assertTrue(zh.contains("已是串行"));

        String en = HermesParallelExecutionPolicy.userMessageForBatchFailure("too many requests", false, 1);
        assertFalse(en.contains("Retry in serial mode"));
        assertTrue(en.contains("already running serially"));
    }

    @Test
    public void ordinaryErrorsDoNotDowngrade() {
        assertFalse(HermesParallelExecutionPolicy.shouldDowngradeToSerial("javac failed"));
    }
}
