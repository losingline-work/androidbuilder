package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StreamFusePolicyTest {
    @Test
    public void exceedsOnlyAfterConfiguredMaximum() {
        assertFalse(StreamFusePolicy.exceeds(StreamFusePolicy.MAX_STREAM_CHARS));
        assertTrue(StreamFusePolicy.exceeds(StreamFusePolicy.MAX_STREAM_CHARS + 1));
    }

    @Test
    public void fuseErrorIncludesStableLimitForRetryClassification() {
        assertEquals("Streaming response exceeded 200000 chars; generation aborted as runaway.",
                StreamFusePolicy.fuseError(StreamFusePolicy.MAX_STREAM_CHARS + 20));
    }
}
