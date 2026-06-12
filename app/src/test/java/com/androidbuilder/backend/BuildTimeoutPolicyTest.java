package com.androidbuilder.backend;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuildTimeoutPolicyTest {
    @Test
    public void idleTimeoutTriggersAfterConfiguredSilence() {
        long now = 1_000_000L;

        assertTrue(BuildTimeoutPolicy.exceededIdleTimeout(now, now - BuildTimeoutPolicy.IDLE_TIMEOUT_MS - 1));
        assertFalse(BuildTimeoutPolicy.exceededIdleTimeout(now, now - BuildTimeoutPolicy.IDLE_TIMEOUT_MS + 1));
    }

    @Test
    public void totalTimeoutTriggersAfterConfiguredRuntime() {
        long now = 2_000_000L;

        assertTrue(BuildTimeoutPolicy.exceededTotalTimeout(now, now - BuildTimeoutPolicy.TOTAL_TIMEOUT_MS - 1));
        assertFalse(BuildTimeoutPolicy.exceededTotalTimeout(now, now - BuildTimeoutPolicy.TOTAL_TIMEOUT_MS + 1));
    }

    @Test
    public void timeoutMessageUsesChineseWhenRequested() {
        String message = BuildTimeoutPolicy.timeoutMessage(11 * 60_000L, 0, 9 * 60_000L, true);

        assertTrue(message.contains("构建运行 11 分钟后超时"));
        assertTrue(message.contains("距离上次构建输出已经 2 分钟"));
    }
}
