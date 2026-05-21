package com.androidbuilder.model;

public class ChatMessage {
    public long id;
    public long projectId;
    public String role;
    public String content;
    public long createdAt;
    public Long linkedBuildJobId;

    public ChatMessage(long id, long projectId, String role, String content, long createdAt, Long linkedBuildJobId) {
        this.id = id;
        this.projectId = projectId;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
        this.linkedBuildJobId = linkedBuildJobId;
    }
}
