package com.androidbuilder.util;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

public final class LocaleUtils {
    private LocaleUtils() {
    }

    public static Context wrap(Context context) {
        String language = AppSettings.language(context);
        if (AppSettings.LANGUAGE_SYSTEM.equals(language)) {
            return context;
        }
        Locale locale = AppSettings.LANGUAGE_ZH.equals(language) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        Locale.setDefault(locale);
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(locale);
        return context.createConfigurationContext(configuration);
    }
}
