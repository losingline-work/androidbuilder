package com.androidbuilder.agent;

import com.androidbuilder.model.ProjectMilestoneRecord;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MilestoneSplitPolicyTest {
    private ProjectMilestoneRecord milestone(String title, String slice) {
        return new ProjectMilestoneRecord(0, 0, 0, title, title, slice == null ? title : slice,
                MilestoneStatus.PENDING, "", 0, 0, 0, 0);
    }

    @Test
    public void splitsMultipleTablesIntoOnePerTable() {
        // Real coarse milestone from the project-171 log.
        List<ProjectMilestoneRecord> parts = MilestoneSplitPolicy.split(
                milestone("分类与流水基础：Category / Transaction 表与预置分类", null), true);

        assertEquals(2, parts.size());
        assertTrue(parts.get(0).title.contains("Category"));
        assertTrue(parts.get(1).title.contains("Transaction"));
        // each sub-milestone is scoped to just its unit
        assertTrue(parts.get(0).slice.contains("Category"));
        assertTrue(parts.get(0).description.contains("只"));
    }

    @Test
    public void splitsMultipleScreensIntoOnePerScreen() {
        List<ProjectMilestoneRecord> parts = MilestoneSplitPolicy.split(
                milestone("流水列表与详情：BookFragment + RecordDetailActivity", null), true);

        assertEquals(2, parts.size());
        assertTrue(parts.get(0).title.contains("BookFragment"));
        assertTrue(parts.get(1).title.contains("RecordDetailActivity"));
    }

    @Test
    public void splitsScreenPlusComponent() {
        List<ProjectMilestoneRecord> parts = MilestoneSplitPolicy.split(
                milestone("记账页：AddRecordActivity + 自定义数字键盘", null), true);

        assertEquals(2, parts.size());
        assertTrue(parts.get(0).title.contains("AddRecordActivity"));
        assertTrue(parts.get(1).title.contains("键盘"));
    }

    @Test
    public void doesNotSplitHelperFromItsFirstTable() {
        // DBHelper + the first table stay together (a helper alone is not a unit).
        List<ProjectMilestoneRecord> parts = MilestoneSplitPolicy.split(
                milestone("数据层基础：DBHelper + Account 表与预置账户", null), true);

        assertEquals(1, parts.size());
        assertEquals("数据层基础：DBHelper + Account 表与预置账户", parts.get(0).title);
    }

    @Test
    public void doesNotSplitChartSubAspects() {
        // "收支对比 / 累计结余" are two series of ONE chart — they must NOT become two milestones.
        List<ProjectMilestoneRecord> parts = MilestoneSplitPolicy.split(
                milestone("折线图：收支对比 / 累计结余", "折线图"), true);

        assertEquals(1, parts.size());
    }

    @Test
    public void doesNotSplitASingleUnitMilestone() {
        List<ProjectMilestoneRecord> parts = MilestoneSplitPolicy.split(
                milestone("主页仪表盘：MainActivity + DashboardFragment", null), true);
        // This one DOES split (two screens) — sanity that the policy is active...
        assertEquals(2, parts.size());

        // ...but a genuinely single-unit milestone is untouched.
        List<ProjectMilestoneRecord> single = MilestoneSplitPolicy.split(
                milestone("预算管理页", null), true);
        assertEquals(1, single.size());
        assertEquals("预算管理页", single.get(0).title);
    }

    @Test
    public void doesNotExplodePastTheCap() {
        // Five class-named units exceed MAX_SPLIT_PARTS — keep the model's grouping rather than over-split.
        List<ProjectMilestoneRecord> parts = MilestoneSplitPolicy.split(
                milestone("巨型页：AView + BView + CView + DView + EView", null), true);
        assertEquals(1, parts.size());
    }

    @Test
    public void integratesWithMilestonePlanPolicyExpansion() {
        java.util.List<ProjectMilestoneRecord> slices = new java.util.ArrayList<>();
        slices.add(milestone("分类与流水基础：Category / Transaction 表与预置分类", null));
        slices.add(milestone("预算管理页", null));

        List<ProjectMilestoneRecord> normalized = MilestonePlanPolicy.normalize(slices, true);
        // skeleton M0 + (Category, Transaction) + (预算管理页) = 4, contiguously ordered
        assertEquals(4, normalized.size());
        assertEquals(0, normalized.get(0).orderIndex);
        assertEquals(3, normalized.get(3).orderIndex);
        assertTrue(normalized.get(1).title.contains("Category"));
        assertTrue(normalized.get(2).title.contains("Transaction"));
    }
}
