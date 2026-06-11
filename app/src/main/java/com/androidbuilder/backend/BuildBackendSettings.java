package com.androidbuilder.backend;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

public final class BuildBackendSettings {
    public static final String PREFS = "build_backend";
    public static final String KEY_BACKEND = "backend";
    public static final String KEY_BOOTSTRAP_URL = "bootstrap_url";
    public static final String KEY_DEPENDENCY_MODE = "dependency_mode";
    public static final String KEY_CONFIRM_RISKY_PLAN_CHOICES = "confirm_risky_plan_choices";
    public static final String EMBEDDED = "embedded";
    public static final String EXTERNAL_TERMUX = "external_termux";
    public static final String DEPENDENCY_OFFLINE_SAFE = "offline_safe";
    public static final String DEPENDENCY_LOCAL_CACHE = "local_cache";
    public static final String DEPENDENCY_ONLINE = "online";

    private BuildBackendSettings() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String selected(Context context) {
        return prefs(context).getString(KEY_BACKEND, EMBEDDED);
    }

    public static void setSelected(Context context, String backend) {
        prefs(context).edit().putString(KEY_BACKEND, backend).apply();
    }

    public static String dependencyMode(Context context) {
        return prefs(context).getString(KEY_DEPENDENCY_MODE, DEPENDENCY_OFFLINE_SAFE);
    }

    public static void setDependencyMode(Context context, String mode) {
        prefs(context).edit().putString(KEY_DEPENDENCY_MODE, mode).apply();
    }

    public static boolean confirmRiskyPlanChoices(Context context) {
        return prefs(context).getBoolean(KEY_CONFIRM_RISKY_PLAN_CHOICES, true);
    }

    public static void setConfirmRiskyPlanChoices(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_CONFIRM_RISKY_PLAN_CHOICES, value).apply();
    }

    public static File offlineMavenDir(Context context) {
        return new File(context.getFilesDir(), "offline-maven");
    }
}
