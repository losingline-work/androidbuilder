package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ProjectBuildLogTitlePolicyTest {
    @Test
    public void titlesSuccessfulBuildCardAsSuccess() {
        BuildJobRecord job = new BuildJobRecord(1, 1, "success", "finished", "/tmp/build.log", "/tmp/app.apk", null, 0, 0, 0);

        assertEquals(ProjectBuildLogTitlePolicy.Title.BUILD_SUCCESS, ProjectBuildLogTitlePolicy.titleFor(job));
    }

    @Test
    public void titlesRepairCardAsRepairRecord() {
        BuildJobRecord job = new BuildJobRecord(1, 1, "generated", "repairing_build_failure", "/tmp/build.log", null, null, 0, 0, 0);

        assertEquals(ProjectBuildLogTitlePolicy.Title.REPAIR_RECORD, ProjectBuildLogTitlePolicy.titleFor(job));
    }

    @Test
    public void titlesPlanTaskExecutionFailureSeparatelyFromBuildFailure() {
        BuildJobRecord job = new BuildJobRecord(1, 1, "failed", "coding_failed", "/tmp/build.log", null, "Task failed", 0, 0, 0);

        assertEquals(ProjectBuildLogTitlePolicy.Title.TASK_EXECUTION_FAILED, ProjectBuildLogTitlePolicy.titleFor(job));
    }
}
