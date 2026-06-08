package com.androidbuilder.model;

public class AiConversationRecord {
    public final long id;
    public final long projectId;
    public final String source;
    public final String title;
    public final String requestText;
    public final String responseText;
    public final String status;
    public final String metadata;
    public final Long linkedBuildJobId;
    public final long createdAt;

    public AiConversationRecord(
            long id,
            long projectId,
            String source,
            String title,
            String requestText,
            String responseText,
            String status,
            String metadata,
            Long linkedBuildJobId,
            long createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.source = source == null ? "" : source;
        this.title = title == null ? "" : title;
        this.requestText = requestText == null ? "" : requestText;
        this.responseText = responseText == null ? "" : responseText;
        this.status = status == null ? "" : status;
        this.metadata = metadata == null ? "" : metadata;
        this.linkedBuildJobId = linkedBuildJobId;
        this.createdAt = createdAt;
    }
}
