package com.androidbuilder.agent;

import com.androidbuilder.backend.BuildBackendSettings;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlanConstraintComposerTest {
    @Test
    public void offlineSafeConstraintsForceSdkOnlyPlanAndUserChoices() {
        String prompt = PlanConstraintComposer.withPlanningConstraints(
                "Build a personal finance app with charts.",
                BuildBackendSettings.DEPENDENCY_OFFLINE_SAFE,
                false,
                true,
                false);

        assertTrue(prompt.contains("Build a personal finance app"));
        assertTrue(prompt.contains("Current dependency mode: Offline safe"));
        assertTrue(prompt.contains("Android SDK + Java + XML + SQLiteOpenHelper"));
        assertTrue(prompt.contains("Do not plan Room"));
        assertTrue(prompt.contains("Pending User Choices"));
    }

    @Test
    public void localCacheConstraintsWarnWhenCacheIsMissing() {
        String constraints = PlanConstraintComposer.planningConstraints(
                BuildBackendSettings.DEPENDENCY_LOCAL_CACHE,
                false,
                true,
                false);

        assertTrue(constraints.contains("Current dependency mode: Local cache"));
        assertTrue(constraints.contains("No local Maven cache is available"));
        assertTrue(constraints.contains("fall back to Android SDK"));
    }

    @Test
    public void onlineConstraintsStillRequirePinnedApprovedDependencies() {
        String constraints = PlanConstraintComposer.planningConstraints(
                BuildBackendSettings.DEPENDENCY_ONLINE,
                true,
                true,
                false);

        assertTrue(constraints.contains("Current dependency mode: Online enhanced"));
        assertTrue(constraints.contains("approved dependencies"));
        assertTrue(constraints.contains("pinned versions"));
        assertTrue(constraints.contains("Pending User Choices"));
    }

    @Test
    public void canDisableChoiceConfirmation() {
        String constraints = PlanConstraintComposer.planningConstraints(
                BuildBackendSettings.DEPENDENCY_ONLINE,
                true,
                false,
                false);

        assertFalse(constraints.contains("Pending User Choices"));
    }
}
