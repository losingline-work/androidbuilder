package com.androidbuilder.agent;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class OnlineDependencyPolicy {
    // Always-approved exact coordinates, also used as prompt examples.
    private static final Set<String> APPROVED_EXACT = new LinkedHashSet<>(Arrays.asList(
            "androidx.core:core-ktx:1.13.1",
            "androidx.appcompat:appcompat:1.7.0",
            "com.google.android.material:material:1.12.0",
            "androidx.recyclerview:recyclerview:1.3.2",
            "androidx.constraintlayout:constraintlayout:2.2.0",
            "androidx.activity:activity-ktx:1.9.3",
            "androidx.lifecycle:lifecycle-runtime-ktx:2.8.7",
            "androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7"
    ));

    // Trusted Maven groups: Java-friendly, AGP 8.7-compatible, mirrored. Any pinned version is allowed.
    private static final List<String> TRUSTED_GROUPS = Arrays.asList(
            "androidx",
            "com.google.android.material",
            "com.google.code.gson",
            "com.google.guava",
            "com.google.code.findbugs",
            "org.apache.commons",
            "commons-io",
            "commons-codec",
            "joda-time"
    );

    // Blocked even under a trusted group: these need Kotlin, the Compose plugin, or annotation processors,
    // none of which survive the Java-only / on-device build constraints.
    private static final List<String> BLOCKED_GROUPS = Arrays.asList(
            "androidx.compose",
            "androidx.room",
            "androidx.hilt",
            "com.google.dagger"
    );

    private OnlineDependencyPolicy() {
    }

    public static boolean isApproved(String group, String name, String version) {
        if (APPROVED_EXACT.contains(group + ":" + name + ":" + version)) {
            return true;
        }
        return isPinnedVersion(version) && !isBlockedCoordinate(group, name) && isTrustedGroup(group);
    }

    /** A coordinate version must be exact: no {@code +}, ranges, {@code latest.*}, or SNAPSHOT. */
    public static boolean isPinnedVersion(String version) {
        if (version == null) {
            return false;
        }
        String value = version.trim();
        if (value.isEmpty() || value.indexOf('+') >= 0) {
            return false;
        }
        if (value.indexOf('[') >= 0 || value.indexOf(']') >= 0
                || value.indexOf('(') >= 0 || value.indexOf(')') >= 0 || value.indexOf(',') >= 0) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return !lower.startsWith("latest.") && !lower.endsWith("-snapshot") && !lower.endsWith(".snapshot");
    }

    /** True for coordinates that require Kotlin/Compose/annotation processing even under a trusted group. */
    public static boolean isBlockedCoordinate(String group, String name) {
        String g = group == null ? "" : group;
        String n = name == null ? "" : name;
        for (String blocked : BLOCKED_GROUPS) {
            if (g.equals(blocked) || g.startsWith(blocked + ".")) {
                return true;
            }
        }
        return n.contains("compiler") || n.contains("annotation-processor");
    }

    public static boolean isTrustedGroup(String group) {
        if (group == null || group.isEmpty()) {
            return false;
        }
        for (String trusted : TRUSTED_GROUPS) {
            if (group.equals(trusted) || group.startsWith(trusted + ".")) {
                return true;
            }
        }
        return false;
    }

    /** Human-readable list of trusted groups, the single source for prompt and rejection messages. */
    public static String trustedGroupsSummary() {
        StringBuilder summary = new StringBuilder();
        for (String group : TRUSTED_GROUPS) {
            if (summary.length() > 0) {
                summary.append(", ");
            }
            summary.append("androidx".equals(group) ? "androidx.*" : group);
        }
        return summary.toString();
    }

    public static String prompt() {
        return "Dependency mode is online enhanced. Prefer Android SDK/Java/XML/SQLiteOpenHelper and add Maven dependencies only when necessary. " +
                "You may use dependencies from these trusted groups, but only with an exact pinned version: " +
                trustedGroupsSummary() + ". " +
                "Always pin a concrete version such as 1.2.3; never use +, a version range, latest.release, or a SNAPSHOT. " +
                "Do not add Kotlin, kotlinOptions, Compose (androidx.compose.*), Room (androidx.room.*), Hilt/Dagger, any *-compiler annotation processor, KSP, KAPT, DataBinding, ViewBinding, or any Gradle plugin beyond com.android.application. " +
                "Common stable examples: " + String.join(", ", APPROVED_EXACT) + ".";
    }
}
