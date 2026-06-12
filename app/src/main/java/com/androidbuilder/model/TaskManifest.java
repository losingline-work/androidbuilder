package com.androidbuilder.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TaskManifest {
    // A resource-heavy coarse task (e.g. all drawables + layouts of a large plan) legitimately
    // plans ~90 files; every file is batch-validated, so this cap only stops absurd manifests.
    public static final int MAX_FILES = 120;

    public final String summary;
    public final List<Entry> files;
    public final boolean blocked;
    public final String blockedReason;
    public final String prerequisiteWork;

    public TaskManifest(String summary, List<Entry> files, boolean blocked, String blockedReason, String prerequisiteWork) {
        this.summary = summary == null ? "" : summary.trim();
        this.files = immutableFiles(files);
        this.blocked = blocked;
        this.blockedReason = blockedReason == null ? "" : blockedReason.trim();
        this.prerequisiteWork = prerequisiteWork == null ? "" : prerequisiteWork.trim();
        if (!blocked && this.files.isEmpty()) {
            throw new IllegalArgumentException("Task manifest file list is empty.");
        }
        if (this.files.size() > MAX_FILES) {
            throw new IllegalArgumentException("Task manifest has too many files: " + this.files.size() + " (cap " + MAX_FILES + "). Keep only files this task strictly needs and defer the rest to other tasks.");
        }
    }

    public TaskOperations toBlockedOperations() {
        return new TaskOperations(summary, Collections.<FileOperation>emptyList(), true, blockedReason, prerequisiteWork);
    }

    private static List<Entry> immutableFiles(List<Entry> files) {
        if (files == null || files.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(files));
    }

    public static class Entry {
        public final String path;
        public final String action;
        public final String intent;

        public Entry(String path, String action, String intent) {
            this.path = path == null ? "" : path.trim();
            this.action = action == null ? "" : action.trim();
            this.intent = intent == null ? "" : intent.trim();
        }
    }
}
