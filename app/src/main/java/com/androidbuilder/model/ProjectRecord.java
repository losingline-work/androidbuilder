package com.androidbuilder.model;

public class ProjectRecord {
    public long id;
    public String name;
    public String packageName;
    public String description;
    public long createdAt;
    public long updatedAt;
    public String lastBuildStatus;

    public ProjectRecord(long id, String name, String packageName, String description, long createdAt, long updatedAt, String lastBuildStatus) {
        this.id = id;
        this.name = name;
        this.packageName = packageName;
        this.description = description;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastBuildStatus = lastBuildStatus;
    }
}
