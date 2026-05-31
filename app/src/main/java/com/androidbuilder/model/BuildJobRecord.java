package com.androidbuilder.model;

public class BuildJobRecord {
    public long id;
    public long projectId;
    public String status;
    public String phase;
    public String logsPath;
    public String apkPath;
    public String errorSummary;
    public int retryCount;
    public long createdAt;
    public long updatedAt;

    public BuildJobRecord(long id, long projectId, String status, String phase, String logsPath, String apkPath, String errorSummary, int retryCount, long createdAt, long updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.status = status;
        this.phase = phase;
        this.logsPath = logsPath;
        this.apkPath = apkPath;
        this.errorSummary = errorSummary;
        this.retryCount = retryCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
