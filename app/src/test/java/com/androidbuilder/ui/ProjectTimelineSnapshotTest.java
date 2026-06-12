package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ChatMessage;
import com.androidbuilder.model.ProjectPlanRecord;
import com.androidbuilder.model.ProjectTaskRecord;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ProjectTimelineSnapshotTest {
    @Test
    public void anchorsTaskGroupByTaskCreatedAtAmongMessages() {
        ProjectPlanRecord plan = new ProjectPlanRecord(1, 1, "# plan", "planned", 1L, 0, 0);
        // Tasks created at t=300 sit between the plan message (t=200) and a later tweak (t=400).
        ProjectTaskRecord task = new ProjectTaskRecord(1, 1, 0, "Task", "", "pending", "", 300, 300, 0, 0);

        ProjectTimelineSnapshot snapshot = ProjectTimelineSnapshot.create(
                Arrays.asList(
                        new ChatMessage(1, 1, "user", "Build me an app", 100, null),
                        new ChatMessage(2, 1, "assistant", "# Engineering Plan\n- screen", 200, null),
                        new ChatMessage(3, 1, "user", "Also add a settings page", 400, null)),
                false,
                plan,
                Collections.singletonList(task),
                null,
                id -> null);

        assertEquals(4, snapshot.size());
        assertEquals(ProjectTimelinePolicy.Kind.MESSAGE, snapshot.entryAt(0).kind);
        assertEquals(ProjectTimelinePolicy.Kind.PLAN_CARD, snapshot.entryAt(1).kind);
        assertEquals(ProjectTimelinePolicy.Kind.TASK_GROUP, snapshot.entryAt(2).kind);
        assertEquals(ProjectTimelinePolicy.Kind.MESSAGE, snapshot.entryAt(3).kind);
        assertEquals(2, snapshot.entryAt(3).sourceIndex);
    }

    @Test
    public void resolvesEachLinkedBuildJobOnlyOnce() {
        BuildJobRecord job = new BuildJobRecord(7, 1, "success", "finished", "/tmp/build.log", "/tmp/app.apk", null, 0, 100, 200);
        AtomicInteger lookups = new AtomicInteger();

        ProjectTimelineSnapshot snapshot = ProjectTimelineSnapshot.create(
                Arrays.asList(
                        new ChatMessage(1, 1, "assistant", "Build started", 100, 7L),
                        new ChatMessage(2, 1, "assistant", "Build complete", 200, 7L)),
                false,
                null,
                Collections.emptyList(),
                job,
                id -> {
                    lookups.incrementAndGet();
                    return job;
                });

        assertEquals(1, lookups.get());
        ProjectTimelinePolicy.Entry buildLogEntry = snapshot.entryAt(snapshot.size() - 1);
        assertEquals(ProjectTimelinePolicy.Kind.BUILD_LOG, buildLogEntry.kind);
        assertNotNull(snapshot.buildLogJob(buildLogEntry));
        assertNotNull(snapshot.jobForMessage(new ChatMessage(3, 1, "assistant", "copy", 300, 7L)));
        assertEquals(1, lookups.get());
    }

    @Test
    public void collapsesRepairStatusMessagesIntoSingleRepairRecord() {
        BuildJobRecord job = new BuildJobRecord(7, 1, "generated", "repairing_build_failure", "/tmp/build.log", null, null, 0, 100, 300);

        ProjectTimelineSnapshot snapshot = ProjectTimelineSnapshot.create(
                Arrays.asList(
                        new ChatMessage(1, 1, "assistant", "正在根据构建日志修复当前源码。", 100, 7L),
                        new ChatMessage(2, 1, "assistant", "已完成构建修复：修复 DAO 字段。", 300, 7L)),
                false,
                null,
                Collections.emptyList(),
                job,
                id -> job);

        assertEquals(1, snapshot.size());
        assertEquals(ProjectTimelinePolicy.Kind.BUILD_LOG, snapshot.entryAt(0).kind);
        assertEquals(1, snapshot.entryAt(0).sourceIndex);
        assertNotNull(snapshot.buildLogJob(snapshot.entryAt(0)));
    }

    @Test
    public void foldsStandalonePlanExecutionFailureMessageIntoFailedBuildLogCard() {
        String error = "Merged 0 Hermes agent result(s), failed 1.\n"
                + "Task 637/2 agent failed: Generated source policy blocked missing XML id: R.id.toolbar in BaseActivity.java.";
        BuildJobRecord job = new BuildJobRecord(7, 1, "failed", "coding_failed", "/tmp/build.log", null, error, 0, 100, 300);

        ProjectTimelineSnapshot snapshot = ProjectTimelineSnapshot.create(
                Arrays.asList(
                        new ChatMessage(1, 1, "assistant", "并行执行下一批：Java source wiring", 100, 7L),
                        new ChatMessage(2, 1, "assistant", "执行计划失败：" + error, 300, null)),
                false,
                null,
                Collections.emptyList(),
                job,
                id -> job);

        assertEquals(1, snapshot.size());
        assertEquals(ProjectTimelinePolicy.Kind.BUILD_LOG, snapshot.entryAt(0).kind);
        assertEquals(0, snapshot.entryAt(0).sourceIndex);
        assertNotNull(snapshot.buildLogJob(snapshot.entryAt(0)));
    }

    @Test
    public void showsLatestFailedPlanJobEvenWithoutLinkedMessages() {
        BuildJobRecord job = new BuildJobRecord(7, 1, "failed", "coding_failed", "/tmp/build.log", null, "Task failed before dispatch", 0, 100, 300);

        ProjectTimelineSnapshot snapshot = ProjectTimelineSnapshot.create(
                Collections.singletonList(new ChatMessage(1, 1, "user", "执行计划", 100, null)),
                false,
                null,
                Collections.emptyList(),
                job,
                id -> null);

        assertEquals(2, snapshot.size());
        assertEquals(ProjectTimelinePolicy.Kind.MESSAGE, snapshot.entryAt(0).kind);
        assertEquals(ProjectTimelinePolicy.Kind.BUILD_LOG, snapshot.entryAt(1).kind);
        assertEquals(-1, snapshot.entryAt(1).sourceIndex);
        assertNotNull(snapshot.buildLogJob(snapshot.entryAt(1)));
    }
}
