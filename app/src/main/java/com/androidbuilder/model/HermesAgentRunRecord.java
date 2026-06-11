package com.androidbuilder.model;

public class HermesAgentRunRecord {
    public final long id;
    public final long executionRunId;
    public final long projectTaskId;
    public final int batchIndex;
    public final int agentIndex;
    public final String status;
    public final String workDir;
    public final String baseSourceHash;
    public final String mergedSourceHash;
    public final String lockedPathsJson;
    public final String summary;
    public final String errorSummary;
    public final long startedAt;
    public final long completedAt;

    public HermesAgentRunRecord(long id, long executionRunId, long projectTaskId, int batchIndex,
                                int agentIndex, String status, String workDir, String baseSourceHash,
                                String mergedSourceHash, String lockedPathsJson, String summary,
                                String errorSummary, long startedAt, long completedAt) {
        this.id = id;
        this.executionRunId = executionRunId;
        this.projectTaskId = projectTaskId;
        this.batchIndex = batchIndex;
        this.agentIndex = agentIndex;
        this.status = clean(status);
        this.workDir = clean(workDir);
        this.baseSourceHash = clean(baseSourceHash);
        this.mergedSourceHash = clean(mergedSourceHash);
        this.lockedPathsJson = clean(lockedPathsJson);
        this.summary = clean(summary);
        this.errorSummary = clean(errorSummary);
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
