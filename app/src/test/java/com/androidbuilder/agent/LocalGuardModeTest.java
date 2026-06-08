package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalGuardModeTest {
    @Test
    public void defaultsToPolicyErrorOnlyToKeepTheLocalModelCheap() {
        assertEquals(LocalGuardMode.POLICY_ERROR_ONLY, LocalGuardMode.fromValue(null));
        assertEquals(LocalGuardMode.POLICY_ERROR_ONLY, LocalGuardMode.fromValue(""));
        assertEquals(LocalGuardMode.POLICY_ERROR_ONLY, LocalGuardMode.DEFAULT);
    }

    @Test
    public void modeControlsTriggerPoints() {
        assertFalse(LocalGuardMode.OFF.shouldPreflight());
        assertFalse(LocalGuardMode.OFF.shouldRewritePolicyError());

        assertFalse(LocalGuardMode.POLICY_ERROR_ONLY.shouldPreflight());
        assertTrue(LocalGuardMode.POLICY_ERROR_ONLY.shouldRewritePolicyError());

        assertTrue(LocalGuardMode.PREFLIGHT_AND_POLICY_ERROR.shouldPreflight());
        assertTrue(LocalGuardMode.PREFLIGHT_AND_POLICY_ERROR.shouldRewritePolicyError());
    }

    @Test
    public void buildTriageRunsInEveryNonOffMode() {
        assertFalse(LocalGuardMode.OFF.shouldTriageBuildFailure());
        assertTrue(LocalGuardMode.POLICY_ERROR_ONLY.shouldTriageBuildFailure());
        assertTrue(LocalGuardMode.PREFLIGHT_AND_POLICY_ERROR.shouldTriageBuildFailure());
    }

    @Test
    public void parsesPersistedValuesSafely() {
        assertEquals(LocalGuardMode.OFF, LocalGuardMode.fromValue("off"));
        assertEquals(LocalGuardMode.POLICY_ERROR_ONLY, LocalGuardMode.fromValue("policy_error_only"));
        assertEquals(LocalGuardMode.PREFLIGHT_AND_POLICY_ERROR, LocalGuardMode.fromValue("preflight_and_policy_error"));
        assertEquals(LocalGuardMode.POLICY_ERROR_ONLY, LocalGuardMode.fromValue("unknown"));
    }
}
