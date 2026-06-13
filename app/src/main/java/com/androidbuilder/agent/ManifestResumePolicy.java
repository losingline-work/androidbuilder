package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskManifest;
import com.androidbuilder.model.TaskOperations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ManifestResumePolicy {
    private ManifestResumePolicy() {
    }

    static boolean hasManifest(TaskOperations draft) {
        return draft != null && draft.manifestJson != null && !draft.manifestJson.trim().isEmpty();
    }

    static boolean shouldResume(TaskOperations draft) {
        if (!hasManifest(draft)) {
            return false;
        }
        try {
            return !remainingBatches(TaskManifestParser.fromJson(draft.manifestJson), draft.acceptedPaths).isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    static TaskManifest manifest(TaskOperations draft) throws Exception {
        if (!hasManifest(draft)) {
            throw new IllegalArgumentException("Task draft has no manifest to resume.");
        }
        return TaskManifestParser.fromJson(draft.manifestJson);
    }

    static List<FileOperation> acceptedOperations(TaskOperations draft) {
        List<FileOperation> accepted = new ArrayList<>();
        if (draft == null || draft.operations == null || draft.operations.isEmpty()) {
            return accepted;
        }
        Set<String> acceptedPaths = canonicalSet(draft.acceptedPaths);
        if (acceptedPaths.isEmpty()) {
            return accepted;
        }
        for (FileOperation operation : draft.operations) {
            if (operation == null || operation.path == null) {
                continue;
            }
            if (acceptedPaths.contains(CanonicalPathPolicy.canonicalize(operation.path))) {
                accepted.add(operation);
            }
        }
        return accepted;
    }

    static List<List<TaskManifest.Entry>> remainingBatches(TaskManifest manifest, List<String> acceptedPaths) {
        List<List<TaskManifest.Entry>> batches = ManifestBatchPolicy.batches(manifest == null ? null : manifest.files);
        Set<String> accepted = canonicalSet(acceptedPaths);
        if (accepted.isEmpty()) {
            return batches;
        }
        List<List<TaskManifest.Entry>> remaining = new ArrayList<>();
        for (List<TaskManifest.Entry> batch : batches) {
            if (!allAccepted(batch, accepted)) {
                remaining.add(batch);
            }
        }
        return remaining;
    }

    static List<String> acceptedPathsFor(List<FileOperation> operations) {
        List<String> paths = new ArrayList<>();
        if (operations == null) {
            return paths;
        }
        Set<String> seen = new HashSet<>();
        for (FileOperation operation : operations) {
            if (operation == null || operation.path == null || operation.path.trim().isEmpty()) {
                continue;
            }
            String path = CanonicalPathPolicy.canonicalize(operation.path);
            if (seen.add(path)) {
                paths.add(path);
            }
        }
        return paths;
    }

    private static boolean allAccepted(List<TaskManifest.Entry> batch, Set<String> accepted) {
        if (batch == null || batch.isEmpty()) {
            return true;
        }
        for (TaskManifest.Entry entry : batch) {
            if (entry == null || !accepted.contains(CanonicalPathPolicy.canonicalize(entry.path))) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> canonicalSet(List<String> paths) {
        Set<String> canonical = new HashSet<>();
        if (paths == null) {
            return canonical;
        }
        for (String path : paths) {
            if (path == null || path.trim().isEmpty()) {
                continue;
            }
            canonical.add(CanonicalPathPolicy.canonicalize(path));
        }
        return canonical;
    }
}
