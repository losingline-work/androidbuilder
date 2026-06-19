package com.androidbuilder.agent;

/**
 * The lifecycle states of a {@link com.androidbuilder.model.ProjectMilestoneRecord}. Stored as the row's
 * status string. Kept as plain constants (no enum persisted) to match the project_tasks status convention.
 */
public final class MilestoneStatus {
    /** Not started yet. */
    public static final String PENDING = "pending";
    /** Its task set is being generated. */
    public static final String GENERATING = "generating";
    /** Generated; the project is building. */
    public static final String BUILDING = "building";
    /** The build failed and the auto-repair loop is running. */
    public static final String REPAIRING = "repairing";
    /** Built green and checkpointed — a stable, runnable app. */
    public static final String DONE = "done";
    /** Could not be made to build within the repair budget; the app rolled back to the previous checkpoint. */
    public static final String FAILED = "failed";
    /** Reached a green checkpoint and the march is paused here at the user's request. */
    public static final String PAUSED = "paused";

    private MilestoneStatus() {
    }

    /** A milestone whose work is finished and green. */
    public static boolean isComplete(String status) {
        return DONE.equals(status);
    }

    /** A milestone still owing work (not done, not terminally failed). */
    public static boolean isPending(String status) {
        return PENDING.equals(status) || PAUSED.equals(status);
    }

    /** A milestone currently mid-flight (the orchestrator is acting on it). */
    public static boolean isActive(String status) {
        return GENERATING.equals(status) || BUILDING.equals(status) || REPAIRING.equals(status);
    }
}
