package com.androidbuilder.agent;

import com.androidbuilder.model.TaskManifest;
import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BatchEscalationPolicyTest {
    @Test
    public void blocksMissingResourceWhenManifestCannotProduceIt() {
        TaskManifest manifest = new TaskManifest("task", Collections.singletonList(
                new TaskManifest.Entry("app/src/main/java/com/example/MainActivity.java", "write", "activity")), false, "", "");

        TaskOperations blocked = BatchEscalationPolicy.blockedIfManifestCannotProduce(
                "Batch validation: missing XML id R.id.toolbar in MainActivity.java.",
                manifest);

        assertTrue(blocked.blocked);
        assertTrue(blocked.blockedReason.contains("R.id.toolbar"));
        assertTrue(blocked.prerequisiteWork.contains("resource"));
    }

    @Test
    public void doesNotBlockWhenManifestPlansAResourceFileThatCouldProduceIt() {
        TaskManifest manifest = new TaskManifest("task", Arrays.asList(
                new TaskManifest.Entry("app/src/main/res/layout/activity_main.xml", "write", "layout"),
                new TaskManifest.Entry("app/src/main/java/com/example/MainActivity.java", "write", "activity")), false, "", "");

        TaskOperations blocked = BatchEscalationPolicy.blockedIfManifestCannotProduce(
                "Batch validation: missing XML id R.id.toolbar in MainActivity.java.",
                manifest);

        assertNull(blocked);
    }

    @Test
    public void ignoresNonResourceValidationErrors() {
        TaskManifest manifest = new TaskManifest("task", Collections.singletonList(
                new TaskManifest.Entry("app/src/main/java/com/example/MainActivity.java", "write", "activity")), false, "", "");

        TaskOperations blocked = BatchEscalationPolicy.blockedIfManifestCannotProduce(
                "Batch validation: missing planned file app/src/main/java/com/example/MainActivity.java.",
                manifest);

        assertNull(blocked);
        assertFalse(manifest.blocked);
    }
}
