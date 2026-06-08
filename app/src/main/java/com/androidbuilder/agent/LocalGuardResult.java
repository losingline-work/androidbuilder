package com.androidbuilder.agent;

public final class LocalGuardResult {
    public enum Decision {
        OK,
        REWRITE
    }

    public final Decision decision;
    public final String summary;
    public final String additionalInstruction;
    public final boolean usable;

    private LocalGuardResult(Decision decision, String summary, String additionalInstruction, boolean usable) {
        this.decision = decision == null ? Decision.OK : decision;
        this.summary = summary == null ? "" : summary;
        this.additionalInstruction = additionalInstruction == null ? "" : additionalInstruction;
        this.usable = usable;
    }

    public static LocalGuardResult ok(String summary) {
        return new LocalGuardResult(Decision.OK, summary, "", true);
    }

    public static LocalGuardResult rewrite(String summary, String additionalInstruction) {
        return new LocalGuardResult(Decision.REWRITE, summary, additionalInstruction, true);
    }

    public static LocalGuardResult unusable(String summary) {
        return new LocalGuardResult(Decision.OK, summary, "", false);
    }
}
