package com.androidbuilder.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MilestoneMarchPolicyTest {
    @Test
    public void buildInProgress_waits() {
        assertEquals(MilestoneMarchPolicy.Action.WAIT,
                MilestoneMarchPolicy.onBuildResult(AutoRepairLoopPolicy.Decision.IN_PROGRESS, true, false, false));
    }

    @Test
    public void repairableFailure_repairs() {
        assertEquals(MilestoneMarchPolicy.Action.REPAIR,
                MilestoneMarchPolicy.onBuildResult(AutoRepairLoopPolicy.Decision.AUTO_REPAIR, true, false, false));
    }

    @Test
    public void stalledRepairs_escalate() {
        assertEquals(MilestoneMarchPolicy.Action.ESCALATE_REPAIR,
                MilestoneMarchPolicy.onBuildResult(AutoRepairLoopPolicy.Decision.AUTO_REPAIR_ESCALATE, true, false, false));
    }

    @Test
    public void exhaustedRepairs_rollBackAndStop() {
        assertEquals(MilestoneMarchPolicy.Action.ROLLBACK_AND_STOP,
                MilestoneMarchPolicy.onBuildResult(AutoRepairLoopPolicy.Decision.GIVE_UP, true, false, false));
    }

    @Test
    public void green_withMoreToDo_autoMarch_advances() {
        assertEquals(MilestoneMarchPolicy.Action.CHECKPOINT_AND_ADVANCE,
                MilestoneMarchPolicy.onBuildResult(AutoRepairLoopPolicy.Decision.SUCCEEDED, true, false, false));
    }

    @Test
    public void green_whenPaused_pausesAtCheckpoint() {
        assertEquals(MilestoneMarchPolicy.Action.CHECKPOINT_AND_PAUSE,
                MilestoneMarchPolicy.onBuildResult(AutoRepairLoopPolicy.Decision.SUCCEEDED, true, true, false));
    }

    @Test
    public void green_whenSingleStepping_pausesAtCheckpoint() {
        assertEquals(MilestoneMarchPolicy.Action.CHECKPOINT_AND_PAUSE,
                MilestoneMarchPolicy.onBuildResult(AutoRepairLoopPolicy.Decision.SUCCEEDED, true, false, true));
    }

    @Test
    public void green_withNothingLeft_isDone() {
        assertEquals(MilestoneMarchPolicy.Action.CHECKPOINT_AND_DONE,
                MilestoneMarchPolicy.onBuildResult(AutoRepairLoopPolicy.Decision.SUCCEEDED, false, false, false));
    }

    @Test
    public void green_doneTakesPrecedenceOverPause() {
        // No next milestone: even a paused user is simply done (the app is complete and green).
        assertEquals(MilestoneMarchPolicy.Action.CHECKPOINT_AND_DONE,
                MilestoneMarchPolicy.onBuildResult(AutoRepairLoopPolicy.Decision.SUCCEEDED, false, true, true));
    }
}
