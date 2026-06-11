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
    public void conflictSkipsLaterTaskAndMergesEarlier() throws Exception {
        File source = temporaryFolder.newFolder("source");
        HermesAgentResult laterInputFirst = resultWithOperation(
                2,
                "docs/shared.txt",
                new FileOperation("write", "docs/shared.txt", "later\n"));
        HermesAgentResult earlierInputSecond = resultWithOperation(
                1,
                "docs/shared.txt",
                new FileOperation("write", "docs/shared.txt", "earlier\n"));

        HermesMergeCoordinator.MergeResult merge = HermesMergeCoordinator.merge(source, Arrays.asList(laterInputFirst, earlierInputSecond));

        assertTrue(merge.success);
        assertEquals(1, merge.mergedResults.size());
        assertEquals(1, merge.mergedResults.get(0).task.sortOrder);
        assertEquals("earlier\n", FileUtils.readText(new File(source, "docs/shared.txt")));
        assertEquals(1, merge.failedResults.size());
        assertEquals(2, merge.failedResults.get(0).result.task.sortOrder);
        assertTrue(merge.failedResults.get(0).reason.contains("Path conflict"));
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
        assertEquals(0, merge.failedResults.size());
        assertEquals("merged\n", FileUtils.readText(new File(source, "docs/generated.txt")));
    }

    @Test
    public void mergePreflightIgnoresNamespaceMentionOutsideGradleFiles() throws Exception {
        File source = temporaryFolder.newFolder("source");
        FileUtils.writeText(new File(source, "docs/notes.txt"), "namespace \"com.generated.app\"\n");
        HermesAgentResult result = resultWithOperation(
                1,
                "app/src/main/java/com/generated/app/ui/MainActivity.java",
                new FileOperation("write", "app/src/main/java/com/generated/app/ui/MainActivity.java",
                        "package com.generated.app.ui;\n"
                                + "public class MainActivity {\n"
                                + "  Class<?> rClass() { return R.class; }\n"
                                + "}\n"));

        HermesMergeCoordinator.MergeResult merge = HermesMergeCoordinator.merge(source, Collections.singletonList(result));

        assertTrue(merge.success);
        assertEquals(1, merge.mergedResults.size());
        assertEquals(0, merge.failedResults.size());
        assertTrue(new File(source, "app/src/main/java/com/generated/app/ui/MainActivity.java").isFile());
    }

    @Test
    public void contractRejectionOnlyFailsOffendingResult() throws Exception {
        File source = temporaryFolder.newFolder("source");
        HermesAgentResult rejected = resultWithContract(
                1,
                "docs/rejected.txt",
                new FileOperation("write", "docs/rejected.txt", "bad\n"),
                new HermesTaskContract(
                        Collections.emptyList(),
                        Collections.singletonList("docs/expected.txt"),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        "",
                        false));
        HermesAgentResult clean = resultWithOperation(
                2,
                "docs/clean.txt",
                new FileOperation("write", "docs/clean.txt", "clean\n"));

        HermesMergeCoordinator.MergeResult merge = HermesMergeCoordinator.merge(source, Arrays.asList(rejected, clean));

        assertTrue(merge.success);
        assertEquals(1, merge.mergedResults.size());
        assertEquals("clean\n", FileUtils.readText(new File(source, "docs/clean.txt")));
        assertFalse(new File(source, "docs/rejected.txt").exists());
        assertEquals(1, merge.failedResults.size());
        assertTrue(merge.failedResults.get(0).reason.contains("Contract guard rejected"));
    }

    @Test
    public void unsafeOperationPathOnlyFailsOffendingResult() throws Exception {
        File source = temporaryFolder.newFolder("source");
        HermesAgentResult badApply = resultWithOperation(
                1,
                "docs/safe-label.txt",
                new FileOperation("write", "../evil.txt", "bad\n"));
        HermesAgentResult clean = resultWithOperation(
                2,
                "docs/clean.txt",
                new FileOperation("write", "docs/clean.txt", "clean\n"));

        HermesMergeCoordinator.MergeResult merge = HermesMergeCoordinator.merge(source, Arrays.asList(badApply, clean));

        assertTrue(merge.success);
        assertEquals(1, merge.mergedResults.size());
        assertEquals("clean\n", FileUtils.readText(new File(source, "docs/clean.txt")));
        assertEquals(1, merge.failedResults.size());
        assertTrue(merge.failedResults.get(0).reason.contains("declared unsafe touched path"));
    }

    @Test
    public void allFailedYieldsNoMergedResults() throws Exception {
        File source = temporaryFolder.newFolder("source");
        HermesTaskContract contract = new HermesTaskContract(
                Collections.emptyList(),
                Collections.singletonList("docs/expected.txt"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                "",
                false);
        HermesAgentResult first = resultWithContract(1, "docs/one.txt", new FileOperation("write", "docs/one.txt", "one\n"), contract);
        HermesAgentResult second = resultWithContract(2, "docs/two.txt", new FileOperation("write", "docs/two.txt", "two\n"), contract);

        HermesMergeCoordinator.MergeResult merge = HermesMergeCoordinator.merge(source, Arrays.asList(first, second));

        assertTrue(merge.success);
        assertEquals(0, merge.mergedResults.size());
        assertEquals(2, merge.failedResults.size());
    }

    @Test
    public void agentErrorCountsAsFailedResult() throws Exception {
        File source = temporaryFolder.newFolder("source");
        HermesAgentResult failed = resultWithError(1, new IllegalArgumentException("Unterminated array at character 632"));

        HermesMergeCoordinator.MergeResult merge = HermesMergeCoordinator.merge(source, Collections.singletonList(failed));

        assertTrue(merge.success);
        assertEquals(0, merge.mergedResults.size());
        assertEquals(1, merge.failedResults.size());
        assertTrue(merge.summary.contains("failed 1"));
        assertTrue(merge.failedResults.get(0).reason.contains("Unterminated array"));
    }

    private HermesAgentResult resultWithPath(int sortOrder, String path) {
        return resultWithOperation(sortOrder, path, new FileOperation("write", path, ""));
    }

    private HermesAgentResult resultWithOperation(int sortOrder, String path, FileOperation operation) {
        return resultWithContract(sortOrder, path, operation, HermesTaskContract.empty());
    }

    private HermesAgentResult resultWithContract(int sortOrder, String path, FileOperation operation, HermesTaskContract contract) {
        ProjectTaskRecord task = new ProjectTaskRecord(
                sortOrder, 1, sortOrder, "Task " + sortOrder, "", "merge_pending", "", 0, 0, 0, 0);
        return new HermesAgentResult(
                task,
                null,
                contract,
                new TaskOperations("write " + path, Collections.singletonList(operation)),
                Collections.singletonList(path),
                "ok",
                null);
    }

    private HermesAgentResult resultWithError(int sortOrder, Exception error) {
        ProjectTaskRecord task = new ProjectTaskRecord(
                sortOrder, 1, sortOrder, "Task " + sortOrder, "", "failed", "", 0, 0, 0, 0);
        return new HermesAgentResult(
                task,
                null,
                HermesTaskContract.empty(),
                null,
                Collections.singletonList("app/src/main/java/com/generated/app/Broken.java"),
                "",
                error);
    }
}
