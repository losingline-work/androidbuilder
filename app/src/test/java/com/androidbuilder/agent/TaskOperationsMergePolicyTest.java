package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TaskOperationsMergePolicyTest {
    @Test
    public void mergePreservesManifestAndAcceptedPathsSoResumeSurvives() {
        // A partial-batch draft carried by a BatchGenerationException must keep its manifest + accepted
        // paths through the merge, or the next attempt cannot RESUME (hasManifest fails) and re-rolls a
        // fresh manifest, discarding the carry-forward foundation.
        TaskOperations previous = new TaskOperations("round 1",
                Arrays.asList(write("app/src/main/java/Foo.java", "class Foo {}")),
                false, "", "",
                "{\"files\":[{\"path\":\"app/src/main/java/Foo.java\"}]}",
                Arrays.asList("app/src/main/java/Foo.java"));
        TaskOperations correction = operations("round 2",
                write("app/src/main/java/Bar.java", "class Bar {}"));

        TaskOperations merged = TaskOperationsMergePolicy.merge(previous, correction);

        assertEquals("{\"files\":[{\"path\":\"app/src/main/java/Foo.java\"}]}", merged.manifestJson);
        assertTrue(merged.acceptedPaths.contains("app/src/main/java/Foo.java"));
        assertTrue(merged.acceptedPaths.contains("app/src/main/java/Bar.java"));
    }

    @Test
    public void mergeOverridesByNormalizedPathAndPreservesPreviousOrder() {
        TaskOperations previous = operations("previous",
                write("app/src/main/java/Foo.java", "old foo"),
                write("app/src/main/java/Bar.java", "old bar"));
        TaskOperations correction = operations("correction",
                write(" app/src/main/java/Bar.java ", "new bar"),
                write("app/src/main/java/Baz.java", "new baz"));

        TaskOperations merged = TaskOperationsMergePolicy.merge(previous, correction);

        assertEquals("correction", merged.summary);
        assertEquals(3, merged.operations.size());
        assertEquals("app/src/main/java/Foo.java", merged.operations.get(0).path);
        assertEquals("old foo", merged.operations.get(0).content);
        assertEquals("app/src/main/java/Bar.java", merged.operations.get(1).path);
        assertEquals("new bar", merged.operations.get(1).content);
        assertEquals("app/src/main/java/Baz.java", merged.operations.get(2).path);
    }

    @Test
    public void mergeLetsDeleteOverridePreviousWrite() {
        TaskOperations previous = operations("previous",
                write("app/src/main/res/layout/activity_main.xml", "<LinearLayout />"));
        TaskOperations correction = operations("",
                delete("app/src/main/res/layout/activity_main.xml"));

        TaskOperations merged = TaskOperationsMergePolicy.merge(previous, correction);

        assertEquals("previous", merged.summary);
        assertEquals(1, merged.operations.size());
        assertEquals("delete", merged.operations.get(0).action);
        assertEquals("app/src/main/res/layout/activity_main.xml", merged.operations.get(0).path);
    }

    @Test
    public void mergeConsumesDropByRemovingPreviousOperation() {
        TaskOperations previous = operations("previous",
                write("app/src/main/res/values/extra.xml", "<resources />"),
                write("app/src/main/java/Foo.java", "class Foo {}"));
        TaskOperations correction = operations("correction",
                drop("app/src/main/res/values/extra.xml"));

        TaskOperations merged = TaskOperationsMergePolicy.merge(previous, correction);

        assertEquals("correction", merged.summary);
        assertEquals(1, merged.operations.size());
        assertEquals("app/src/main/java/Foo.java", merged.operations.get(0).path);
    }

    @Test
    public void stripDropsRemovesDropOperationsFromFullGeneration() {
        TaskOperations operations = operations("full",
                drop("app/src/main/res/values/extra.xml"),
                write("app/src/main/java/Foo.java", "class Foo {}"));

        TaskOperations stripped = TaskOperationsMergePolicy.stripDrops(operations);

        assertEquals("full", stripped.summary);
        assertEquals(1, stripped.operations.size());
        assertEquals("app/src/main/java/Foo.java", stripped.operations.get(0).path);
    }

    @Test
    public void mergeMaterializesEditOperationsIntoFullWrites() {
        TaskOperations previous = operations("previous",
                write("app/src/main/java/Foo.java", "class Foo { int count = 1; }\n"));
        TaskOperations correction = operations("correction",
                edit("app/src/main/java/Foo.java", "int count = 1;", "int count = 2;"));

        TaskOperations merged = TaskOperationsMergePolicy.merge(previous, correction);

        assertEquals(1, merged.operations.size());
        assertEquals("write", merged.operations.get(0).action);
        assertEquals("class Foo { int count = 2; }\n", merged.operations.get(0).content);
    }

    @Test
    public void mergeRejectsEditWhenPreviousWriteIsMissing() {
        TaskOperations previous = operations("previous",
                write("app/src/main/java/Bar.java", "class Bar {}\n"));
        TaskOperations correction = operations("correction",
                edit("app/src/main/java/Foo.java", "old", "new"));

        IllegalArgumentException error = org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> TaskOperationsMergePolicy.merge(previous, correction));

        assertEquals("edit target not found in app/src/main/java/Foo.java (the file may have changed); resend the full file with action write",
                error.getMessage());
    }

    @Test
    public void salvageMergePreservesUntouchedCalleeFromAccumulatedDraft() {
        // RC1 invariant: when a generation/stream abort salvages only the caller, merging the
        // salvage onto the accumulated draft must keep the callee (the DAO with its added method),
        // so the tree the guard later validates is not internally inconsistent.
        TaskOperations accumulated = operations("round 1",
                write("app/src/main/java/com/x/data/dao/TransactionDao.java",
                        "class TransactionDao { List listInRange(long a, long b) { return null; } }"),
                write("app/src/main/java/com/x/data/repo/TransactionRepository.java",
                        "class TransactionRepository { void a() {} }"));
        TaskOperations callerOnlySalvage = operations("partial draft salvaged from aborted stream",
                write("app/src/main/java/com/x/data/repo/TransactionRepository.java",
                        "class TransactionRepository { void a() { dao.listInRange(0, 1); } }"));

        TaskOperations merged = TaskOperationsMergePolicy.merge(accumulated, callerOnlySalvage);

        assertEquals(2, merged.operations.size());
        assertEquals("app/src/main/java/com/x/data/dao/TransactionDao.java", merged.operations.get(0).path);
        org.junit.Assert.assertTrue("DAO method must survive a caller-only salvage",
                merged.operations.get(0).content.contains("listInRange"));
        org.junit.Assert.assertTrue(merged.operations.get(1).content.contains("dao.listInRange(0, 1)"));
    }

    @Test
    public void salvageMergeStillHonorsIntentionalDrop() {
        // A salvage that explicitly drops a file must still drop it (no resurrection from the draft).
        TaskOperations accumulated = operations("round 1",
                write("app/src/main/java/com/x/Foo.java", "class Foo {}"),
                write("app/src/main/java/com/x/Bar.java", "class Bar {}"));
        TaskOperations salvage = operations("partial", drop("app/src/main/java/com/x/Bar.java"));

        TaskOperations merged = TaskOperationsMergePolicy.merge(accumulated, salvage);

        assertEquals(1, merged.operations.size());
        assertEquals("app/src/main/java/com/x/Foo.java", merged.operations.get(0).path);
    }

    private static TaskOperations operations(String summary, FileOperation... operations) {
        return new TaskOperations(summary, Arrays.asList(operations));
    }

    private static FileOperation write(String path, String content) {
        return new FileOperation("write", path, content);
    }

    private static FileOperation delete(String path) {
        return new FileOperation("delete", path, "");
    }

    private static FileOperation drop(String path) {
        return new FileOperation("drop", path, "");
    }

    private static FileOperation edit(String path, String find, String replace) {
        return new FileOperation("edit", path, "", find, replace);
    }
}
