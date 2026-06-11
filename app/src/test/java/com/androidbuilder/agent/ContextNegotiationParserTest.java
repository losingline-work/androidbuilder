package com.androidbuilder.agent;

import com.androidbuilder.model.ContextNegotiation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ContextNegotiationParserTest {
    @Test
    public void parsesValidNegotiationJsonAndIgnoresUnsafePaths() throws Exception {
        ContextNegotiation result = ContextNegotiationParser.fromJson("{"
                + "\"ready\":false,"
                + "\"neededFiles\":[\"app/src/main/java/com/example/DBHelper.java\",\"../secret.txt\",\"/tmp/outside.java\"],"
                + "\"focusTerms\":[\"DBHelper\",\"RecordDao\"],"
                + "\"riskNotes\":[\"Keep DAO signatures synchronized.\"],"
                + "\"patchIntent\":\"Modify existing DAO only; do not recreate the project.\""
                + "}");

        assertFalse(result.ready);
        assertEquals(1, result.neededFiles.size());
        assertEquals("app/src/main/java/com/example/DBHelper.java", result.neededFiles.get(0));
        assertEquals("DBHelper", result.focusTerms.get(0));
        assertEquals("Keep DAO signatures synchronized.", result.riskNotes.get(0));
        assertTrue(result.patchIntent.contains("do not recreate"));
    }

    @Test
    public void trimsAndCapsLists() throws Exception {
        StringBuilder json = new StringBuilder("{\"ready\":true,\"neededFiles\":[],\"focusTerms\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append("\"Term").append(i).append("\"");
        }
        json.append("],\"riskNotes\":[\"").append(repeat("x", 500)).append("\"],");
        json.append("\"patchIntent\":\"").append(repeat("p", 2500)).append("\"}");

        ContextNegotiation result = ContextNegotiationParser.fromJson(json.toString());

        assertEquals(ContextNegotiationParser.MAX_ITEMS_FOR_TEST, result.focusTerms.size());
        assertEquals(ContextNegotiationParser.MAX_RISK_NOTE_CHARS_FOR_TEST, result.riskNotes.get(0).length());
        assertEquals(ContextNegotiationParser.MAX_PATCH_INTENT_CHARS_FOR_TEST, result.patchIntent.length());
    }

    @Test
    public void missingJsonThrowsClearError() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                ContextNegotiationParser.fromJson("not json"));

        assertEquals("Context negotiation response did not contain a JSON object.", error.getMessage());
    }

    @Test
    public void emptyPatchIntentThrowsClearError() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                ContextNegotiationParser.fromJson("{\"ready\":true,\"patchIntent\":\"   \"}"));

        assertEquals("Context negotiation patchIntent is empty.", error.getMessage());
    }

    private static String repeat(String text, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(text);
        }
        return builder.toString();
    }
}
