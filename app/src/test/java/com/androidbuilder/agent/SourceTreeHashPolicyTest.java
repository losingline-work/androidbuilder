package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class SourceTreeHashPolicyTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void sourceHashChangesWhenTrackedFileChanges() throws Exception {
        File root = temporaryFolder.newFolder("source");
        FileUtils.writeText(new File(root, "app/src/main/java/MainActivity.java"), "class A {}\n");
        String before = SourceTreeHashPolicy.hash(root);

        FileUtils.writeText(new File(root, "app/src/main/java/MainActivity.java"), "class B {}\n");

        assertNotEquals(before, SourceTreeHashPolicy.hash(root));
    }

    @Test
    public void sourceHashIgnoresBuildDirectory() throws Exception {
        File root = temporaryFolder.newFolder("source");
        FileUtils.writeText(new File(root, "app/src/main/java/MainActivity.java"), "class A {}\n");
        FileUtils.writeText(new File(root, "app/build/generated.txt"), "before\n");
        String before = SourceTreeHashPolicy.hash(root);

        FileUtils.writeText(new File(root, "app/build/generated.txt"), "after\n");

        assertEquals(before, SourceTreeHashPolicy.hash(root));
    }
}
