package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

final class LocalGuardModelStore {
    private static final String DIRECTORY_NAME = "local-guard";
    private static final String MODEL_FILE_NAME = "model.gguf";
    private static final String TEMP_MODEL_FILE_NAME = "model.gguf.tmp";

    private LocalGuardModelStore() {
    }

    static File modelFile(File filesDir) {
        return new File(localGuardDir(filesDir), MODEL_FILE_NAME);
    }

    static ImportedModel saveImportedModel(File filesDir, String displayName, InputStream input) throws IOException {
        if (displayName == null || !displayName.trim().toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            throw new IllegalArgumentException("Local guard model must be a .gguf file.");
        }
        if (input == null) {
            throw new IOException("Cannot open local guard model input.");
        }

        File dir = localGuardDir(filesDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create directory: " + dir);
        }

        File temp = new File(dir, TEMP_MODEL_FILE_NAME);
        File target = modelFile(filesDir);
        try (InputStream in = input; FileOutputStream out = new FileOutputStream(temp)) {
            FileUtils.copy(in, out);
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Cannot replace existing local guard model: " + target);
        }
        if (!temp.renameTo(target)) {
            throw new IOException("Cannot move local guard model into place: " + target);
        }
        return new ImportedModel(displayName.trim(), target.length(), target);
    }

    static void clear(File filesDir) {
        FileUtils.deleteRecursively(localGuardDir(filesDir));
    }

    private static File localGuardDir(File filesDir) {
        return new File(filesDir, DIRECTORY_NAME);
    }

    static final class ImportedModel {
        final String name;
        final long size;
        final File file;

        ImportedModel(String name, long size, File file) {
            this.name = name;
            this.size = size;
            this.file = file;
        }
    }
}
