package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalGuardResultParserTest {
    @Test
    public void parsesRewriteJson() {
        LocalGuardResult result = LocalGuardResultParser.parse("{\"decision\":\"rewrite\",\"summary\":\"DAO mismatch\",\"additionalInstruction\":\"Update DBHelper and CategoryDao together.\"}");

        assertTrue(result.usable);
        assertEquals(LocalGuardResult.Decision.REWRITE, result.decision);
        assertEquals("DAO mismatch", result.summary);
        assertEquals("Update DBHelper and CategoryDao together.", result.additionalInstruction);
    }

    @Test
    public void parsesOkJson() {
        LocalGuardResult result = LocalGuardResultParser.parse("{\"decision\":\"ok\",\"summary\":\"Looks consistent\"}");

        assertTrue(result.usable);
        assertEquals(LocalGuardResult.Decision.OK, result.decision);
        assertEquals("Looks consistent", result.summary);
        assertEquals("", result.additionalInstruction);
    }

    @Test
    public void malformedOutputIsUnusableFallback() {
        LocalGuardResult result = LocalGuardResultParser.parse("not json");

        assertFalse(result.usable);
        assertEquals(LocalGuardResult.Decision.OK, result.decision);
        assertTrue(result.summary.contains("unparseable"));
        assertEquals("", result.additionalInstruction);
    }
}
