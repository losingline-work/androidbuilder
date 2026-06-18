package com.androidbuilder.crash;

import android.content.Context;

import com.androidbuilder.util.FileUtils;

import java.io.File;

/**
 * Where the control app keeps crash reports posted by generated apps (one latest-wins file per package,
 * under the control app's own files dir). The generated app cannot write here directly across the uid
 * boundary; it posts through {@link CrashReportProvider}, whose {@code insert} runs in THIS process and
 * lands here. The core takes a base dir so it is unit-testable without a Context.
 */
public final class CrashReportStore {
    private CrashReportStore() {
    }

    public static void write(File baseDir, String packageName, String stack) {
        if (baseDir == null || packageName == null || stack == null || stack.trim().isEmpty()) {
            return;
        }
        try {
            FileUtils.writeText(fileFor(baseDir, packageName), stack);
        } catch (Exception ignored) {
        }
    }

    /** The latest captured crash for a package, or "" if none. */
    public static String read(File baseDir, String packageName) {
        try {
            File file = fileFor(baseDir, packageName);
            return file.isFile() ? FileUtils.readText(file) : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    public static void clear(File baseDir, String packageName) {
        File file = fileFor(baseDir, packageName);
        if (file.isFile()) {
            file.delete();
        }
    }

    public static boolean has(File baseDir, String packageName) {
        return !read(baseDir, packageName).trim().isEmpty();
    }

    private static File fileFor(File baseDir, String packageName) {
        String safe = packageName.replaceAll("[^A-Za-z0-9_.]", "_");
        return new File(new File(baseDir, "crashes"), safe + ".txt");
    }

    // --- Context overloads (control app side) ---

    public static File baseDir(Context context) {
        return context.getFilesDir();
    }

    public static void write(Context context, String packageName, String stack) {
        write(baseDir(context), packageName, stack);
    }

    public static String read(Context context, String packageName) {
        return read(baseDir(context), packageName);
    }

    public static void clear(Context context, String packageName) {
        clear(baseDir(context), packageName);
    }
}
