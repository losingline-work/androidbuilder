package com.androidbuilder.agent;

import com.androidbuilder.model.ProjectMilestoneRecord;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MilestonePlanPolicyTest {
    private ProjectMilestoneRecord slice(String title) {
        return new ProjectMilestoneRecord(0, 0, 0, title, title + " desc", title + " slice",
                MilestoneStatus.PENDING, "", 0, 0, 0, 0);
    }

    @Test
    public void normalize_prependsSkeletonAndReindexesContiguously() {
        List<ProjectMilestoneRecord> slices = new ArrayList<>();
        slices.add(slice("Record a bill"));
        slices.add(slice("Bill list"));

        List<ProjectMilestoneRecord> out = MilestonePlanPolicy.normalize(slices, false);

        assertEquals(3, out.size());
        assertEquals(0, out.get(0).orderIndex);
        assertEquals("Runnable skeleton", out.get(0).title);
        assertEquals(MilestoneStatus.PENDING, out.get(0).status);
        assertEquals(1, out.get(1).orderIndex);
        assertEquals("Record a bill", out.get(1).title);
        assertEquals(2, out.get(2).orderIndex);
        assertEquals("Bill list", out.get(2).title);
    }

    @Test
    public void normalize_emptyOrNullSlicesYieldsSkeletonOnly() {
        assertEquals(1, MilestonePlanPolicy.normalize(Collections.<ProjectMilestoneRecord>emptyList(), false).size());
        assertEquals(1, MilestonePlanPolicy.normalize(null, false).size());
    }

    @Test
    public void normalize_localizesSkeletonTitle() {
        assertEquals("可运行骨架", MilestonePlanPolicy.normalize(null, true).get(0).title);
        assertEquals("Runnable skeleton", MilestonePlanPolicy.normalize(null, false).get(0).title);
    }

    @Test
    public void normalize_capsRunawayListsAndReportsTruncation() {
        List<ProjectMilestoneRecord> slices = new ArrayList<>();
        for (int i = 0; i < MilestonePlanPolicy.MAX_FEATURE_MILESTONES + 5; i++) {
            slices.add(slice("feature " + i));
        }

        List<ProjectMilestoneRecord> out = MilestonePlanPolicy.normalize(slices, false);

        // skeleton + exactly MAX feature milestones
        assertEquals(MilestonePlanPolicy.MAX_FEATURE_MILESTONES + 1, out.size());
        assertTrue(MilestonePlanPolicy.truncated(slices));
        // order stays contiguous through the cap
        assertEquals(MilestonePlanPolicy.MAX_FEATURE_MILESTONES, out.get(out.size() - 1).orderIndex);
    }

    @Test
    public void normalize_doesNotReportTruncationWithinBound() {
        List<ProjectMilestoneRecord> slices = new ArrayList<>();
        slices.add(slice("only feature"));
        assertFalse(MilestonePlanPolicy.truncated(slices));
    }

    @Test
    public void milestonePlanPrompt_statesTheLoadBearingConstraints() {
        String prompt = OpenAiClient.milestonePlanSystemPromptForTest(false);
        // returns only the milestones array
        assertTrue(prompt.contains("\"milestones\""));
        // skeleton is excluded (added automatically as milestone 0)
        assertTrue(prompt.contains("do NOT include the runnable skeleton"));
        assertTrue(prompt.contains("milestone 0"));
        // each milestone is a buildable vertical slice, ordered by dependency
        assertTrue(prompt.contains("VERTICAL"));
        assertTrue(prompt.contains("COMPILES and RUNS"));
        assertTrue(prompt.contains("dependency"));
    }

    @Test
    public void milestonePlanPrompt_demandsFineGrainedSlices() {
        String prompt = OpenAiClient.milestonePlanSystemPromptForTest(false);
        // tiny slices, not a whole data layer in one milestone
        assertTrue(prompt.contains("TINY"));
        assertTrue(prompt.contains("ONE core table"));
        assertTrue(prompt.contains("one milestone per tab"));
        assertTrue(prompt.contains("10 to 20"));
    }
}
