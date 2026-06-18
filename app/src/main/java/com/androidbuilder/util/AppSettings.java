package com.androidbuilder.util;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppSettings {
    public static final String PREFS = "app_settings";
    public static final String KEY_LANGUAGE = "language";
    public static final String LANGUAGE_SYSTEM = "system";
    public static final String LANGUAGE_EN = "en";
    public static final String LANGUAGE_ZH = "zh";

    private AppSettings() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String language(Context context) {
        return prefs(context).getString(KEY_LANGUAGE, LANGUAGE_SYSTEM);
    }

    public static boolean isChinese(Context context) {
        String language = language(context);
        if (LANGUAGE_ZH.equals(language)) {
            return true;
        }
        if (LANGUAGE_EN.equals(language)) {
            return false;
        }
        return context.getResources().getConfiguration().getLocales().get(0).getLanguage().startsWith("zh");
    }

    private static final String KEY_CODE_REVIEW = "code_review_enabled";

    /** Whether the pre-build LLM code-review gate runs after generation. Default on. */
    public static boolean isCodeReviewEnabled(Context context) {
        return prefs(context).getBoolean(KEY_CODE_REVIEW, true);
    }

    public static void setCodeReviewEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_CODE_REVIEW, enabled).apply();
    }
}
