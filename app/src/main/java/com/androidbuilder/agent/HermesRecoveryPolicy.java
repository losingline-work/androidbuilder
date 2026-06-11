package com.androidbuilder.agent;

public final class HermesRecoveryPolicy {
    public enum Action {
        NONE,
        MARK_TASK_FAILED_AND_ALLOW_RESUME,
        SHOW_REBUILD_PROMPT,
        SHOW_REPAIR_PROMPT,
        RESET_PLANNING
    }

    public static final class Decision {
        public final Action action;
        public final String messageKey;

        Decision(Action action, String messageKey) {
            this.action = action == null ? Action.NONE : action;
            this.messageKey = messageKey == null ? "" : messageKey;
        }
    }

    private HermesRecoveryPolicy() {
    }

    public static Decision decide(String planStatus, String jobStatus, boolean hasRunningTask) {
        String plan = planStatus == null ? "" : planStatus;
        String job = jobStatus == null ? "" : jobStatus;
        if ("coding".equals(plan) || hasRunningTask) {
            return new Decision(Action.MARK_TASK_FAILED_AND_ALLOW_RESUME, "resume_plan");
        }
        if ("planning".equals(plan)) {
            return new Decision(Action.RESET_PLANNING, "reset_planning");
        }
        if ("building".equals(job) || "queued".equals(job)) {
            return new Decision(Action.SHOW_REBUILD_PROMPT, "rebuild");
        }
        if ("generating".equals(job)) {
            return new Decision(Action.SHOW_REPAIR_PROMPT, "repair_or_resume");
        }
        return new Decision(Action.NONE, "");
    }
}
