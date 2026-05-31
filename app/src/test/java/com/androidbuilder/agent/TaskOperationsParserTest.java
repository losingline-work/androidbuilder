package com.androidbuilder.agent;

import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

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
