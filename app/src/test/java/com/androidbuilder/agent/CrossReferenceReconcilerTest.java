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

public class CrossReferenceReconcilerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void seedsMissingColorAndStringReferences() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                        + "android:background=\"@color/colorSurface\">"
                        + "<TextView android:text=\"@string/home_title\" /></LinearLayout>");

        List<String> seeded = CrossReferenceReconciler.reconcile(root);

        String colors = FileUtils.readText(new File(root, "app/src/main/res/values/colors.xml"));
        String strings = FileUtils.readText(new File(root, "app/src/main/res/values/strings.xml"));
        assertTrue(colors.contains("name=\"colorSurface\""));
        assertTrue(strings.contains("name=\"home_title\""));
        assertNull("seeded XML must be valid", TaskOperationsPreflight.xmlError(colors));
        assertNull("seeded XML must be valid", TaskOperationsPreflight.xmlError(strings));
        assertTrue(seeded.contains("@color/colorSurface"));
        assertTrue(seeded.contains("@string/home_title"));
    }

    @Test
    public void seedsMissingColorReferencedFromJava() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/x/Widget.java",
                "package com.x;\nclass Widget { int c() { return R.color.colorAccentLight; } }\n");

        CrossReferenceReconciler.reconcile(root);

        String colors = FileUtils.readText(new File(root, "app/src/main/res/values/colors.xml"));
        assertTrue(colors.contains("name=\"colorAccentLight\""));
    }

    @Test
    public void seedsEmptyMenuForReference() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<com.google.android.material.bottomnavigation.BottomNavigationView "
                        + "xmlns:app=\"http://schemas.android.com/apk/res-auto\" app:menu=\"@menu/bottom_nav_menu\" />");

        CrossReferenceReconciler.reconcile(root);

        File menu = new File(root, "app/src/main/res/menu/bottom_nav_menu.xml");
        assertTrue(menu.isFile());
        assertNull(TaskOperationsPreflight.xmlError(FileUtils.readText(menu)));
    }

    @Test
    public void seedsLauncherMipmapAndDrawableReferences() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/AndroidManifest.xml",
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">"
                        + "<application android:icon=\"@mipmap/ic_launcher\" "
                        + "android:roundIcon=\"@mipmap/ic_launcher_round\" /></manifest>");
        write(root, "app/src/main/res/drawable/bg_card.xml",
                "<selector xmlns:android=\"http://schemas.android.com/apk/res/android\">"
                        + "<item android:drawable=\"@drawable/bg_circle_light\" /></selector>");

        CrossReferenceReconciler.reconcile(root);

        assertTrue(new File(root, "app/src/main/res/mipmap/ic_launcher.xml").isFile());
        assertTrue(new File(root, "app/src/main/res/mipmap/ic_launcher_round.xml").isFile());
        assertTrue(new File(root, "app/src/main/res/drawable/bg_circle_light.xml").isFile());
    }

    @Test
    public void stubsMissingActivityClassFromFullyQualifiedReference() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", "android { namespace 'com.generated.app' }\n");
        write(root, "app/src/main/java/com/generated/app/fragment/HomeFragment.java",
                "package com.generated.app.fragment;\n"
                        + "class HomeFragment {\n"
                        + "  void go() {\n"
                        + "    android.content.Intent i = new android.content.Intent(null, com.generated.app.activity.TransactionDetailActivity.class);\n"
                        + "  }\n}\n");

        List<String> seeded = CrossReferenceReconciler.reconcile(root);

        File stub = new File(root, "app/src/main/java/com/generated/app/activity/TransactionDetailActivity.java");
        assertTrue(stub.isFile());
        String content = FileUtils.readText(stub);
        assertTrue(content.contains("package com.generated.app.activity;"));
        assertTrue(content.contains("class TransactionDetailActivity extends android.app.Activity"));
        assertTrue(content.contains(StubReconciler.STUB_TAG));
        assertTrue(seeded.contains("com.generated.app.activity.TransactionDetailActivity (class stub)"));
    }

    @Test
    public void doesNotStubExistingClassOrFrameworkNames() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", "android { namespace 'com.generated.app' }\n");
        write(root, "app/src/main/java/com/generated/app/model/Transaction.java",
                "package com.generated.app.model;\npublic class Transaction {}\n");
        write(root, "app/src/main/java/com/generated/app/Caller.java",
                "package com.generated.app;\n"
                        + "class Caller {\n"
                        + "  Object a = com.generated.app.model.Transaction.class;\n"   // existing -> no stub
                        + "  int b = com.generated.app.R.string.app_name;\n"             // R -> never stubbed
                        + "}\n");

        CrossReferenceReconciler.reconcile(root);

        // The existing Transaction.java is untouched (still the real class, not a stub).
        String transaction = FileUtils.readText(new File(root, "app/src/main/java/com/generated/app/model/Transaction.java"));
        assertFalse(transaction.contains(StubReconciler.STUB_TAG));
        // R is never materialized as a source file.
        assertFalse(new File(root, "app/src/main/java/com/generated/app/R.java").exists());
    }

    @Test
    public void neverOverwritesAnExistingResource() throws Exception {
        File root = temporaryFolder.newFolder("source");
        String original = "<resources>\n    <color name=\"colorPrimary\">#FF6200EE</color>\n</resources>\n";
        write(root, "app/src/main/res/values/colors.xml", original);
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<View xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                        + "android:background=\"@color/colorPrimary\" />");

        List<String> seeded = CrossReferenceReconciler.reconcile(root);

        // colorPrimary already exists -> unchanged, not re-declared, not reported.
        assertEquals(original, FileUtils.readText(new File(root, "app/src/main/res/values/colors.xml")));
        assertTrue(seeded.isEmpty());
    }

    @Test
    public void isIdempotentAndNoOpWhenTreeIsAlreadyWhole() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<View xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                        + "android:background=\"@color/colorSurface\" />");

        List<String> first = CrossReferenceReconciler.reconcile(root);
        List<String> second = CrossReferenceReconciler.reconcile(root);

        assertFalse(first.isEmpty());
        assertTrue("a second pass over a now-whole tree seeds nothing", second.isEmpty());
    }

    @Test
    public void doesNotSeedFrameworkNamespacedReferences() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<View xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                        + "android:background=\"@android:color/white\" />");

        List<String> seeded = CrossReferenceReconciler.reconcile(root);

        assertTrue(seeded.isEmpty());
        assertFalse(new File(root, "app/src/main/res/values/colors.xml").exists());
    }

    @Test
    public void doesNotClobberAMalformedExistingValuesFile() throws Exception {
        File root = temporaryFolder.newFolder("source");
        String malformed = "<resources>\n    <color name=\"colorPrimary\">#FF6200EE</color>\n"; // no </resources>
        write(root, "app/src/main/res/values/colors.xml", malformed);
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<View xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                        + "android:background=\"@color/colorSurface\" />");

        CrossReferenceReconciler.reconcile(root);

        // The malformed model file is left exactly as-is rather than rebuilt/clobbered.
        assertEquals(malformed, FileUtils.readText(new File(root, "app/src/main/res/values/colors.xml")));
    }

    private void write(File root, String path, String content) throws Exception {
        FileUtils.writeText(new File(root, path), content);
    }
}
