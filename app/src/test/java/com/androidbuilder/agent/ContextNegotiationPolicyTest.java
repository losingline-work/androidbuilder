package com.androidbuilder.agent;

import com.androidbuilder.model.ContextNegotiation;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContextNegotiationPolicyTest {
    @Test
    public void negotiatesForFailedTaskRepairAndPolicyRetry() {
        assertFalse(ContextNegotiationPolicy.shouldNegotiate(false, 1, "", ""));
        // A fresh-task retry (no failure, no policy error) must NOT scout just because attempt > 1.
        assertFalse(ContextNegotiationPolicy.shouldNegotiate(false, 3, "", ""));
        assertTrue(ContextNegotiationPolicy.shouldNegotiate(true, 1, "previous task failed", ""));
        assertTrue(ContextNegotiationPolicy.shouldNegotiate(true, 1, "build log javac failed", ""));
        assertTrue(ContextNegotiationPolicy.shouldNegotiate(false, 2, "", "Generated source policy blocked missing method."));
    }

    @Test
    public void focusTextIncludesNeededFilesTermsAndFailure() {
        ContextNegotiation result = new ContextNegotiation(
                false,
                Arrays.asList("app/src/main/java/com/example/DBHelper.java"),
                Arrays.asList("CategoryDao"),
                Collections.singletonList("Keep DAO synchronized."),
                "Patch existing DAO only.");

        String focus = ContextNegotiationPolicy.focusText(result, "constructor argument mismatch");

        assertTrue(focus.contains("app/src/main/java/com/example/DBHelper.java"));
        assertTrue(focus.contains("CategoryDao"));
        assertTrue(focus.contains("constructor argument mismatch"));
    }

    @Test
    public void retryContextIncludesNoRecreateInstructionFailureAndPatchIntent() {
        ContextNegotiation result = new ContextNegotiation(
                true,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singletonList("Keep DBHelper constants synchronized."),
                "Modify existing DBHelper and RecordDao only.");

        String context = ContextNegotiationPolicy.retryContext("missing method RecordDao.update", result);

        assertTrue(context.contains("Do not recreate the project"));
        assertTrue(context.contains("missing method RecordDao.update"));
        assertTrue(context.contains("Modify existing DBHelper"));
        assertTrue(context.contains("Keep DBHelper constants synchronized"));
        assertTrue(context.contains("If a file appears in NO part of the snapshot inventory, it does not exist yet"));
        assertTrue(context.contains("never return blocked because a nonexistent file is \"missing\""));
        assertTrue(context.contains("Previous failure summary (the snapshot has changed since this failure"));
        assertTrue(context.contains("Negotiated patch intent (advisory; it cannot forbid creating files"));
    }

    @Test
    public void retryContextIncludesMissingNeededFileVerdicts() {
        ContextNegotiation result = new ContextNegotiation(
                false,
                Collections.singletonList("app/src/main/java/com/example/DBContract.java"),
                Collections.emptyList(),
                Collections.emptyList(),
                "Create missing data contracts.");

        String context = ContextNegotiationPolicy.retryContext(
                "previous failure",
                result,
                Collections.singletonList("app/src/main/java/com/example/DBContract.java"));

        assertTrue(context.contains("File existence verdict for the files you requested"));
        assertTrue(context.contains("app/src/main/java/com/example/DBContract.java: does NOT exist in the project"));
        assertTrue(context.contains("create it yourself if the task requires it"));
    }

    @Test
    public void retryContextOmitsMissingNeededFileVerdictsWhenEmpty() {
        ContextNegotiation result = new ContextNegotiation(
                false,
                Collections.singletonList("app/src/main/java/com/example/DBHelper.java"),
                Collections.emptyList(),
                Collections.emptyList(),
                "Patch existing DBHelper.");

        String context = ContextNegotiationPolicy.retryContext("previous failure", result, Collections.emptyList());

        assertFalse(context.contains("File existence verdict"));
    }

    @Test
    public void continuesNegotiationOnlyWhenContextScoutIsNotReadyAndRoundsRemain() {
        ContextNegotiation notReady = new ContextNegotiation(
                false,
                Collections.singletonList("app/src/main/java/com/example/DBHelper.java"),
                Collections.emptyList(),
                Collections.emptyList(),
                "Need DBHelper before patching DAO.");
        ContextNegotiation ready = new ContextNegotiation(
                true,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                "Patch existing DAO only.");

        assertFalse(ContextNegotiationPolicy.shouldContinueNegotiation(notReady, 1));
        assertFalse(ContextNegotiationPolicy.shouldContinueNegotiation(ready, 1));
        assertFalse(ContextNegotiationPolicy.shouldContinueNegotiation(notReady, ContextNegotiationPolicy.MAX_NEGOTIATION_ROUNDS));
    }
}
