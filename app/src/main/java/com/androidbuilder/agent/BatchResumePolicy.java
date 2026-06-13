package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When the whole-tree merge guard rejects a batched task, the rejected files (the callers named in
 * the guard message) are evicted from the accepted set so the next attempt RESUMES the manifest and
 * regenerates only those files against the frozen foundation - rather than re-emitting the whole
 * task in single-shot mode, which truncates the foundation and re-rolls every signature.
 */
final class BatchResumePolicy {
    // Guard messages name the offending file as "... in CategoryDao.java." (and ". java" variants).
    private static final Pattern IN_FILE = Pattern.compile("\\bin ([A-Za-z_][A-Za-z0-9_]*\\.(?:java|xml))\\b");

    private BatchResumePolicy() {
    }

    static Set<String> rejectedFileNames(String guardMessage) {
        Set<String> names = new LinkedHashSet<>();
        if (guardMessage == null) {
            return names;
        }
        Matcher matcher = IN_FILE.matcher(guardMessage);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * Returns a resumable draft with the rejected files evicted from the accepted set, or null if
     * there is no manifest to resume or nothing was actually evicted (so the caller keeps its
     * existing retry path).
     */
    static TaskOperations resumeDraftEvicting(TaskOperations batchedDraft, String guardMessage) {
        if (!ManifestResumePolicy.hasManifest(batchedDraft)) {
            return null;
        }
        Set<String> rejected = rejectedFileNames(guardMessage);
        if (rejected.isEmpty()) {
            return null;
        }
        List<FileOperation> kept = new ArrayList<>();
        boolean evictedAny = false;
        for (FileOperation operation : batchedDraft.operations) {
            if (operation == null || operation.path == null) {
                continue;
            }
            if (rejected.contains(new File(operation.path).getName())) {
                evictedAny = true;
                continue;
            }
            kept.add(operation);
        }
        if (!evictedAny) {
            return null;
        }
        return new TaskOperations(
                batchedDraft.summary,
                kept,
                false,
                "",
                "",
                batchedDraft.manifestJson,
                ManifestResumePolicy.acceptedPathsFor(kept));
    }
}
