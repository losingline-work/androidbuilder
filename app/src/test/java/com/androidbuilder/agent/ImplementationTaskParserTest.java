package com.androidbuilder.agent;

import com.androidbuilder.model.ProjectTaskRecord;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ImplementationTaskParserTest {
    @Test
    public void fromJson_acceptsTaskList() throws Exception {
        List<ProjectTaskRecord> tasks = ImplementationTaskParser.fromJson("{\"tasks\":[" +
                "{\"title\":\"Create skeleton\",\"instruction\":\"Write Gradle files\"}," +
                "{\"title\":\"Add main screen\",\"instruction\":\"Write activity and layout\"}" +
                "]}");

        assertEquals(2, tasks.size());
        assertEquals("Create skeleton", tasks.get(0).title);
        assertEquals("Write activity and layout", tasks.get(1).instruction);
        assertEquals(0, tasks.get(0).sortOrder);
    }

    @Test
    public void fromJson_rejectsEmptyTaskList() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ImplementationTaskParser.fromJson("{\"tasks\":[]}"));

        assertEquals("Implementation task list is empty.", error.getMessage());
    }
}
