package com.androidbuilder.agent;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SourceSnapshotComposerTest {
    @Test
    public void assembleDedupesFullTextSectionsAndKeepsLayerOrder() {
        String snapshot = SourceSnapshotComposer.assemble(
                Arrays.asList(
                        SourceSnapshotComposer.textSection("app/src/main/java/com/example/Focus.java", "class Focus {}\n"),
                        SourceSnapshotComposer.textSection("app/src/main/java/com/example/Focus.java", "duplicate\n")),
                "class Other { void api(); }",
                "R.id: title",
                "The project is larger than the context budget.",
                200,
                1000);

        assertEquals(snapshot.indexOf("--- app/src/main/java/com/example/Focus.java ---"),
                snapshot.lastIndexOf("--- app/src/main/java/com/example/Focus.java ---"));
        assertTrue(snapshot.indexOf("class Focus") < snapshot.indexOf("--- Java API digest"));
        assertTrue(snapshot.indexOf("--- Java API digest") < snapshot.indexOf("--- resource index"));
        assertTrue(snapshot.indexOf("--- resource index") < snapshot.indexOf("--- context note ---"));
    }

    @Test
    public void assembleTruncatesFullTextLayerBeforeApiAndResourceLayers() {
        String snapshot = SourceSnapshotComposer.assemble(
                Arrays.asList(
                        SourceSnapshotComposer.textSection("app/src/main/java/com/example/Large.java",
                                repeat("x", 180) + "tail")),
                "class Other { void api(); }",
                "R.id: title",
                "",
                80,
                1000);

        assertTrue(snapshot.contains("...[truncated]"));
        assertTrue(snapshot.contains("--- Java API digest"));
        assertTrue(snapshot.contains("--- resource index"));
        assertTrue(snapshot.indexOf("...[truncated]") < snapshot.indexOf("--- Java API digest"));
    }

    @Test
    public void oversizedResourceIndexIsNeverCutByTotalBudget() {
        // The resource index may exceed its own layer budget (critical sections are never cut),
        // so the total budget must squeeze the full-text layer instead of the tail.
        String resourceIndex = "R.id: " + repeat("a", 300) + "_last";
        String snapshot = SourceSnapshotComposer.assemble(
                Arrays.asList(
                        SourceSnapshotComposer.textSection("app/src/main/java/com/example/Large.java",
                                repeat("x", 600))),
                "class Other { void api(); }",
                resourceIndex,
                "The project is larger than the context budget.",
                500,
                1000);

        assertTrue(snapshot.length() <= 1000);
        assertTrue(snapshot.contains(resourceIndex));
        assertTrue(snapshot.contains("--- context note ---"));
        assertTrue(snapshot.contains("...[truncated]"));
        assertTrue(snapshot.indexOf("...[truncated]") < snapshot.indexOf("--- Java API digest"));
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
