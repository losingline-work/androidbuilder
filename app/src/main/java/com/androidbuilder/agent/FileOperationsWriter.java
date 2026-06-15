package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;
import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class FileOperationsWriter {
    private static final String[] REQUIRED_PROJECT_FILES = {
            "settings.gradle",
            "build.gradle",
            "app/build.gradle",
            "app/src/main/AndroidManifest.xml"
    };

    private final DependencyGuard dependencyGuard;
    private final AndroidSourceGuard sourceGuard = new AndroidSourceGuard();
    private final boolean stubReconciliation;
    private List<String> lastStubs = new ArrayList<>();

    public FileOperationsWriter() {
        this(null);
    }

    public FileOperationsWriter(DependencyGuard dependencyGuard) {
        this(dependencyGuard, true);
    }

    public FileOperationsWriter(DependencyGuard dependencyGuard, boolean stubReconciliation) {
        this.dependencyGuard = dependencyGuard;
        this.stubReconciliation = stubReconciliation;
    }

    /** The stub members written by the last successful {@link #apply} (each "Class.member -> type"),
     *  so the caller can surface the unfinished-behaviour debt to the user. */
    public List<String> lastStubReconciliations() {
        return lastStubs;
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
            JavaApiReconciler.reconcile(tempDir);
            // Last resort: splice compiling stubs for genuinely-missing members the model never closed,
            // so the tree builds instead of looping. Runs BEFORE the guard, which still validates the
            // result - a stub never bypasses the guard.
            lastStubs = stubReconciliation ? StubReconciler.reconcile(tempDir, sourceGuard) : new ArrayList<String>();
            validateNoRequiredFileRemoved(sourceDir, tempDir);
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
            String canonicalPath = CanonicalPathPolicy.canonicalize(operation.path);
            if (!canonicalPath.equals(operation.path)) {
                throw new IOException("Operation path is not in canonical Android layout: " + operation.path + "; use app/src/main/...");
            }
            File target = new File(sourceDir, canonicalPath);
            String targetPath = target.getCanonicalPath();
            if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
                throw new IOException("Generated file escapes project source directory: " + operation.path);
            }
            if ("delete".equals(operation.action)) {
                FileUtils.deleteRecursively(target);
            } else if ("write".equals(operation.action)) {
                FileUtils.writeText(target, operation.content);
            } else {
                throw new IllegalArgumentException("Unsupported file operation action: " + operation.action);
            }
        }
    }

    /**
     * The project is built up incrementally across tasks (the first task scaffolds Gradle, a later
     * task adds the Manifest, etc.), so we do NOT require all four files to exist after every task.
     * We only forbid a task from deleting a required file that already existed — completeness is
     * checked once at build time via {@link #firstMissingRequiredProjectFile(File)}.
     */
    private void validateNoRequiredFileRemoved(File originalDir, File appliedDir) {
        for (String path : REQUIRED_PROJECT_FILES) {
            boolean existedBefore = new File(originalDir, path).isFile();
            boolean existsAfter = new File(appliedDir, path).isFile();
            if (existedBefore && !existsAfter) {
                throw new IllegalArgumentException("Generated source policy blocked deletion of required project file: " + path + ". Keep it in the project.");
            }
        }
    }

    /** Returns the first required project file missing from {@code sourceDir}, or null if complete. */
    public static String firstMissingRequiredProjectFile(File sourceDir) {
        for (String path : REQUIRED_PROJECT_FILES) {
            if (sourceDir == null || !new File(sourceDir, path).isFile()) {
                return path;
            }
        }
        return null;
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
