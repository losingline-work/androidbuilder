package com.androidbuilder.model;

import java.util.Locale;

public class ProjectLogEntry {
    public enum Kind {
        AI,
        MESSAGE,
        TASK,
        BUILD
    }

    public final Kind kind;
    public final long sourceId;
    public final long createdAt;
    public final long updatedAt;
    public final String title;
    public final String subtitle;
    public final String body;
    public final String copyText;
    public final String status;

    public ProjectLogEntry(
            Kind kind,
            long sourceId,
            long createdAt,
            long updatedAt,
            String title,
            String subtitle,
            String body,
            String copyText,
            String status) {
        this.kind = kind;
        this.sourceId = sourceId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.title = title == null ? "" : title;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.body = body == null ? "" : body;
        this.copyText = copyText == null ? "" : copyText;
        this.status = status == null ? "" : status;
    }

    public long displayTime() {
        return updatedAt > 0 ? updatedAt : createdAt;
    }

    public String searchableText() {
        return (kind.name() + "\n" + title + "\n" + subtitle + "\n" + body + "\n" + copyText + "\n" + status).toLowerCase(Locale.ROOT);
    }
}
