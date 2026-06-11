package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectOperationStatusTest {
    @Test
    public void hiddenWhenOperationFinished() {
        assertFalse(ProjectOperationStatus.shouldShow("Executing next step...", false, false, null, false));
    }

    @Test
    public void visibleWhileUiIsBusy() {
        assertTrue(ProjectOperationStatus.shouldShow("Executing next step...", true, false, null, false));
    }

    @Test
    public void visibleWhileBuildIsRunning() {
        BuildJobRecord job = new BuildJobRecord(1, 1, "building", "embedded_runtime", null, null, null, 0, 0, 0);

        assertTrue(ProjectOperationStatus.shouldShow("Embedded build started.", false, false, job, false));
    }

    @Test
    public void hiddenWhenTaskPanelIsLive() {
        BuildJobRecord job = new BuildJobRecord(1, 1, "generating", "cloud_spec", null, null, null, 0, 0, 0);

        assertFalse(ProjectOperationStatus.shouldShow("Executing next step...", true, true, job, true));
    }

    @Test
    public void visibleWhileBuildIsRunningWithoutTaskPanel() {
        BuildJobRecord job = new BuildJobRecord(1, 1, "building", "embedded_runtime", null, null, null, 0, 0, 0);

        assertTrue(ProjectOperationStatus.shouldShow("Embedded build started.", false, false, job, false));
    }

    @Test
    public void hiddenWhenMessageIsEmpty() {
        assertFalse(ProjectOperationStatus.shouldShow(" ", true, true, null, true));
    }

    @Test
    public void displayTextIncludesElapsedTimeWhenProvided() {
        assertEquals("Repairing failed build... · 3s elapsed",
                ProjectOperationStatus.displayText("Repairing failed build...", "3s elapsed"));
    }

    @Test
    public void displayTextTrimsEmptyElapsedTime() {
        assertEquals("Repairing failed build...",
                ProjectOperationStatus.displayText(" Repairing failed build... ", " "));
    }
}
