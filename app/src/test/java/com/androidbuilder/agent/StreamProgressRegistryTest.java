package com.androidbuilder.agent;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StreamProgressRegistryTest {
    @Test
    public void preservesPhaseWhenCountsArrive() {
        StreamProgressRegistry registry = new StreamProgressRegistry();

        registry.updatePhase("task:7", "coding", 2, 5);
        registry.updateCounts("task:7", 1200, 0);

        StreamProgressRegistry.StreamProgress progress = registry.snapshot().get("task:7");
        assertEquals("coding", progress.phase);
        assertEquals(2, progress.attempt);
        assertEquals(5, progress.maxAttempts);
        assertEquals(1200, progress.answerChars);
    }

    @Test
    public void clearsByTagAndIgnoresEmptyTags() {
        StreamProgressRegistry registry = new StreamProgressRegistry();

        registry.updateCounts(" ", 100, 0);
        registry.updateCounts("task:1", 100, 0);
        registry.clear("task:1");

        Map<String, StreamProgressRegistry.StreamProgress> snapshot = registry.snapshot();
        assertTrue(snapshot.isEmpty());
    }

    @Test
    public void resetsCountsWhenPhaseChanges() {
        StreamProgressRegistry registry = new StreamProgressRegistry();

        registry.updatePhase("task:7", "coding", 1, 5);
        registry.updateCounts("task:7", 1200, 0);
        registry.updatePhase("task:7", "reviewing", 1, 5);

        StreamProgressRegistry.StreamProgress progress = registry.snapshot().get("task:7");
        assertEquals("reviewing", progress.phase);
        assertEquals(0, progress.answerChars);
    }

    @Test
    public void batchProgressSurvivesCountUpdatesAndResetsOnPhaseChange() {
        StreamProgressRegistry registry = new StreamProgressRegistry();

        registry.updatePhase("task:7", "coding", 1, 5);
        registry.updateBatch("task:7", 3, 8);
        registry.updateCounts("task:7", 800, 0);

        StreamProgressRegistry.StreamProgress coding = registry.snapshot().get("task:7");
        assertEquals(3, coding.batchDone);
        assertEquals(8, coding.batchTotal);
        assertEquals(800, coding.answerChars);

        // A new batch in the same phase bumps the counter and resets the streamed char count.
        registry.updatePhase("task:7", "coding", 1, 5);
        registry.updateBatch("task:7", 4, 8);
        StreamProgressRegistry.StreamProgress nextBatch = registry.snapshot().get("task:7");
        assertEquals(4, nextBatch.batchDone);

        // Leaving the coding phase clears batch progress.
        registry.updatePhase("task:7", "reviewing", 1, 5);
        StreamProgressRegistry.StreamProgress reviewing = registry.snapshot().get("task:7");
        assertEquals(0, reviewing.batchDone);
        assertEquals(0, reviewing.batchTotal);
    }

    @Test
    public void snapshotIsStableCopy() {
        StreamProgressRegistry registry = new StreamProgressRegistry();
        registry.updateCounts("task:1", 100, 0);

        Map<String, StreamProgressRegistry.StreamProgress> snapshot = registry.snapshot();
        registry.clear("task:1");

        assertFalse(snapshot.isEmpty());
    }
}
