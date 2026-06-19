package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MilestoneCheckpointStoreTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void checkpointDir_isUnderProjectRootByOrder() {
        File root = new File("/tmp/project-9");
        assertEquals(new File("/tmp/project-9/checkpoints/m0"), MilestoneCheckpointStore.checkpointDir(root, 0));
        assertEquals(new File("/tmp/project-9/checkpoints/m3"), MilestoneCheckpointStore.checkpointDir(root, 3));
    }

    @Test
    public void saveThenRestore_roundTripsTheSourceTree() throws Exception {
        File projectRoot = temporaryFolder.newFolder("project");
        File sourceDir = new File(projectRoot, "source");
        FileUtils.writeText(new File(sourceDir, "app/src/main/java/A.java"), "class A {}");
        FileUtils.writeText(new File(sourceDir, "app/build.gradle"), "android {}");

        File ckpt = MilestoneCheckpointStore.checkpointDir(projectRoot, 0);
        MilestoneCheckpointStore.save(sourceDir, ckpt);
        assertTrue(MilestoneCheckpointStore.exists(ckpt));
        assertEquals("class A {}", FileUtils.readText(new File(ckpt, "app/src/main/java/A.java")));

        // A later milestone scribbles on the source and adds a bad file, then fails — roll back.
        FileUtils.writeText(new File(sourceDir, "app/src/main/java/A.java"), "class A { BROKEN }");
        FileUtils.writeText(new File(sourceDir, "app/src/main/java/Bad.java"), "garbage");

        MilestoneCheckpointStore.restore(ckpt, sourceDir);

        assertEquals("class A {}", FileUtils.readText(new File(sourceDir, "app/src/main/java/A.java")));
        assertFalse("rollback removes files added after the checkpoint",
                new File(sourceDir, "app/src/main/java/Bad.java").exists());
    }

    @Test
    public void save_overwritesAPriorCheckpointAtTheSameOrder() throws Exception {
        File projectRoot = temporaryFolder.newFolder("project");
        File sourceDir = new File(projectRoot, "source");
        File ckpt = MilestoneCheckpointStore.checkpointDir(projectRoot, 1);

        FileUtils.writeText(new File(sourceDir, "v.txt"), "v1");
        MilestoneCheckpointStore.save(sourceDir, ckpt);
        FileUtils.writeText(new File(sourceDir, "v.txt"), "v2");
        MilestoneCheckpointStore.save(sourceDir, ckpt);

        assertEquals("v2", FileUtils.readText(new File(ckpt, "v.txt")));
    }

    @Test
    public void restore_missingCheckpointThrows() {
        File projectRoot = new File(temporaryFolder.getRoot(), "project");
        File ckpt = MilestoneCheckpointStore.checkpointDir(projectRoot, 0);
        assertFalse(MilestoneCheckpointStore.exists(ckpt));
        assertThrows(Exception.class, () -> MilestoneCheckpointStore.restore(ckpt, new File(projectRoot, "source")));
    }
}
