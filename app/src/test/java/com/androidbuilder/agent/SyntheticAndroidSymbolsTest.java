package com.androidbuilder.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class SyntheticAndroidSymbolsTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void modelsIdsValuesAndFileResourcesFromNamespace() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle",
                "plugins { id 'com.android.application' }\nandroid { namespace 'com.example.app'; compileSdk 34 }\n");
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\">"
                        + "<TextView android:id=\"@+id/title\"/><Button android:id=\"@+id/save\"/></LinearLayout>");
        write(root, "app/src/main/res/values/strings.xml",
                "<resources><string name=\"app_name\">App</string><string name=\"greeting\">Hi</string></resources>");
        write(root, "app/src/main/res/values/colors.xml",
                "<resources><color name=\"brand\">#fff</color></resources>");
        write(root, "app/src/main/res/drawable/ic_logo.xml", "<vector/>");
        write(root, "app/src/main/res/menu/main_menu.xml", "<menu/>");

        SyntheticAndroidSymbols symbols = SyntheticAndroidSymbols.from(root);

        assertEquals("com.example.app", symbols.packageName);
        String r = symbols.rJavaSource;
        assertTrue(r.contains("package com.example.app;"));
        assertTrue(r.contains("public static final class id {"));
        assertTrue(r.contains("public static final int title ="));
        assertTrue(r.contains("public static final int save ="));
        assertTrue(r.contains("public static final int app_name ="));
        assertTrue(r.contains("public static final int greeting ="));
        assertTrue(r.contains("public static final int brand ="));
        assertTrue(r.contains("public static final int ic_logo ="));
        assertTrue(r.contains("public static final int main_menu ="));
    }

    @Test
    public void buildConfigCarriesStandardMembers() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", "android { namespace 'com.x' }\n");

        SyntheticAndroidSymbols symbols = SyntheticAndroidSymbols.from(root);

        assertTrue(symbols.buildConfigSource.contains("package com.x;"));
        assertTrue(symbols.buildConfigSource.contains("public static final boolean DEBUG = true;"));
        assertTrue(symbols.buildConfigSource.contains("public static final String APPLICATION_ID = \"com.x\";"));
    }

    @Test
    public void dottedResourceNamesAreSanitizedToJavaIdentifiers() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", "android { namespace 'com.x' }\n");
        write(root, "app/src/main/res/values/styles.xml",
                "<resources><style name=\"Theme.App.Dark\">x</style></resources>");

        SyntheticAndroidSymbols symbols = SyntheticAndroidSymbols.from(root);

        assertTrue(symbols.rJavaSource.contains("public static final int Theme_App_Dark ="));
    }

    @Test
    public void emptyProjectStillRendersCompilableSkeleton() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle", "android { namespace 'com.x' }\n");

        SyntheticAndroidSymbols symbols = SyntheticAndroidSymbols.from(root);

        assertTrue(symbols.rJavaSource.contains("public final class R {"));
        assertTrue(symbols.rJavaSource.trim().endsWith("}"));
    }

    private void write(File root, String path, String content) throws Exception {
        FileUtils.writeText(new File(root, path), content);
    }
}
