package com.androidbuilder.agent;

import com.androidbuilder.model.AppSpec;
import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GeneratedProjectWriterTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void writesJavaOnlyProjectSkeleton() throws Exception {
        File root = temporaryFolder.newFolder("source");
        AppSpec spec = new AppSpec("Demo", "com.example.demo", "Demo app", "Item", "Title", "Notes", "en");

        new GeneratedProjectWriter().write(root, spec);

        assertTrue(new File(root, "app/src/main/java/com/example/demo/MainActivity.java").exists());
        assertTrue(new File(root, "app/src/main/java/com/example/demo/EditItemActivity.java").exists());
        assertTrue(new File(root, "app/src/main/java/com/example/demo/ItemDbHelper.java").exists());
        assertFalse(containsFileWithSuffix(root, ".kt"));
        String rootBuild = FileUtils.readText(new File(root, "build.gradle"));
        String appBuild = FileUtils.readText(new File(root, "app/build.gradle"));
        assertFalse(rootBuild.contains("kotlin"));
        assertFalse(appBuild.contains("kotlin"));
        assertTrue(appBuild.contains("sourceCompatibility JavaVersion.VERSION_1_8"));
        assertTrue(appBuild.contains("targetCompatibility JavaVersion.VERSION_1_8"));
        assertFalse(appBuild.contains("VERSION_17"));
    }

    private boolean containsFileWithSuffix(File file, String suffix) {
        if (file.isFile()) {
            return file.getName().endsWith(suffix);
        }
        File[] children = file.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (containsFileWithSuffix(child, suffix)) {
                return true;
            }
        }
        return false;
    }
}
