package com.androidbuilder.agent;

import com.androidbuilder.model.HermesRunEvent;

final class HermesRunEventFormatter {
    private HermesRunEventFormatter() {
    }

    static String requestText(HermesRunEvent event) {
        if (event == null) {
            return "";
        }
        return "phase: " + event.phase
                + "\nrole: " + event.role
                + "\nattempt: " + event.attempt
                + "\ninputSummary:\n" + event.inputSummary
                + "\nreason:\n" + event.reason;
    }

    static String responseText(HermesRunEvent event) {
        if (event == null) {
            return "";
        }
        return "decision: " + event.decision
                + "\noutputSummary:\n" + event.outputSummary;
    }

    static String metadata(HermesRunEvent event) {
        if (event == null) {
            return "provider=hermes-orchestrator";
        }
        return "provider=hermes-orchestrator"
                + "\nhermesRunId=" + event.runId
                + "\nhermesPhase=" + event.phase
                + "\nhermesRole=" + event.role
                + "\nhermesAttempt=" + event.attempt;
    }
}
