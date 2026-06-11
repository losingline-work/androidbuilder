package com.androidbuilder.ui;

import com.androidbuilder.model.ProjectTaskRecord;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectTaskListDisplayPolicyTest {
    @Test
    public void taskListStartsCollapsedByDefault() {
        assertTrue(ProjectTaskListDisplayPolicy.defaultCollapsed());
    }

    @Test
    public void groupsTasksByHermesContractProducesSignal() {
        List<ProjectTaskRecord> tasks = Arrays.asList(
                task(0, "Gradle", instructionWithProduces("foundation"), "done"),
                task(1, "Data", instructionWithProduces("data"), "pending"),
                task(2, "DAO", instructionWithProduces("data"), "pending"));

        List<ProjectTaskListDisplayPolicy.Group> groups = ProjectTaskListDisplayPolicy.groups(tasks, true);

        assertEquals("foundation", groups.get(0).key);
        assertEquals("data", groups.get(1).key);
        assertEquals(2, groups.get(1).tasks.size());
    }

    @Test
    public void collapsedListShowsFailedRunningAndNextPendingOnly() {
        List<ProjectTaskRecord> tasks = Arrays.asList(
                task(0, "Done", "", "done"),
                task(1, "Failed", "", "failed"),
                task(2, "Next", "", "pending"),
                task(3, "Later", "", "pending"));

        List<ProjectTaskRecord> visible = ProjectTaskListDisplayPolicy.visibleTasks(tasks, true);

        assertEquals(2, visible.size());
        assertEquals("Failed", visible.get(0).title);
        assertEquals("Next", visible.get(1).title);
        assertFalse(visible.contains(tasks.get(3)));
    }

    private static ProjectTaskRecord task(int order, String title, String instruction, String status) {
        return new ProjectTaskRecord(0, 1, order, title, instruction, status, "", 0, 0, 0, 0);
    }

    private static String instructionWithProduces(String produces) {
        return "Instruction\n\n[HermesTaskContract]\n{\"produces\":[\"" + produces + "\"]}\n[/HermesTaskContract]";
    }
}
