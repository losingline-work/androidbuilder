package com.androidbuilder.agent;

import com.androidbuilder.model.ProjectTaskRecord;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HermesDispatchBudgetTest {
    @Test
    public void allowsFirstDispatchAndOneRetryOnly() {
        Map<Long, Integer> counts = new HashMap<>();

        assertTrue(HermesDispatchBudget.allows(counts, 7));
        HermesDispatchBudget.markDispatched(counts, 7);
        assertTrue(HermesDispatchBudget.allows(counts, 7));
        HermesDispatchBudget.markDispatched(counts, 7);
        assertFalse(HermesDispatchBudget.allows(counts, 7));
    }

    @Test
    public void keepsDoneAndPendingTasksButDropsExhaustedFailures() {
        Map<Long, Integer> counts = new HashMap<>();
        counts.put(2L, HermesDispatchBudget.MAX_DISPATCHES_PER_EXECUTION);

        List<ProjectTaskRecord> allowed = HermesDispatchBudget.allowedTasks(Arrays.asList(
                task(1, "done"),
                task(2, "failed"),
                task(3, "pending")), counts);

        assertEquals(2, allowed.size());
        assertEquals(1, allowed.get(0).id);
        assertEquals(3, allowed.get(1).id);
    }

    private static ProjectTaskRecord task(long id, String status) {
        return new ProjectTaskRecord(id, 1, (int) id, "Task " + id, "Do task.", status, "", 0, 0, 0, 0);
    }
}
