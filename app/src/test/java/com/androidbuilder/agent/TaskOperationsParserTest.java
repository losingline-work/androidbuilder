package com.androidbuilder.agent;

import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TaskOperationsParserTest {
    @Test
    public void fromJson_acceptsWriteOperations() throws Exception {
        TaskOperations operations = TaskOperationsParser.fromJson("{\"summary\":\"Wrote files\",\"operations\":[" +
                "{\"action\":\"write\",\"path\":\"settings.gradle\",\"content\":\"include ':app'\\n\"}," +
                "{\"action\":\"write\",\"path\":\"app/build.gradle\",\"content\":\"plugins {}\\n\"}" +
                "]}");

        assertEquals("Wrote files", operations.summary);
        assertEquals(2, operations.operations.size());
        assertEquals("settings.gradle", operations.operations.get(0).path);
        assertFalse(operations.blocked);
    }

    @Test
    public void fromJson_acceptsDropOperationsForDraftCorrection() throws Exception {
        TaskOperations operations = TaskOperationsParser.fromJson("{\"summary\":\"Drop stale file\",\"operations\":[" +
                "{\"action\":\"drop\",\"path\":\"app/src/main/res/values/extra.xml\",\"content\":\"\"}" +
                "]}");

        assertEquals(1, operations.operations.size());
        assertEquals("drop", operations.operations.get(0).action);
        assertEquals("app/src/main/res/values/extra.xml", operations.operations.get(0).path);
        assertEquals("", operations.operations.get(0).content);
    }

    @Test
    public void fromJson_acceptsBlockedResponseWithReasonAndPrerequisiteWork() throws Exception {
        TaskOperations operations = TaskOperationsParser.fromJson("{"
                + "\"summary\":\"Cannot safely continue\","
                + "\"blocked\":true,"
                + "\"blockedReason\":\"layouts missing\","
                + "\"prerequisiteWork\":\"create manifest, values, and layouts first\""
                + "}");

        assertTrue(operations.blocked);
        assertEquals("Cannot safely continue", operations.summary);
        assertEquals("layouts missing", operations.blockedReason);
        assertEquals("create manifest, values, and layouts first", operations.prerequisiteWork);
        assertEquals(0, operations.operations.size());
    }

    @Test
    public void fromJson_rejectsBlockedResponseWithoutReason() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TaskOperationsParser.fromJson("{"
                + "\"summary\":\"Cannot safely continue\","
                + "\"blocked\":true,"
                + "\"blockedReason\":\"\","
                + "\"prerequisiteWork\":\"create layouts first\""
                + "}"));

        assertEquals("Task operation list is empty.", error.getMessage());
    }

    @Test
    public void fromJson_recoversOperationObjectsPrefixedByStrayQuote() throws Exception {
        TaskOperations operations = TaskOperationsParser.fromJson("{\"summary\":\"Wrote files\",\"operations\":[" +
                "{\"action\":\"write\",\"path\":\"settings.gradle\",\"content\":\"include ':app'\\n\"}," +
                "\"{\"action\":\"write\",\"path\":\"app/build.gradle\",\"content\":\"plugins {}\\n\"}" +
                "]}");

        assertEquals("Wrote files", operations.summary);
        assertEquals(2, operations.operations.size());
        assertEquals("app/build.gradle", operations.operations.get(1).path);
    }

    @Test
    public void fromJson_rejectsUnsafePath() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TaskOperationsParser.fromJson("{\"summary\":\"x\",\"operations\":[" +
                "{\"action\":\"write\",\"path\":\"../settings.gradle\",\"content\":\"bad\"}" +
                "]}"));

        assertEquals("Unsafe generated file path: ../settings.gradle", error.getMessage());
    }

    @Test
    public void fromJson_rejectsUnsupportedAction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TaskOperationsParser.fromJson("{\"summary\":\"x\",\"operations\":[" +
                "{\"action\":\"append\",\"path\":\"settings.gradle\",\"content\":\"bad\"}" +
                "]}"));

        assertEquals("Unsupported file operation action: append", error.getMessage());
    }
}
