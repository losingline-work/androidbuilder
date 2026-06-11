package com.androidbuilder.model;

public class HermesExecutionRunRecord {
    public final long id;
    public final long projectId;
    public final long buildJobId;
    public final String status;
    public final String mode;
    public final int maxParallel;
    public final String baseSourceHash;
    public final long createdAt;
    public final long updatedAt;

    public HermesExecutionRunRecord(long id, long projectId, long buildJobId, String status,
                                    String mode, int maxParallel, String baseSourceHash,
                                    long createdAt, long updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.buildJobId = buildJobId;
        this.status = clean(status);
        this.mode = clean(mode);
        this.maxParallel = maxParallel;
        this.baseSourceHash = clean(baseSourceHash);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
