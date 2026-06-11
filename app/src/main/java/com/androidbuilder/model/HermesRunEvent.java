package com.androidbuilder.model;

public class HermesRunEvent {
    public final String runId;
    public final String phase;
    public final String role;
    public final String decision;
    public final String reason;
    public final String inputSummary;
    public final String outputSummary;
    public final int attempt;

    public HermesRunEvent(
            String runId,
            String phase,
            String role,
            String decision,
            String reason,
            String inputSummary,
            String outputSummary,
            int attempt) {
        this.runId = clean(runId);
        this.phase = clean(phase);
        this.role = clean(role);
        this.decision = clean(decision);
        this.reason = clean(reason);
        this.inputSummary = clean(inputSummary);
        this.outputSummary = clean(outputSummary);
        this.attempt = attempt;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
