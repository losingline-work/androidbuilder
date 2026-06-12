package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.HermesTaskContract;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TaskStreamPreflightTest {
    @Test
    public void rejectsKotlinSourcePaths() {
        String error = TaskStreamPreflight.review(Collections.singletonList(
                new FileOperation("write", "app/src/main/java/com/example/MainActivity.kt", "class MainActivity")
        ), HermesTaskContract.empty());

        assertTrue(error.contains("Kotlin source file"));
    }

    @Test
    public void rejectsPathsOutsideAllowedContract() {
        HermesTaskContract contract = new HermesTaskContract(
                Collections.singletonList("app/src/main/res/layout/*"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                "",
                false);

        String error = TaskStreamPreflight.review(Collections.singletonList(
                new FileOperation("write", "app/src/main/java/com/example/MainActivity.java", "class MainActivity {}")
        ), contract);

        assertTrue(error.contains("outside allowedPaths"));
        assertTrue(error.contains("app/src/main/java/com/example/MainActivity.java"));
    }

    @Test
    public void rejectsJavaLambdasAfterStrippingCommentsAndStrings() {
        String harmless = TaskStreamPreflight.review(Collections.singletonList(
                new FileOperation("write", "app/src/main/java/com/example/Notes.java",
                        "class Notes { String text = \"a -> b\"; /* x -> y */ }")
        ), HermesTaskContract.empty());
        String fatal = TaskStreamPreflight.review(Collections.singletonList(
                new FileOperation("write", "app/src/main/java/com/example/MainActivity.java",
                        "class MainActivity { void bind() { button.setOnClickListener(v -> save()); } }")
        ), HermesTaskContract.empty());

        assertNull(harmless);
        assertTrue(fatal.contains("Java lambda syntax"));
    }

    @Test
    public void rejectsDataBindingImports() {
        String error = TaskStreamPreflight.review(Collections.singletonList(
                new FileOperation("write", "app/src/main/java/com/example/MainActivity.java",
                        "import com.example.databinding.ActivityMainBinding;\nclass MainActivity {}")
        ), HermesTaskContract.empty());

        assertTrue(error.contains("DataBinding"));
    }

    @Test
    public void rejectsMalformedXml() {
        String error = TaskStreamPreflight.review(Collections.singletonList(
                new FileOperation("write", "app/src/main/res/layout/activity_main.xml",
                        "<LinearLayout><TextView>")
        ), HermesTaskContract.empty());

        assertTrue(error.contains("Malformed XML"));
    }

    @Test
    public void doesNotRejectResourceExistenceDuringStream() {
        String error = TaskStreamPreflight.review(Collections.singletonList(
                new FileOperation("write", "app/src/main/java/com/example/MainActivity.java",
                        "class MainActivity { int id() { return R.id.missing_later; } }")
        ), HermesTaskContract.empty());

        assertNull(error);
    }

    @Test
    public void rejectsOperationCountOverPreflightLimit() {
        List<FileOperation> operations = new ArrayList<>();
        for (int i = 0; i < TaskOperationsPreflight.MAX_OPERATIONS_PER_TASK + 1; i++) {
            operations.add(new FileOperation("write", "app/src/main/res/drawable/item_" + i + ".xml",
                    "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" />"));
        }

        String error = TaskStreamPreflight.review(operations, HermesTaskContract.empty());

        assertTrue(error.contains("Unusually many file operations"));
    }

    @Test
    public void allowsCanonicalizableShortAndroidPathsDuringStream() {
        String error = TaskStreamPreflight.review(Collections.singletonList(
                new FileOperation("write", "res/layout/activity_main.xml",
                        "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" />")
        ), HermesTaskContract.empty());

        assertNull(error);
    }
}
