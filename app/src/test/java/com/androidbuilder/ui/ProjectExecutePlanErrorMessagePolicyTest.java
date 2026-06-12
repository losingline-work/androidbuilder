package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectExecutePlanErrorMessagePolicyTest {
    @Test
    public void suppressesStandaloneMessageWhenFailedCodingJobAlreadyCarriesSameError() {
        String error = "Merged 0 Hermes agent result(s), failed 1.\n"
                + "Task 637/2 agent failed: Generated source policy blocked missing XML id: R.id.toolbar in BaseActivity.java.";
        BuildJobRecord job = new BuildJobRecord(7, 1, "failed", "coding_failed", "/tmp/build.log", null, error, 0, 0, 0);

        assertFalse(ProjectExecutePlanErrorMessagePolicy.shouldAddStandaloneMessage(job, error));
    }

    @Test
    public void keepsStandaloneMessageWhenFailureWasNotRecordedOnBuildJob() {
        assertTrue(ProjectExecutePlanErrorMessagePolicy.shouldAddStandaloneMessage(null, "API key missing"));

        BuildJobRecord olderFailure = new BuildJobRecord(7, 1, "failed", "coding_failed", "/tmp/build.log", null, "different", 0, 0, 0);
        assertTrue(ProjectExecutePlanErrorMessagePolicy.shouldAddStandaloneMessage(olderFailure, "new failure"));
    }
}
