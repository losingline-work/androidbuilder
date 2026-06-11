package com.androidbuilder.agent;

import com.androidbuilder.model.HermesReview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HermesReviewParserTest {
    @Test
    public void parsesOkDecision() throws Exception {
        HermesReview result = HermesReviewParser.fromJson("{"
                + "\"decision\":\"ok\","
                + "\"summary\":\"Patch is focused.\","
                + "\"rewriteInstruction\":\"\""
                + "}");

        assertEquals(HermesReview.Decision.OK, result.decision);
        assertEquals("Patch is focused.", result.summary);
        assertEquals("", result.rewriteInstruction);
    }

    @Test
    public void parsesRewriteDecision() throws Exception {
        HermesReview result = HermesReviewParser.fromJson("{"
                + "\"decision\":\"rewrite\","
                + "\"summary\":\"DAO and caller disagree.\","
                + "\"rewriteInstruction\":\"Rewrite RecordDao and caller together.\""
                + "}");

        assertEquals(HermesReview.Decision.REWRITE, result.decision);
        assertTrue(result.rewriteInstruction.contains("RecordDao"));
    }

    @Test
    public void parsesFallbackDecision() throws Exception {
        HermesReview result = HermesReviewParser.fromJson("{"
                + "\"decision\":\"fallback\","
                + "\"summary\":\"Reviewer unavailable.\","
                + "\"rewriteInstruction\":\"\""
                + "}");

        assertEquals(HermesReview.Decision.FALLBACK, result.decision);
    }

    @Test
    public void invalidDecisionThrowsClearError() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                HermesReviewParser.fromJson("{\"decision\":\"maybe\"}"));

        assertEquals("Hermes reviewer decision is invalid: maybe", error.getMessage());
    }

    @Test
    public void missingJsonThrowsClearError() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                HermesReviewParser.fromJson("not json"));

        assertEquals("Hermes reviewer response did not contain a JSON object.", error.getMessage());
    }
}
