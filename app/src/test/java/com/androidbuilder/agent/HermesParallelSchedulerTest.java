package com.androidbuilder.agent;

import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.HermesAgentRunRecord;
import com.androidbuilder.model.ProjectTaskRecord;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class HermesParallelSchedulerTest {
    @Test
    public void schedulerBatchesIndependentDisjointTasks() throws Exception {
        ProjectTaskRecord dao = task(1, 0, "DAO", contract("{\"allowedPaths\":[\"app/src/main/java/com/example/RecordDao.java\"],\"produces\":[\"data\"]}"), "pending");
        ProjectTaskRecord layout = task(2, 1, "Layout", contract("{\"allowedPaths\":[\"app/src/main/res/layout/activity_main.xml\"],\"produces\":[\"ui\"]}"), "pending");

        HermesParallelBatch batch = HermesParallelScheduler.nextBatch(Arrays.asList(dao, layout), Collections.emptyList(), 2);

        assertEquals(2, batch.tasks.size());
        assertEquals("", batch.exclusiveReason);
    }

    @Test
    public void schedulerDoesNotBatchOverlappingLocks() throws Exception {
        ProjectTaskRecord left = task(1, 0, "Strings A", contract("{\"allowedPaths\":[\"app/src/main/res/values/strings.xml\"]}"), "pending");
        ProjectTaskRecord right = task(2, 1, "Strings B", contract("{\"allowedPaths\":[\"app/src/main/res/values/strings.xml\"]}"), "pending");

        HermesParallelBatch batch = HermesParallelScheduler.nextBatch(Arrays.asList(left, right), Collections.emptyList(), 2);

        assertEquals(1, batch.tasks.size());
        assertEquals(left.id, batch.tasks.get(0).id);
    }

    @Test
    public void schedulerRunsGradleBarrierAsSingleTask() throws Exception {
        ProjectTaskRecord gradle = task(1, 0, "Update Gradle", "Change app/build.gradle", "pending");
        ProjectTaskRecord layout = task(2, 1, "Layout", contract("{\"allowedPaths\":[\"app/src/main/res/layout/activity_main.xml\"]}"), "pending");

        HermesParallelBatch batch = HermesParallelScheduler.nextBatch(Arrays.asList(gradle, layout), Collections.emptyList(), 2);

        assertEquals(1, batch.tasks.size());
        assertEquals(gradle.id, batch.tasks.get(0).id);
        assertEquals("exclusive_barrier", batch.exclusiveReason);
    }

    @Test
    public void schedulerSkipsTasksConflictingWithActiveRunLocks() throws Exception {
        ProjectTaskRecord strings = task(1, 0, "Strings", contract("{\"allowedPaths\":[\"app/src/main/res/values/strings.xml\"]}"), "pending");
        ProjectTaskRecord layout = task(2, 1, "Layout", contract("{\"allowedPaths\":[\"app/src/main/res/layout/activity_main.xml\"]}"), "pending");

        HermesParallelBatch batch = HermesParallelScheduler.nextBatch(
                Arrays.asList(strings, layout),
                Collections.singletonList(activeRun("running", "[\"app/src/main/res/values/strings.xml\"]")),
                2);

        assertEquals(1, batch.tasks.size());
        assertEquals(layout.id, batch.tasks.get(0).id);
    }

    @Test
    public void schedulerReturnsFailedTaskAsSinglePriorityTask() throws Exception {
        ProjectTaskRecord pending = task(1, 0, "Pending", contract("{\"allowedPaths\":[\"app/src/main/res/layout/activity_main.xml\"]}"), "pending");
        ProjectTaskRecord failed = task(2, 1, "Failed", contract("{\"allowedPaths\":[\"app/src/main/java/com/example/RecordDao.java\"]}"), "failed");

        HermesParallelBatch batch = HermesParallelScheduler.nextBatch(Arrays.asList(pending, failed), Collections.emptyList(), 2);

        assertEquals(1, batch.tasks.size());
        assertEquals(failed.id, batch.tasks.get(0).id);
        assertEquals("failed_retry", batch.exclusiveReason);
    }

    @Test
    public void schedulerUsesSerialModeWhenMaxParallelIsOne() throws Exception {
        ProjectTaskRecord dao = task(1, 0, "DAO", contract("{\"allowedPaths\":[\"app/src/main/java/com/example/RecordDao.java\"]}"), "pending");
        ProjectTaskRecord layout = task(2, 1, "Layout", contract("{\"allowedPaths\":[\"app/src/main/res/layout/activity_main.xml\"]}"), "pending");

        HermesParallelBatch batch = HermesParallelScheduler.nextBatch(Arrays.asList(dao, layout), Collections.emptyList(), 1);

        assertEquals(1, batch.tasks.size());
        assertEquals(dao.id, batch.tasks.get(0).id);
        assertEquals("serial", batch.exclusiveReason);
    }

    private static ProjectTaskRecord task(long id, int order, String title, String instruction, String status) {
        return new ProjectTaskRecord(id, 1, order, title, instruction, status, "", 0, 0, 0, 0);
    }

    private static String contract(String json) throws Exception {
        HermesTaskContract contract = HermesTaskContractCodec.fromJson(new JSONObject(json));
        return HermesTaskContractCodec.appendToInstruction("Do task.", contract);
    }

    private static HermesAgentRunRecord activeRun(String status, String lockedPathsJson) {
        return new HermesAgentRunRecord(
                1, 1, 1, 0, 0, status, "", "", "", lockedPathsJson, "", "", 0, 0);
    }
}
