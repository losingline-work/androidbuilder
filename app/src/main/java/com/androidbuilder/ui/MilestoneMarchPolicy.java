package com.androidbuilder.ui;

/**
 * The brain of the incremental milestone march. Given the existing build-level repair decision
 * ({@link AutoRepairLoopPolicy.Decision}) plus milestone-level context, it decides the next march action.
 * The activity owns all I/O (generate, build, repair, checkpoint, rollback); this is pure so it is unit-tested.
 *
 * <p>March shape: generate a milestone → build → on green checkpoint and advance to the next; on a repairable
 * failure run the existing bounded auto-repair loop; when repairs are exhausted, roll the source back to the
 * last green checkpoint and stop. Auto-march by default; a paused/single-step user stops at the next green
 * checkpoint (always a runnable app).
 */
final class MilestoneMarchPolicy {

    enum Action {
        /** Build not terminal yet — do nothing. */
        WAIT,
        /** Repairable failure with budget left — repair from the log and rebuild. */
        REPAIR,
        /** Repairs are not shrinking the errors — repair once more forcing full-file rewrites. */
        ESCALATE_REPAIR,
        /** This milestone cannot be made to build — roll the source back to the last green checkpoint and stop. */
        ROLLBACK_AND_STOP,
        /** Repairs are exhausted but a simplify retry is still available — re-derive this milestone as a
         * smallest-viable version and try once more before rolling back. */
        SIMPLIFY_AND_RETRY,
        /** Milestone is green — checkpoint it and generate the next pending milestone. */
        CHECKPOINT_AND_ADVANCE,
        /** Milestone is green but the user paused / is single-stepping — checkpoint it and stop here. */
        CHECKPOINT_AND_PAUSE,
        /** Milestone is green and none remain — checkpoint it; the whole app is done. */
        CHECKPOINT_AND_DONE
    }

    private MilestoneMarchPolicy() {
    }

    /**
     * When a milestone's bounded auto-repair loop is exhausted (or its generation failed outright): retry it as
     * a smallest-viable version if that is still available, otherwise roll back to the last green checkpoint
     * and stop. Pure so the decision is unit-tested; the activity performs the I/O.
     */
    static Action onRepairExhausted(boolean simplifyAvailable) {
        return simplifyAvailable ? Action.SIMPLIFY_AND_RETRY : Action.ROLLBACK_AND_STOP;
    }

    /**
     * @param repairDecision         the build-level decision from {@link AutoRepairLoopPolicy#decide}
     * @param hasNextPendingMilestone whether another milestone remains after the current one
     * @param paused                 the user paused the march
     * @param singleStep             the user asked for just this one milestone
     */
    static Action onBuildResult(AutoRepairLoopPolicy.Decision repairDecision,
                                boolean hasNextPendingMilestone, boolean paused, boolean singleStep) {
        switch (repairDecision) {
            case IN_PROGRESS:
                return Action.WAIT;
            case AUTO_REPAIR:
                return Action.REPAIR;
            case AUTO_REPAIR_ESCALATE:
                return Action.ESCALATE_REPAIR;
            case GIVE_UP:
                return Action.ROLLBACK_AND_STOP;
            case SUCCEEDED:
            default:
                if (!hasNextPendingMilestone) {
                    return Action.CHECKPOINT_AND_DONE;
                }
                if (paused || singleStep) {
                    return Action.CHECKPOINT_AND_PAUSE;
                }
                return Action.CHECKPOINT_AND_ADVANCE;
        }
    }
}
