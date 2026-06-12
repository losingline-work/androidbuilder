package com.androidbuilder.agent;

import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.ProjectTaskRecord;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class SequentialFailureGateTest {
    @Test
    public void exhaustedFailedTaskPausesLaterTasksWithoutExplicitDependencies() {
        ProjectTaskRecord failed = task(3, "Resources", "Do resources.", "failed");
        ProjectTaskRecord java = task(4, "Java", "Do java.", "pending");
        ProjectTaskRecord stats = task(5, "Stats", "Do stats.", "pending");
        Map<Long, Integer> counts = exhausted(failed.id);

        List<ProjectTaskRecord> filtered = SequentialFailureGate.filter(
                Arrays.asList(failed, java, stats),
                Arrays.asList(java, stats),
                counts,
                Collections.emptySet());

        assertEquals(0, filtered.size());
    }

    @Test
    public void explicitSatisfiedDependenciesCanProceedPastExhaustedFailure() throws Exception {
        ProjectTaskRecord data = task(1, "Data", contract("{\"produces\":[\"data\"]}"), "done");
        ProjectTaskRecord failed = task(3, "Resources", "Do resources.", "failed");
        ProjectTaskRecord independent = task(4, "Independent", contract("{\"dependsOn\":[\"data\"]}"), "pending");
        Map<Long, Integer> counts = exhausted(failed.id);

        List<ProjectTaskRecord> filtered = SequentialFailureGate.filter(
                Arrays.asList(data, failed, independent),
                Collections.singletonList(independent),
                counts,
                new HashSet<>(Collections.singletonList("data")));

        assertEquals(1, filtered.size());
        assertEquals(independent.id, filtered.get(0).id);
    }

    @Test
    public void failedTaskWithRetryBudgetDoesNotPauseLaterTasks() {
        ProjectTaskRecord failed = task(3, "Resources", "Do resources.", "failed");
        ProjectTaskRecord java = task(4, "Java", "Do java.", "pending");
        Map<Long, Integer> counts = new HashMap<>();
        counts.put(failed.id, HermesDispatchBudget.MAX_DISPATCHES_PER_EXECUTION - 1);

        List<ProjectTaskRecord> filtered = SequentialFailureGate.filter(
                Arrays.asList(failed, java),
                Collections.singletonList(java),
                counts,
                Collections.emptySet());

        assertEquals(1, filtered.size());
        assertEquals(java.id, filtered.get(0).id);
    }

    @Test
    public void noFailedTaskLeavesAllowedTasksUnchanged() {
        ProjectTaskRecord first = task(1, "One", "Do one.", "done");
        ProjectTaskRecord second = task(2, "Two", "Do two.", "pending");

        List<ProjectTaskRecord> filtered = SequentialFailureGate.filter(
                Arrays.asList(first, second),
                Collections.singletonList(second),
                Collections.emptyMap(),
                Collections.emptySet());

        assertEquals(1, filtered.size());
        assertEquals(second.id, filtered.get(0).id);
    }

    @Test
    public void doneProducesForTasksUsesDoneTaskContracts() throws Exception {
        ProjectTaskRecord data = task(1, "Data", contract("{\"produces\":[\"Data Layer\"]}"), "done");
        ProjectTaskRecord pending = task(2, "UI", contract("{\"produces\":[\"UI\"]}"), "pending");

        assertEquals(new HashSet<>(Collections.singletonList("data layer")),
                SequentialFailureGate.doneProducesForTasks(Arrays.asList(data, pending)));
    }

    private static Map<Long, Integer> exhausted(long taskId) {
        Map<Long, Integer> counts = new HashMap<>();
        counts.put(taskId, HermesDispatchBudget.MAX_DISPATCHES_PER_EXECUTION);
        return counts;
    }

    private static ProjectTaskRecord task(long id, String title, String instruction, String status) {
        return new ProjectTaskRecord(id, 1, (int) id, title, instruction, status, "", 0, 0, 0, 0);
    }

    private static String contract(String json) throws Exception {
        HermesTaskContract contract = HermesTaskContractCodec.fromJson(new JSONObject(json));
        return HermesTaskContractCodec.appendToInstruction("Do task.", contract);
    }
}
