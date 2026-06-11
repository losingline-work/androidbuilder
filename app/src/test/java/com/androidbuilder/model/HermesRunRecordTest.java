package com.androidbuilder.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HermesRunRecordTest {
    @Test
    public void executionRunRecordCleansNullableTextFields() {
        HermesExecutionRunRecord record = new HermesExecutionRunRecord(
                1, 2, 3, null, null, 2, null, 0, 0);

        assertEquals("", record.status);
        assertEquals("", record.mode);
        assertEquals("", record.baseSourceHash);
    }

    @Test
    public void agentRunRecordCleansNullableTextFields() {
        HermesAgentRunRecord record = new HermesAgentRunRecord(
                1, 2, 3, 4, 1, null, null, null, null, null, null, null, 0, 0);

        assertEquals("", record.status);
        assertEquals("", record.workDir);
        assertEquals("", record.baseSourceHash);
        assertEquals("", record.mergedSourceHash);
        assertEquals("", record.lockedPathsJson);
        assertEquals("", record.summary);
        assertEquals("", record.errorSummary);
    }

    @Test
    public void recordTextFieldsAreTrimmed() {
        HermesExecutionRunRecord execution = new HermesExecutionRunRecord(
                1, 2, 3, " running ", " parallel ", 2, " abc ", 0, 0);
        HermesAgentRunRecord agent = new HermesAgentRunRecord(
                1, 2, 3, 4, 1, " pending ", " /tmp/run ", " base ", " merged ",
                " [] ", " ok ", " none ", 0, 0);

        assertEquals("running", execution.status);
        assertEquals("parallel", execution.mode);
        assertEquals("abc", execution.baseSourceHash);
        assertEquals("pending", agent.status);
        assertEquals("/tmp/run", agent.workDir);
        assertEquals("base", agent.baseSourceHash);
        assertEquals("merged", agent.mergedSourceHash);
        assertEquals("[]", agent.lockedPathsJson);
        assertEquals("ok", agent.summary);
        assertEquals("none", agent.errorSummary);
    }
}
