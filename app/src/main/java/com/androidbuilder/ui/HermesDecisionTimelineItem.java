package com.androidbuilder.ui;

public class HermesDecisionTimelineItem {
    public final String phase;
    public final String role;
    public final String decision;
    public final String summary;
    public final long createdAt;

    public HermesDecisionTimelineItem(String phase, String role, String decision, String summary, long createdAt) {
        this.phase = clean(phase);
        this.role = clean(role);
        this.decision = clean(decision);
        this.summary = clean(summary);
        this.createdAt = createdAt;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
