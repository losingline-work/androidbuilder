package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ResourceIndexDigestTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void digestExtractsSortedDedupedResourceNames() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" android:id=\"@+id/root\"><TextView android:id=\"@+id/title\" /><Button android:id=\"@+id/save_button\" /></LinearLayout>");
        write(root, "app/src/main/res/layout-land/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" android:id=\"@+id/root\" />");
        write(root, "app/src/main/res/menu/menu_main.xml",
                "<menu xmlns:android=\"http://schemas.android.com/apk/res/android\"><item android:id=\"@+id/action_settings\" /></menu>");
        write(root, "app/src/main/res/drawable/ic_add.xml",
                "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" />");
        write(root, "app/src/main/res/color/chip_tint.xml",
                "<selector xmlns:android=\"http://schemas.android.com/apk/res/android\" />");
        write(root, "app/src/main/res/values/strings.xml",
                "<resources><string name=\"app_name\">Demo</string><string name=\"save\">Save</string></resources>");
        write(root, "app/src/main/res/values/colors.xml",
                "<resources><color name=\"primary\">#336699</color></resources>");
        write(root, "app/src/main/res/values/dimens.xml",
                "<resources><dimen name=\"space_m\">16dp</dimen></resources>");
        write(root, "app/src/main/res/values/styles.xml",
                "<resources><style name=\"Theme.Demo\" /></resources>");

        String digest = ResourceIndexDigest.digest(root);

        assertEquals("R.id: action_settings, root, save_button, title | R.layout: activity_main | R.menu: menu_main | R.drawable: ic_add | R.color: chip_tint, primary | R.dimen: space_m | R.string: app_name, save | R.style: Theme_Demo", digest);
    }

    @Test
    public void budgetedDigestNeverTruncatesIdsLayoutsOrStrings() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" android:id=\"@+id/critical_title\" />");
        write(root, "app/src/main/res/values/strings.xml",
                "<resources><string name=\"critical_label\">Label</string></resources>");
        for (int i = 0; i < 20; i++) {
            write(root, "app/src/main/res/drawable/ic_extra_" + i + ".xml",
                    "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" />");
        }

        String digest = ResourceIndexDigest.digest(root, 80);

        assertTrue(digest.contains("R.id: critical_title"));
        assertTrue(digest.contains("R.layout: activity_main"));
        assertTrue(digest.contains("R.string: critical_label"));
        assertTrue(digest.contains("...[truncated]"));
    }

    private static void write(File root, String path, String content) throws Exception {
        FileUtils.writeText(new File(root, path), content);
    }
}
