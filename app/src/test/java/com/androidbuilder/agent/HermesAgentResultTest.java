package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class HermesAgentResultTest {
    @Test
    public void successfulResultUsesOperationPathsInsteadOfSchedulingLocks() {
        TaskOperations operations = new TaskOperations("write java", Collections.singletonList(
                new FileOperation("write", "app/src/main/java/com/generated/app/MainActivity.java", "class MainActivity {}")));

        HermesAgentResult result = new HermesAgentResult(
                null,
                null,
                operations,
                Arrays.asList("*", "app/src/main/res/values/*", "app/build.gradle"),
                "ok",
                null);

        assertEquals(Collections.singletonList("app/src/main/java/com/generated/app/MainActivity.java"), result.touchedPaths);
    }
}
