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

public class LayoutColorAttributeReconcilerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void stripsCardBackgroundColorPointingAtDrawable() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/layout/fragment_home.xml",
                "<androidx.cardview.widget.CardView xmlns:android=\"http://schemas.android.com/apk/res/android\""
                        + " xmlns:app=\"http://schemas.android.com/apk/res-auto\""
                        + " app:cardBackgroundColor=\"@drawable/bg_card\" android:layout_width=\"wrap_content\""
                        + " android:layout_height=\"wrap_content\" />");

        List<String> fixed = LayoutColorAttributeReconciler.reconcile(root);

        String layout = FileUtils.readText(new File(root, "app/src/main/res/layout/fragment_home.xml"));
        assertFalse(layout.contains("cardBackgroundColor"));
        assertTrue(layout.contains("androidx.cardview.widget.CardView"));
        assertNull(TaskOperationsPreflight.xmlError(layout));
        assertFalse(fixed.isEmpty());
    }

    @Test
    public void stripsTextColorAndTintPointingAtDrawable() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/layout/row.xml",
                "<TextView xmlns:android=\"http://schemas.android.com/apk/res/android\""
                        + " android:textColor=\"@drawable/x\" android:backgroundTint=\"@drawable/y\" />");

        LayoutColorAttributeReconciler.reconcile(root);

        String layout = FileUtils.readText(new File(root, "app/src/main/res/layout/row.xml"));
        assertFalse(layout.contains("textColor"));
        assertFalse(layout.contains("backgroundTint"));
    }

    @Test
    public void leavesValidColorReferenceUntouched() throws Exception {
        File root = temporaryFolder.newFolder("source");
        String original = "<androidx.cardview.widget.CardView"
                + " xmlns:app=\"http://schemas.android.com/apk/res-auto\""
                + " app:cardBackgroundColor=\"@color/colorSurface\" />";
        write(root, "app/src/main/res/layout/c.xml", original);

        List<String> fixed = LayoutColorAttributeReconciler.reconcile(root);

        assertEquals(original, FileUtils.readText(new File(root, "app/src/main/res/layout/c.xml")));
        assertTrue(fixed.isEmpty());
    }

    @Test
    public void leavesAndroidBackgroundDrawableUntouched() throws Exception {
        // android:background legitimately takes a drawable — must NOT be stripped.
        File root = temporaryFolder.newFolder("source");
        String original = "<View xmlns:android=\"http://schemas.android.com/apk/res/android\""
                + " android:background=\"@drawable/bg_card\" />";
        write(root, "app/src/main/res/layout/v.xml", original);

        List<String> fixed = LayoutColorAttributeReconciler.reconcile(root);

        assertEquals(original, FileUtils.readText(new File(root, "app/src/main/res/layout/v.xml")));
        assertTrue(fixed.isEmpty());
    }

    private void write(File root, String path, String content) throws Exception {
        FileUtils.writeText(new File(root, path), content);
    }
}
