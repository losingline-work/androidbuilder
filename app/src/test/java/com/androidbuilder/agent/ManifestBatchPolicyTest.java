package com.androidbuilder.agent;

import com.androidbuilder.model.TaskManifest;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ManifestBatchPolicyTest {
    @Test
    public void smallManifestsStayInOneBatchWithOriginalOrder() {
        List<TaskManifest.Entry> files = Arrays.asList(
                entry("app/src/main/java/MainActivity.java"),
                entry("app/src/main/res/values/strings.xml"),
                entry("app/src/main/res/layout/activity_main.xml"));

        List<List<TaskManifest.Entry>> batches = ManifestBatchPolicy.batches(files);

        assertEquals(1, batches.size());
        assertEquals("app/src/main/java/MainActivity.java", batches.get(0).get(0).path);
        assertEquals("app/src/main/res/values/strings.xml", batches.get(0).get(1).path);
    }

    @Test
    public void largerManifestsOrderValuesThenOtherResourcesThenConfigThenJava() {
        List<TaskManifest.Entry> files = Arrays.asList(
                entry("app/src/main/java/MainActivity.java"),
                entry("app/src/main/res/layout/activity_main.xml"),
                entry("app/build.gradle"),
                entry("app/src/main/res/values/strings.xml"),
                entry("app/src/main/res/drawable/ic_add.xml"),
                entry("app/src/main/AndroidManifest.xml"),
                entry("app/src/main/java/RecordDao.java"));

        List<List<TaskManifest.Entry>> batches = ManifestBatchPolicy.batches(files);

        assertEquals(2, batches.size());
        assertEquals("app/src/main/res/values/strings.xml", batches.get(0).get(0).path);
        assertEquals("app/src/main/res/layout/activity_main.xml", batches.get(0).get(1).path);
        assertEquals("app/src/main/res/drawable/ic_add.xml", batches.get(0).get(2).path);
        assertEquals("app/build.gradle", batches.get(0).get(3).path);
        assertEquals("app/src/main/AndroidManifest.xml", batches.get(0).get(4).path);
        assertEquals("app/src/main/java/MainActivity.java", batches.get(1).get(0).path);
        assertEquals("app/src/main/java/RecordDao.java", batches.get(1).get(1).path);
    }

    @Test
    public void batchesAreSlicedByContentWeightNotFileCount() {
        List<TaskManifest.Entry> files = Arrays.asList(
                entry("app/src/main/res/values/strings.xml"),
                entry("app/src/main/res/values/colors.xml"),
                entry("app/src/main/res/layout/a.xml"),
                entry("app/src/main/res/layout/b.xml"),
                entry("app/src/main/java/A.java"),
                entry("app/src/main/java/B.java"),
                entry("app/src/main/java/C.java"));

        List<List<TaskManifest.Entry>> batches = ManifestBatchPolicy.batches(files);

        // values 1+1 + layouts 2+2 + first java 3 = weight 9; the remaining two java files
        // (weight 3 each) form the second batch.
        assertEquals(2, batches.size());
        assertEquals(5, batches.get(0).size());
        assertEquals(2, batches.get(1).size());
    }

    @Test
    public void lightDrawablesPackDenselyUpToTheWeightCap() {
        List<TaskManifest.Entry> files = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            files.add(entry("app/src/main/res/drawable/ic_" + i + ".xml"));
        }

        List<List<TaskManifest.Entry>> batches = ManifestBatchPolicy.batches(files);

        assertEquals(2, batches.size());
        assertEquals(ManifestBatchPolicy.MAX_BATCH_WEIGHT, batches.get(0).size());
        assertEquals(2, batches.get(1).size());
    }

    private static TaskManifest.Entry entry(String path) {
        return new TaskManifest.Entry(path, "write", "intent");
    }
}
