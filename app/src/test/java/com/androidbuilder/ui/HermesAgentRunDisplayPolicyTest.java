package com.androidbuilder.ui;

import com.androidbuilder.model.HermesAgentRunRecord;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

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

    @Test
    public void activeSummaryShowsRunningAndMergePendingAgents() {
        List<HermesAgentRunRecord> runs = Arrays.asList(
                new HermesAgentRunRecord(2, 1, 10, 0, 1, "merge_pending", "", "", "", "[\"app/src/main/res/layout/main.xml\"]", "", "", 10, 0),
                new HermesAgentRunRecord(1, 1, 9, 0, 0, "running", "", "", "", "[\"app/src/main/java/MainActivity.java\"]", "", "", 20, 0),
                new HermesAgentRunRecord(3, 1, 8, 0, 2, "done", "", "", "", "[\"app/src/main/AndroidManifest.xml\"]", "", "", 1, 30));

        String summary = HermesAgentRunDisplayPolicy.activeSummary(runs, true);

        assertTrue(summary.contains("2 个子 Agent"));
        assertTrue(summary.contains("Agent 1"));
        assertTrue(summary.contains("运行中"));
        assertTrue(summary.contains("app/src/main/java/MainActivity.java"));
        assertTrue(summary.contains("Agent 2"));
        assertTrue(summary.contains("等待合并"));
        assertTrue(summary.contains("app/src/main/res/layout/main.xml"));
    }

    @Test
    public void activeSummaryIsEmptyWhenNoAgentsAreActive() {
        List<HermesAgentRunRecord> runs = Arrays.asList(runWithStatus("done"), runWithStatus("failed"));

        assertEquals("", HermesAgentRunDisplayPolicy.activeSummary(runs, false));
    }

    private static HermesAgentRunRecord runWithStatus(String status) {
        return new HermesAgentRunRecord(1, 2, 3, 0, 0, status, "", "", "", "[]", "", "", 0, 0);
    }
}
