package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CompletedBatchContextPolicyTest {
    @Test
    public void includesAcceptedFileContentWithinLimit() {
        String context = CompletedBatchContextPolicy.context(Arrays.asList(
                new FileOperation("write", "app/src/main/res/values/strings.xml",
                        "<resources><string name=\"app_name\">App</string></resources>")
        ), 1000);

        assertTrue(context.contains("--- app/src/main/res/values/strings.xml ---"));
        assertTrue(context.contains("app_name"));
    }

    @Test
    public void summarizesLargeJavaFilesWhenContextWouldExceedLimit() {
        String context = CompletedBatchContextPolicy.context(Arrays.asList(
                new FileOperation("write", "app/src/main/java/com/example/MainActivity.java",
                        "package com.example;\npublic class MainActivity { public void veryLongMethod() { "
                                + repeat("int value = 1;", 200)
                                + " } }")
        ), 180);

        assertTrue(context.contains("--- app/src/main/java/com/example/MainActivity.java API ---"));
        assertTrue(context.contains("MainActivity"));
        assertFalse(context.contains("veryLongMethod() { int value"));
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
