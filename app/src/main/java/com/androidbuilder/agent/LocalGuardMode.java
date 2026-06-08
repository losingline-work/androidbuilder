package com.androidbuilder.agent;

import java.util.Locale;

public enum LocalGuardMode {
    OFF("off"),
    POLICY_ERROR_ONLY("policy_error_only"),
    PREFLIGHT_AND_POLICY_ERROR("preflight_and_policy_error");

    private final String value;

    LocalGuardMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean shouldPreflight() {
        return this == PREFLIGHT_AND_POLICY_ERROR;
    }

    public boolean shouldRewritePolicyError() {
        return this == POLICY_ERROR_ONLY || this == PREFLIGHT_AND_POLICY_ERROR;
    }

    /** Build-log triage is the highest-value local-model use, so it runs in any non-OFF mode. */
    public boolean shouldTriageBuildFailure() {
        return this != OFF;
    }

    /** Default kept cheap: the local model only runs after a deterministic policy rejection. */
    public static final LocalGuardMode DEFAULT = POLICY_ERROR_ONLY;

    public static LocalGuardMode fromValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (LocalGuardMode mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        return DEFAULT;
    }
}
