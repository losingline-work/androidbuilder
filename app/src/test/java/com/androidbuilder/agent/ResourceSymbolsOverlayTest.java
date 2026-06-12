package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertTrue;

public class ResourceSymbolsOverlayTest {
    @Test
    public void extractsSymbolsFromAcceptedXmlOperations() {
        ResourceSymbolsOverlay overlay = ResourceSymbolsOverlay.empty();

        overlay.absorb(Arrays.asList(
                new FileOperation("write", "app/src/main/res/layout/activity_main.xml",
                        "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"><TextView android:id=\"@+id/title\" /></LinearLayout>"),
                new FileOperation("write", "app/src/main/res/values/strings.xml",
                        "<resources><string name=\"app_name\">App</string><color name=\"brand.primary\">#000000</color><style name=\"AppTheme\" /></resources>"),
                new FileOperation("write", "app/src/main/res/drawable/ic_add.xml", "<vector />"),
                new FileOperation("write", "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml", "<adaptive-icon />")
        ));

        assertTrue(overlay.ids.contains("title"));
        assertTrue(overlay.layouts.contains("activity_main"));
        assertTrue(overlay.strings.contains("app_name"));
        assertTrue(overlay.colors.contains("brand.primary"));
        assertTrue(overlay.colors.contains("brand_primary"));
        assertTrue(overlay.styles.contains("AppTheme"));
        assertTrue(overlay.drawables.contains("ic_add"));
        assertTrue(overlay.mipmaps.contains("ic_launcher"));
    }
}
