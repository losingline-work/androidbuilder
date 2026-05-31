package com.androidbuilder.model;

public class ProjectTaskRecord {
    public final long id;
    public final long projectId;
    public final int sortOrder;
    public final String title;
    public final String instruction;
    public final String status;
    public final String resultSummary;
    public final long createdAt;
    public final long updatedAt;
    public final long startedAt;
    public final long completedAt;

    public ProjectTaskRecord(long id, long projectId, int sortOrder, String title, String instruction, String status, String resultSummary, long createdAt, long updatedAt, long startedAt, long completedAt) {
        this.id = id;
        this.projectId = projectId;
        this.sortOrder = sortOrder;
        this.title = title;
        this.instruction = instruction;
        this.status = status;
        this.resultSummary = resultSummary;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }
}
