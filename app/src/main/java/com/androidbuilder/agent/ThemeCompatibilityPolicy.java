package com.androidbuilder.agent;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure decision logic for the #1 generated-app launch crash: an Activity that extends
 * {@code AppCompatActivity} (or a layout using Material Components widgets) under a FRAMEWORK theme
 * ({@code android:Theme.Material*}, {@code Theme.Holo}, …) throws at {@code onCreate}:
 * "IllegalStateException: You need to use a Theme.AppCompat theme (or descendant) with this activity".
 *
 * <p>This class only decides WHAT a theme provides and WHETHER the applied theme (resolved through its
 * parent chain) satisfies what the code requires. {@link ThemeCompatibilityReconciler} does the file I/O
 * and the additive auto-fix. No device I/O — fully unit-testable.
 */
final class ThemeCompatibilityPolicy {
    /** What a theme provides, or what the code requires. MATERIAL ⊃ APPCOMPAT (Material3 IS an AppCompat theme). */
    enum Requirement {
        NONE,
        APPCOMPAT,
        MATERIAL
    }

    private ThemeCompatibilityPolicy() {
    }

    /** What a theme NAME provides. Framework {@code Theme.Material} (no digit) and {@code android:} themes give NONE. */
    static Requirement satisfiedBy(String themeName) {
        String name = normalize(themeName);
        if (name.isEmpty() || name.startsWith("android:")) {
            // A framework theme (android:Theme.Material.*, android:Theme.Holo, …) is NOT AppCompat-compatible.
            return Requirement.NONE;
        }
        if (name.startsWith("Theme.Material3") || name.contains(".Material3.")
                || name.startsWith("Theme.MaterialComponents") || name.contains(".MaterialComponents.")) {
            return Requirement.MATERIAL;
        }
        if (name.startsWith("Theme.AppCompat") || name.contains(".AppCompat.")) {
            return Requirement.APPCOMPAT;
        }
        return Requirement.NONE;
    }

    /** True when a theme that provides {@code provided} satisfies a {@code required} capability. */
    static boolean satisfies(Requirement provided, Requirement required) {
        switch (required) {
            case NONE:
                return true;
            case APPCOMPAT:
                return provided == Requirement.APPCOMPAT || provided == Requirement.MATERIAL;
            case MATERIAL:
                return provided == Requirement.MATERIAL;
            default:
                return false;
        }
    }

    /** What the generated code requires: Material widgets ⇒ MATERIAL, an AppCompatActivity ⇒ APPCOMPAT. */
    static Requirement requirementOf(Collection<String> activitySuperclasses, boolean usesMaterialWidgets) {
        if (usesMaterialWidgets) {
            return Requirement.MATERIAL;
        }
        if (activitySuperclasses != null) {
            for (String superclass : activitySuperclasses) {
                if (superclass != null && superclass.contains("AppCompatActivity")) {
                    return Requirement.APPCOMPAT;
                }
            }
        }
        return Requirement.NONE;
    }

    /**
     * Resolves what the applied theme provides by walking its parent chain through {@code styleParents}
     * (style name → parent name). If ANY ancestor is an AppCompat/Material theme the chain PASSES — this is
     * the false-positive guard: a custom {@code MyTheme} whose parent is {@code Theme.AppCompat.Light} must
     * resolve to APPCOMPAT and never be rewritten. Handles implicit dotted inheritance ({@code AppTheme.Card}
     * inherits {@code AppTheme}) and terminates on cycles.
     */
    static Requirement resolveApplied(String appliedThemeName, Map<String, String> styleParents) {
        String current = normalize(appliedThemeName);
        Set<String> visited = new HashSet<>();
        while (!current.isEmpty()) {
            Requirement provided = satisfiedBy(current);
            if (provided != Requirement.NONE) {
                return provided;
            }
            if (current.startsWith("android:") || !visited.add(current)) {
                break;
            }
            String parent = styleParents == null ? null : styleParents.get(current);
            if (parent == null || parent.trim().isEmpty()) {
                // Implicit dotted inheritance: "AppTheme.Card" with no explicit parent inherits "AppTheme".
                int dot = current.lastIndexOf('.');
                if (dot > 0 && styleParents != null && styleParents.containsKey(current.substring(0, dot))) {
                    current = current.substring(0, dot);
                    continue;
                }
                break;
            }
            current = normalize(parent);
        }
        return Requirement.NONE;
    }

    /** Strips {@code @style/}, {@code @android:style/}, {@code style/} prefixes; keeps an {@code android:} marker. */
    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String value = name.trim();
        if (value.startsWith("@")) {
            value = value.substring(1);
        }
        if (value.startsWith("android:style/")) {
            return "android:" + value.substring("android:style/".length());
        }
        if (value.startsWith("style/")) {
            value = value.substring("style/".length());
        }
        return value;
    }
}
