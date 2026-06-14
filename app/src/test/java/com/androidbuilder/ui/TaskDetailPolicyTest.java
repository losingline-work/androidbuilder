package com.androidbuilder.ui;

import com.androidbuilder.model.ProjectTaskRecord;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TaskDetailPolicyTest {
    @Test
    public void extractsCleanDescriptionFileOutputsAndChecks() {
        String instruction = "Write the data layer foundation."
                + "\n[HermesTaskContract]{\"expectedFiles\":["
                + "\"app/src/main/java/com/x/data/db/DbHelper.java\","
                + "\"app/src/main/java/com/x/data/dao/CategoryDao.java\"],"
                + "\"acceptanceChecks\":[\"compiles cleanly\"],"
                + "\"dependsOn\":[\"Resources\"]}[/HermesTaskContract]";

        TaskDetailPolicy.Detail detail = TaskDetailPolicy.of(task(instruction, "pending"));

        assertEquals("Write the data layer foundation.", detail.description);
        assertFalse(detail.description.contains("HermesTaskContract"));
        assertFalse(detail.description.contains("expectedFiles"));
        assertTrue(detail.outputs.contains("DbHelper.java"));
        assertTrue(detail.outputs.contains("CategoryDao.java"));
        assertFalse(detail.outputs.toString().contains("app/src/main"));
        assertTrue(detail.acceptanceChecks.contains("compiles cleanly"));
        assertEquals(1, detail.dependsOn.size());
        assertEquals("Resources", detail.dependsOn.get(0));
    }

    @Test
    public void dropsRetryScaffoldingFromDescription() {
        String instruction = "Write Java source wiring.\n"
                + "Additional retry/repair context:\nThis is a retry of an existing source tree.\n"
                + "Previous failure summary: something failed.";

        TaskDetailPolicy.Detail detail = TaskDetailPolicy.of(task(instruction, "failed"));

        assertEquals("Write Java source wiring.", detail.description);
        assertFalse(detail.description.toLowerCase().contains("previous failure"));
    }

    @Test
    public void fallsBackToProducesWhenNoExpectedFiles() {
        String instruction = "Build the stats screen."
                + "\n[HermesTaskContract]{\"produces\":[\"statistics UI\",\"charts\"]}[/HermesTaskContract]";

        TaskDetailPolicy.Detail detail = TaskDetailPolicy.of(task(instruction, "pending"));

        assertTrue(detail.outputs.contains("statistics UI"));
        assertTrue(detail.outputs.contains("charts"));
    }

    private static ProjectTaskRecord task(String instruction, String status) {
        return new ProjectTaskRecord(1, 1, 0, "Java source wiring", instruction, status, "", 0, 0, 0, 0);
    }
}
