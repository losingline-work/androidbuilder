package com.androidbuilder.model;

/** One task as captured in a milestone's retained task-list snapshot (title + final status). */
public class MilestoneTaskSnapshot {
    public final String title;
    public final String status;

    public MilestoneTaskSnapshot(String title, String status) {
        this.title = title == null ? "" : title;
        this.status = status == null ? "" : status;
    }
}
