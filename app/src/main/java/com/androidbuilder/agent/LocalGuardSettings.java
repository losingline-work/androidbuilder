package com.androidbuilder.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.InputStream;

public final class LocalGuardSettings {
    public static final String PREFS = "local_guard";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_MODE = "mode";
    public static final String KEY_MODEL_NAME = "model_name";
    public static final String KEY_MODEL_SIZE = "model_size";

    private LocalGuardSettings() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static LocalGuardMode mode(Context context) {
        return LocalGuardMode.fromValue(prefs(context).getString(KEY_MODE, LocalGuardMode.DEFAULT.value()));
    }

    public static void setMode(Context context, LocalGuardMode mode) {
        LocalGuardMode safeMode = mode == null ? LocalGuardMode.DEFAULT : mode;
        prefs(context).edit().putString(KEY_MODE, safeMode.value()).apply();
    }

    public static File modelFile(Context context) {
        return LocalGuardModelStore.modelFile(context.getApplicationContext().getFilesDir());
    }

    public static boolean isModelReady(Context context) {
        File model = modelFile(context);
        return model.exists() && model.isFile() && model.length() > 0;
    }

    public static String modelName(Context context) {
        return prefs(context).getString(KEY_MODEL_NAME, "");
    }

    public static long modelSize(Context context) {
        return prefs(context).getLong(KEY_MODEL_SIZE, 0L);
    }

    public static void saveImportedModel(Context context, Uri uri) throws Exception {
        String displayName = displayName(context, uri);
        InputStream raw = context.getContentResolver().openInputStream(uri);
        LocalGuardModelStore.ImportedModel model = LocalGuardModelStore.saveImportedModel(
                context.getApplicationContext().getFilesDir(),
                displayName,
                raw);
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putString(KEY_MODE, mode(context).value())
                .putString(KEY_MODEL_NAME, model.name)
                .putLong(KEY_MODEL_SIZE, model.size)
                .apply();
    }

    public static void clearModel(Context context) {
        LocalGuardModelStore.clear(context.getApplicationContext().getFilesDir());
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, false)
                .remove(KEY_MODEL_NAME)
                .remove(KEY_MODEL_SIZE)
                .apply();
    }

    private static String displayName(Context context, Uri uri) {
        String name = queryDisplayName(context, uri);
        if (name != null && !name.trim().isEmpty()) {
            return name;
        }
        String segment = uri == null ? "" : uri.getLastPathSegment();
        if (segment == null || segment.trim().isEmpty()) {
            return "model.gguf";
        }
        int slash = segment.lastIndexOf('/');
        return slash >= 0 ? segment.substring(slash + 1) : segment;
    }

    private static String queryDisplayName(Context context, Uri uri) {
        if (uri == null) {
            return "";
        }
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
            // Fallback to lastPathSegment below.
        }
        return "";
    }
}
