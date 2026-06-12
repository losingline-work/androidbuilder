package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.AiConversationRecord;
import com.androidbuilder.model.ChatMessage;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectBuildFailureContextPolicyTest {
    @Test
    public void copiesFailureContextOnlyForFailedJobsWithLogs() {
        assertTrue(ProjectBuildFailureContextPolicy.canCopyFailureContext(
                new BuildJobRecord(1, 1, "failed", "embedded_runtime_finished", "/tmp/build.log", null, "javac failed", 0, 0, 0)));
        assertFalse(ProjectBuildFailureContextPolicy.canCopyFailureContext(
                new BuildJobRecord(2, 1, "success", "finished", "/tmp/build.log", "/tmp/app.apk", null, 0, 0, 0)));
        assertFalse(ProjectBuildFailureContextPolicy.canCopyFailureContext(
                new BuildJobRecord(3, 1, "failed", "embedded_runtime_finished", "", null, "javac failed", 0, 0, 0)));
    }

    @Test
    public void failureContextIncludesSummaryAnchoredContextAndTail() {
        String logs = repeat("startup line\n", 120)
                + "before failure context\n"
                + "app/src/main/java/MainActivity.java:42: error: cannot find symbol\n"
                + "after failure context\n"
                + repeat("middle line\n", 120)
                + "BUILD FAILED in 12s\n"
                + "last diagnostic line\n";

        String context = ProjectBuildFailureContextPolicy.copyText(
                new BuildJobRecord(1, 1, "failed", "embedded_runtime_finished", "/tmp/build.log", null, "Cannot find symbol", 0, 0, 0),
                logs);

        assertTrue(context.contains("Job #1"));
        assertTrue(context.contains("Error summary"));
        assertTrue(context.contains("Cannot find symbol"));
        assertTrue(context.contains("before failure context"));
        assertTrue(context.contains("error: cannot find symbol"));
        assertTrue(context.contains("after failure context"));
        assertTrue(context.contains("BUILD FAILED"));
        assertTrue(context.contains("last diagnostic line"));
    }

    @Test
    public void failureContextUsesChineseLabelsWhenRequested() {
        String context = ProjectBuildFailureContextPolicy.copyText(
                new BuildJobRecord(1, 1, "failed", "embedded_runtime_finished", "/tmp/build.log", null, "Cannot find symbol", 0, 0, 0),
                "",
                true);

        assertTrue(context.contains("任务 #1"));
        assertTrue(context.contains("状态"));
        assertTrue(context.contains("错误摘要"));
        assertTrue(context.contains("没有捕获到构建日志。"));
    }

    @Test
    public void fullFailureContextIncludesMessagesAiRequestsResponsesAndHermesRecordsForSameJob() {
        BuildJobRecord job = new BuildJobRecord(
                20, 1, "failed", "coding_failed", "", null,
                "Task 637/2 agent failed", 0, 1000, 9000);
        List<ChatMessage> messages = Arrays.asList(
                new ChatMessage(1, 1, "assistant", "正在执行计划任务批次：Java source wiring", 2000, 20L),
                new ChatMessage(2, 1, "assistant", "other job chatter", 2500, 21L),
                new ChatMessage(3, 1, "user", "unlinked user note", 3000, null));
        List<AiConversationRecord> records = Arrays.asList(
                new AiConversationRecord(
                        10,
                        1,
                        "cloud",
                        "云端 AI · 文件操作生成 #1",
                        "Request:\nApproved engineering plan\nCurrent source tree",
                        "Response:\n{\"summary\":\"draft\",\"operations\":[]}",
                        "success",
                        "provider=openai\nmodel=gpt-5\ntaskId=637",
                        20L,
                        4000),
                new AiConversationRecord(
                        11,
                        1,
                        "hermes",
                        "Hermes · 文件操作审查 #1",
                        "Generated operations JSON",
                        "decision: rewrite\nsummary: missing toolbar",
                        "rewrite",
                        "provider=deterministic-preflight\ntaskId=637",
                        20L,
                        5000),
                new AiConversationRecord(
                        12,
                        1,
                        "cloud",
                        "Other job",
                        "Other request",
                        "Other response",
                        "success",
                        "",
                        21L,
                        6000));

        String context = ProjectBuildFailureContextPolicy.copyText(job, "build log tail", messages, records, true);

        assertTrue(ProjectBuildFailureContextPolicy.canCopyFailureContext(job, messages, records));
        assertTrue(context.contains("失败上下文"));
        assertTrue(context.contains("正在执行计划任务批次：Java source wiring"));
        assertTrue(context.contains("云端 AI · 文件操作生成 #1"));
        assertTrue(context.contains("Approved engineering plan"));
        assertTrue(context.contains("{\"summary\":\"draft\""));
        assertTrue(context.contains("Hermes · 文件操作审查 #1"));
        assertTrue(context.contains("decision: rewrite"));
        assertTrue(context.contains("build log tail"));
        assertFalse(context.contains("other job chatter"));
        assertFalse(context.contains("Other request"));
        assertFalse(context.contains("unlinked user note"));
    }

    @Test
    public void failureContextIsBoundedForVeryLargeLogs() {
        String logs = repeat("line before\n", 4000)
                + "error: resource color/missing not found\n"
                + repeat("line after\n", 4000);

        String context = ProjectBuildFailureContextPolicy.copyText(
                new BuildJobRecord(1, 1, "failed", "embedded_runtime_finished", "/tmp/build.log", null, "", 0, 0, 0),
                logs);

        assertTrue(context.contains("error: resource color/missing not found"));
        assertTrue(context.length() <= 18050);
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
