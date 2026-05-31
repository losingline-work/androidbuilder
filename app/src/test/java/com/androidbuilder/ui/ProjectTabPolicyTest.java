package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ProjectPlanRecord;
import com.androidbuilder.model.ProjectTaskRecord;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class ProjectTabPolicyTest {
    @Test
    public void restoresSavedTabWhenAvailable() {
        BuildJobRecord building = new BuildJobRecord(1, 1, "building", "embedded", null, null, null, 0, 0, 0);

        assertEquals(ProjectTabPolicy.TAB_EXECUTE, ProjectTabPolicy.initialTab(ProjectTabPolicy.TAB_EXECUTE, null, Collections.emptyList(), building));
    }

    @Test
    public void opensBuildTabWhenBuildIsRunningWithoutSavedTab() {
        BuildJobRecord building = new BuildJobRecord(1, 1, "building", "embedded", null, null, null, 0, 0, 0);

        assertEquals(ProjectTabPolicy.TAB_BUILD, ProjectTabPolicy.initialTab(-1, null, Collections.emptyList(), building));
    }

    @Test
    public void opensExecuteTabWhenPlanIsCodingWithoutSavedTab() {
        ProjectPlanRecord plan = new ProjectPlanRecord(1, 1, "# plan", "coding", 2L, 0, 0);

        assertEquals(ProjectTabPolicy.TAB_EXECUTE, ProjectTabPolicy.initialTab(-1, plan, Collections.emptyList(), null));
    }

    @Test
    public void opensExecuteTabWhenTaskIsRunningWithoutSavedTab() {
        ProjectTaskRecord task = new ProjectTaskRecord(1, 1, 0, "Task", "", "running", "", 0, 0, 0, 0);

        assertEquals(ProjectTabPolicy.TAB_EXECUTE, ProjectTabPolicy.initialTab(-1, null, Collections.singletonList(task), null));
    }

    @Test
    public void opensDesignTabWhenNoWorkIsRunning() {
        BuildJobRecord success = new BuildJobRecord(1, 1, "success", "finished", null, "app.apk", null, 0, 0, 0);

        assertEquals(ProjectTabPolicy.TAB_DESIGN, ProjectTabPolicy.initialTab(-1, null, Collections.emptyList(), success));
    }
}
