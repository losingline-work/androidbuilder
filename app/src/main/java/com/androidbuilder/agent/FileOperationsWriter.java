package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;
import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.io.IOException;

public class FileOperationsWriter {
    private static final String[] REQUIRED_PROJECT_FILES = {
            "settings.gradle",
            "build.gradle",
            "app/build.gradle",
            "app/src/main/AndroidManifest.xml"
    };

    private final DependencyGuard dependencyGuard;
    private final AndroidSourceGuard sourceGuard = new AndroidSourceGuard();

    public FileOperationsWriter() {
        this(null);
    }

    public FileOperationsWriter(DependencyGuard dependencyGuard) {
        this.dependencyGuard = dependencyGuard;
    }

    public void apply(File sourceDir, TaskOperations taskOperations) throws IOException {
        if (dependencyGuard != null) {
            dependencyGuard.validate(taskOperations);
        }
        File parent = sourceDir.getParentFile();
        if (parent == null) {
            throw new IOException("Source directory has no parent: " + sourceDir);
        }
        File tempDir = new File(parent, sourceDir.getName() + ".apply-" + System.currentTimeMillis());
        FileUtils.deleteRecursively(tempDir);
        if (sourceDir.exists()) {
            FileUtils.copyRecursively(sourceDir, tempDir);
        } else if (!tempDir.mkdirs()) {
            throw new IOException("Cannot create temporary source directory: " + tempDir);
        }
        try {
            applyToDirectory(tempDir, taskOperations);
            DatabaseContractNormalizer.normalize(tempDir);
            validateRequiredProjectFiles(tempDir);
            try {
                sourceGuard.validate(tempDir);
            } catch (Exception error) {
                if (error instanceof IOException) {
                    throw (IOException) error;
                }
                throw new IllegalArgumentException(error.getMessage(), error);
            }
            replaceSourceDirectory(sourceDir, tempDir);
        } finally {
            FileUtils.deleteRecursively(tempDir);
        }
    }

    private void applyToDirectory(File sourceDir, TaskOperations taskOperations) throws IOException {
        String rootPath = sourceDir.getCanonicalPath();
        for (FileOperation operation : taskOperations.operations) {
            File target = new File(sourceDir, operation.path);
            String targetPath = target.getCanonicalPath();
            if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
                throw new IOException("Generated file escapes project source directory: " + operation.path);
            }
            if ("delete".equals(operation.action)) {
                FileUtils.deleteRecursively(target);
            } else {
                FileUtils.writeText(target, operation.content);
            }
        }
    }

    private void validateRequiredProjectFiles(File sourceDir) {
        for (String path : REQUIRED_PROJECT_FILES) {
            if (!new File(sourceDir, path).isFile()) {
                throw new IllegalArgumentException("Generated source policy blocked missing required project file: " + path + ".");
            }
        }
    }

    private void replaceSourceDirectory(File sourceDir, File tempDir) throws IOException {
        File backupDir = new File(sourceDir.getParentFile(), sourceDir.getName() + ".backup-" + System.currentTimeMillis());
        FileUtils.deleteRecursively(backupDir);
        boolean hadSource = sourceDir.exists();
        if (hadSource && !sourceDir.renameTo(backupDir)) {
            throw new IOException("Cannot prepare source directory replacement: " + sourceDir);
        }
        try {
            FileUtils.copyRecursively(tempDir, sourceDir);
        } catch (IOException error) {
            FileUtils.deleteRecursively(sourceDir);
            if (hadSource) {
                FileUtils.copyRecursively(backupDir, sourceDir);
            }
            throw error;
        } finally {
            FileUtils.deleteRecursively(backupDir);
        }
    }
}
