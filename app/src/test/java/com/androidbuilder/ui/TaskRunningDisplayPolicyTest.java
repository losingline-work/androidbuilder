package com.androidbuilder.ui;

import com.androidbuilder.model.ProjectTaskRecord;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TaskRunningDisplayPolicyTest {
    @Test
    public void predictsPendingTaskOnlyWhenNoTaskIsActuallyRunning() {
        ProjectTaskRecord pending = task(1, 0, "pending");

        assertTrue(TaskRunningDisplayPolicy.shouldShowPredictedRunning(
                true, 0, "pending", Collections.singletonList(pending)));
    }

    @Test
    public void doesNotPredictSecondRunningTaskWhenRepositoryHasRunningTask() {
        ProjectTaskRecord running = task(1, 0, "running");
        ProjectTaskRecord pending = task(2, 1, "pending");

        assertFalse(TaskRunningDisplayPolicy.shouldShowPredictedRunning(
                true, 1, "pending", Arrays.asList(running, pending)));
    }

    @Test
    public void doesNotPredictWhenUiIsIdle() {
        ProjectTaskRecord pending = task(1, 0, "pending");

        assertFalse(TaskRunningDisplayPolicy.shouldShowPredictedRunning(
                false, 0, "pending", Collections.singletonList(pending)));
    }

    private ProjectTaskRecord task(long id, int order, String status) {
        return new ProjectTaskRecord(id, 1, order, "Task", "", status, "", 0, 0, 0, 0);
    }
}
