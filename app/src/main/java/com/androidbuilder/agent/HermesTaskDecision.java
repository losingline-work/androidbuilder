package com.androidbuilder.agent;

final class HermesTaskDecision {
    enum Action {
        CODE,
        BUILD,
        PAUSE
    }

    final Action action;
    final String reason;
    final boolean requiresContextScout;
    final boolean requiresBuildAfter;
    final String retryMode;

    HermesTaskDecision(Action action, String reason, boolean requiresContextScout, boolean requiresBuildAfter, String retryMode) {
        this.action = action == null ? Action.CODE : action;
        this.reason = reason == null ? "" : reason.trim();
        this.requiresContextScout = requiresContextScout;
        this.requiresBuildAfter = requiresBuildAfter;
        this.retryMode = retryMode == null || retryMode.trim().isEmpty() ? "normal" : retryMode.trim();
    }
}
