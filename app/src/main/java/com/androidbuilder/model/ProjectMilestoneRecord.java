package com.androidbuilder.model;

/**
 * One ordered milestone of incremental app development. Milestone 0 is the runnable skeleton; each later
 * milestone adds a single small vertical feature-slice that is generated, built, repaired-if-needed and
 * checkpointed before the next milestone begins. The markdown vision plan stays in {@link ProjectPlanRecord};
 * milestone rows are the machine-readable, ordered execution list.
 */
public class ProjectMilestoneRecord {
    public final long id;
    public final long projectId;
    public final int orderIndex;
    public final String title;
    public final String description;
    /** The concrete feature-slice this milestone adds (focus text for snapshot-scoped task derivation). */
    public final String slice;
    public final String status;
    /** Absolute path of the green source snapshot taken after this milestone built, or "" if none yet. */
    public final String checkpointPath;
    /** The build job that last generated/built this milestone, or 0 if none yet. */
    public final long buildJobId;
    public final int repairRounds;
    public final long createdAt;
    public final long updatedAt;
    /** Snapshot of this milestone's task list (title+status JSON), captured when it finished. "" while pending. */
    public final String tasksJson;
    /** How many times this milestone was re-derived as a SMALLEST-viable version after exhausting repairs. */
    public final int simplifyAttempts;

    public ProjectMilestoneRecord(long id, long projectId, int orderIndex, String title, String description,
                                  String slice, String status, String checkpointPath, long buildJobId,
                                  int repairRounds, long createdAt, long updatedAt) {
        this(id, projectId, orderIndex, title, description, slice, status, checkpointPath, buildJobId,
                repairRounds, createdAt, updatedAt, "");
    }

    public ProjectMilestoneRecord(long id, long projectId, int orderIndex, String title, String description,
                                  String slice, String status, String checkpointPath, long buildJobId,
                                  int repairRounds, long createdAt, long updatedAt, String tasksJson) {
        this(id, projectId, orderIndex, title, description, slice, status, checkpointPath, buildJobId,
                repairRounds, createdAt, updatedAt, tasksJson, 0);
    }

    public ProjectMilestoneRecord(long id, long projectId, int orderIndex, String title, String description,
                                  String slice, String status, String checkpointPath, long buildJobId,
                                  int repairRounds, long createdAt, long updatedAt, String tasksJson,
                                  int simplifyAttempts) {
        this.id = id;
        this.projectId = projectId;
        this.orderIndex = orderIndex;
        this.title = title;
        this.description = description;
        this.slice = slice;
        this.status = status;
        this.checkpointPath = checkpointPath;
        this.buildJobId = buildJobId;
        this.repairRounds = repairRounds;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.tasksJson = tasksJson == null ? "" : tasksJson;
        this.simplifyAttempts = simplifyAttempts;
    }
}
