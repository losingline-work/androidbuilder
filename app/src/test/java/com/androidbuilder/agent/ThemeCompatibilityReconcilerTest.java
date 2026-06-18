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

public class ThemeCompatibilityReconcilerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final String MATERIAL_DEP = "dependencies { implementation 'com.google.android.material:material:1.12.0' }\n";
    private static final String APPCOMPAT_DEP = "dependencies { implementation 'androidx.appcompat:appcompat:1.7.0' }\n";
    private static final String MANIFEST =
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">"
                    + "<application android:theme=\"@style/AppTheme\"><activity android:name=\".MainActivity\" /></application></manifest>";
    private static final String FRAMEWORK_STYLES =
            "<resources>\n    <style name=\"AppTheme\" parent=\"android:style/Theme.Material.Light.NoActionBar\">\n"
                    + "        <item name=\"android:colorAccent\">#FF0000</item>\n    </style>\n</resources>\n";

    @Test
    public void rewritesFrameworkThemeWhenAppCompatActivityUsedWithDependency() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", MATERIAL_DEP);
        write(root, "app/src/main/AndroidManifest.xml", MANIFEST);
        write(root, "app/src/main/res/values/styles.xml", FRAMEWORK_STYLES);
        write(root, "app/src/main/java/com/x/MainActivity.java",
                "package com.x;\npublic class MainActivity extends androidx.appcompat.app.AppCompatActivity {}\n");

        List<String> fixed = ThemeCompatibilityReconciler.reconcile(root);

        String styles = FileUtils.readText(new File(root, "app/src/main/res/values/styles.xml"));
        assertTrue(styles.contains("parent=\"Theme.Material3.DayNight.NoActionBar\""));
        assertFalse(styles.contains("Theme.Material.Light.NoActionBar"));
        // The style's own items are preserved (additive rewrite of only the parent).
        assertTrue(styles.contains("android:colorAccent"));
        assertNull(TaskOperationsPreflight.xmlError(styles));
        assertFalse(fixed.isEmpty());
    }

    @Test
    public void rewritesToAppCompatParentWhenOnlyAppCompatDependencyPresent() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", APPCOMPAT_DEP);
        write(root, "app/src/main/AndroidManifest.xml", MANIFEST);
        write(root, "app/src/main/res/values/styles.xml", FRAMEWORK_STYLES);
        write(root, "app/src/main/java/com/x/MainActivity.java",
                "package com.x;\npublic class MainActivity extends androidx.appcompat.app.AppCompatActivity {}\n");

        ThemeCompatibilityReconciler.reconcile(root);

        String styles = FileUtils.readText(new File(root, "app/src/main/res/values/styles.xml"));
        assertTrue(styles.contains("parent=\"Theme.AppCompat.DayNight.NoActionBar\""));
    }

    @Test
    public void noOpWhenDependencyAbsent() throws Exception {
        // No AppCompat/Material dependency -> AppCompatActivity would fail to COMPILE, a build error, not a
        // launch crash; the theme guard correctly stays out.
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", "dependencies { }\n");
        write(root, "app/src/main/AndroidManifest.xml", MANIFEST);
        write(root, "app/src/main/res/values/styles.xml", FRAMEWORK_STYLES);
        write(root, "app/src/main/java/com/x/MainActivity.java",
                "package com.x;\npublic class MainActivity extends androidx.appcompat.app.AppCompatActivity {}\n");

        List<String> fixed = ThemeCompatibilityReconciler.reconcile(root);

        assertEquals(FRAMEWORK_STYLES, FileUtils.readText(new File(root, "app/src/main/res/values/styles.xml")));
        assertTrue(fixed.isEmpty());
    }

    @Test
    public void noOpWhenThemeAlreadyCompatible() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", MATERIAL_DEP);
        write(root, "app/src/main/AndroidManifest.xml", MANIFEST);
        String compatible = "<resources>\n    <style name=\"AppTheme\" parent=\"Theme.Material3.DayNight.NoActionBar\" />\n</resources>\n";
        write(root, "app/src/main/res/values/styles.xml", compatible);
        write(root, "app/src/main/java/com/x/MainActivity.java",
                "package com.x;\npublic class MainActivity extends androidx.appcompat.app.AppCompatActivity {}\n");

        List<String> fixed = ThemeCompatibilityReconciler.reconcile(root);

        assertEquals(compatible, FileUtils.readText(new File(root, "app/src/main/res/values/styles.xml")));
        assertTrue(fixed.isEmpty());
    }

    @Test
    public void noOpWhenNoAppCompatOrMaterialUsage() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", MATERIAL_DEP);
        write(root, "app/src/main/AndroidManifest.xml", MANIFEST);
        write(root, "app/src/main/res/values/styles.xml", FRAMEWORK_STYLES);
        write(root, "app/src/main/java/com/x/MainActivity.java",
                "package com.x;\npublic class MainActivity extends android.app.Activity {}\n");

        List<String> fixed = ThemeCompatibilityReconciler.reconcile(root);

        assertEquals(FRAMEWORK_STYLES, FileUtils.readText(new File(root, "app/src/main/res/values/styles.xml")));
        assertTrue(fixed.isEmpty());
    }

    @Test
    public void rewritesNightVariantTooAndIsTriggeredByMaterialWidgets() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", MATERIAL_DEP);
        write(root, "app/src/main/AndroidManifest.xml", MANIFEST);
        write(root, "app/src/main/res/values/styles.xml", FRAMEWORK_STYLES);
        write(root, "app/src/main/res/values-night/styles.xml", FRAMEWORK_STYLES);
        // No AppCompatActivity; the trigger is a Material widget in a layout.
        write(root, "app/src/main/java/com/x/MainActivity.java",
                "package com.x;\npublic class MainActivity extends android.app.Activity {}\n");
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<com.google.android.material.button.MaterialButton xmlns:android=\"http://schemas.android.com/apk/res/android\" />");

        ThemeCompatibilityReconciler.reconcile(root);

        assertTrue(FileUtils.readText(new File(root, "app/src/main/res/values/styles.xml")).contains("Theme.Material3"));
        assertTrue(FileUtils.readText(new File(root, "app/src/main/res/values-night/styles.xml")).contains("Theme.Material3"));
    }

    private void write(File root, String path, String content) throws Exception {
        FileUtils.writeText(new File(root, path), content);
    }
}
