package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ProjectTaskRecord;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WorkAwakePolicyTest {
    @Test
    public void keepsScreenOnWhileUiIsBusy() {
        assertTrue(WorkAwakePolicy.shouldKeepScreenOn(true, false, Collections.emptyList(), null));
    }

    @Test
    public void keepsScreenOnWhileTaskIsRunning() {
        ProjectTaskRecord task = new ProjectTaskRecord(1, 1, 0, "Task", "", "running", "", 0, 0, 0, 0);

        assertTrue(WorkAwakePolicy.shouldKeepScreenOn(false, false, Collections.singletonList(task), null));
    }

    @Test
    public void keepsScreenOnWhileBuildIsRunning() {
        BuildJobRecord job = new BuildJobRecord(1, 1, "building", "embedded_runtime", null, null, null, 0, 0, 0);

        assertTrue(WorkAwakePolicy.shouldKeepScreenOn(false, false, Collections.emptyList(), job));
    }

    @Test
    public void allowsScreenOffWhenWorkIsFinished() {
        BuildJobRecord job = new BuildJobRecord(1, 1, "success", "finished", null, "app.apk", null, 0, 0, 0);

        assertFalse(WorkAwakePolicy.shouldKeepScreenOn(false, false, Collections.emptyList(), job));
    }
}
