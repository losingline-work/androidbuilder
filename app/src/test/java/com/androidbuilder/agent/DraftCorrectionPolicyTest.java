package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DraftCorrectionPolicyTest {
    @Test
    public void noPreviousDraftUsesFullGeneration() {
        assertFalse(DraftCorrectionPolicy.shouldCorrect(
                false,
                "Generated source policy blocked missing XML id: R.id.toolbar in BaseActivity.java.",
                1));
    }

    @Test
    public void structuralErrorsUseFullGeneration() {
        assertFalse(DraftCorrectionPolicy.shouldCorrect(
                true,
                "Task operation response did not contain a JSON object.",
                1));
        assertFalse(DraftCorrectionPolicy.shouldCorrect(
                true,
                "Unsupported file operation action: append",
                1));
    }

    @Test
    public void oversizedOperationBatchesUseFullGeneration() {
        assertFalse(DraftCorrectionPolicy.shouldCorrect(
                true,
                "Unusually many file operations for one task: 45.",
                1));
    }

    @Test
    public void streamingRunawayUsesFullGeneration() {
        assertFalse(DraftCorrectionPolicy.shouldCorrect(
                true,
                "Streaming response exceeded 200000 chars; generation aborted as runaway.",
                1));
    }

    @Test
    public void editStructuralErrorsUseFullGeneration() {
        assertFalse(DraftCorrectionPolicy.shouldCorrect(
                true,
                "edit target not found in app/src/main/java/A.java (the file may have changed); resend the full file with action write",
                1));
        assertFalse(DraftCorrectionPolicy.shouldCorrect(
                true,
                "edit operation has empty find text in app/src/main/java/A.java; resend the full file with action write",
                1));
    }

    @Test
    public void normalGuardErrorWithDraftUsesCorrection() {
        assertTrue(DraftCorrectionPolicy.shouldCorrect(
                true,
                "Generated source policy blocked missing XML id: R.id.toolbar in BaseActivity.java.",
                1));
    }

    @Test
    public void repeatedSameErrorFallsBackToFullGeneration() {
        assertFalse(DraftCorrectionPolicy.shouldCorrect(
                true,
                "Generated source policy blocked missing XML id: R.id.toolbar in BaseActivity.java.",
                2));
    }

    @Test
    public void signatureIgnoresWhitespaceNoise() {
        assertEquals(
                DraftCorrectionPolicy.errorSignature("Generated source policy blocked missing XML id: R.id.toolbar in BaseActivity.java."),
                DraftCorrectionPolicy.errorSignature("  Generated source policy blocked missing XML id: R.id.toolbar   in BaseActivity.java.  "));
    }

    @Test
    public void signatureNormalizesNumbers() {
        assertEquals(
                DraftCorrectionPolicy.errorSignature("Unusually many file operations for one task: 78."),
                DraftCorrectionPolicy.errorSignature("Unusually many file operations for one task: 80."));
    }

    @Test
    public void signatureKeepsDifferentIdentifiersDistinct() {
        assertFalse(DraftCorrectionPolicy.errorSignature("Generated source policy blocked missing XML id: R.id.toolbar in A.java.")
                .equals(DraftCorrectionPolicy.errorSignature("Generated source policy blocked missing XML id: R.id.title in A.java.")));
    }
}
