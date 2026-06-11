package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.ProjectTaskRecord;
import com.androidbuilder.model.TaskOperations;
import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HermesMergeCoordinatorTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void mergeRejectsTwoResultsTouchingSamePath() {
        HermesAgentResult left = resultWithPath(1, "app/src/main/res/values/strings.xml");
        HermesAgentResult right = resultWithPath(2, "app/src/main/res/values/strings.xml");

        HermesMergeCoordinator.MergePlan plan = HermesMergeCoordinator.plan(Arrays.asList(left, right));

        assertFalse(plan.canMergeAll);
        assertEquals(1, plan.conflicts.size());
    }

    @Test
    public void mergePlanAllowsDisjointResults() {
        HermesAgentResult left = resultWithPath(1, "app/src/main/res/values/strings.xml");
        HermesAgentResult right = resultWithPath(2, "app/src/main/res/layout/activity_main.xml");

        HermesMergeCoordinator.MergePlan plan = HermesMergeCoordinator.plan(Arrays.asList(left, right));

        assertTrue(plan.canMergeAll);
        assertEquals(2, plan.mergeableResults.size());
    }

    @Test
    public void mergeWritesOperationsIntoCanonicalSource() throws Exception {
        File source = temporaryFolder.newFolder("source");
        HermesAgentResult result = resultWithOperation(
                1,
                "docs/generated.txt",
                new FileOperation("write", "docs/generated.txt", "merged\n"));

        HermesMergeCoordinator.MergeResult merge = HermesMergeCoordinator.merge(source, Collections.singletonList(result));

        assertTrue(merge.success);
        assertEquals("merged\n", FileUtils.readText(new File(source, "docs/generated.txt")));
    }

    private HermesAgentResult resultWithPath(int sortOrder, String path) {
        return resultWithOperation(sortOrder, path, new FileOperation("write", path, ""));
    }

    private HermesAgentResult resultWithOperation(int sortOrder, String path, FileOperation operation) {
        ProjectTaskRecord task = new ProjectTaskRecord(
                sortOrder, 1, sortOrder, "Task " + sortOrder, "", "merge_pending", "", 0, 0, 0, 0);
        return new HermesAgentResult(
                task,
                null,
                HermesTaskContract.empty(),
                new TaskOperations("write " + path, Collections.singletonList(operation)),
                Collections.singletonList(path),
                "ok",
                null);
    }
}
