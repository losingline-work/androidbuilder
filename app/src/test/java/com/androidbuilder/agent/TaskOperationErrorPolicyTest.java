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
    public void retriesStaleEditAnchorsAsFullWrites() {
        // project-134 unlock: an unanchored edit must be rewriteable so the loop retries with a full write
        // instead of rethrowing immediately and freezing on the same diagnostics.
        assertTrue(TaskOperationErrorPolicy.shouldRequestRewrite(new IllegalArgumentException(
                "edit target not found in app/src/main/java/X.java (the file may have changed); resend the full file with action write")));
        assertTrue(TaskOperationErrorPolicy.shouldRequestRewrite(new IllegalArgumentException(
                "edit target is ambiguous in app/src/main/java/X.java (2 matches); include more surrounding context in find, or resend the full file")));
        assertTrue(TaskOperationErrorPolicy.shouldRequestRewrite(new IllegalArgumentException(
                "edit operation has empty find text in app/src/main/java/X.java; resend the full file with action write")));
    }

    @Test
    public void keepsUnrelatedErrorsNonRewriteable() {
        assertFalse(TaskOperationErrorPolicy.shouldRequestRewrite(new IllegalArgumentException("Project not found.")));
    }
}
