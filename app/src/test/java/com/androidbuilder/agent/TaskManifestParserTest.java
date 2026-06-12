package com.androidbuilder.agent;

import com.androidbuilder.model.TaskManifest;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TaskManifestParserTest {
    @Test
    public void parsesManifestAndCanonicalizesPaths() throws Exception {
        TaskManifest manifest = TaskManifestParser.fromJson("{\"summary\":\"write files\",\"files\":["
                + "{\"path\":\"res/values/colors.xml\",\"action\":\"write\",\"intent\":\"base colors\"},"
                + "{\"path\":\"app/src/main/java/com/example/MainActivity.java\",\"action\":\"delete\",\"intent\":\"remove stale activity\"}"
                + "]}");

        assertEquals("write files", manifest.summary);
        assertEquals(2, manifest.files.size());
        assertEquals("app/src/main/res/values/colors.xml", manifest.files.get(0).path);
        assertEquals("write", manifest.files.get(0).action);
        assertEquals("base colors", manifest.files.get(0).intent);
        assertEquals("delete", manifest.files.get(1).action);
    }

    @Test
    public void parsesBlockedManifest() throws Exception {
        TaskManifest manifest = TaskManifestParser.fromJson("{"
                + "\"summary\":\"blocked\","
                + "\"blocked\":true,"
                + "\"blockedReason\":\"missing dependency\","
                + "\"prerequisiteWork\":\"add allowed dependency first\""
                + "}");

        assertTrue(manifest.blocked);
        assertEquals("missing dependency", manifest.blockedReason);
        assertEquals("add allowed dependency first", manifest.prerequisiteWork);
        assertEquals(0, manifest.files.size());
        assertTrue(manifest.toBlockedOperations().blocked);
    }

    @Test
    public void rejectsEmptyManifestWithoutBlockedReason() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TaskManifestParser.fromJson("{\"summary\":\"x\",\"files\":[]}"));

        assertEquals("Task manifest file list is empty.", error.getMessage());
    }

    @Test
    public void rejectsTooManyManifestFiles() {
        StringBuilder json = new StringBuilder("{\"summary\":\"x\",\"files\":[");
        for (int i = 0; i < TaskOperationsPreflight.MAX_OPERATIONS_PER_TASK + 1; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"path\":\"app/src/main/java/F").append(i).append(".java\",\"action\":\"write\",\"intent\":\"file\"}");
        }
        json.append("]}");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TaskManifestParser.fromJson(json.toString()));

        assertTrue(error.getMessage().contains("too many files"));
    }

    @Test
    public void duplicateCanonicalPathsKeepLastEntryAtLastPosition() throws Exception {
        TaskManifest manifest = TaskManifestParser.fromJson("{\"summary\":\"x\",\"files\":["
                + "{\"path\":\"res/values/colors.xml\",\"action\":\"write\",\"intent\":\"old\"},"
                + "{\"path\":\"app/src/main/res/layout/activity_main.xml\",\"action\":\"write\",\"intent\":\"layout\"},"
                + "{\"path\":\"app/src/main/res/values/colors.xml\",\"action\":\"delete\",\"intent\":\"remove duplicate\"}"
                + "]}");

        assertEquals(2, manifest.files.size());
        assertEquals("app/src/main/res/layout/activity_main.xml", manifest.files.get(0).path);
        assertEquals("app/src/main/res/values/colors.xml", manifest.files.get(1).path);
        assertEquals("delete", manifest.files.get(1).action);
        assertEquals("remove duplicate", manifest.files.get(1).intent);
    }

    @Test
    public void rejectsUnsupportedManifestAction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TaskManifestParser.fromJson("{\"summary\":\"x\",\"files\":[{\"path\":\"settings.gradle\",\"action\":\"append\",\"intent\":\"bad\"}]}"));

        assertEquals("Unsupported task manifest action: append", error.getMessage());
    }

    @Test
    public void rejectsManualConstructionWithTooManyFiles() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskManifest("x", Collections.nCopies(TaskOperationsPreflight.MAX_OPERATIONS_PER_TASK + 1,
                        new TaskManifest.Entry("app/src/main/java/A.java", "write", "same")), false, "", ""));
    }
}
