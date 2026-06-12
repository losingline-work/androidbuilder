package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;
import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class FileOperationsWriterTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rejectedOperationsDoNotModifySourceDirectory() throws Exception {
        File root = temporaryFolder.newFolder("source");
        writeRequiredProjectFiles(root);
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" />");
        write(root, "app/src/main/java/com/example/MainActivity.java",
                "package com.example;\nclass MainActivity {}\n");

        TaskOperations badOperations = new TaskOperations("bad", Collections.singletonList(
                new FileOperation("write", "app/src/main/java/com/example/MainActivity.kt", "package com.example\nclass MainActivity")
        ));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                new FileOperationsWriter().apply(root, badOperations));

        assertEquals("Generated source policy blocked Kotlin source file: MainActivity.kt. Use Java source files (.java) only.", error.getMessage());
        assertFalse(new File(root, "app/src/main/java/com/example/MainActivity.kt").exists());
        assertEquals("package com.example;\nclass MainActivity {}\n",
                FileUtils.readText(new File(root, "app/src/main/java/com/example/MainActivity.java")));
    }

    @Test
    public void acceptedOperationsReplaceSourceDirectory() throws Exception {
        File root = temporaryFolder.newFolder("source");
        writeRequiredProjectFiles(root);
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" />");

        TaskOperations operations = new TaskOperations("ok", Arrays.asList(
                new FileOperation("write", "app/src/main/res/layout/activity_main.xml",
                        "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"><TextView android:id=\"@+id/title\" /></LinearLayout>"),
                new FileOperation("write", "app/src/main/java/com/example/MainActivity.java",
                        "package com.example;\nclass MainActivity { void bind() { Object title = findViewById(R.id.title); } }\n")
        ));

        new FileOperationsWriter().apply(root, operations);

        assertEquals("package com.example;\nclass MainActivity { void bind() { Object title = findViewById(R.id.title); } }\n",
                FileUtils.readText(new File(root, "app/src/main/java/com/example/MainActivity.java")));
    }

    @Test
    public void fillsMissingDbHelperColumnConstantsBeforeSourceGuardRuns() throws Exception {
        File root = temporaryFolder.newFolder("source");
        writeRequiredProjectFiles(root);
        write(root, "app/src/main/java/com/example/DBHelper.java",
                "package com.example;\nclass DBHelper {\n    public static final String TABLE_CATEGORY = \"categories\";\n}\n");

        TaskOperations operations = new TaskOperations("add category dao", Collections.singletonList(
                new FileOperation("write", "app/src/main/java/com/example/CategoryDao.java",
                        "package com.example;\nclass CategoryDao { String where() { return DBHelper.COL_CATEGORY_ID + \"=?\"; } }\n")
        ));

        new FileOperationsWriter().apply(root, operations);

        String helper = FileUtils.readText(new File(root, "app/src/main/java/com/example/DBHelper.java"));
        assertTrue(helper.contains("public static final String COL_CATEGORY_ID = \"category_id\";"));
        assertTrue(new File(root, "app/src/main/java/com/example/CategoryDao.java").exists());
    }

    @Test
    public void rejectedOperationsCannotRemoveRequiredProjectFiles() throws Exception {
        File root = temporaryFolder.newFolder("source");
        writeRequiredProjectFiles(root);

        TaskOperations operations = new TaskOperations("bad", Collections.singletonList(
                new FileOperation("delete", "app/src/main/AndroidManifest.xml", "")
        ));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                new FileOperationsWriter().apply(root, operations));

        assertEquals("Generated source policy blocked deletion of required project file: app/src/main/AndroidManifest.xml. Keep it in the project.", error.getMessage());
        assertTrue(new File(root, "app/src/main/AndroidManifest.xml").exists());
    }

    @Test
    public void rejectedOperationsCannotApplyUnknownActions() throws Exception {
        File root = temporaryFolder.newFolder("source");
        writeRequiredProjectFiles(root);

        TaskOperations operations = new TaskOperations("bad", Collections.singletonList(
                new FileOperation("drop", "app/src/main/res/values/extra.xml", "")
        ));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                new FileOperationsWriter().apply(root, operations));

        assertEquals("Unsupported file operation action: drop", error.getMessage());
        assertFalse(new File(root, "app/src/main/res/values/extra.xml").exists());
    }

    @Test
    public void rejectedOperationsCannotApplyUncanonicalAndroidPaths() throws Exception {
        File root = temporaryFolder.newFolder("source");
        writeRequiredProjectFiles(root);

        TaskOperations operations = new TaskOperations("bad", Collections.singletonList(
                new FileOperation("write", "res/values/colors.xml", "<resources />")
        ));

        IOException error = assertThrows(IOException.class, () ->
                new FileOperationsWriter().apply(root, operations));

        assertEquals("Operation path is not in canonical Android layout: res/values/colors.xml; use app/src/main/...",
                error.getMessage());
        assertFalse(new File(root, "res/values/colors.xml").exists());
        assertFalse(new File(root, "app/src/main/res/values/colors.xml").exists());
    }

    @Test
    public void scaffoldTaskMayWriteGradleOnlyBeforeManifestExists() throws Exception {
        // A fresh project starts empty; the first task writes only the Gradle skeleton. The Manifest
        // arrives in a later task, so this must NOT fail on a missing required file.
        File root = temporaryFolder.newFolder("source");

        TaskOperations gradleOnly = new TaskOperations("gradle skeleton", Arrays.asList(
                new FileOperation("write", "settings.gradle", "rootProject.name = 'App'\ninclude ':app'\n"),
                new FileOperation("write", "build.gradle", "plugins { id 'com.android.application' version '8.7.3' apply false }\n"),
                new FileOperation("write", "app/build.gradle",
                        "plugins { id 'com.android.application' }\n" +
                                "android { namespace 'com.example'; compileSdk 34\n" +
                                "    defaultConfig { applicationId 'com.example'; minSdk 24; targetSdk 34; versionCode 1; versionName '1.0' }\n" +
                                "}\n")));

        new FileOperationsWriter().apply(root, gradleOnly);

        assertTrue(new File(root, "settings.gradle").exists());
        assertFalse(new File(root, "app/src/main/AndroidManifest.xml").exists());
        // Completeness is reported at build time, not per task.
        assertEquals("app/src/main/AndroidManifest.xml", FileOperationsWriter.firstMissingRequiredProjectFile(root));
    }

    private void write(File root, String path, String content) throws Exception {
        FileUtils.writeText(new File(root, path), content);
    }

    private void writeRequiredProjectFiles(File root) throws Exception {
        write(root, "settings.gradle", "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ninclude ':app'\n");
        write(root, "build.gradle", "plugins { id 'com.android.application' version '8.7.3' apply false }\n");
        write(root, "app/build.gradle",
                "plugins { id 'com.android.application' }\n" +
                        "android { namespace 'com.example'; compileSdk 34\n" +
                        "    defaultConfig { applicationId 'com.example'; minSdk 24; targetSdk 34; versionCode 1; versionName '1.0' }\n" +
                        "}\n");
        write(root, "app/src/main/AndroidManifest.xml", "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" />\n");
    }
}
