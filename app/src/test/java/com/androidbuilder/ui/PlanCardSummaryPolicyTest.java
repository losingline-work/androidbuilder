package com.androidbuilder.ui;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlanCardSummaryPolicyTest {
    @Test
    public void recognizesChineseAndEnglishPlanMessages() {
        assertTrue(PlanCardSummaryPolicy.isPlanMessage("# 工程计划\n\n## 数据层"));
        assertTrue(PlanCardSummaryPolicy.isPlanMessage("  # Engineering Plan\n\n## Data layer"));
        assertFalse(PlanCardSummaryPolicy.isPlanMessage("普通回复"));
    }

    @Test
    public void extractsUpToFourSectionTitles() {
        List<String> sections = PlanCardSummaryPolicy.sections(
                "# 工程计划\n\n"
                        + "## 数据层\n"
                        + "## 首页\n"
                        + "## 编辑页\n"
                        + "## 统计页\n"
                        + "## 设置页\n");

        assertEquals(4, sections.size());
        assertEquals("数据层", sections.get(0));
        assertEquals("统计页", sections.get(3));
    }

    @Test
    public void formatsSummaryAndFallback() {
        assertEquals("Data · UI",
                PlanCardSummaryPolicy.summary("# Engineering Plan\n\n## Data\n## UI", false));
        assertEquals("完整计划已收起",
                PlanCardSummaryPolicy.summary("# 工程计划\n正文", true));
    }
}
