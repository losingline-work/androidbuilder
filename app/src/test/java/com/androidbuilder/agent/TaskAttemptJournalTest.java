package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TaskAttemptJournalTest {
    @Test
    public void rendersCompactExecutionSummary() {
        TaskAttemptJournal journal = new TaskAttemptJournal();
        journal.recordManifest();
        journal.recordBatchProgress(2, 5);
        journal.recordRewrite();
        journal.recordReview();
        journal.recordAttempt(3, "batch-generation", "batch validation failed");

        String summary = journal.renderSummary("failed");

        assertTrue(summary.contains("attempts=1"));
        assertTrue(summary.contains("manifests=1"));
        assertTrue(summary.contains("batches=2/5"));
        assertTrue(summary.contains("rewrites=1"));
        assertTrue(summary.contains("reviews=1"));
        assertTrue(summary.contains("reason=failed"));
        assertTrue(summary.contains("last=attempt=3 phase=batch-generation reason=batch validation failed"));
    }

    @Test
    public void recordAttemptReturnsStructuredLineWithAttemptPhaseReason() {
        TaskAttemptJournal journal = new TaskAttemptJournal();

        String line = journal.recordAttempt(2, "stream-abort", "stream exceeded 200000 chars");

        assertEquals("attempt=2 phase=stream-abort reason=stream exceeded 200000 chars", line);
    }

    @Test
    public void everyAttemptReasonIsKeptNotOverwritten() {
        // The original bug: lastTermination overwrote earlier reasons. The summary's last= must be
        // the latest, but the attempt count proves all were recorded.
        TaskAttemptJournal journal = new TaskAttemptJournal();
        journal.recordAttempt(1, "generation", "first reason");
        journal.recordAttempt(2, "batch-generation", "second reason");
        journal.recordAttempt(3, "policy-error", "third reason");

        String summary = journal.renderSummary("exhausted");

        assertTrue(summary.contains("attempts=3"));
        assertTrue(summary.contains("last=attempt=3 phase=policy-error reason=third reason"));
    }

    @Test
    public void reasonIsTruncatedToFiveHundredChars() {
        TaskAttemptJournal journal = new TaskAttemptJournal();
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 800; i++) {
            huge.append('x');
        }

        String line = journal.recordAttempt(1, "generation", huge.toString());

        assertTrue(line.length() < 600);
        assertTrue(line.endsWith("…"));
    }
}
