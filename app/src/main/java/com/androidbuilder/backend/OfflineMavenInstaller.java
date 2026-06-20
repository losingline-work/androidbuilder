package com.androidbuilder.backend;

import android.content.Context;

import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs the APK-bundled offline Maven cache so common libraries (charts, image, AndroidX, Material, …)
 * resolve with NO network — out of the box. On first run (or when the bundled version changes) it extracts
 * {@code assets/offline-maven.zip} into the offline-maven cache directory. A no-op when no bundle ships in the
 * build (the zip is produced by tools/build-offline-maven.sh on a networked machine and is git-ignored).
 */
public final class OfflineMavenInstaller {
    public static final String ASSET = "offline-maven.zip";
    private static final String STAMP = ".bundle-version";
    /** Bump when the shipped bundle's contents change so the device re-extracts it. */
    private static final String VERSION = "1";

    private OfflineMavenInstaller() {
    }

    /** Best-effort, idempotent. Safe to call on every app start from a background thread. */
    public static void installBundledIfPresent(Context context) {
        File dir = BuildBackendSettings.offlineMavenDir(context);
        if (isInstalled(new File(dir, STAMP))) {
            return;
        }
        InputStream asset = openAsset(context);
        if (asset == null) {
            return; // No bundle in this build — nothing to do.
        }
        try {
            FileUtils.deleteRecursively(dir);
            extract(asset, dir);
            FileUtils.writeText(new File(dir, STAMP), VERSION);
        } catch (Exception ignored) {
            // Best-effort: a failed extract just leaves the cache empty (build falls back to online mirrors).
        }
    }

    private static boolean isInstalled(File stamp) {
        try {
            return stamp.isFile() && VERSION.equals(FileUtils.readText(stamp).trim());
        } catch (Exception error) {
            return false;
        }
    }

    private static InputStream openAsset(Context context) {
        try {
            return context.getAssets().open(ASSET);
        } catch (Exception error) {
            return null;
        }
    }

    /** Zip-slip-safe extraction of an offline-maven zip (Maven repo layout) into {@code targetDir}. */
    public static void extract(InputStream raw, File targetDir) throws IOException {
        String rootPath = targetDir.getCanonicalPath();
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Cannot create offline-maven directory: " + targetDir);
        }
        try (ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(targetDir, entry.getName());
                String targetPath = target.getCanonicalPath();
                if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
                    throw new IOException("Unsafe zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    target.mkdirs();
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("Cannot create directory: " + parent);
                    }
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        FileUtils.copy(zip, out);
                    }
                }
                zip.closeEntry();
            }
        }
    }
}
