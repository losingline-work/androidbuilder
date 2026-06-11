package com.androidbuilder.model;

public class HermesReview {
    public enum Decision {
        OK,
        REWRITE,
        FALLBACK
    }

    public final Decision decision;
    public final String summary;
    public final String rewriteInstruction;

    public HermesReview(Decision decision, String summary, String rewriteInstruction) {
        this.decision = decision == null ? Decision.FALLBACK : decision;
        this.summary = summary == null ? "" : summary;
        this.rewriteInstruction = rewriteInstruction == null ? "" : rewriteInstruction;
    }
}
