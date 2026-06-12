package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RetryContextPolicyTest {
    @Test
    public void mergeCapsAdditionalRetrySignalBlocksAtTwo() {
        String merged = RetryContextPolicy.merge("base failure", "first");
        merged = RetryContextPolicy.merge(merged, "second");
        merged = RetryContextPolicy.merge(merged, "third");

        assertTrue(merged.contains("base failure"));
        assertFalse(merged.contains("first"));
        assertTrue(merged.contains("second"));
        assertTrue(merged.contains("third"));
    }

    @Test
    public void mergeDeduplicatesAdditionalRetrySignal() {
        String merged = RetryContextPolicy.merge("base failure", "same");
        merged = RetryContextPolicy.merge(merged, "same");

        assertTrue(merged.contains("same"));
        assertFalse(merged.contains("same\n\nAdditional retry signal:\nsame"));
    }
}
