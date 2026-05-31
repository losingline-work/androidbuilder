package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuildRepairPolicyTest {
    @Test
    public void failedRepairableJobCanAutoRepairBeforeLimit() {
        BuildJobRecord job = job("failed", 2);

        assertTrue(BuildRepairPolicy.canAutoRepair(job, false, true));
    }

    @Test
    public void failedRepairableJobStopsAtLimit() {
        BuildJobRecord job = job("failed", 3);

        assertFalse(BuildRepairPolicy.canAutoRepair(job, false, true));
        assertTrue(BuildRepairPolicy.reachedAutoRepairLimit(job));
    }

    @Test
    public void manualBuildStartsNewRepairRound() {
        BuildJobRecord job = job("failed", 3);

        assertEquals(0, BuildRepairPolicy.retryCountForManualBuild(job));
    }

    @Test
    public void nextRetryCountIncrementsFailedAttempt() {
        BuildJobRecord job = job("failed", 2);

        assertEquals(3, BuildRepairPolicy.nextRetryCount(job));
    }

    private BuildJobRecord job(String status, int retryCount) {
        return new BuildJobRecord(1, 1, status, "embedded_runtime_finished", "build.log", null, "failed", retryCount, 0, 0);
    }
}
