package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class CanonicalPathPolicyTest {
    @Test
    public void canonicalizeMapsCommonAndroidShortPathsToAppSourceLayout() {
        assertEquals("app/src/main/res/drawable/ic_add.xml",
                CanonicalPathPolicy.canonicalize("res/drawable/ic_add.xml"));
        assertEquals("app/src/main/res/values/colors.xml",
                CanonicalPathPolicy.canonicalize("res/values/colors.xml"));
        assertEquals("app/src/main/AndroidManifest.xml",
                CanonicalPathPolicy.canonicalize("AndroidManifest.xml"));
        assertEquals("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
                CanonicalPathPolicy.canonicalize("res/mipmap-anydpi-v26/ic_launcher.xml"));
        assertEquals("app/src/main/java/com/example/MainActivity.java",
                CanonicalPathPolicy.canonicalize("src/main/java/com/example/MainActivity.java"));
        assertEquals("app/src/main/res/layout/activity_main.xml",
                CanonicalPathPolicy.canonicalize("app/res/layout/activity_main.xml"));
    }

    @Test
    public void canonicalizeLeavesCanonicalAndRootProjectFilesUntouched() {
        assertEquals("app/src/main/res/layout/activity_main.xml",
                CanonicalPathPolicy.canonicalize("app/src/main/res/layout/activity_main.xml"));
        assertEquals("app/build.gradle", CanonicalPathPolicy.canonicalize("app/build.gradle"));
        assertEquals("settings.gradle", CanonicalPathPolicy.canonicalize("settings.gradle"));
    }

    @Test
    public void canonicalizeAllDeduplicatesAfterCanonicalizationKeepingLastOperationPosition() {
        TaskOperations operations = new TaskOperations("resources", Arrays.asList(
                new FileOperation("write", "res/values/colors.xml", "<resources><color name=\"primary\">#000000</color></resources>"),
                new FileOperation("write", "app/src/main/res/layout/activity_main.xml", "<LinearLayout />"),
                new FileOperation("write", "app/src/main/res/values/colors.xml", "<resources><color name=\"primary\">#ffffff</color></resources>")
        ));

        TaskOperations canonical = CanonicalPathPolicy.canonicalizeAll(operations);

        assertEquals("resources", canonical.summary);
        assertEquals(2, canonical.operations.size());
        assertEquals("app/src/main/res/layout/activity_main.xml", canonical.operations.get(0).path);
        assertEquals("app/src/main/res/values/colors.xml", canonical.operations.get(1).path);
        assertEquals("<resources><color name=\"primary\">#ffffff</color></resources>",
                canonical.operations.get(1).content);
    }

    @Test
    public void canonicalizeRejectsUnsafePaths() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CanonicalPathPolicy.canonicalize("../settings.gradle"));

        assertEquals("Unsafe generated file path: ../settings.gradle", error.getMessage());
    }
}
