package com.androidbuilder.backend;

import android.content.Context;
import android.content.SharedPreferences;

public final class BuildBackendSettings {
    public static final String PREFS = "build_backend";
    public static final String KEY_BACKEND = "backend";
    public static final String KEY_BOOTSTRAP_URL = "bootstrap_url";
    public static final String EMBEDDED = "embedded";
    public static final String EXTERNAL_TERMUX = "external_termux";

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
}
