package com.androidbuilder.agent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FrameworkResourcePolicyTest {
    @Test
    public void materialAndAppCompatLibraryStylesAreLibraryProvided() {
        assertTrue(FrameworkResourcePolicy.isLibraryProvided("style", "Widget.Material3.Button"));
        assertTrue(FrameworkResourcePolicy.isLibraryProvided("style", "Widget.Material3.Button.TextButton"));
        assertTrue(FrameworkResourcePolicy.isLibraryProvided("style", "Widget.Material3.TextInputLayout.OutlinedBox"));
        assertTrue(FrameworkResourcePolicy.isLibraryProvided("style", "ThemeOverlay.Material3.Toolbar.Surface"));
        assertTrue(FrameworkResourcePolicy.isLibraryProvided("style", "TextAppearance.Material3.HeadlineSmall"));
        assertTrue(FrameworkResourcePolicy.isLibraryProvided("style", "Theme.MaterialComponents.DayNight"));
        assertTrue(FrameworkResourcePolicy.isLibraryProvided("style", "Widget.AppCompat.Button"));
    }

    @Test
    public void appOwnedStylesAreNotLibraryProvided() {
        assertFalse(FrameworkResourcePolicy.isLibraryProvided("style", "AppTheme"));
        assertFalse(FrameworkResourcePolicy.isLibraryProvided("style", "AppTheme.NoActionBar"));
        // Starts with "Widget." but is the app's own family (no Material/AppCompat).
        assertFalse(FrameworkResourcePolicy.isLibraryProvided("style", "Widget.MyApp.Button"));
    }

    @Test
    public void onlyStyleTypeIsLibraryProvided() {
        assertFalse(FrameworkResourcePolicy.isLibraryProvided("color", "Widget.Material3.Button"));
        assertFalse(FrameworkResourcePolicy.isLibraryProvided("string", "Material"));
        assertFalse(FrameworkResourcePolicy.isLibraryProvided("style", null));
    }
}
