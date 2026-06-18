package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ManifestCompletenessPolicyTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final String GRADLE = "android { namespace 'com.app' }\n";

    private static String manifest(String activitiesXml) {
        return "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                + "    <application android:label=\"@string/app_name\">\n" + activitiesXml + "    </application>\n</manifest>\n";
    }

    private static final String LAUNCHER_MAIN =
            "        <activity android:name=\".MainActivity\" android:exported=\"true\">\n"
                    + "            <intent-filter>\n"
                    + "                <action android:name=\"android.intent.action.MAIN\" />\n"
                    + "                <category android:name=\"android.intent.category.LAUNCHER\" />\n"
                    + "            </intent-filter>\n        </activity>\n";

    @Test
    public void declaresStartedShortFormActivity() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", GRADLE);
        write(root, "app/src/main/AndroidManifest.xml", manifest(LAUNCHER_MAIN));
        write(root, "app/src/main/java/com/app/MainActivity.java",
                "package com.app;\npublic class MainActivity extends android.app.Activity { void go() {"
                        + " startActivity(new android.content.Intent(this, DetailActivity.class)); } }\n");
        write(root, "app/src/main/java/com/app/DetailActivity.java",
                "package com.app;\npublic class DetailActivity extends android.app.Activity {}\n");

        List<String> changes = ManifestCompletenessPolicy.reconcile(root);

        String manifest = FileUtils.readText(new File(root, "app/src/main/AndroidManifest.xml"));
        assertTrue(manifest.contains("android:name=\".DetailActivity\""));
        assertNull(TaskOperationsPreflight.xmlError(manifest));
        assertTrue(changes.stream().anyMatch(c -> c.contains("DetailActivity")));
    }

    @Test
    public void declaresStartedFullyQualifiedActivity() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", GRADLE);
        write(root, "app/src/main/AndroidManifest.xml", manifest(LAUNCHER_MAIN));
        write(root, "app/src/main/java/com/app/MainActivity.java",
                "package com.app;\npublic class MainActivity extends android.app.Activity { void go() {"
                        + " startActivity(new android.content.Intent(this, com.app.detail.DetailActivity.class)); } }\n");
        write(root, "app/src/main/java/com/app/detail/DetailActivity.java",
                "package com.app.detail;\npublic class DetailActivity extends android.app.Activity {}\n");

        ManifestCompletenessPolicy.reconcile(root);

        String manifest = FileUtils.readText(new File(root, "app/src/main/AndroidManifest.xml"));
        assertTrue(manifest.contains("android:name=\".detail.DetailActivity\""));
    }

    @Test
    public void doesNotDeclareNonActivityClass() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", GRADLE);
        write(root, "app/src/main/AndroidManifest.xml", manifest(LAUNCHER_MAIN));
        write(root, "app/src/main/java/com/app/MainActivity.java",
                "package com.app;\npublic class MainActivity extends android.app.Activity { Object t = Helper.class; }\n");
        write(root, "app/src/main/java/com/app/Helper.java",
                "package com.app;\npublic class Helper {}\n");

        List<String> changes = ManifestCompletenessPolicy.reconcile(root);

        assertFalse(FileUtils.readText(new File(root, "app/src/main/AndroidManifest.xml")).contains("Helper"));
        assertTrue(changes.isEmpty());
    }

    @Test
    public void ignoresFrameworkClassLiterals() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", GRADLE);
        write(root, "app/src/main/AndroidManifest.xml", manifest(LAUNCHER_MAIN));
        write(root, "app/src/main/java/com/app/MainActivity.java",
                "package com.app;\npublic class MainActivity extends android.app.Activity {"
                        + " Object a = String.class; Object b = android.app.Activity.class; }\n");

        List<String> changes = ManifestCompletenessPolicy.reconcile(root);

        assertTrue(changes.isEmpty());
    }

    @Test
    public void isIdempotentAndDoesNotRedeclare() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", GRADLE);
        write(root, "app/src/main/AndroidManifest.xml",
                manifest(LAUNCHER_MAIN + "        <activity android:name=\".DetailActivity\" />\n"));
        write(root, "app/src/main/java/com/app/MainActivity.java",
                "package com.app;\npublic class MainActivity extends android.app.Activity { void go() {"
                        + " startActivity(new android.content.Intent(this, DetailActivity.class)); } }\n");
        write(root, "app/src/main/java/com/app/DetailActivity.java",
                "package com.app;\npublic class DetailActivity extends android.app.Activity {}\n");

        String before = FileUtils.readText(new File(root, "app/src/main/AndroidManifest.xml"));
        List<String> changes = ManifestCompletenessPolicy.reconcile(root);

        assertEquals(before, FileUtils.readText(new File(root, "app/src/main/AndroidManifest.xml")));
        assertTrue(changes.isEmpty());
    }

    @Test
    public void addsLauncherWhenNoneExists() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", GRADLE);
        write(root, "app/src/main/AndroidManifest.xml",
                manifest("        <activity android:name=\".MainActivity\" />\n"));
        write(root, "app/src/main/java/com/app/MainActivity.java",
                "package com.app;\npublic class MainActivity extends android.app.Activity {}\n");

        ManifestCompletenessPolicy.reconcile(root);

        String manifest = FileUtils.readText(new File(root, "app/src/main/AndroidManifest.xml"));
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"));
        assertTrue(manifest.contains("android:exported=\"true\""));
        assertNull(TaskOperationsPreflight.xmlError(manifest));
    }

    @Test
    public void noOpWhenLauncherAlreadyPresentAndNoMissingActivities() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", GRADLE);
        write(root, "app/src/main/AndroidManifest.xml", manifest(LAUNCHER_MAIN));
        write(root, "app/src/main/java/com/app/MainActivity.java",
                "package com.app;\npublic class MainActivity extends android.app.Activity {}\n");

        String before = FileUtils.readText(new File(root, "app/src/main/AndroidManifest.xml"));
        List<String> changes = ManifestCompletenessPolicy.reconcile(root);

        assertEquals(before, FileUtils.readText(new File(root, "app/src/main/AndroidManifest.xml")));
        assertTrue(changes.isEmpty());
    }

    @Test
    public void noOpWhenNamespaceMissing() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", "android { }\n");
        write(root, "app/src/main/AndroidManifest.xml", manifest(LAUNCHER_MAIN));
        write(root, "app/src/main/java/com/app/MainActivity.java",
                "package com.app;\npublic class MainActivity extends android.app.Activity { Object x ="
                        + " new android.content.Intent(this, DetailActivity.class); }\n");
        write(root, "app/src/main/java/com/app/DetailActivity.java",
                "package com.app;\npublic class DetailActivity extends android.app.Activity {}\n");

        List<String> changes = ManifestCompletenessPolicy.reconcile(root);

        assertTrue(changes.isEmpty());
    }

    private void write(File root, String path, String content) throws Exception {
        FileUtils.writeText(new File(root, path), content);
    }
}
