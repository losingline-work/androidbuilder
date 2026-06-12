package com.androidbuilder.agent;

import com.androidbuilder.model.TaskOperations;
import com.androidbuilder.model.HermesTaskContract;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class TaskStreamInspectionPolicyTest {
    @Test
    public void abortsWhenCompletedOperationFailsStreamPreflight() {
        TaskStreamInspectionPolicy inspector = new TaskStreamInspectionPolicy(HermesTaskContract.empty());
        String partial = "{\"summary\":\"x\",\"operations\":["
                + "{\"action\":\"write\",\"path\":\"app/src/main/java/MainActivity.java\",\"content\":\"class MainActivity { void bind() { v -> save(); } }\"}"
                + "]}";

        OpenAiClient.StreamAbortException error = assertThrows(OpenAiClient.StreamAbortException.class,
                () -> inspector.onContent(partial));

        assertEquals("Generated source policy blocked Java lambda syntax in MainActivity.java. Use anonymous listener classes instead of ->.",
                error.getMessage());
    }

    @Test
    public void abortsRunawayStreamsBeforeParsingOperations() {
        TaskStreamInspectionPolicy inspector = new TaskStreamInspectionPolicy(HermesTaskContract.empty());

        OpenAiClient.StreamAbortException error = assertThrows(OpenAiClient.StreamAbortException.class,
                () -> inspector.onContent(repeat("x", StreamFusePolicy.MAX_STREAM_CHARS + 1)));

        assertEquals("Streaming response exceeded 200000 chars; generation aborted as runaway.",
                error.getMessage());
    }

    @Test
    public void salvagesCompletedOperationsFromAbortedStream() throws Exception {
        TaskStreamInspectionPolicy inspector = new TaskStreamInspectionPolicy(HermesTaskContract.empty());
        inspector.onContent("{\"summary\":\"x\",\"operations\":["
                + "{\"action\":\"write\",\"path\":\"res/values/colors.xml\",\"content\":\"<resources />\"},"
                + "{\"action\":\"write\",\"path\":\"app/src/main/java/B.java\",\"content\":\"class B {\"");

        TaskOperations draft = inspector.partialDraft("partial draft salvaged from aborted stream");

        assertEquals("partial draft salvaged from aborted stream", draft.summary);
        assertEquals(1, draft.operations.size());
        assertEquals("app/src/main/res/values/colors.xml", draft.operations.get(0).path);
    }

    @Test
    public void salvagedDraftDropsEditOperations() throws Exception {
        TaskStreamInspectionPolicy inspector = new TaskStreamInspectionPolicy(HermesTaskContract.empty());
        inspector.onContent("{\"summary\":\"x\",\"operations\":["
                + "{\"action\":\"edit\",\"path\":\"app/src/main/java/A.java\",\"find\":\"a\",\"replace\":\"b\"},"
                + "{\"action\":\"write\",\"path\":\"app/src/main/res/values/colors.xml\",\"content\":\"<resources />\"}"
                + "]}");

        TaskOperations draft = inspector.partialDraft("partial");

        assertEquals(1, draft.operations.size());
        assertEquals("app/src/main/res/values/colors.xml", draft.operations.get(0).path);
        assertEquals("write", draft.operations.get(0).action);
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
