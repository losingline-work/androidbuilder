package com.androidbuilder.ui;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;

public class BaseActivitySystemBarsTest {
    @Test
    public void rootViewDoesNotAddNavigationBarInsetAsBottomPadding() throws Exception {
        String source = new String(Files.readAllBytes(baseActivitySource()), StandardCharsets.UTF_8);

        assertFalse("The app does not opt into edge-to-edge, so applying the navigation bar inset to "
                        + "the DecorView duplicates Android's normal content fitting and leaves blank space.",
                source.contains("insets.getSystemWindowInsetBottom()"));
    }

    private static Path baseActivitySource() {
        Path modulePath = Paths.get("src/main/java/com/androidbuilder/ui/BaseActivity.java");
        if (Files.exists(modulePath)) {
            return modulePath;
        }
        return Paths.get("app/src/main/java/com/androidbuilder/ui/BaseActivity.java");
    }
}
