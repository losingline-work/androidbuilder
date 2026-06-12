package com.androidbuilder.ui;

import com.androidbuilder.model.HermesAgentRunRecord;
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
        assertEquals("Foundation", groups.get(0).label);
        assertEquals(2, groups.get(1).tasks.size());
    }

    @Test
    public void groupsUseChineseLabelsWhenRequested() {
        List<ProjectTaskRecord> tasks = Arrays.asList(
                task(0, "Gradle", instructionWithProduces("foundation"), "done"),
                task(1, "Stats", instructionWithProduces("stats"), "pending"),
                task(2, "Polish", instructionWithProduces("polish"), "pending"));

        List<ProjectTaskListDisplayPolicy.Group> groups = ProjectTaskListDisplayPolicy.groups(tasks, false, true);

        assertEquals("基础", groups.get(0).label);
        assertEquals("统计", groups.get(1).label);
        assertEquals("收尾", groups.get(2).label);
    }

    @Test
    public void expandedGroupsPreserveTaskSortOrderWhenGroupKeysRepeat() {
        List<ProjectTaskRecord> tasks = Arrays.asList(
                task(0, "Data first", instructionWithProduces("data"), "done"),
                task(1, "UI middle", instructionWithProduces("ui"), "pending"),
                task(2, "Data later", instructionWithProduces("data"), "pending"));

        List<ProjectTaskListDisplayPolicy.Group> groups = ProjectTaskListDisplayPolicy.groups(tasks, false);

        assertEquals(3, groups.size());
        assertEquals("Data first", groups.get(0).tasks.get(0).title);
        assertEquals("UI middle", groups.get(1).tasks.get(0).title);
        assertEquals("Data later", groups.get(2).tasks.get(0).title);
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

    @Test
    public void collapsedListShowsTaskWithMergePendingAgentRun() {
        List<ProjectTaskRecord> tasks = Arrays.asList(
                task(10, 0, "Done", "", "done"),
                task(11, 1, "Merging", "", "done"),
                task(12, 2, "Next", "", "pending"));
        List<HermesAgentRunRecord> agentRuns = Arrays.asList(
                new HermesAgentRunRecord(1, 2, 11, 1, 0, "merge_pending", "", "", "", "[]", "", "", 0, 0));

        List<ProjectTaskRecord> visible = ProjectTaskListDisplayPolicy.visibleTasks(tasks, true, agentRuns);

        assertEquals(2, visible.size());
        assertEquals("Merging", visible.get(0).title);
        assertEquals("Next", visible.get(1).title);
    }

    @Test
    public void completedTasksCollapseToSummary() {
        List<ProjectTaskRecord> tasks = Arrays.asList(
                task(10, 0, "One", "", "done", 1000, 3000),
                task(11, 1, "Two", "", "done", 2000, 61000));

        assertTrue(ProjectTaskListDisplayPolicy.shouldCollapseCompleted(tasks));
        assertEquals("✓ 已完成 2/2 项任务 · 总用时 1m",
                ProjectTaskListDisplayPolicy.completionSummary(tasks, true));
    }

    @Test
    public void failedTasksDoNotCollapseToCompletedSummary() {
        List<ProjectTaskRecord> tasks = Arrays.asList(
                task(10, 0, "One", "", "done"),
                task(11, 1, "Two", "", "failed"));

        assertFalse(ProjectTaskListDisplayPolicy.shouldCollapseCompleted(tasks));
        assertEquals("", ProjectTaskListDisplayPolicy.completionSummary(tasks, true));
    }

    private static ProjectTaskRecord task(int order, String title, String instruction, String status) {
        return task(0, order, title, instruction, status);
    }

    private static ProjectTaskRecord task(long id, int order, String title, String instruction, String status) {
        return new ProjectTaskRecord(id, 1, order, title, instruction, status, "", 0, 0, 0, 0);
    }

    private static ProjectTaskRecord task(long id, int order, String title, String instruction, String status, long startedAt, long completedAt) {
        return new ProjectTaskRecord(id, 1, order, title, instruction, status, "", 0, 0, startedAt, completedAt);
    }

    private static String instructionWithProduces(String produces) {
        return "Instruction\n\n[HermesTaskContract]\n{\"produces\":[\"" + produces + "\"]}\n[/HermesTaskContract]";
    }
}
