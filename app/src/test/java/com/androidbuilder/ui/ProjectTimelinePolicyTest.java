package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ProjectPlanRecord;
import com.androidbuilder.model.ProjectTaskRecord;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ProjectTimelinePolicyTest {
    @Test
    public void ordersLinearProjectEventsAfterConversation() {
        ProjectPlanRecord plan = new ProjectPlanRecord(1, 1, "# plan", "planned", 1L, 0, 0);
        ProjectTaskRecord task = new ProjectTaskRecord(1, 1, 0, "Task", "", "pending", "", 0, 0, 0, 0);
        BuildJobRecord job = new BuildJobRecord(1, 1, "building", "embedded", "/tmp/build.log", null, null, 0, 0, 0);

        List<ProjectTimelinePolicy.Entry> entries = ProjectTimelinePolicy.entries(2, true, plan, Collections.singletonList(task), job, true);

        assertEquals(ProjectTimelinePolicy.Kind.MESSAGE, entries.get(0).kind);
        assertEquals(ProjectTimelinePolicy.Kind.MESSAGE, entries.get(1).kind);
        assertEquals(ProjectTimelinePolicy.Kind.OPERATION_STATUS, entries.get(2).kind);
        assertEquals(ProjectTimelinePolicy.Kind.TASK, entries.get(3).kind);
        assertEquals(0, entries.get(3).sourceIndex);
        assertEquals(ProjectTimelinePolicy.Kind.BUILD_LOG, entries.get(4).kind);
        assertEquals(5, entries.size());
    }

    @Test
    public void showsEmptyTaskRowWhenPlanExistsBeforeTasksAreSplit() {
        ProjectPlanRecord plan = new ProjectPlanRecord(1, 1, "# plan", "planned", 1L, 0, 0);

        List<ProjectTimelinePolicy.Entry> entries = ProjectTimelinePolicy.entries(0, false, plan, Collections.emptyList(), null, false);

        assertEquals(1, entries.size());
        assertEquals(ProjectTimelinePolicy.Kind.EMPTY_TASKS, entries.get(0).kind);
    }

    @Test
    public void keepsBuildLogVisibleAfterUserOpenedBuildLog() {
        List<ProjectTimelinePolicy.Entry> entries = ProjectTimelinePolicy.entries(0, false, null, Collections.emptyList(), null, true);

        assertEquals(1, entries.size());
        assertEquals(ProjectTimelinePolicy.Kind.BUILD_LOG, entries.get(0).kind);
    }
}
