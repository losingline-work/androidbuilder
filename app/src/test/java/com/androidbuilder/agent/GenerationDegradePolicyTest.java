package com.androidbuilder.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GenerationDegradePolicyTest {

    @Test
    public void healthyMarchStaysAtLevelZero() {
        assertEquals(0, GenerationDegradePolicy.level(new GenerationDegradePolicy.Counters()));
        assertEquals(0, GenerationDegradePolicy.level(null));
    }

    @Test
    public void oneHardFailureReachesLevelOne() {
        GenerationDegradePolicy.Counters c = new GenerationDegradePolicy.Counters();
        c.parseFailures = 1; // weighted x2 = 2 == LEVEL1_THRESHOLD
        assertEquals(1, GenerationDegradePolicy.level(c));
    }

    @Test
    public void accumulatedFailuresReachLevelTwo() {
        GenerationDegradePolicy.Counters c = new GenerationDegradePolicy.Counters();
        c.parseFailures = 1;         // 2
        c.streamAborts = 1;          // 2
        c.salvaged = 1;              // 1  => 5 == LEVEL2_THRESHOLD
        assertEquals(2, GenerationDegradePolicy.level(c));
    }

    @Test
    public void softSignalsAloneDegradeMoreSlowly() {
        GenerationDegradePolicy.Counters c = new GenerationDegradePolicy.Counters();
        c.salvaged = 1;              // 1 -> still level 0
        assertEquals(0, GenerationDegradePolicy.level(c));
        c.preflightFindings = 1;     // 2 -> level 1
        assertEquals(1, GenerationDegradePolicy.level(c));
    }

    @Test
    public void levelIsMonotonicInFailures() {
        GenerationDegradePolicy.Counters c = new GenerationDegradePolicy.Counters();
        int prev = GenerationDegradePolicy.level(c);
        for (int i = 0; i < 5; i++) {
            c.parseFailures++;
            int now = GenerationDegradePolicy.level(c);
            assertTrue("level must not decrease as failures grow", now >= prev);
            prev = now;
        }
    }

    @Test
    public void batchWeightsShrinkWithLevel() {
        assertEquals(ManifestBatchPolicy.MAX_BATCH_WEIGHT, GenerationDegradePolicy.maxBatchWeight(0));
        assertEquals(6, GenerationDegradePolicy.maxBatchWeight(1));
        assertEquals(3, GenerationDegradePolicy.maxBatchWeight(2));
        assertEquals(ManifestBatchPolicy.SINGLE_BATCH_THRESHOLD, GenerationDegradePolicy.singleBatchThreshold(0));
        assertEquals(1, GenerationDegradePolicy.singleBatchThreshold(2));
    }

    @Test
    public void temperatureDropsToGreedyOnceDegrading() {
        assertEquals(0.2, GenerationDegradePolicy.temperature(0, 0.2), 0.0001);
        assertEquals(0.0, GenerationDegradePolicy.temperature(1, 0.2), 0.0001);
        assertEquals(0.0, GenerationDegradePolicy.temperature(2, 0.2), 0.0001);
    }

    @Test
    public void fullTextLimitShrinksWithLevel() {
        assertEquals(14000, GenerationDegradePolicy.fullTextLimit(0, 14000));
        assertEquals(10000, GenerationDegradePolicy.fullTextLimit(1, 14000));
        assertEquals(8000, GenerationDegradePolicy.fullTextLimit(2, 14000));
    }
}
