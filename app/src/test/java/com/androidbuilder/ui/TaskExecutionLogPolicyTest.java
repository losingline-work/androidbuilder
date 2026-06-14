package com.androidbuilder.ui;

import com.androidbuilder.model.AiConversationRecord;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TaskExecutionLogPolicyTest {
    @Test
    public void rendersStepsWithGlyphTitleAndDecision() {
        List<AiConversationRecord> steps = Arrays.asList(
                step("云端 AI · 文件操作生成 #1", "success"),
                step("确定性预检 #1", "ok"),
                step("确定性规则 · 源码写入前预审", "rewrite"),
                step("合并应用", "failed"));

        String log = TaskExecutionLogPolicy.render(steps, true);

        assertTrue(log.contains("✅ 云端 AI · 文件操作生成 #1 · 成功"));
        assertTrue(log.contains("✅ 确定性预检 #1 · 通过"));
        assertTrue(log.contains("🔁 确定性规则 · 源码写入前预审 · 要求重写"));
        assertTrue(log.contains("❌ 合并应用 · 失败"));
    }

    @Test
    public void collapsesNearIdenticalRunsIntoOneCountedLine() {
        // "批次 1/21 … 3/21" all-success collapse into one line keyed on the digit-normalised title.
        List<AiConversationRecord> steps = Arrays.asList(
                step("云端 AI · 文件操作生成批次 1/21", "success"),
                step("云端 AI · 文件操作生成批次 2/21", "success"),
                step("云端 AI · 文件操作生成批次 3/21", "success"),
                step("Hermes 审查", "rewrite"));

        String log = TaskExecutionLogPolicy.render(steps, true);

        assertEquals(2, log.split("\n").length);
        assertTrue(log.contains("×3"));
        assertTrue(log.contains("Hermes 审查 · 要求重写"));
    }

    @Test
    public void capsLongLogsAndNotesOmittedSteps() {
        // Alternating status keeps each line distinct (no collapse), so 60 lines exercise the cap.
        List<AiConversationRecord> steps = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            steps.add(step("review", i % 2 == 0 ? "ok" : "rewrite"));
        }

        String log = TaskExecutionLogPolicy.render(steps, false);

        assertTrue(log.contains("earlier steps omitted"));
        assertEquals(41, log.split("\n").length); // 1 omitted-notice + 40 lines
    }

    @Test
    public void emptyForNoSteps() {
        assertEquals("", TaskExecutionLogPolicy.render(null, true));
        assertEquals("", TaskExecutionLogPolicy.render(Collections.<AiConversationRecord>emptyList(), true));
    }

    private static AiConversationRecord step(String title, String status) {
        return new AiConversationRecord(1, 1, "deterministic", title, "", "", status, "taskId=718", null, 0L);
    }
}
