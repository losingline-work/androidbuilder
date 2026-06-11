package com.androidbuilder.ui;

import com.androidbuilder.agent.StreamProgressRegistry;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StreamProgressDisplayPolicyTest {
    @Test
    public void formatsChineseCodingProgress() {
        StreamProgressRegistry.StreamProgress progress = new StreamProgressRegistry.StreamProgress(
                "task:7", "coding", 2, 5, 1200, 0, 1000, 61000);

        assertEquals("生成中 · 第 2/5 次 · 1.2k 字 · 1m",
                StreamProgressDisplayPolicy.text(progress, true, 61000));
    }

    @Test
    public void formatsReasoningProgressWhenAnswerIsEmpty() {
        StreamProgressRegistry.StreamProgress progress = new StreamProgressRegistry.StreamProgress(
                "task:7", "scouting", 1, 5, 0, 240, 1000, 3000);

        assertEquals("scouting · attempt 1/5 · thinking 240 chars · 2s",
                StreamProgressDisplayPolicy.text(progress, false, 3000));
    }
}
