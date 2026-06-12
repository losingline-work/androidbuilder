package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectBuildActionPolicyTest {
    @Test
    public void buildIsAvailableWhenSourceExistsWithoutBuildJob() {
        assertTrue(ProjectBuildActionPolicy.canBuild(false, true));
    }

    @Test
    public void buildIsBlockedOnlyWhenBusyOrSourceIsMissing() {
        assertFalse(ProjectBuildActionPolicy.canBuild(true, true));
        assertFalse(ProjectBuildActionPolicy.canBuild(false, false));
    }

    @Test
    public void buildIsBlockedAfterPlanTaskExecutionFailure() {
        BuildJobRecord taskFailure = new BuildJobRecord(1, 1, "failed", "coding_failed", "/tmp/build.log", null, "Task failed", 0, 0, 0);

        assertFalse(ProjectBuildActionPolicy.canBuild(false, true, taskFailure));
    }

    @Test
    public void repairIsAvailableOnlyForRepairableFailedBuilds() {
        BuildJobRecord failed = new BuildJobRecord(1, 1, "failed", "embedded_runtime_finished", "/tmp/build.log", null, "javac failed", 0, 0, 0);
        BuildJobRecord success = new BuildJobRecord(2, 1, "success", "embedded_runtime_finished", "/tmp/build.log", "/tmp/app.apk", null, 0, 0, 0);

        assertTrue(ProjectBuildActionPolicy.canRepair(false, failed, true));
        assertFalse(ProjectBuildActionPolicy.canRepair(false, failed, false));
        assertFalse(ProjectBuildActionPolicy.canRepair(false, success, true));
        assertFalse(ProjectBuildActionPolicy.canRepair(true, failed, true));
    }

    @Test
    public void primaryActionSwitchesToRepairForRepairableFailedBuild() {
        BuildJobRecord failed = new BuildJobRecord(1, 1, "failed", "embedded_runtime_finished", "/tmp/build.log", null, "javac failed", 0, 0, 0);

        assertEquals(ProjectBuildActionPolicy.PrimaryAction.REPAIR, ProjectBuildActionPolicy.primaryAction(failed, true));
    }

    @Test
    public void primaryActionSwitchesBackToBuildAfterRepairGeneratesSource() {
        BuildJobRecord repairGenerated = new BuildJobRecord(2, 1, "generated", "ready_for_build", "/tmp/repair.log", null, null, 0, 0, 0);

        assertEquals(ProjectBuildActionPolicy.PrimaryAction.BUILD, ProjectBuildActionPolicy.primaryAction(repairGenerated, true));
    }
}
