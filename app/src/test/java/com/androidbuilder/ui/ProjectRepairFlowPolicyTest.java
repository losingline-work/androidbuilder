package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ProjectRepairFlowPolicyTest {
    @Test
    public void usesPreviousFailedBuildAsRepairTargetAfterRepairAttemptFails() {
        BuildJobRecord previousFailedBuild = new BuildJobRecord(1, 1, "failed", "embedded_runtime_finished", "/tmp/failed.log", null, "javac failed", 0, 0, 0);
        BuildJobRecord repairFailure = new BuildJobRecord(2, 1, "failed", "repair_failed", null, null, "model failed", 0, 0, 0);

        assertEquals(previousFailedBuild, ProjectRepairFlowPolicy.repairTargetJob(repairFailure, previousFailedBuild));
    }

    @Test
    public void doesNotOfferRepairAfterSuccessfulRepairUntilAnotherBuildFails() {
        BuildJobRecord repairSuccess = new BuildJobRecord(2, 1, "generated", "ready_for_build", "/tmp/repair.log", null, null, 0, 0, 0);
        BuildJobRecord previousFailedBuild = new BuildJobRecord(1, 1, "failed", "embedded_runtime_finished", "/tmp/failed.log", null, "javac failed", 0, 0, 0);

        assertNull(ProjectRepairFlowPolicy.repairTargetJob(repairSuccess, previousFailedBuild));
    }
}
