package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class TaskOperationsMergePolicyTest {
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
