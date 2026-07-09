package com.androidbuilder.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;

public class RepairFocusPolicyTest {

    private static final String LOG =
            "/tmp/work/source/app/src/main/java/com/x/Main.java:12: error: cannot find symbol\n"
                    + "  symbol: method foo()\n"
                    + "/tmp/work/source/app/src/main/java/com/x/Main.java:20: error: ';' expected\n"
                    + "/tmp/work/source/app/src/main/java/com/x/Dao.java:5: error: incompatible types\n"
                    + "/tmp/work/source/app/src/main/java/com/x/Ui.java:8: error: cannot find symbol\n";

    @Test
    public void clustersGroupErrorsByNormalizedFileInFirstSeenOrder() {
        LinkedHashMap<String, List<String>> clusters = BuildLogContextExtractor.perFileErrorClusters(LOG);

        assertEquals(3, clusters.size());
        // Keys normalized to the project-relative path so the model can match them to the snapshot.
        assertTrue(clusters.containsKey("app/src/main/java/com/x/Main.java"));
        assertEquals(2, clusters.get("app/src/main/java/com/x/Main.java").size());
        assertEquals(1, clusters.get("app/src/main/java/com/x/Dao.java").size());
        // First-seen order preserved.
        assertEquals("app/src/main/java/com/x/Main.java", clusters.keySet().iterator().next());
    }

    @Test
    public void shouldFocusOnlyWhenDegradingNotEscalatingAndMultiFile() {
        assertTrue(RepairFocusPolicy.shouldFocus(1, false, 3));
        assertTrue(RepairFocusPolicy.shouldFocus(2, false, 5));
        assertFalse("healthy march does not focus", RepairFocusPolicy.shouldFocus(0, false, 5));
        assertFalse("escalation needs the whole log", RepairFocusPolicy.shouldFocus(2, true, 5));
        assertFalse("too few files to bother", RepairFocusPolicy.shouldFocus(2, false, 2));
    }

    @Test
    public void pickFocusChoosesTheFileWithMostErrors() {
        LinkedHashMap<String, List<String>> clusters = BuildLogContextExtractor.perFileErrorClusters(LOG);

        assertEquals("app/src/main/java/com/x/Main.java",
                RepairFocusPolicy.pickFocusFile(clusters, null, 0));
    }

    @Test
    public void pickFocusRotatesOffAStuckFileThatDidNotShrink() {
        LinkedHashMap<String, List<String>> clusters = BuildLogContextExtractor.perFileErrorClusters(LOG);

        // Main was focused last round with 2 errors and still has 2 → stuck → yield to the next-worst file.
        String next = RepairFocusPolicy.pickFocusFile(clusters, "app/src/main/java/com/x/Main.java", 2);
        assertFalse("app/src/main/java/com/x/Main.java".equals(next));
        assertTrue(clusters.containsKey(next));
    }

    @Test
    public void pickFocusKeepsAFileThatShrank() {
        LinkedHashMap<String, List<String>> clusters = BuildLogContextExtractor.perFileErrorClusters(LOG);

        // Main had 3 errors last round, now 2 → shrank → still the worst, keep focusing it.
        assertEquals("app/src/main/java/com/x/Main.java",
                RepairFocusPolicy.pickFocusFile(clusters, "app/src/main/java/com/x/Main.java", 3));
    }

    @Test
    public void focusClauseNamesTheFileAndListsErrors() {
        String zh = RepairFocusPolicy.focusClause("app/src/main/java/com/x/Main.java",
                java.util.Arrays.asList("Main.java:12: error: cannot find symbol"), true);
        assertTrue(zh.contains("本轮只修复"));
        assertTrue(zh.contains("app/src/main/java/com/x/Main.java"));
        assertTrue(zh.contains("cannot find symbol"));

        String en = RepairFocusPolicy.focusClause("Main.java", java.util.Collections.<String>emptyList(), false);
        assertTrue(en.contains("fix ONLY the compile errors in Main.java"));
    }
}
