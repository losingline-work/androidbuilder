package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TaskOperationsMergePolicy {
    /** Locale-independent, greppable marker that prefixes the deferred-edit note in a merged summary. */
    static final String DEFERRED_EDIT_MARKER = "deferred-edits:";

    private TaskOperationsMergePolicy() {
    }

    static TaskOperations merge(TaskOperations previousDraft, TaskOperations correction) {
        if (previousDraft == null) {
            return normalizedCopy(correction);
        }
        if (correction == null) {
            return normalizedCopy(previousDraft);
        }
        Map<String, FileOperation> byPath = new LinkedHashMap<>();
        List<String> deferredEdits = new ArrayList<>();
        for (FileOperation operation : previousDraft.operations) {
            FileOperation normalized = normalized(operation);
            if ("drop".equals(normalized.action)) {
                byPath.remove(normalized.path);
            } else if ("edit".equals(normalized.action)) {
                // A previous-draft op is normally an already-applied write; a bare edit here has no known
                // body to anchor against. Defer it instead of discarding the whole accumulated draft.
                deferredEdits.add(normalized.path);
            } else {
                byPath.put(normalized.path, normalized);
            }
        }
        for (FileOperation operation : correction.operations) {
            FileOperation normalized = normalized(operation);
            if ("drop".equals(normalized.action)) {
                byPath.remove(normalized.path);
            } else if ("edit".equals(normalized.action)) {
                FileOperation previous = byPath.get(normalized.path);
                String existingContent = previous == null || !"write".equals(previous.action) ? "" : previous.content;
                try {
                    String updated = EditOperationPolicy.apply(existingContent, normalized.find, normalized.replace, normalized.path);
                    byPath.put(normalized.path, new FileOperation("write", normalized.path, updated));
                } catch (IllegalArgumentException staleAnchor) {
                    // One stale/ambiguous/empty anchor must NOT nuke the whole correction batch
                    // (project-134: a single unmatched edit froze the repair loop for ~80 rounds). Defer
                    // just this edit; the surviving write/delete ops still commit, so the round makes net
                    // progress and the deferred file resurfaces in the next build for a full-write pass.
                    // Catch ONLY EditOperationPolicy's anchor failure here - never a structural error.
                    deferredEdits.add(normalized.path);
                }
            } else {
                byPath.put(normalized.path, normalized);
            }
        }
        String correctionSummary = correction.summary == null ? "" : correction.summary.trim();
        String previousSummary = previousDraft.summary == null ? "" : previousDraft.summary.trim();
        // Preserve the batch manifest + accepted paths across the merge. Without this the merged draft
        // dropped them, so a partial-batch draft carried by a BatchGenerationException could never RESUME
        // its manifest on the next attempt (hasManifest() was false) - it re-rolled a fresh manifest and
        // discarded the accepted foundation, defeating carry-forward/resume entirely. byPath's keys are
        // the surviving (non-dropped) canonical paths, i.e. exactly the accepted set.
        String manifestJson = !correction.manifestJson.isEmpty() ? correction.manifestJson : previousDraft.manifestJson;
        return new TaskOperations(
                withDeferredNote(correctionSummary.isEmpty() ? previousSummary : correctionSummary, deferredEdits),
                new ArrayList<>(byPath.values()),
                false, "", "",
                manifestJson,
                new ArrayList<>(byPath.keySet()));
    }

    /** Appends a greppable note naming the edits that could not be anchored, so the deferral is visible. */
    private static String withDeferredNote(String summary, List<String> deferredEdits) {
        if (deferredEdits.isEmpty()) {
            return summary;
        }
        String note = DEFERRED_EDIT_MARKER + " " + String.join("; ", deferredEdits)
                + " (anchor not found; resend as full write)";
        return summary.isEmpty() ? note : summary + " | " + note;
    }

    static TaskOperations stripDrops(TaskOperations operations) {
        if (operations == null) {
            return new TaskOperations("", new ArrayList<FileOperation>());
        }
        List<FileOperation> stripped = new ArrayList<>();
        for (FileOperation operation : operations.operations) {
            FileOperation normalized = normalized(operation);
            if (!"drop".equals(normalized.action)) {
                stripped.add(normalized);
            }
        }
        return new TaskOperations(
                operations.summary == null ? "" : operations.summary.trim(),
                stripped,
                operations.blocked,
                operations.blockedReason,
                operations.prerequisiteWork);
    }

    private static TaskOperations normalizedCopy(TaskOperations operations) {
        if (operations == null) {
            return new TaskOperations("", new ArrayList<FileOperation>());
        }
        List<FileOperation> normalized = new ArrayList<>();
        List<String> deferredEdits = new ArrayList<>();
        for (FileOperation operation : operations.operations) {
            FileOperation item = normalized(operation);
            if ("drop".equals(item.action)) {
                continue;
            }
            if ("edit".equals(item.action)) {
                // No previous body to anchor against in a single-input copy; defer rather than discard the
                // whole draft (the file resurfaces in the next build for a full-write pass).
                deferredEdits.add(item.path);
                continue;
            }
            normalized.add(item);
        }
        // Preserve manifest + accepted paths here too (single-input copy), so resume survives.
        List<String> acceptedPaths = new ArrayList<>();
        for (FileOperation item : normalized) {
            acceptedPaths.add(item.path);
        }
        return new TaskOperations(
                withDeferredNote(operations.summary == null ? "" : operations.summary.trim(), deferredEdits),
                normalized, false, "", "", operations.manifestJson, acceptedPaths);
    }

    private static FileOperation normalized(FileOperation operation) {
        return CanonicalPathPolicy.canonicalOperation(operation);
    }
}
