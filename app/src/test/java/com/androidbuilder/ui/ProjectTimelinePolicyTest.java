package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ProjectPlanRecord;
import com.androidbuilder.model.ProjectTaskRecord;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ProjectTimelinePolicyTest {
    @Test
    public void placesProjectTaskGroupBeforeMessagesSoLatestLogsStayAtBottom() {
        ProjectPlanRecord plan = new ProjectPlanRecord(1, 1, "# plan", "planned", 1L, 0, 0);
        ProjectTaskRecord first = new ProjectTaskRecord(1, 1, 0, "Task 1", "", "pending", "", 0, 0, 0, 0);
        ProjectTaskRecord second = new ProjectTaskRecord(2, 1, 1, "Task 2", "", "pending", "", 0, 0, 0, 0);
        BuildJobRecord job = new BuildJobRecord(1, 1, "building", "embedded", "/tmp/build.log", null, null, 0, 0, 0);

        List<ProjectTimelinePolicy.Entry> entries = ProjectTimelinePolicy.entries(2, Arrays.asList(null, 9L), true, plan, Arrays.asList(first, second), job, true);

        assertEquals(ProjectTimelinePolicy.Kind.TASK_GROUP, entries.get(0).kind);
        assertEquals(-1, entries.get(0).sourceIndex);
        assertEquals(ProjectTimelinePolicy.Kind.MESSAGE, entries.get(1).kind);
        assertEquals(ProjectTimelinePolicy.Kind.MESSAGE, entries.get(2).kind);
        assertEquals(ProjectTimelinePolicy.Kind.BUILD_LOG, entries.get(3).kind);
        assertEquals(1, entries.get(3).sourceIndex);
        assertEquals(ProjectTimelinePolicy.Kind.OPERATION_STATUS, entries.get(4).kind);
        assertEquals(5, entries.size());
    }

    @Test
    public void anchorsTaskGroupAtChronologicalPositionNotTop() {
        ProjectPlanRecord plan = new ProjectPlanRecord(1, 1, "# plan", "planned", 1L, 0, 0);
        ProjectTaskRecord task = new ProjectTaskRecord(1, 1, 0, "Task", "", "pending", "", 0, 0, 0, 0);

        // taskAnchorIndex = 1 → group sits after message 0, before message 1.
        List<ProjectTimelinePolicy.Entry> entries = ProjectTimelinePolicy.entries(
                2, Arrays.asList(null, null), null, 1, false, plan, Collections.singletonList(task), null, false);

        assertEquals(ProjectTimelinePolicy.Kind.MESSAGE, entries.get(0).kind);
        assertEquals(0, entries.get(0).sourceIndex);
        assertEquals(ProjectTimelinePolicy.Kind.TASK_GROUP, entries.get(1).kind);
        assertEquals(ProjectTimelinePolicy.Kind.MESSAGE, entries.get(2).kind);
        assertEquals(1, entries.get(2).sourceIndex);
        assertEquals(3, entries.size());
    }

    @Test
    public void anchorsTaskGroupAfterAllMessagesWhenIndexBeyondEnd() {
        ProjectPlanRecord plan = new ProjectPlanRecord(1, 1, "# plan", "planned", 1L, 0, 0);
        ProjectTaskRecord task = new ProjectTaskRecord(1, 1, 0, "Task", "", "pending", "", 0, 0, 0, 0);

        List<ProjectTimelinePolicy.Entry> entries = ProjectTimelinePolicy.entries(
                2, Arrays.asList(null, null), null, 5, true, plan, Collections.singletonList(task), null, false);

        assertEquals(ProjectTimelinePolicy.Kind.MESSAGE, entries.get(0).kind);
        assertEquals(ProjectTimelinePolicy.Kind.MESSAGE, entries.get(1).kind);
        assertEquals(ProjectTimelinePolicy.Kind.TASK_GROUP, entries.get(2).kind);
        assertEquals(ProjectTimelinePolicy.Kind.OPERATION_STATUS, entries.get(3).kind);
        assertEquals(4, entries.size());
    }

    @Test
    public void placesOperationStatusAsSeparateItemAfterTasks() {
        ProjectPlanRecord plan = new ProjectPlanRecord(1, 1, "# plan", "planned", 1L, 0, 0);
        ProjectTaskRecord task = new ProjectTaskRecord(1, 1, 0, "Task", "", "pending", "", 0, 0, 0, 0);

        List<ProjectTimelinePolicy.Entry> entries = ProjectTimelinePolicy.entries(0, Collections.emptyList(), true, plan, Collections.singletonList(task), null, false);

        assertEquals(ProjectTimelinePolicy.Kind.TASK_GROUP, entries.get(0).kind);
        assertEquals(ProjectTimelinePolicy.Kind.OPERATION_STATUS, entries.get(1).kind);
        assertEquals(2, entries.size());
    }

    @Test
    public void doesNotShowTaskCardBeforeTasksAreSplit() {
        ProjectPlanRecord plan = new ProjectPlanRecord(1, 1, "# plan", "planned", 1L, 0, 0);

        List<ProjectTimelinePolicy.Entry> entries = ProjectTimelinePolicy.entries(0, Collections.emptyList(), false, plan, Collections.emptyList(), null, false);

        assertEquals(0, entries.size());
    }

    @Test
    public void doesNotShowBuildLogByDefaultForLatestJob() {
        BuildJobRecord job = new BuildJobRecord(1, 1, "success", "finished", "/tmp/build.log", "/tmp/app.apk", null, 0, 0, 0);

        List<ProjectTimelinePolicy.Entry> entries = ProjectTimelinePolicy.entries(0, Collections.emptyList(), false, null, Collections.emptyList(), job, false);

        assertEquals(0, entries.size());
    }

    @Test
    public void buildLogRowSurvivesWhenAnchorMessageHidden() {
        // Two messages link to job 7; the last (the "build complete" chatter) is hidden.
        List<ProjectTimelinePolicy.Entry> entries = ProjectTimelinePolicy.entries(
                2,
                Arrays.asList(7L, 7L),
                Arrays.asList(true, false),
                false,
                null,
                Collections.emptyList(),
                null,
                false);

        assertEquals(ProjectTimelinePolicy.Kind.MESSAGE, entries.get(0).kind);
        assertEquals(0, entries.get(0).sourceIndex);
        // No MESSAGE entry for the hidden index 1, but the BUILD_LOG row still anchors there.
        assertEquals(ProjectTimelinePolicy.Kind.BUILD_LOG, entries.get(1).kind);
        assertEquals(1, entries.get(1).sourceIndex);
        assertEquals(2, entries.size());
    }

    @Test
    public void chatterMessagesDroppedWhileOrderingPreserved() {
        List<ProjectTimelinePolicy.Entry> entries = ProjectTimelinePolicy.entries(
                3,
                Arrays.asList(null, null, null),
                Arrays.asList(true, false, true),
                false,
                null,
                Collections.emptyList(),
                null,
                false);

        assertEquals(2, entries.size());
        assertEquals(ProjectTimelinePolicy.Kind.MESSAGE, entries.get(0).kind);
        assertEquals(0, entries.get(0).sourceIndex);
        assertEquals(ProjectTimelinePolicy.Kind.MESSAGE, entries.get(1).kind);
        assertEquals(2, entries.get(1).sourceIndex);
    }

    @Test
    public void nullVisibilityShowsEveryMessage() {
        List<ProjectTimelinePolicy.Entry> entries = ProjectTimelinePolicy.entries(
                3,
                Arrays.asList(null, null, null),
                null,
                false,
                null,
                Collections.emptyList(),
                null,
                false);

        assertEquals(3, entries.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(ProjectTimelinePolicy.Kind.MESSAGE, entries.get(i).kind);
        }
    }

    @Test
    public void placesBuildLogAfterLastMessageForEachLinkedJob() {
        List<ProjectTimelinePolicy.Entry> entries = ProjectTimelinePolicy.entries(3, Arrays.asList(7L, 7L, 8L), false, null, Collections.emptyList(), null, false);

        assertEquals(ProjectTimelinePolicy.Kind.MESSAGE, entries.get(0).kind);
        assertEquals(ProjectTimelinePolicy.Kind.MESSAGE, entries.get(1).kind);
        assertEquals(ProjectTimelinePolicy.Kind.BUILD_LOG, entries.get(2).kind);
        assertEquals(1, entries.get(2).sourceIndex);
        assertEquals(ProjectTimelinePolicy.Kind.MESSAGE, entries.get(3).kind);
        assertEquals(ProjectTimelinePolicy.Kind.BUILD_LOG, entries.get(4).kind);
        assertEquals(2, entries.get(4).sourceIndex);
        assertEquals(5, entries.size());
    }
}
