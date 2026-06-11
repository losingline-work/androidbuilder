package com.androidbuilder.agent;

import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.ProjectTaskRecord;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HermesTaskGraphTest {
    @Test
    public void taskGraphMarksTaskReadyWhenProducedDependenciesAreDone() throws Exception {
        ProjectTaskRecord data = task(1, 0, "Data", contract("{\"produces\":[\"data\"]}"), "done");
        ProjectTaskRecord ui = task(2, 1, "UI", contract("{\"dependsOn\":[\"data\"],\"produces\":[\"ui\"]}"), "pending");

        HermesTaskGraph graph = HermesTaskGraph.fromTasks(Arrays.asList(data, ui));

        assertTrue(graph.isReady(ui));
        assertFalse(graph.isReady(data));
    }

    @Test
    public void taskGraphDoesNotMarkPendingTaskReadyUntilDependenciesAreProducedByDoneTasks() throws Exception {
        ProjectTaskRecord data = task(1, 0, "Data", contract("{\"produces\":[\"data\"]}"), "pending");
        ProjectTaskRecord ui = task(2, 1, "UI", contract("{\"dependsOn\":[\"data\"]}"), "pending");

        HermesTaskGraph graph = HermesTaskGraph.fromTasks(Arrays.asList(data, ui));

        assertFalse(graph.isReady(ui));
    }

    @Test
    public void failedTasksAreReadyBeforePendingTasks() {
        ProjectTaskRecord failed = task(1, 0, "Failed", "Do task.", "failed");
        ProjectTaskRecord pending = task(2, 1, "Pending", "Do task.", "pending");

        HermesTaskGraph graph = HermesTaskGraph.fromTasks(Arrays.asList(pending, failed));

        List<ProjectTaskRecord> ready = graph.readyTasks();
        assertEquals(1, ready.size());
        assertEquals(failed.id, ready.get(0).id);
    }

    private static ProjectTaskRecord task(long id, int order, String title, String instruction, String status) {
        return new ProjectTaskRecord(id, 1, order, title, instruction, status, "", 0, 0, 0, 0);
    }

    private static String contract(String json) throws Exception {
        HermesTaskContract contract = HermesTaskContractCodec.fromJson(new JSONObject(json));
        return HermesTaskContractCodec.appendToInstruction("Do task.", contract);
    }
}
