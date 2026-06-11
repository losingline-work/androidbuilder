package com.androidbuilder.agent;

import com.androidbuilder.model.HermesRunEvent;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class HermesRunEventFormatterTest {
    @Test
    public void formatsHermesEventForAiConversationLog() {
        HermesRunEvent event = new HermesRunEvent(
                "run-1",
                "task_execution",
                "orchestrator",
                "code",
                "Next pending task selected.",
                "Task: layout XML",
                "Will generate file operations.",
                2);

        assertTrue(HermesRunEventFormatter.requestText(event).contains("phase: task_execution"));
        assertTrue(HermesRunEventFormatter.responseText(event).contains("decision: code"));
        assertTrue(HermesRunEventFormatter.metadata(event).contains("hermesRunId=run-1"));
        assertTrue(HermesRunEventFormatter.metadata(event).contains("hermesRole=orchestrator"));
    }
}
