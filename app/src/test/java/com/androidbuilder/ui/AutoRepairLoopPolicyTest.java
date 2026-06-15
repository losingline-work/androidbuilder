package com.androidbuilder.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AutoRepairLoopPolicyTest {
    @Test
    public void nonTerminalStatusIsInProgress() {
        assertEquals(AutoRepairLoopPolicy.Decision.IN_PROGRESS,
                AutoRepairLoopPolicy.decide("building", true, 0, 6));
        assertEquals(AutoRepairLoopPolicy.Decision.IN_PROGRESS,
                AutoRepairLoopPolicy.decide("generating", true, 0, 6));
    }

    @Test
    public void successResetsAndStops() {
        assertEquals(AutoRepairLoopPolicy.Decision.SUCCEEDED,
                AutoRepairLoopPolicy.decide("success", false, 3, 6));
    }

    @Test
    public void repairableFailureWithRoundsLeftAutoRepairs() {
        assertEquals(AutoRepairLoopPolicy.Decision.AUTO_REPAIR,
                AutoRepairLoopPolicy.decide("failed", true, 0, 6));
        assertEquals(AutoRepairLoopPolicy.Decision.AUTO_REPAIR,
                AutoRepairLoopPolicy.decide("failed", true, 5, 6));
    }

    @Test
    public void repairableFailureAtCapGivesUp() {
        assertEquals(AutoRepairLoopPolicy.Decision.GIVE_UP,
                AutoRepairLoopPolicy.decide("failed", true, 6, 6));
    }

    @Test
    public void nonRepairableFailureGivesUpImmediately() {
        assertEquals(AutoRepairLoopPolicy.Decision.GIVE_UP,
                AutoRepairLoopPolicy.decide("failed", false, 0, 6));
    }
}
