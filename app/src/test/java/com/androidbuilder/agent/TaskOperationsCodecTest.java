package com.androidbuilder.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TaskOperationsCodecTest {

    @Test
    public void cleanJson_tagsJsonOk() {
        TaskOperationsCodec.ParseResult result = TaskOperationsCodec.parse(
                "{\"summary\":\"ok\",\"operations\":[" +
                        "{\"action\":\"write\",\"path\":\"settings.gradle\",\"content\":\"include ':app'\\n\"}]}");

        assertEquals(TaskOperationsCodec.OUTCOME_JSON_OK, result.outcome);
        assertNotNull(result.operations);
        assertNull(result.error);
        assertEquals(1, result.operations.operations.size());
        assertEquals("settings.gradle", result.operationsOrThrow().operations.get(0).path);
    }

    @Test
    public void truncatedArray_tagsJsonSalvaged() {
        // An oversized reply cut off mid-array: the first object is complete, the array never closes.
        String truncated = "{\"summary\":\"partial\",\"operations\":[" +
                "{\"action\":\"write\",\"path\":\"app/build.gradle\",\"content\":\"plugins {}\\n\"}," +
                "{\"action\":\"write\",\"path\":\"app/src/main/java/com/x/Main.java\",\"content\":\"class Main {";

        TaskOperationsCodec.ParseResult result = TaskOperationsCodec.parse(truncated);

        assertEquals(TaskOperationsCodec.OUTCOME_JSON_SALVAGED, result.outcome);
        assertNotNull(result.operations);
        // Only the one fully-formed operation survives salvage.
        assertEquals(1, result.operations.operations.size());
        assertEquals("app/build.gradle", result.operations.operations.get(0).path);
    }

    @Test
    public void garbage_tagsParseFailedAndThrowsOnUnwrap() {
        TaskOperationsCodec.ParseResult result = TaskOperationsCodec.parse("not json at all, no braces");

        assertEquals(TaskOperationsCodec.OUTCOME_PARSE_FAILED, result.outcome);
        assertNull(result.operations);
        assertNotNull(result.error);
        // operationsOrThrow rethrows the captured IllegalArgumentException so existing retry catch blocks fire.
        assertThrows(IllegalArgumentException.class, result::operationsOrThrow);
    }

    @Test
    public void emptyOperations_isParseFailed() {
        TaskOperationsCodec.ParseResult result = TaskOperationsCodec.parse("{\"summary\":\"nothing\",\"operations\":[]}");

        assertEquals(TaskOperationsCodec.OUTCOME_PARSE_FAILED, result.outcome);
        assertNull(result.operations);
    }

    @Test
    public void blockedReply_parsesAsUsableJsonOk() {
        TaskOperationsCodec.ParseResult result = TaskOperationsCodec.parse(
                "{\"summary\":\"blocked\",\"operations\":[],\"blocked\":true,\"blockedReason\":\"needs a DB first\"}");

        // A blocked reply is a valid, usable result (not a parse failure) — the flow reads it as a signal.
        assertEquals(TaskOperationsCodec.OUTCOME_JSON_OK, result.outcome);
        assertNotNull(result.operations);
        assertTrue(result.operations.blocked);
    }

    @Test
    public void fencedReply_tagsFencedOk() {
        String reply = "===SUMMARY===\nadd\n===FILE app/build.gradle===\nplugins {}\n===END===\n";

        TaskOperationsCodec.ParseResult result = TaskOperationsCodec.parse(reply);

        assertEquals(TaskOperationsCodec.OUTCOME_FENCED_OK, result.outcome);
        assertNotNull(result.operations);
        assertEquals("app/build.gradle", result.operations.operations.get(0).path);
    }

    @Test
    public void jsonReplyStillParsesWhenNoFencedMarkers() {
        // Regression: adding the fenced path must not disturb strong models that keep returning JSON.
        TaskOperationsCodec.ParseResult result = TaskOperationsCodec.parse(
                "{\"summary\":\"ok\",\"operations\":[{\"action\":\"write\",\"path\":\"a\",\"content\":\"y\"}]}");

        assertEquals(TaskOperationsCodec.OUTCOME_JSON_OK, result.outcome);
        assertNotNull(result.operations);
    }

    @Test
    public void fencedCompletedOperationsSalvageClosedBlocks() {
        String partial = "===FILE a.txt===\nx\n===END===\n===FILE b.txt===\ncut off";

        assertEquals(1, TaskOperationsCodec.completedOperations(partial).size());
    }

    @Test
    public void completedOperations_matchesParserSalvage() {
        String partial = "{\"operations\":[" +
                "{\"action\":\"write\",\"path\":\"a.txt\",\"content\":\"x\"}," +
                "{\"action\":\"write\",\"path\":\"b.txt\",\"content\":\"y";

        assertEquals(1, TaskOperationsCodec.completedOperations(partial).size());
    }

    @Test
    public void outcomeTagsAreDistinct() {
        // Guards against a copy-paste collapse of the outcome constants.
        assertTrue(!TaskOperationsCodec.OUTCOME_JSON_OK.equals(TaskOperationsCodec.OUTCOME_JSON_SALVAGED));
        assertTrue(!TaskOperationsCodec.OUTCOME_JSON_OK.equals(TaskOperationsCodec.OUTCOME_PARSE_FAILED));
        assertTrue(!TaskOperationsCodec.OUTCOME_JSON_LENIENT.equals(TaskOperationsCodec.OUTCOME_JSON_OK));
    }
}
