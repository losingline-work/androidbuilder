package com.androidbuilder.agent;

import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BlockedTaskPolicyTest {
    @Test
    public void shouldExpandScopeOnlyForFirstBlockedResponse() {
        TaskOperations blocked = new TaskOperations(
                "blocked",
                Collections.emptyList(),
                true,
                "layouts missing",
                "create activity_main.xml");
        TaskOperations regular = new TaskOperations("ok", Collections.emptyList());

        assertTrue(BlockedTaskPolicy.shouldExpandScope(blocked, false));
        assertFalse(BlockedTaskPolicy.shouldExpandScope(blocked, true));
        assertFalse(BlockedTaskPolicy.shouldExpandScope(regular, false));
    }

    @Test
    public void scopeExpandedInstructionIncludesPrerequisitesAndOriginalTask() {
        TaskOperations blocked = new TaskOperations(
                "blocked",
                Collections.emptyList(),
                true,
                "layouts missing",
                "create manifest/values/layouts");

        String instruction = BlockedTaskPolicy.scopeExpandedInstruction("Wire MainActivity.", blocked);

        assertTrue(instruction.contains("The original task boundary is lifted by the orchestrator for this retry."));
        assertTrue(instruction.contains("Prerequisites: create manifest/values/layouts"));
        assertTrue(instruction.contains("Wire MainActivity."));
        assertTrue(instruction.contains("resource id consistent"));
    }

    @Test
    public void blockedSummaryUsesReason() {
        TaskOperations blocked = new TaskOperations(
                "",
                Collections.emptyList(),
                true,
                "layouts missing",
                "");

        assertEquals("blocked: layouts missing", BlockedTaskPolicy.blockedSummary(blocked));
    }
}
