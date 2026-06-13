package com.androidbuilder.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TaskOperations {
    public final String summary;
    public final List<FileOperation> operations;
    public final boolean blocked;
    public final String blockedReason;
    public final String prerequisiteWork;
    public final String manifestJson;
    public final List<String> acceptedPaths;

    public TaskOperations(String summary, List<FileOperation> operations) {
        this(summary, operations, false, "", "");
    }

    public TaskOperations(String summary, List<FileOperation> operations, boolean blocked, String blockedReason, String prerequisiteWork) {
        this(summary, operations, blocked, blockedReason, prerequisiteWork, "", Collections.<String>emptyList());
    }

    public TaskOperations(String summary,
                          List<FileOperation> operations,
                          boolean blocked,
                          String blockedReason,
                          String prerequisiteWork,
                          String manifestJson,
                          List<String> acceptedPaths) {
        this.summary = summary == null ? "" : summary;
        this.operations = Collections.unmodifiableList(operations == null ? Collections.emptyList() : operations);
        this.blocked = blocked;
        this.blockedReason = blockedReason == null ? "" : blockedReason.trim();
        this.prerequisiteWork = prerequisiteWork == null ? "" : prerequisiteWork.trim();
        this.manifestJson = manifestJson == null ? "" : manifestJson.trim();
        this.acceptedPaths = Collections.unmodifiableList(acceptedPaths == null
                ? Collections.<String>emptyList()
                : new ArrayList<>(acceptedPaths));
    }
}
