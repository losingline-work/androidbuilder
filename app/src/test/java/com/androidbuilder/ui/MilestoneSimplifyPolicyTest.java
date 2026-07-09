package com.androidbuilder.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MilestoneSimplifyPolicyTest {

    @Test
    public void simplifyIsAllowedOnceThenExhausted() {
        assertTrue(MilestoneSimplifyPolicy.shouldSimplify(0));
        assertFalse(MilestoneSimplifyPolicy.shouldSimplify(1));
        assertFalse(MilestoneSimplifyPolicy.shouldSimplify(2));
    }

    @Test
    public void simplifiedInstructionWrapsTheSliceWithAMinimalDirective() {
        String zh = MilestoneSimplifyPolicy.simplifiedSliceInstruction("记账列表页", true);
        assertTrue(zh.contains("记账列表页"));
        assertTrue(zh.contains("最小可运行版本"));

        String en = MilestoneSimplifyPolicy.simplifiedSliceInstruction("Transaction list screen", false);
        assertTrue(en.contains("Transaction list screen"));
        assertTrue(en.contains("SMALLEST runnable version"));
    }

    @Test
    public void simplifiedInstructionToleratesNullOrBlankSlice() {
        // Must not NPE; the minimal directive still applies.
        assertTrue(MilestoneSimplifyPolicy.simplifiedSliceInstruction(null, false).contains("SMALLEST"));
        assertTrue(MilestoneSimplifyPolicy.simplifiedSliceInstruction("  ", true).contains("最小可运行版本"));
    }

    @Test
    public void reducedRepairBudgetIsSmallerThanTheDefault() {
        assertTrue(MilestoneSimplifyPolicy.SIMPLIFIED_REPAIR_ROUNDS >= 1);
        assertTrue(MilestoneSimplifyPolicy.SIMPLIFIED_REPAIR_ROUNDS < 5);
    }

    @Test
    public void marchPolicyRetriesSimplifiedWhenAvailableElseRollsBack() {
        assertEquals(MilestoneMarchPolicy.Action.SIMPLIFY_AND_RETRY,
                MilestoneMarchPolicy.onRepairExhausted(true));
        assertEquals(MilestoneMarchPolicy.Action.ROLLBACK_AND_STOP,
                MilestoneMarchPolicy.onRepairExhausted(false));
    }
}
