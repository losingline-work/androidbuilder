package com.androidbuilder.ui;

import com.androidbuilder.model.ProjectLogEntry;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectLogQueryPolicyTest {
    @Test
    public void emptyQueryReturnsNewestEntriesFirst() {
        ProjectLogEntry older = entry(ProjectLogEntry.Kind.MESSAGE, 1, 1000, "USER", "first prompt");
        ProjectLogEntry newer = entry(ProjectLogEntry.Kind.AI, 2, 3000, "Cloud AI", "model returned a plan");
        ProjectLogEntry middle = entry(ProjectLogEntry.Kind.MESSAGE, 3, 2000, "ASSISTANT", "middle conversation");

        List<ProjectLogEntry> results = ProjectLogQueryPolicy.filter(Arrays.asList(older, newer, middle), "");

        assertEquals(2, results.get(0).sourceId);
        assertEquals(3, results.get(1).sourceId);
        assertEquals(1, results.get(2).sourceId);
    }

    @Test
    public void queryMatchesCloudAndLocalAiConversationContent() {
        ProjectLogEntry cloud = entry(ProjectLogEntry.Kind.AI, 1, 1000, "Cloud AI · task operations", "返回 replace app/src/main/java/MainActivity.java");
        ProjectLogEntry local = entry(ProjectLogEntry.Kind.AI, 2, 2000, "Local AI · source guard preflight", "本地守卫建议补齐 TransactionAdapter.id 字段");
        ProjectLogEntry message = entry(ProjectLogEntry.Kind.MESSAGE, 3, 3000, "USER", "我要增加分类页面");

        assertEquals(1, ProjectLogQueryPolicy.filter(Arrays.asList(cloud, local, message), "replace mainactivity").get(0).sourceId);
        assertEquals(2, ProjectLogQueryPolicy.filter(Arrays.asList(cloud, local, message), "本地守卫 transactionadapter").get(0).sourceId);
        assertEquals(3, ProjectLogQueryPolicy.filter(Arrays.asList(cloud, local, message), "分类页面").get(0).sourceId);
    }

    @Test
    public void aiConversationWinsTieAgainstContextMessages() {
        ProjectLogEntry message = entry(ProjectLogEntry.Kind.MESSAGE, 1, 1000, "ASSISTANT", "status chatter");
        ProjectLogEntry ai = entry(ProjectLogEntry.Kind.AI, 2, 1000, "Local AI", "guard response");

        List<ProjectLogEntry> results = ProjectLogQueryPolicy.filter(Arrays.asList(message, ai), "");

        assertEquals(ProjectLogEntry.Kind.AI, results.get(0).kind);
    }

    @Test
    public void previewKeepsResultRowsCompact() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            longText.append("line ").append(i).append('\n');
        }

        String preview = ProjectLogQueryPolicy.preview(longText.toString(), 60);

        assertTrue(preview.length() <= 75);
        assertTrue(preview.endsWith("..."));
        assertFalse(preview.contains("\n"));
    }

    private static ProjectLogEntry entry(ProjectLogEntry.Kind kind, long id, long time, String title, String body) {
        return new ProjectLogEntry(kind, id, time, time, title, "meta", body, body, "status");
    }
}
