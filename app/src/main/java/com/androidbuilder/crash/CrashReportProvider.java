package com.androidbuilder.crash;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * The control app's crash sink. A generated app (a SEPARATE uid) cannot write a file the control app can
 * read on Android 11+ scoped storage, but it CAN post here: its injected crash handler calls
 * {@code getContentResolver().insert(content://com.androidbuilder.crashsink/crash, {package, stack})}, and
 * because a ContentProvider runs in ITS declaring app's process, this insert executes in the control app
 * and writes to the control app's own files dir via {@link CrashReportStore}. Works on every API level
 * with zero storage permissions. Exported so the generated app can reach it (a dev-tool tradeoff).
 */
public final class CrashReportProvider extends ContentProvider {
    public static final String AUTHORITY = "com.androidbuilder.crashsink";
    public static final String COLUMN_PACKAGE = "package";
    public static final String COLUMN_STACK = "stack";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (values != null && getContext() != null) {
            String packageName = values.getAsString(COLUMN_PACKAGE);
            String stack = values.getAsString(COLUMN_STACK);
            if (packageName != null && stack != null) {
                CrashReportStore.write(getContext(), packageName, stack);
            }
        }
        return uri;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.com.androidbuilder.crash";
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }
}
