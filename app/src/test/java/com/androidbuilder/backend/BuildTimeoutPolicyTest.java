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
}
