package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectOperationStatusTest {
    @Test
    public void hiddenWhenOperationFinished() {
        assertFalse(ProjectOperationStatus.shouldShow("Executing next step...", false, false, null));
    }

    @Test
    public void visibleWhileUiIsBusy() {
        assertTrue(ProjectOperationStatus.shouldShow("Executing next step...", true, false, null));
    }

    @Test
    public void visibleWhileBuildIsRunning() {
        BuildJobRecord job = new BuildJobRecord(1, 1, "building", "embedded_runtime", null, null, null, 0, 0, 0);

        assertTrue(ProjectOperationStatus.shouldShow("Embedded build started.", false, false, job));
    }

    @Test
    public void hiddenWhenMessageIsEmpty() {
        assertFalse(ProjectOperationStatus.shouldShow(" ", true, true, null));
    }
}
