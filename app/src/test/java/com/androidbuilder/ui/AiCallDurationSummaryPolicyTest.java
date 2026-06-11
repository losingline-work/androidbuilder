package com.androidbuilder.ui;

import com.androidbuilder.model.AiConversationRecord;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AiCallDurationSummaryPolicyTest {
    @Test
    public void summarizesDurationsByCloudCallKind() {
        AiCallDurationSummaryPolicy.Summary summary = AiCallDurationSummaryPolicy.summarize(Arrays.asList(
                record("云端 AI · 文件操作生成 #1", "durationMs=120000"),
                record("Cloud AI · task operations #2", "provider=minimax\ndurationMs=60000"),
                record("Hermes · file operation review #1", "durationMs=30000"),
                record("Hermes · Context Scout #1", "durationMs=10000"),
                record("Cloud AI · engineering plan", "durationMs=5000"),
                record("Cloud AI · build-log triage", "durationMs=7000")));

        assertEquals(6, summary.totalCount);
        assertEquals(232000, summary.totalMs);
        assertEquals(2, summary.coder.count);
        assertEquals(180000, summary.coder.totalMs);
        assertEquals(30000, summary.reviewer.totalMs);
        assertEquals(10000, summary.scout.totalMs);
        assertEquals(5000, summary.planner.totalMs);
        assertEquals(7000, summary.other.totalMs);
    }

    @Test
    public void missingDurationCountsAsZero() {
        AiCallDurationSummaryPolicy.Summary summary = AiCallDurationSummaryPolicy.summarize(Collections.singletonList(
                record("Cloud AI · task operations #1", "provider=openai")));

        assertEquals(1, summary.totalCount);
        assertEquals(0, summary.totalMs);
        assertEquals(1, summary.coder.count);
        assertEquals(0, summary.coder.totalMs);
    }

    @Test
    public void formatsEmptyAndNonEmptySummaries() {
        assertEquals("", AiCallDurationSummaryPolicy.format(AiCallDurationSummaryPolicy.summarize(Collections.emptyList()), true));

        AiCallDurationSummaryPolicy.Summary summary = AiCallDurationSummaryPolicy.summarize(Arrays.asList(
                record("云端 AI · 文件操作生成 #1", "durationMs=120000"),
                record("Hermes · 文件操作审查 #1", "durationMs=30000")));

        String text = AiCallDurationSummaryPolicy.format(summary, true);

        assertTrue(text.contains("云端 2 次"));
        assertTrue(text.contains("总 2m30s"));
        assertTrue(text.contains("coder 2m"));
        assertTrue(text.contains("reviewer 30s"));
    }

    private static AiConversationRecord record(String title, String metadata) {
        return new AiConversationRecord(
                1,
                1,
                "cloud",
                title,
                "",
                "",
                "success",
                metadata,
                null,
                1000L);
    }
}
