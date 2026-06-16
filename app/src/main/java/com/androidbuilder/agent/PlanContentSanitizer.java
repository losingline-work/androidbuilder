package com.androidbuilder.agent;

import java.util.regex.Pattern;

/**
 * Strips the run-of-"null" pollution that a streaming bug used to inject into generated plan text (a
 * provider's {@code content:null} reasoning deltas were appended as the literal string "null"). The bug
 * is fixed at the source, but plans saved by older builds keep the junk in the database; cleaning it
 * when the plan is fed to the model keeps hundreds of wasted "null" tokens out of the context for those
 * existing projects without forcing a re-plan.
 */
final class PlanContentSanitizer {
    // Three or more back-to-back "null" tokens never occur in a real plan; a legitimate "null, null"
    // has separators and is left alone.
    private static final Pattern NULL_RUN = Pattern.compile("(?:null){3,}");

    private PlanContentSanitizer() {
    }

    static String clean(String plan) {
        if (plan == null || plan.isEmpty()) {
            return plan == null ? "" : plan;
        }
        return NULL_RUN.matcher(plan).replaceAll("");
    }
}
