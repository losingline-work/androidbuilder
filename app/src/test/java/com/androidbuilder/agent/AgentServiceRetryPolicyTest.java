package com.androidbuilder.agent;

import com.androidbuilder.model.ContextNegotiation;
import com.androidbuilder.model.HermesReview;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentServiceRetryPolicyTest {
    @Test
    public void agentServiceHasNoLocalModelInjectionConstructor() {
        Constructor<?>[] constructors = AgentService.class.getDeclaredConstructors();

        assertEquals(1, constructors.length);
        assertEquals(2, constructors[0].getParameterCount());
    }

    @Test
    public void operationGenerationHasEnoughAttemptsForPreflightAndPolicyRetries() {
        assertTrue(AgentService.policyRewriteAttemptsForTest() >= 5);
    }

    @Test
    public void contextNegotiationRoundsAreBounded() {
        assertTrue(AgentService.contextNegotiationRoundsForTest() <= 2);
    }

    @Test
    public void taskOperationsLogIncludesRetryContextWhenPresent() {
        String request = AgentService.taskOperationsRequestForAiLogForTest(
                "# Plan",
                "Fix DAO",
                "Instruction",
                "Snapshot",
                "Retry context",
                2);

        assertTrue(request.contains("Attempt: 2"));
        assertTrue(request.contains("Additional retry/repair context"));
        assertTrue(request.contains("Retry context"));
    }

    @Test
    public void taskOperationsLogRendersHermesTaskContractWithoutRawBlock() throws Exception {
        String instruction = HermesTaskContractCodec.appendToInstruction(
                "Create main layout.",
                HermesTaskContractCodec.fromJson(new org.json.JSONObject("{"
                        + "\"allowedPaths\":[\"app/src/main/res/layout/activity_main.xml\"],"
                        + "\"forbiddenPaths\":[\"app/src/main/java/com/example/Legacy.java\"]"
                        + "}")));

        String request = AgentService.taskOperationsRequestForAiLogForTest(
                "# Plan",
                "Write layout",
                instruction,
                "Snapshot",
                "",
                1);

        assertTrue(request.contains("Task instruction:\nCreate main layout."));
        assertTrue(request.contains("Hermes task contract"));
        assertTrue(request.contains("forbiddenPaths: app/src/main/java/com/example/Legacy.java"));
        assertFalse(request.contains(HermesTaskContractCodec.START));
    }

    @Test
    public void retryMergeDropsStaleNegotiatedIntentWhenReviewerScopesToSingleFile() {
        ContextNegotiation broadNegotiation = new ContextNegotiation(
                false,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singletonList("Layouts must cover every screen in one pass."),
                "Write activity_main.xml, fragment_dashboard.xml, item_record.xml, and view_keypad.xml.");
        String existing = ContextNegotiationPolicy.retryContext("previous layout retry", broadNegotiation);
        String scopedRewrite = HermesReviewerPolicy.rewriteContext(new HermesReview(
                HermesReview.Decision.REWRITE,
                "Patch is still too broad.",
                "只重写 app/src/main/res/layout/view_keypad.xml 这一个文件；单一 write 操作。"));

        String merged = RetryContextPolicy.merge(existing, scopedRewrite);

        assertTrue(merged.contains("previous layout retry"));
        assertTrue(merged.contains("只重写 app/src/main/res/layout/view_keypad.xml"));
        assertTrue(merged.contains("Reviewer rewrite scope overrides earlier negotiated patch intent"));
        assertFalse(merged.contains("Negotiated patch intent"));
        assertFalse(merged.contains("Negotiated risk notes"));
        assertFalse(merged.contains("activity_main.xml, fragment_dashboard.xml"));
    }

    @Test
    public void hermesReviewAiLogResponseShowsDeterministicDecision() {
        String response = AgentService.hermesReviewResponseForAiLogForTest(
                new HermesReview(
                        HermesReview.Decision.REWRITE,
                        "Too many file operations for one implementation task: 18.",
                        "Split this into smaller tasks."));

        assertTrue(response.contains("decision: rewrite"));
        assertTrue(response.contains("Too many file operations"));
        assertTrue(response.contains("Split this into smaller tasks"));
    }

    @Test
    public void taskCompletionMessageMentionsBuildWhenContractRequiresIt() {
        String message = AgentService.taskCompletionMessageForTest("Update Gradle", false, true, false);

        assertTrue(message.contains("Update Gradle"));
        assertTrue(message.toLowerCase(java.util.Locale.ROOT).contains("build"));
    }
}
