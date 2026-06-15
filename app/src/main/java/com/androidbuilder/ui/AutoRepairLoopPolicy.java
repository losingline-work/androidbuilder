package com.androidbuilder.ui;

/**
 * Automates the existing manual Repair-then-Build loop: when a build finishes as a model-repairable
 * failure (a javac/resource/dependency error, not an environment/network one), the app may repair the
 * source from the build log and rebuild automatically, instead of waiting for the user to click Repair
 * and then Build. The round count is bounded so a model that cannot close the gap stops cleanly and the
 * manual Repair button takes over, rather than looping forever.
 *
 * <p>Pure decision logic so it is unit-tested; the activity only wires the I/O.
 */
final class AutoRepairLoopPolicy {

    enum Decision {
        /** The build has not reached a terminal state yet; do nothing. */
        IN_PROGRESS,
        /** The build succeeded; reset the auto-repair counter. */
        SUCCEEDED,
        /** A repairable failure with rounds left: repair from the log and rebuild. */
        AUTO_REPAIR,
        /** A failure that is not auto-repairable or the round cap is reached: stop, leave it to the user. */
        GIVE_UP
    }

    private AutoRepairLoopPolicy() {
    }

    static Decision decide(String status, boolean repairableByModel, int roundsUsed, int maxRounds) {
        boolean failed = "failed".equals(status);
        boolean succeeded = "success".equals(status);
        if (!failed && !succeeded) {
            return Decision.IN_PROGRESS;
        }
        if (succeeded) {
            return Decision.SUCCEEDED;
        }
        if (repairableByModel && roundsUsed < maxRounds) {
            return Decision.AUTO_REPAIR;
        }
        return Decision.GIVE_UP;
    }
}
