package com.androidbuilder.ui;

import com.androidbuilder.model.ProjectTaskRecord;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectLiveStateTest {
    @Test
    public void detectsTaskListChangesAfterBackgroundSplit() {
        List<ProjectTaskRecord> current = Collections.emptyList();
        List<ProjectTaskRecord> next = new ArrayList<>();
        next.add(new ProjectTaskRecord(1, 1, 0, "Task", "", "pending", "", 0, 0, 0, 0));

        assertTrue(ProjectLiveState.tasksChanged(current, next));
    }

    @Test
    public void unchangedTaskIdsDoNotForceRefresh() {
        List<ProjectTaskRecord> current = new ArrayList<>();
        List<ProjectTaskRecord> next = new ArrayList<>();
        current.add(new ProjectTaskRecord(1, 1, 0, "Task", "", "pending", "", 0, 0, 0, 0));
        next.add(new ProjectTaskRecord(1, 1, 0, "Task", "", "pending", "", 0, 0, 0, 0));

        assertFalse(ProjectLiveState.tasksChanged(current, next));
    }

    @Test
    public void statusChangeForSameTaskForcesRefresh() {
        List<ProjectTaskRecord> current = new ArrayList<>();
        List<ProjectTaskRecord> next = new ArrayList<>();
        current.add(new ProjectTaskRecord(1, 1, 0, "Task", "", "pending", "", 0, 0, 0, 0));
        next.add(new ProjectTaskRecord(1, 1, 0, "Task", "", "running", "", 0, 0, 0, 0));

        assertTrue(ProjectLiveState.tasksChanged(current, next));
    }
}
