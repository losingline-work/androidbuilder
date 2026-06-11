package com.androidbuilder.agent;

import com.androidbuilder.model.GuardFinding;
import com.androidbuilder.model.HermesReview;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GuardFindingPolicyTest {
    @Test
    public void convertsHermesRewriteReviewToGuardFinding() {
        HermesReview review = new HermesReview(
                HermesReview.Decision.REWRITE,
                "Malformed XML in app/src/main/res/layout/view_keypad.xml.",
                "Return a complete, well-formed XML file.");

        List<GuardFinding> findings = GuardFindingPolicy.fromHermesReview("deterministic-preflight", review);

        assertEquals(1, findings.size());
        assertEquals("deterministic-preflight", findings.get(0).source);
        assertEquals("HERMES_REWRITE", findings.get(0).code);
        assertEquals(GuardFinding.Severity.ERROR, findings.get(0).severity);
        assertTrue(findings.get(0).message.contains("Malformed XML"));
        assertTrue(findings.get(0).suggestion.contains("well-formed XML"));
    }

    @Test
    public void convertsLocalGuardRewriteToGuardFinding() {
        LocalGuardResult result = LocalGuardResult.rewrite(
                "Deterministic rules found high-confidence source/API mismatches.",
                "MainActivity.java references R.drawable.ic_food but no drawable resource is present.");

        List<GuardFinding> findings = GuardFindingPolicy.fromLocalGuardResult("local-guard", result);

        assertEquals(1, findings.size());
        assertEquals("local-guard", findings.get(0).source);
        assertEquals("LOCAL_GUARD_REWRITE", findings.get(0).code);
        assertEquals(GuardFinding.Severity.ERROR, findings.get(0).severity);
        assertTrue(findings.get(0).suggestion.contains("R.drawable.ic_food"));
    }

    @Test
    public void formatsGuardFindingsAsRetryContext() {
        LocalGuardResult result = LocalGuardResult.rewrite(
                "Deterministic rules found high-confidence source/API mismatches.",
                "Update the DAO method signature and all callers together.");

        String context = GuardFindingPolicy.retryContext(
                GuardFindingPolicy.fromLocalGuardResult("local-guard", result));

        assertTrue(context.contains("Guard finding"));
        assertTrue(context.contains("LOCAL_GUARD_REWRITE"));
        assertTrue(context.contains("Deterministic rules found"));
        assertTrue(context.contains("Update the DAO method signature"));
    }
}
