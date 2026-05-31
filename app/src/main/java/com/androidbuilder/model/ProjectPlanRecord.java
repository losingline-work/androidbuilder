package com.androidbuilder.model;

public class ProjectPlanRecord {
    public final long id;
    public final long projectId;
    public final String content;
    public final String status;
    public final Long linkedBuildJobId;
    public final long createdAt;
    public final long updatedAt;

    public ProjectPlanRecord(long id, long projectId, String content, String status, Long linkedBuildJobId, long createdAt, long updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.content = content;
        this.status = status;
        this.linkedBuildJobId = linkedBuildJobId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
