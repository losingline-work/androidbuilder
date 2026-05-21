package com.androidbuilder.model;

public class ArtifactRecord {
    public long id;
    public long projectId;
    public long buildJobId;
    public String type;
    public String path;
    public long createdAt;

    public ArtifactRecord(long id, long projectId, long buildJobId, String type, String path, long createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.buildJobId = buildJobId;
        this.type = type;
        this.path = path;
        this.createdAt = createdAt;
    }
}
