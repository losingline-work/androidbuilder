package com.androidbuilder.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class PlanContentSanitizerTest {
    @Test
    public void removesLongNullRunFromPollutedPlan() {
        StringBuilder nulls = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            nulls.append("null");
        }
        String polluted = "# 工程计划\n\n" + nulls + "## 需求理解\n";

        String cleaned = PlanContentSanitizer.clean(polluted);

        assertFalse(cleaned.contains("nullnull"));
        assertEquals("# 工程计划\n\n## 需求理解\n", cleaned);
    }

    @Test
    public void leavesNormalPlanTextUntouched() {
        String plan = "# Plan\n- a service may return null when not found\n- handle null, null, null cases\n";
        assertEquals(plan, PlanContentSanitizer.clean(plan));
    }

    @Test
    public void handlesNullAndEmpty() {
        assertEquals("", PlanContentSanitizer.clean(null));
        assertEquals("", PlanContentSanitizer.clean(""));
    }
}
