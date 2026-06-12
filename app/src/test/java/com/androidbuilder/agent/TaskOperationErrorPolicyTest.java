package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TaskOperationErrorPolicyTest {
    @Test
    public void retriesEmptyOperationListsFromModel() {
        assertTrue(TaskOperationErrorPolicy.shouldRequestRewrite(new IllegalArgumentException("Task operation list is empty.")));
    }

    @Test
    public void retriesInvalidGeneratedOperationFormats() {
        assertTrue(TaskOperationErrorPolicy.shouldRequestRewrite(new IllegalArgumentException("Task operation response did not contain a JSON object.")));
        assertTrue(TaskOperationErrorPolicy.shouldRequestRewrite(new IllegalArgumentException("Task operation response JSON could not be parsed: Unterminated object")));
        assertTrue(TaskOperationErrorPolicy.shouldRequestRewrite(new IllegalArgumentException("Unsupported file operation action: append")));
        assertTrue(TaskOperationErrorPolicy.shouldRequestRewrite(new IllegalArgumentException("Unsafe generated file path: ../settings.gradle")));
    }

    @Test
    public void keepsUnrelatedErrorsNonRewriteable() {
        assertFalse(TaskOperationErrorPolicy.shouldRequestRewrite(new IllegalArgumentException("Project not found.")));
    }
}
