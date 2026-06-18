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
    private final boolean compileDrivenTypes;
    private List<String> lastStubs = new ArrayList<>();

    public FileOperationsWriter() {
        this(null);
    }

    public FileOperationsWriter(DependencyGuard dependencyGuard) {
        this(dependencyGuard, true, true);
    }

    public FileOperationsWriter(DependencyGuard dependencyGuard, boolean stubReconciliation, boolean compileDrivenTypes) {
        this.dependencyGuard = dependencyGuard;
        this.stubReconciliation = stubReconciliation;
        this.compileDrivenTypes = compileDrivenTypes;
    }

    /** The stub members written by the last successful {@link #apply} (each "Class.member -> type"),
     *  so the caller can surface the unfinished-behaviour debt to the user. */
    public List<String> lastStubReconciliations() {
        return lastStubs;
    }

    /** Strict, atomic apply: any failing operation discards the whole batch (NORMAL generation path). */
    public void apply(File sourceDir, TaskOperations taskOperations) throws IOException {
        applyInternal(sourceDir, taskOperations, false, null);
    }

    /**
     * Lenient apply for the REPAIR path: commit the operations that succeed and SKIP+report the ones that
     * cannot be applied (a stale/ambiguous edit anchor), so a repair round makes net progress instead of
     * discarding every fix because one edit's find-anchor went stale (project-134: ~80 frozen rounds). The
     * batch-level guards (dependency guard, source-policy guard, required-file/path-escape invariants) still
     * gate the whole result atomically — leniency only softens per-operation anchor failures.
     */
    public FileOperationsApplyReport applyLenient(File sourceDir, TaskOperations taskOperations) throws IOException {
        FileOperationsApplyReport report = new FileOperationsApplyReport();
        applyInternal(sourceDir, taskOperations, true, report);
        return report;
    }

    private void applyInternal(File sourceDir, TaskOperations taskOperations, boolean lenient, FileOperationsApplyReport report) throws IOException {
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
            applyToDirectory(tempDir, taskOperations, lenient, report);
            DatabaseContractNormalizer.normalize(tempDir);
            JavaApiReconciler.reconcile(tempDir);
            // Last resort: splice compiling stubs for genuinely-missing members the model never closed,
            // so the tree builds instead of looping. Runs BEFORE the guard, which still validates the
            // result - a stub never bypasses the guard.
            lastStubs = stubReconciliation ? StubReconciler.reconcile(tempDir, sourceGuard) : new ArrayList<String>();
            // Seed minimal valid producers for resources/classes the source references but never declares
            // (a missing colour, menu, launcher icon, or Intent-target Activity), so the build reaches a
            // shallower hole instead of looping aapt/javac repairs to dig it out. Additive + self-validating;
            // a no-op when the tree is already whole.
            if (stubReconciliation) {
                lastStubs.addAll(CrossReferenceReconciler.reconcile(tempDir));
                // Prevent the #1 launch crash: if the code uses AppCompat/Material (and the dependency is on
                // the classpath) but the applied theme is a framework Theme.Material*, rewrite the theme
                // parent to an AppCompat/Material descendant so onCreate doesn't throw at runtime.
                lastStubs.addAll(ThemeCompatibilityReconciler.reconcile(tempDir));
                // Declare every started Activity (else ActivityNotFoundException) and ensure the app has a
                // launcher; the started classes were just stubbed-into-existence by CrossReferenceReconciler.
                lastStubs.addAll(ManifestCompletenessPolicy.reconcile(tempDir));
            }
            validateNoRequiredFileRemoved(sourceDir, tempDir);
            try {
                // Compile-driven mode: enforce only POLICY at merge; cross-file TYPE errors are caught
                // precisely by the real javac at build time (no regex-guard false positives blocking
                // valid code). Full type-check mode remains available for callers that opt out.
                if (compileDrivenTypes) {
                    sourceGuard.validatePolicyOnly(tempDir);
                } else {
                    sourceGuard.validate(tempDir);
                }
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

    private void applyToDirectory(File sourceDir, TaskOperations taskOperations, boolean lenient, FileOperationsApplyReport report) throws IOException {
        String rootPath = sourceDir.getCanonicalPath();
        for (FileOperation operation : taskOperations.operations) {
            String canonicalPath = CanonicalPathPolicy.canonicalize(operation.path);
            if (!canonicalPath.equals(operation.path)) {
                throw new IOException("Operation path is not in canonical Android layout: " + operation.path + "; use app/src/main/...");
            }
            File target = new File(sourceDir, canonicalPath);
            String targetPath = target.getCanonicalPath();
            if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
                // Security invariant: a path escaping the source tree hard-throws even in lenient mode.
                throw new IOException("Generated file escapes project source directory: " + operation.path);
            }
            try {
                if ("delete".equals(operation.action)) {
                    FileUtils.deleteRecursively(target);
                } else if ("write".equals(operation.action)) {
                    FileUtils.writeText(target, operation.content);
                } else if ("edit".equals(operation.action)) {
                    // Precise in-place edit against the on-disk file (the file already exists from a prior
                    // task or from earlier in this same operation batch, since operations apply in order).
                    // EditOperationPolicy enforces a unique find-match so an edit can never land in the
                    // wrong place; a missing/ambiguous target throws a clear "resend the full file" message.
                    String existing = target.isFile() ? FileUtils.readText(target) : "";
                    FileUtils.writeText(target, EditOperationPolicy.apply(existing, operation.find, operation.replace, canonicalPath));
                } else {
                    throw new IllegalArgumentException("Unsupported file operation action: " + operation.action);
                }
                if (report != null) {
                    report.recordApplied(canonicalPath);
                }
            } catch (IllegalArgumentException opError) {
                // Strict (generation) mode: propagate so the whole batch is discarded atomically.
                if (!lenient) {
                    throw opError;
                }
                // Lenient (repair) mode: skip just this op (stale/ambiguous anchor, or a malformed action)
                // and record it; the surviving ops still commit so the round makes progress.
                report.recordFailed(operation.action, canonicalPath, opError.getMessage());
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
