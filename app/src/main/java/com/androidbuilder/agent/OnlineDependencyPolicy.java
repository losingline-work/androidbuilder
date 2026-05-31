package com.androidbuilder.agent;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public final class OnlineDependencyPolicy {
    private static final Set<String> APPROVED = new LinkedHashSet<>(Arrays.asList(
            "androidx.core:core-ktx:1.13.1",
            "androidx.appcompat:appcompat:1.7.0",
            "com.google.android.material:material:1.12.0",
            "androidx.recyclerview:recyclerview:1.3.2",
            "androidx.constraintlayout:constraintlayout:2.2.0",
            "androidx.activity:activity-ktx:1.9.3",
            "androidx.lifecycle:lifecycle-runtime-ktx:2.8.7",
            "androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7"
    ));

    private OnlineDependencyPolicy() {
    }

    public static boolean isApproved(String group, String name, String version) {
        return APPROVED.contains(group + ":" + name + ":" + version);
    }

    public static String prompt() {
        return "Dependency mode is online enhanced, but dependencies are controlled for build reliability. " +
                "Prefer Android SDK/Java/XML/SQLiteOpenHelper and add Maven dependencies only when necessary. " +
                "Allowed Maven dependencies with exact versions: " + String.join(", ", APPROVED) + ". " +
                "Do not add Kotlin, kotlinOptions, Compose, Room, Retrofit, OkHttp, Glide, Coil, DataBinding, ViewBinding, KSP, KAPT, or any Gradle plugin beyond com.android.application. " +
                "Do not invent versions; use only the allowed coordinates exactly as listed.";
    }
}
