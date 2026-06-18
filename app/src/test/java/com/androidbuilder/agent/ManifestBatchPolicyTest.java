package com.androidbuilder.agent;

import com.androidbuilder.model.TaskManifest;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ManifestBatchPolicyTest {
    @Test
    public void smallLightManifestsStayInOneBatchWithOriginalOrder() {
        // A few genuinely-light files (no heavy layout/strings) stay in one batch, in original order.
        List<TaskManifest.Entry> files = Arrays.asList(
                entry("app/src/main/res/values/colors.xml"),
                entry("app/src/main/res/values/dimens.xml"),
                entry("app/src/main/res/drawable/ic_add.xml"));

        List<List<TaskManifest.Entry>> batches = ManifestBatchPolicy.batches(files);

        assertEquals(1, batches.size());
        assertEquals("app/src/main/res/values/colors.xml", batches.get(0).get(0).path);
        assertEquals("app/src/main/res/values/dimens.xml", batches.get(0).get(1).path);
    }

    @Test
    public void heavyValueSetIsSplitNotBundledIntoOneOversizedBatch() {
        // project-141: the whole values/ set (a big strings.xml + verbose themes/arrays) plus menu must NOT
        // be one response that overflows max_tokens and loops the carry-forward to exhaustion.
        List<TaskManifest.Entry> files = Arrays.asList(
                entry("app/src/main/res/values/colors.xml"),
                entry("app/src/main/res/values/strings.xml"),
                entry("app/src/main/res/values/dimens.xml"),
                entry("app/src/main/res/values/arrays.xml"),
                entry("app/src/main/res/values/themes.xml"),
                entry("app/src/main/res/values-night/themes.xml"),
                entry("app/src/main/res/menu/menu_bottom_nav.xml"));

        List<List<TaskManifest.Entry>> batches = ManifestBatchPolicy.batches(files);

        assertTrue("the heavy value set must split across batches", batches.size() >= 2);
        for (List<TaskManifest.Entry> batch : batches) {
            // strings.xml never shares a batch with BOTH themes files (the worst-case overflow combo).
            boolean hasStrings = false;
            int themes = 0;
            for (TaskManifest.Entry e : batch) {
                if (e.path.endsWith("/strings.xml")) {
                    hasStrings = true;
                }
                if (e.path.endsWith("/themes.xml")) {
                    themes++;
                }
            }
            assertFalse("strings.xml bundled with both themes files", hasStrings && themes >= 2);
        }
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

        // Category order (values -> other resources -> config -> java) is preserved across batches.
        List<String> order = flatten(batches);
        assertEquals("app/src/main/res/values/strings.xml", order.get(0));
        assertEquals("app/src/main/res/layout/activity_main.xml", order.get(1));
        assertEquals("app/src/main/res/drawable/ic_add.xml", order.get(2));
        assertEquals("app/build.gradle", order.get(3));
        assertEquals("app/src/main/AndroidManifest.xml", order.get(4));
        assertEquals("app/src/main/java/MainActivity.java", order.get(5));
        assertEquals("app/src/main/java/RecordDao.java", order.get(6));
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

        // Heavy layouts (weight 6) split sparsely: at most one layout per batch so a response never overflows.
        assertTrue(batches.size() >= 3);
        for (List<TaskManifest.Entry> batch : batches) {
            assertTrue("at most one layout per batch", countLayouts(batch) <= 1);
        }
    }

    @Test
    public void fewHeavyLayoutsAreStillSplitNotCollapsedIntoOneBatch() {
        // project-14: 5 complex layouts (count <= SINGLE_BATCH_THRESHOLD) must NOT collapse into one
        // oversized batch that overflows max_tokens and loops the carry-forward to exhaustion.
        List<TaskManifest.Entry> files = Arrays.asList(
                entry("app/src/main/res/layout/fragment_add_transaction.xml"),
                entry("app/src/main/res/layout/fragment_budget.xml"),
                entry("app/src/main/res/layout/fragment_profile.xml"),
                entry("app/src/main/res/layout/item_transaction.xml"),
                entry("app/src/main/res/layout/item_account.xml"));

        List<List<TaskManifest.Entry>> batches = ManifestBatchPolicy.batches(files);

        assertTrue("five heavy layouts must not be one batch", batches.size() >= 5);
        for (List<TaskManifest.Entry> batch : batches) {
            assertTrue("at most one layout per batch", countLayouts(batch) <= 1);
        }
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

    @Test
    public void javaFilesAreOrderedProducerBeforeConsumer() {
        // Data layer given in a deliberately shuffled (consumer-first) manifest order; batching must
        // emit DB foundation -> entities -> DAOs -> repositories -> ui so a caller's batch always
        // follows its callee's batch.
        List<TaskManifest.Entry> files = Arrays.asList(
                entry("app/src/main/java/com/example/ui/MainActivity.java"),
                entry("app/src/main/java/com/example/data/repo/CategoryRepository.java"),
                entry("app/src/main/java/com/example/data/dao/CategoryDao.java"),
                entry("app/src/main/java/com/example/data/entity/Category.java"),
                entry("app/src/main/java/com/example/data/db/DbHelper.java"),
                entry("app/src/main/java/com/example/util/DateUtils.java"),
                entry("app/src/main/java/com/example/App.java"));

        List<List<TaskManifest.Entry>> batches = ManifestBatchPolicy.batches(files);
        List<String> order = new java.util.ArrayList<>();
        for (List<TaskManifest.Entry> batch : batches) {
            for (TaskManifest.Entry e : batch) {
                order.add(e.path);
            }
        }

        int db = order.indexOf("app/src/main/java/com/example/data/db/DbHelper.java");
        int entity = order.indexOf("app/src/main/java/com/example/data/entity/Category.java");
        int dao = order.indexOf("app/src/main/java/com/example/data/dao/CategoryDao.java");
        int repo = order.indexOf("app/src/main/java/com/example/data/repo/CategoryRepository.java");
        int ui = order.indexOf("app/src/main/java/com/example/ui/MainActivity.java");
        assertTrue(db < entity);
        assertTrue(entity < dao);
        assertTrue(dao < repo);
        assertTrue(repo < ui);
    }

    private static List<String> flatten(List<List<TaskManifest.Entry>> batches) {
        List<String> order = new java.util.ArrayList<>();
        for (List<TaskManifest.Entry> batch : batches) {
            for (TaskManifest.Entry e : batch) {
                order.add(e.path);
            }
        }
        return order;
    }

    private static int countLayouts(List<TaskManifest.Entry> batch) {
        int count = 0;
        for (TaskManifest.Entry e : batch) {
            if (e.path != null && e.path.startsWith("app/src/main/res/layout")) {
                count++;
            }
        }
        return count;
    }

    private static TaskManifest.Entry entry(String path) {
        return new TaskManifest.Entry(path, "write", "intent");
    }
}
