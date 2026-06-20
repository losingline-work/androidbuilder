package com.androidbuilder.ui;

import com.androidbuilder.agent.MilestoneStatus;
import com.androidbuilder.agent.MilestoneTasksCodec;
import com.androidbuilder.model.ProjectMilestoneRecord;
import com.androidbuilder.model.ProjectTaskRecord;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MilestoneTimelinePolicyTest {
    private ProjectMilestoneRecord milestone(long id, int order, String status, String tasksJson) {
        return new ProjectMilestoneRecord(id, 1, order, "M" + order, "", "", status, "", 0, 0, 0, 0, tasksJson);
    }

    private ProjectTaskRecord task(String title, String status) {
        return new ProjectTaskRecord(0, 0, 0, title, "", status, "", 0, 0, 0, 0);
    }

    @Test
    public void doneMilestoneUsesItsSnapshot() {
        String snapshot = MilestoneTasksCodec.encode(Arrays.asList(task("a", "done"), task("b", "done")));
        List<MilestoneCardModel> cards = MilestoneTimelinePolicy.cards(
                Collections.singletonList(milestone(5, 1, MilestoneStatus.DONE, snapshot)), 0, null);

        assertEquals(1, cards.size());
        assertEquals(2, cards.get(0).totalTasks());
        assertEquals(2, cards.get(0).doneTasks());
    }

    @Test
    public void activeMilestoneWithNoSnapshotUsesLiveTasks() {
        List<ProjectTaskRecord> live = Arrays.asList(task("x", "done"), task("y", "running"));
        List<MilestoneCardModel> cards = MilestoneTimelinePolicy.cards(
                Collections.singletonList(milestone(7, 2, MilestoneStatus.GENERATING, "")), 7, live);

        assertEquals(2, cards.get(0).totalTasks());
        assertEquals(1, cards.get(0).doneTasks());
    }

    @Test
    public void pendingMilestoneWithoutSnapshotShowsNoTasks() {
        List<MilestoneCardModel> cards = MilestoneTimelinePolicy.cards(
                Collections.singletonList(milestone(9, 3, MilestoneStatus.PENDING, "")), 7, null);

        assertEquals(0, cards.get(0).totalTasks());
    }

    @Test
    public void cardsPreserveMilestoneOrderAndIdentity() {
        List<MilestoneCardModel> cards = MilestoneTimelinePolicy.cards(Arrays.asList(
                milestone(1, 0, MilestoneStatus.DONE, ""),
                milestone(2, 1, MilestoneStatus.BUILDING, "")), 0, null);

        assertEquals(2, cards.size());
        assertEquals(0, cards.get(0).orderIndex);
        assertEquals(2L, cards.get(1).milestoneId);
        assertTrue(cards.get(0).title.contains("M0"));
    }
}
