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

public class CrashReporterInjectorTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final String GRADLE = "android { namespace 'com.generated.app' }\n";
    private static final String MANIFEST =
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                    + "    <application android:label=\"@string/app_name\">\n"
                    + "        <activity android:name=\".MainActivity\" />\n"
                    + "    </application>\n</manifest>\n";

    @Test
    public void injectsReporterProviderAndQueriesAndSource() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", GRADLE);
        write(root, "app/src/main/AndroidManifest.xml", MANIFEST);

        List<String> changes = CrashReporterInjector.reconcile(root);

        String manifest = FileUtils.readText(new File(root, "app/src/main/AndroidManifest.xml"));
        // app's own reporter provider (unique authority, not exported)
        assertTrue(manifest.contains("android:name=\".AbCrashReporter\""));
        assertTrue(manifest.contains("com.generated.app.abcrashreporter"));
        // package-visibility for the control app's sink
        assertTrue(manifest.contains("<queries>"));
        assertTrue(manifest.contains("com.androidbuilder.crashsink"));
        assertNull("manifest stays well-formed", TaskOperationsPreflight.xmlError(manifest));
        // the injected source
        String source = FileUtils.readText(new File(root, "app/src/main/java/com/generated/app/AbCrashReporter.java"));
        assertTrue(source.contains("package com.generated.app;"));
        assertTrue(source.contains("extends ContentProvider"));
        assertTrue(source.contains("setDefaultUncaughtExceptionHandler"));
        assertTrue(source.contains("content://com.androidbuilder.crashsink/crash"));
        assertFalse("generated source must not use lambdas", source.contains("->"));
        assertFalse(changes.isEmpty());
    }

    @Test
    public void isIdempotent() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", GRADLE);
        write(root, "app/src/main/AndroidManifest.xml", MANIFEST);

        CrashReporterInjector.reconcile(root);
        String afterFirst = FileUtils.readText(new File(root, "app/src/main/AndroidManifest.xml"));
        List<String> second = CrashReporterInjector.reconcile(root);

        assertEquals(afterFirst, FileUtils.readText(new File(root, "app/src/main/AndroidManifest.xml")));
        assertTrue(second.isEmpty());
    }

    @Test
    public void noOpWhenNamespaceMissing() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", "android { }\n");
        write(root, "app/src/main/AndroidManifest.xml", MANIFEST);

        List<String> changes = CrashReporterInjector.reconcile(root);

        assertEquals(MANIFEST, FileUtils.readText(new File(root, "app/src/main/AndroidManifest.xml")));
        assertTrue(changes.isEmpty());
    }

    private void write(File root, String path, String content) throws Exception {
        FileUtils.writeText(new File(root, path), content);
    }
}
