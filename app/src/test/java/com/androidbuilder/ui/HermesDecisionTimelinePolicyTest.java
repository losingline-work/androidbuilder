package com.androidbuilder.ui;

import com.androidbuilder.model.AiConversationRecord;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class HermesDecisionTimelinePolicyTest {
    @Test
    public void extractsHermesEventsFromAiConversationMetadata() {
        AiConversationRecord record = new AiConversationRecord(
                1,
                1,
                "hermes",
                "Hermes · orchestrator · task_execution",
                "phase: task_execution\nreason:\nSelected task",
                "decision: code\noutputSummary:\nrequiresContextScout=true",
                "code",
                "provider=hermes-orchestrator\nhermesRunId=7:3\nhermesRole=orchestrator\nhermesPhase=task_execution",
                7L,
                1000L);

        List<HermesDecisionTimelineItem> items = HermesDecisionTimelinePolicy.fromRecords(Collections.singletonList(record));

        assertEquals(1, items.size());
        assertEquals("task_execution", items.get(0).phase);
        assertEquals("orchestrator", items.get(0).role);
        assertEquals("code", items.get(0).decision);
        assertEquals("requiresContextScout=true", items.get(0).summary);
    }

    @Test
    public void ignoresNonHermesLogs() {
        AiConversationRecord cloud = new AiConversationRecord(
                1, 1, "cloud", "Cloud AI", "", "decision: code", "success", "provider=openai", null, 1000L);
        AiConversationRecord hermes = new AiConversationRecord(
                2, 1, "deterministic", "Hermes · task contract preflight", "", "decision: rewrite", "rewrite", "provider=hermes-contract", null, 2000L);

        List<HermesDecisionTimelineItem> items = HermesDecisionTimelinePolicy.fromRecords(Arrays.asList(cloud, hermes));

        assertEquals(1, items.size());
        assertEquals("rewrite", items.get(0).decision);
    }
}
