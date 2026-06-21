package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LayoutValueReconcilerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rewritesPercentSizeToMatchParent() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                        + "android:layout_width=\"100%\" android:layout_height=\"100%\" />");

        LayoutValueReconciler.reconcile(root);

        String xml = FileUtils.readText(new File(root, "app/src/main/res/layout/activity_main.xml"));
        assertFalse(xml.contains("100%"));
        assertTrue(xml.contains("android:layout_width=\"match_parent\""));
        assertTrue(xml.contains("android:layout_height=\"match_parent\""));
        assertNull(TaskOperationsPreflight.xmlError(xml));
    }

    @Test
    public void rewritesPrivateMenuDrawableToLocalReference() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/menu/main_menu.xml",
                "<menu xmlns:android=\"http://schemas.android.com/apk/res/android\">"
                        + "<item android:icon=\"@android:drawable/ic_menu_home\" android:title=\"Home\" /></menu>");

        LayoutValueReconciler.reconcile(root);

        String xml = FileUtils.readText(new File(root, "app/src/main/res/menu/main_menu.xml"));
        assertFalse(xml.contains("@android:drawable/ic_menu_home"));
        assertTrue(xml.contains("@drawable/fw_ic_menu_home"));

        // CrossReferenceReconciler then seeds a valid placeholder for the rewritten local drawable.
        CrossReferenceReconciler.reconcile(root);
        File placeholder = new File(root, "app/src/main/res/drawable/fw_ic_menu_home.xml");
        assertTrue(placeholder.isFile());
        assertNull(TaskOperationsPreflight.xmlError(FileUtils.readText(placeholder)));
    }

    @Test
    public void leavesPublicFrameworkDrawablesAndNormalSizesAlone() throws Exception {
        File root = temporaryFolder.newFolder("source");
        String original = "<ImageView xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                + "android:layout_width=\"match_parent\" android:layout_height=\"48dp\" "
                + "android:src=\"@android:drawable/ic_dialog_alert\" />";
        write(root, "app/src/main/res/layout/row.xml", original);

        LayoutValueReconciler.reconcile(root);

        assertTrue(original.equals(FileUtils.readText(new File(root, "app/src/main/res/layout/row.xml"))));
    }

    private void write(File root, String path, String content) throws Exception {
        FileUtils.writeText(new File(root, path), content);
    }
}
