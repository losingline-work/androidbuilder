package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskManifest;
import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ManifestResumePolicyTest {
    @Test
    public void remainingBatchesSkipsFullyAcceptedBatches() {
        TaskManifest manifest = new TaskManifest("task", Arrays.asList(
                entry("app/src/main/res/values/strings.xml"),
                entry("app/src/main/res/layout/activity_main.xml"),
                entry("app/src/main/java/com/example/MainActivity.java"),
                entry("app/src/main/java/com/example/ScreenA.java"),
                entry("app/src/main/java/com/example/ScreenB.java"),
                entry("app/src/main/java/com/example/ScreenC.java"),
                entry("app/src/main/java/com/example/ScreenD.java")), false, "", "");
        List<List<TaskManifest.Entry>> batches = ManifestBatchPolicy.batches(manifest.files);
        java.util.List<String> accepted = new java.util.ArrayList<>();
        for (TaskManifest.Entry entry : batches.get(0)) {
            accepted.add(entry.path);
        }

        List<List<TaskManifest.Entry>> remaining = ManifestResumePolicy.remainingBatches(
                manifest,
                accepted);

        assertEquals(batches.size() - 1, remaining.size());
        assertEquals(batches.get(1).get(0).path, remaining.get(0).get(0).path);
    }

    @Test
    public void shouldResumeWhenManifestHasRemainingFilesEvenWithoutAcceptedOperations() {
        TaskOperations draft = new TaskOperations(
                "manifest only",
                Collections.<FileOperation>emptyList(),
                false,
                "",
                "",
                "{\"summary\":\"task\",\"files\":[{\"path\":\"app/src/main/java/com/example/MainActivity.java\",\"action\":\"write\",\"intent\":\"activity\"}]}",
                Collections.<String>emptyList());

        assertTrue(ManifestResumePolicy.shouldResume(draft));
    }

    @Test
    public void shouldNotResumeWhenAllManifestPathsAreAccepted() {
        TaskOperations draft = new TaskOperations(
                "complete",
                Collections.singletonList(new FileOperation("write", "app/src/main/java/com/example/MainActivity.java", "class MainActivity {}")),
                false,
                "",
                "",
                "{\"summary\":\"task\",\"files\":[{\"path\":\"app/src/main/java/com/example/MainActivity.java\",\"action\":\"write\",\"intent\":\"activity\"}]}",
                Collections.singletonList("app/src/main/java/com/example/MainActivity.java"));

        assertFalse(ManifestResumePolicy.shouldResume(draft));
    }

    private static TaskManifest.Entry entry(String path) {
        return new TaskManifest.Entry(path, "write", "");
    }
}
