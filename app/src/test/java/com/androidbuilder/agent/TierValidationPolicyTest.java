package com.androidbuilder.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.androidbuilder.model.ProjectTaskRecord;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class TierValidationPolicyTest {
    @Test
    public void classifiesCannedPhaseTitlesIntoTiers() {
        assertEquals(TierValidationPolicy.Tier.GRADLE, TierValidationPolicy.tierOf("Gradle skeleton and dependencies"));
        assertEquals(TierValidationPolicy.Tier.RESOURCE, TierValidationPolicy.tierOf("resources: values, themes, and menu"));
        assertEquals(TierValidationPolicy.Tier.RESOURCE, TierValidationPolicy.tierOf("drawable and layout XML"));
        assertEquals(TierValidationPolicy.Tier.CODE, TierValidationPolicy.tierOf("Java source wiring"));
    }

    @Test
    public void resourceCheckpointFiresOnlyWhenResourcesDoneAndCodePending() {
        // Resource tasks still running -> not yet.
        assertFalse(TierValidationPolicy.shouldValidateResources(Arrays.asList(
                task("resources: values, themes, and menu", "done"),
                task("drawable and layout XML", "running"),
                task("Java source wiring", "pending"))));

        // All resources done, code still pending -> validate resources now.
        assertTrue(TierValidationPolicy.shouldValidateResources(Arrays.asList(
                task("resources: values, themes, and menu", "done"),
                task("drawable and layout XML", "done"),
                task("Java source wiring", "pending"))));

        // Code already done -> the resource checkpoint is moot.
        assertFalse(TierValidationPolicy.shouldValidateResources(Arrays.asList(
                task("drawable and layout XML", "done"),
                task("Java source wiring", "done"))));
    }

    @Test
    public void codeCheckpointFiresWhenEveryCodeTaskDone() {
        assertFalse(TierValidationPolicy.shouldValidateCode(Arrays.asList(
                task("drawable and layout XML", "done"),
                task("Java source wiring", "running"))));

        assertTrue(TierValidationPolicy.shouldValidateCode(Arrays.asList(
                task("drawable and layout XML", "done"),
                task("Java source wiring", "done"))));
    }

    @Test
    public void noCheckpointsWithoutTheRelevantTier() {
        List<ProjectTaskRecord> gradleOnly = Arrays.asList(task("Gradle skeleton and dependencies", "done"));
        assertFalse(TierValidationPolicy.shouldValidateResources(gradleOnly));
        assertFalse(TierValidationPolicy.shouldValidateCode(gradleOnly));
    }

    private static ProjectTaskRecord task(String title, String status) {
        return new ProjectTaskRecord(0, 0, 0, title, "instruction", status, "", 0, 0, 0, 0);
    }
}
