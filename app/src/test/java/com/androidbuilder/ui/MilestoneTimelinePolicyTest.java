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
    public void consolidatesBuildAttemptsIntoOneSummary() {
        // A milestone built once + 3 repair rounds, ending failed → ONE summary, not 4 rows.
        ProjectMilestoneRecord m = new ProjectMilestoneRecord(1, 1, 2, "M2", "", "", MilestoneStatus.FAILED,
                "", 88, 3, 0, 0, "");
        com.androidbuilder.model.BuildJobRecord failed = new com.androidbuilder.model.BuildJobRecord(
                88, 1, "failed", "compileDebugJavaWithJavac", "/x.log", null,
                "cannot find symbol: DBContract.COL_X\nsecond line", 0, 0, 0);

        List<MilestoneCardModel> cards = MilestoneTimelinePolicy.cards(
                Collections.singletonList(m), 0, null, id -> id == 88 ? failed : null);

        MilestoneCardModel card = cards.get(0);
        assertTrue(card.hasBuild);
        assertEquals(4, card.buildAttempts);
        assertEquals("failed", card.buildResult);
        assertTrue(card.buildError.contains("cannot find symbol"));
        // excerpt is the first non-empty line only
        assertTrue(!card.buildError.contains("second line"));
    }

    @Test
    public void statusHintLandsOnlyOnTheActiveMilestone() {
        List<MilestoneCardModel> cards = MilestoneTimelinePolicy.cards(Arrays.asList(
                milestone(1, 0, MilestoneStatus.DONE, ""),
                milestone(2, 1, MilestoneStatus.REPAIRING, "")),
                2, null, id -> null, "里程碑 M1 自动修复中（第 2 轮）…");

        assertEquals("", cards.get(0).statusHint);
        assertTrue(cards.get(1).statusHint.contains("修复中"));
    }

    @Test
    public void statusHintCanLandOnADifferentHostThanTheActiveMilestone() {
        // No march active (activeMilestoneId = 0), but a manual repair targets milestone 2: the hint must
        // land on the host (2), not spill out — and NOT on the active-id path (which is 0 / none).
        List<MilestoneCardModel> cards = MilestoneTimelinePolicy.cards(Arrays.asList(
                milestone(1, 0, MilestoneStatus.DONE, ""),
                milestone(2, 1, MilestoneStatus.FAILED, "")),
                0, 2, null, id -> null, "正在修复失败构建… · 思考中…1.1k 字");

        assertEquals("", cards.get(0).statusHint);
        assertTrue(cards.get(1).statusHint.contains("正在修复失败构建"));
    }

    @Test
    public void noBuildJobMeansNoBuildSummary() {
        ProjectMilestoneRecord m = new ProjectMilestoneRecord(1, 1, 1, "M1", "", "", MilestoneStatus.GENERATING,
                "", 0, 0, 0, 0, "");
        MilestoneCardModel card = MilestoneTimelinePolicy.cards(
                Collections.singletonList(m), 1, null, id -> null).get(0);
        assertTrue(!card.hasBuild);
        assertEquals(0, card.buildAttempts);
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
