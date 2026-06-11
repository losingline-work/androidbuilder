package com.androidbuilder.ui;

import com.androidbuilder.model.HermesAgentRunRecord;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HermesAgentRunDisplayPolicyTest {
    @Test
    public void runningAgentRunShowsBatchAndAgentIndex() {
        HermesAgentRunRecord run = new HermesAgentRunRecord(
                1, 2, 3, 4, 2, "running", "", "", "", "[\"app/src/main/res/layout/activity_main.xml\"]",
                "", "", 0, 0);

        HermesAgentRunDisplayPolicy.Item item = HermesAgentRunDisplayPolicy.item(run, true);

        assertEquals("批次 5 · Agent 3", item.title);
        assertEquals("...", item.iconText);
        assertTrue(item.subtitle.contains("运行中"));
        assertTrue(item.subtitle.contains("app/src/main/res/layout/activity_main.xml"));
    }

    @Test
    public void failedAgentRunShowsEnglishTitleErrorAndIcon() {
        HermesAgentRunRecord run = new HermesAgentRunRecord(
                1, 2, 3, 0, 0, "failed", "", "", "", "[]",
                "", "Merge conflict", 0, 0);

        HermesAgentRunDisplayPolicy.Item item = HermesAgentRunDisplayPolicy.item(run, false);

        assertEquals("Batch 1 · Agent 1", item.title);
        assertEquals("!", item.iconText);
        assertTrue(item.subtitle.contains("Failed"));
        assertTrue(item.subtitle.contains("Merge conflict"));
    }

    @Test
    public void statusIconsMatchHermesAgentStates() {
        assertEquals("·", HermesAgentRunDisplayPolicy.item(runWithStatus("pending"), false).iconText);
        assertEquals("...", HermesAgentRunDisplayPolicy.item(runWithStatus("running"), false).iconText);
        assertEquals("⇄", HermesAgentRunDisplayPolicy.item(runWithStatus("merge_pending"), false).iconText);
        assertEquals("✓", HermesAgentRunDisplayPolicy.item(runWithStatus("done"), false).iconText);
        assertEquals("!", HermesAgentRunDisplayPolicy.item(runWithStatus("failed"), false).iconText);
    }

    private static HermesAgentRunRecord runWithStatus(String status) {
        return new HermesAgentRunRecord(1, 2, 3, 0, 0, status, "", "", "", "[]", "", "", 0, 0);
    }
}
