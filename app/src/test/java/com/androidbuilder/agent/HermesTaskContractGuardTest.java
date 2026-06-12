package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.HermesReview;
import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HermesTaskContractGuardTest {
    @Test
    public void rewritesWhenOperationTouchesForbiddenPath() {
        HermesTaskContract contract = new HermesTaskContract(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singletonList("app/src/main/java/com/example/Legacy.java"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                "high",
                false);
        TaskOperations operations = new TaskOperations("bad", Collections.singletonList(
                new FileOperation("write", "app/src/main/java/com/example/Legacy.java", "class Legacy {}")));

        HermesReview review = HermesTaskContractGuard.review(contract, operations);

        assertEquals(HermesReview.Decision.REWRITE, review.decision);
        assertTrue(review.summary.contains("forbiddenPaths"));
        assertTrue(review.rewriteInstruction.contains("Legacy.java"));
    }

    @Test
    public void rewritesWhenExpectedFilesAreSeverelyUnderDelivered() {
        HermesTaskContract contract = new HermesTaskContract(
                Collections.emptyList(),
                Arrays.asList(
                        "app/src/main/res/layout/activity_main.xml",
                        "app/src/main/res/layout/row_item.xml",
                        "app/src/main/res/values/strings.xml",
                        "app/src/main/res/values/colors.xml",
                        "app/src/main/res/drawable/ic_add.xml",
                        "app/src/main/java/com/example/MainActivity.java"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                "medium",
                false);
        TaskOperations operations = new TaskOperations("partial", Arrays.asList(
                new FileOperation("write", "app/src/main/res/layout/activity_main.xml", "<LinearLayout />"),
                new FileOperation("write", "app/src/main/res/values/strings.xml", "<resources />")));

        HermesReview review = HermesTaskContractGuard.review(contract, operations);

        assertEquals(HermesReview.Decision.REWRITE, review.decision);
        assertTrue(review.summary.contains("expectedFiles"));
        assertTrue(review.rewriteInstruction.contains("row_item.xml"));
    }

    @Test
    public void acceptsOperationsThatSatisfyExpectedFiles() {
        HermesTaskContract contract = new HermesTaskContract(
                Collections.emptyList(),
                Arrays.asList("app/src/main/res/layout/activity_main.xml", "app/src/main/res/values/strings.xml"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                "low",
                false);
        TaskOperations operations = new TaskOperations("ok", Arrays.asList(
                new FileOperation("write", "app/src/main/res/layout/activity_main.xml", "<LinearLayout />"),
                new FileOperation("write", "app/src/main/res/values/strings.xml", "<resources />")));

        HermesReview review = HermesTaskContractGuard.review(contract, operations);

        assertEquals(HermesReview.Decision.OK, review.decision);
    }

    @Test
    public void acceptsExpectedFilesWhenCoverageIsAtLeastHalf() {
        HermesTaskContract contract = new HermesTaskContract(
                Collections.emptyList(),
                Arrays.asList(
                        "app/src/main/res/layout/activity_main.xml",
                        "app/src/main/res/layout/row_item.xml",
                        "app/src/main/res/values/strings.xml",
                        "app/src/main/res/values/colors.xml"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                "medium",
                false);
        TaskOperations operations = new TaskOperations("partial", Arrays.asList(
                new FileOperation("write", "app/src/main/res/layout/activity_main.xml", "<LinearLayout />"),
                new FileOperation("write", "app/src/main/res/values/strings.xml", "<resources />")));

        HermesReview review = HermesTaskContractGuard.review(contract, operations);

        assertEquals(HermesReview.Decision.OK, review.decision);
    }

    @Test
    public void acceptsOperationsWhenContractHasNoExpectedFiles() {
        HermesTaskContract contract = HermesTaskContract.empty();
        TaskOperations operations = new TaskOperations("ok", Collections.singletonList(
                new FileOperation("write", "app/src/main/res/values/strings.xml", "<resources />")));

        HermesReview review = HermesTaskContractGuard.review(contract, operations);

        assertEquals(HermesReview.Decision.OK, review.decision);
    }
}
