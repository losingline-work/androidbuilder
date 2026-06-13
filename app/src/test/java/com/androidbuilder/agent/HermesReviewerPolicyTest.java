package com.androidbuilder.agent;

import com.androidbuilder.model.HermesReview;
import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HermesReviewerPolicyTest {
    @Test
    public void retryOnlyWhenReviewerRequestsRewriteAndAttemptsRemain() {
        HermesReview rewrite = new HermesReview(
                HermesReview.Decision.REWRITE,
                "DAO and caller mismatch.",
                "Rewrite DAO and caller together.");

        assertTrue(HermesReviewerPolicy.shouldRetry(rewrite, 1, 5));
        assertFalse(HermesReviewerPolicy.shouldRetry(rewrite, 5, 5));
    }

    @Test
    public void fallbackDoesNotRetry() {
        HermesReview fallback = new HermesReview(HermesReview.Decision.FALLBACK, "Reviewer unavailable.", "");

        assertFalse(HermesReviewerPolicy.shouldRetry(fallback, 1, 5));
        assertTrue(HermesReviewerPolicy.shouldFallback(fallback));
    }

    @Test
    public void rewriteContextIncludesReviewerSummaryAndInstruction() {
        HermesReview rewrite = new HermesReview(
                HermesReview.Decision.REWRITE,
                "Patch replaces too many files.",
                "Rewrite only RecordDao.java.");

        String context = HermesReviewerPolicy.rewriteContext(rewrite);

        assertTrue(context.contains("HermesReviewer requested rewrite"));
        assertTrue(context.contains("Patch replaces too many files"));
        assertTrue(context.contains("Rewrite only RecordDao.java"));
    }

    @Test
    public void reviewsOnlyRepairRetryOrHighRiskOperations() {
        TaskOperations singleJavaWrite = new TaskOperations("small", Collections.singletonList(
                new FileOperation("write", "app/src/main/java/com/example/MainActivity.java", "class MainActivity {}")));
        TaskOperations pairedJavaAndLayoutWrite = new TaskOperations("screen", Arrays.asList(
                new FileOperation("write", "app/src/main/res/layout/activity_main.xml", "<LinearLayout />"),
                new FileOperation("write", "app/src/main/java/com/example/MainActivity.java", "class MainActivity {}")));
        TaskOperations deleteOperation = new TaskOperations("delete", Collections.singletonList(
                new FileOperation("delete", "app/src/main/java/com/example/OldActivity.java", "")));
        TaskOperations gradleWrite = new TaskOperations("gradle", Collections.singletonList(
                new FileOperation("write", "app/build.gradle", "plugins { id 'com.android.application' }")));

        assertFalse(HermesReviewerPolicy.shouldReviewOperations(false, 1, null, singleJavaWrite, 0));
        assertTrue(HermesReviewerPolicy.shouldReviewOperations(true, 1, null, singleJavaWrite, 0));
        assertTrue(HermesReviewerPolicy.shouldReviewOperations(false, 2, null, singleJavaWrite, 0));
        assertTrue(HermesReviewerPolicy.shouldReviewOperations(false, 1, null, pairedJavaAndLayoutWrite, 0));
        assertTrue(HermesReviewerPolicy.shouldReviewOperations(false, 1, null, deleteOperation, 0));
        assertTrue(HermesReviewerPolicy.shouldReviewOperations(false, 1, null, gradleWrite, 0));
        assertFalse(HermesReviewerPolicy.shouldReviewOperations(true, 2, null, deleteOperation, 1));
    }

    @Test
    public void skipsCloudReviewWhenRetryCannotUseTheAnswer() {
        TaskOperations deleteOperation = new TaskOperations("delete", Collections.singletonList(
                new FileOperation("delete", "app/src/main/java/com/example/OldActivity.java", "")));

        assertFalse(HermesReviewerPolicy.shouldReviewOperations(true, 5, null, deleteOperation, 0, 5, true));
        assertFalse(HermesReviewerPolicy.shouldReviewOperations(true, 2, null, deleteOperation, 0, 5, false));
    }

    @Test
    public void treatsDeterministicRImportEchoAsStaleDuplicate() {
        HermesReview rewrite = new HermesReview(
                HermesReview.Decision.REWRITE,
                "Java file in subpackage com.generated.app.ui uses R.* but is missing R import.",
                "Add import com.generated.app.R;");

        assertTrue(HermesReviewerPolicy.isStaleOrDuplicate(rewrite, true));
        assertFalse(HermesReviewerPolicy.isStaleOrDuplicate(rewrite, false));
        assertFalse(HermesReviewerPolicy.isValidCloudDecision(new HermesReview(HermesReview.Decision.FALLBACK, "unavailable", "")));
        assertTrue(HermesReviewerPolicy.isValidCloudDecision(new HermesReview(HermesReview.Decision.OK, "ok", "")));
    }
}
