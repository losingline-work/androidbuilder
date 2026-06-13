package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BatchResumePolicyTest {
    @Test
    public void extractsRejectedFileNamesFromGuardMessage() {
        String message = "Generated source policy blocked missing method: DbHelper.getWritableDb() in CategoryDao.java. "
                + "Generated source policy blocked method argument mismatch: SettingDao.putLong(String, long) in SettingRepository.java.";

        assertTrue(BatchResumePolicy.rejectedFileNames(message).contains("CategoryDao.java"));
        assertTrue(BatchResumePolicy.rejectedFileNames(message).contains("SettingRepository.java"));
        assertEquals(2, BatchResumePolicy.rejectedFileNames(message).size());
    }

    @Test
    public void resumeDraftEvictsOnlyRejectedFilesAndKeepsManifest() {
        String manifestJson = "{\"summary\":\"x\",\"files\":["
                + "{\"path\":\"app/src/main/java/com/example/DbHelper.java\",\"action\":\"write\",\"intent\":\"\"},"
                + "{\"path\":\"app/src/main/java/com/example/CategoryDao.java\",\"action\":\"write\",\"intent\":\"\"}]}";
        List<FileOperation> ops = Arrays.asList(
                new FileOperation("write", "app/src/main/java/com/example/DbHelper.java", "class DbHelper {}"),
                new FileOperation("write", "app/src/main/java/com/example/CategoryDao.java", "class CategoryDao {}"));
        TaskOperations batched = new TaskOperations("x", ops, false, "", "", manifestJson,
                ManifestResumePolicy.acceptedPathsFor(ops));

        TaskOperations resume = BatchResumePolicy.resumeDraftEvicting(batched,
                "Generated source policy blocked missing method: DbHelper.getWritableDb() in CategoryDao.java.");

        // DbHelper (the frozen foundation) stays accepted; CategoryDao (the rejected caller) is evicted.
        assertEquals(1, resume.operations.size());
        assertEquals("app/src/main/java/com/example/DbHelper.java", resume.operations.get(0).path);
        assertEquals(manifestJson, resume.manifestJson);
        assertTrue(resume.acceptedPaths.contains("app/src/main/java/com/example/DbHelper.java"));
        assertFalse(resume.acceptedPaths.contains("app/src/main/java/com/example/CategoryDao.java"));
        // The evicted file's batch is now incomplete, so the manifest is resumable.
        assertTrue(ManifestResumePolicy.shouldResume(resume));
    }

    @Test
    public void returnsNullWhenNoManifestOrNothingEvicted() {
        List<FileOperation> ops = Arrays.asList(
                new FileOperation("write", "app/src/main/java/com/example/DbHelper.java", "class DbHelper {}"));
        TaskOperations noManifest = new TaskOperations("x", ops);
        assertNull(BatchResumePolicy.resumeDraftEvicting(noManifest, "... in CategoryDao.java."));

        TaskOperations batched = new TaskOperations("x", ops, false, "", "", "{\"files\":[]}",
                ManifestResumePolicy.acceptedPathsFor(ops));
        // Guard names a file not in this draft -> nothing evicted -> null (keep existing retry path).
        assertNull(BatchResumePolicy.resumeDraftEvicting(batched, "... in Unrelated.java."));
    }
}
