package com.androidbuilder.agent;

/**
 * Recognizes resources that are provided by a dependency or the framework, not declared by the app, so
 * the resource-existence checks do not flag a valid reference as "missing". The Material Components and
 * AppCompat libraries ship a large catalog of {@code @style/Widget.Material3.*}, {@code ThemeOverlay.*},
 * {@code TextAppearance.*}, etc.; a layout referencing one of those must NOT be told to redefine it in
 * the app's styles.xml (which is wrong and loops the task to exhaustion).
 */
final class FrameworkResourcePolicy {
    // Style-name prefixes used by the Material/AppCompat library style catalog.
    private static final String[] LIBRARY_STYLE_PREFIXES = {
            "Widget.", "ThemeOverlay.", "TextAppearance.", "ShapeAppearance.",
            "Theme.", "Base.", "Platform.", "MaterialAlertDialog."
    };

    private FrameworkResourcePolicy() {
    }

    /** True when {@code @type/name} is a library/framework-provided resource the app needn't declare. */
    static boolean isLibraryProvided(String type, String name) {
        if (!"style".equals(type) || name == null) {
            return false;
        }
        // Only the Material/AppCompat style families are library-owned; an app style that merely starts
        // with "Widget." (e.g. Widget.MyApp.Button) is still the app's to declare.
        if (!name.contains("Material") && !name.contains("AppCompat")) {
            return false;
        }
        for (String prefix : LIBRARY_STYLE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
