package com.androidbuilder.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RepairLoopStallPolicyTest {
    private static final String LOG_A =
            "> Task :app:compileDebugJavaWithJavac FAILED\n"
                    + "/work/app/src/main/java/com/x/Repo.java:12: error: cannot find symbol\n"
                    + "        dao.listInRange(0, 1);\n"
                    + "           ^\n"
                    + "  symbol:   method listInRange(int,int)\n"
                    + "1 error\n";

    @Test
    public void identicalDiagnosticsAcrossRoundsStall() {
        String first = RepairLoopStallPolicy.signature(LOG_A);
        String second = RepairLoopStallPolicy.signature(LOG_A);
        assertTrue(RepairLoopStallPolicy.stalled(first, second));
    }

    @Test
    public void differentDiagnosticsDoNotStall() {
        String first = RepairLoopStallPolicy.signature(LOG_A);
        String second = RepairLoopStallPolicy.signature(
                "/work/app/src/main/java/com/x/Repo.java:20: error: incompatible types\n1 error\n");
        assertFalse(RepairLoopStallPolicy.stalled(first, second));
    }

    @Test
    public void emptyDiagnosticsNeverStall() {
        assertEquals("", RepairLoopStallPolicy.signature("BUILD SUCCESSFUL in 2s"));
        assertFalse(RepairLoopStallPolicy.stalled("", ""));
    }

    @Test
    public void cosmeticWhitespaceDifferencesStillStall() {
        String first = RepairLoopStallPolicy.signature(LOG_A);
        String second = RepairLoopStallPolicy.signature(LOG_A.replace("\n", "\n   "));
        assertTrue(RepairLoopStallPolicy.stalled(first, second));
    }

    private static final String AAPT_TWO =
            "> Task :app:processDebugResources FAILED\n"
                    + "/work/app/src/main/res/layout/fragment_profile.xml:5: error: resource string/category_manage (aka com.x:string/category_manage) not found.\n"
                    + "/work/app/src/main/res/layout/fragment_profile.xml:9: error: resource string/account_manage (aka com.x:string/account_manage) not found.\n"
                    + "error: failed linking file resources.\n";

    @Test
    public void resourcePhaseStallIsDetected() {
        // The javac-only signature was blind to aapt; the multi-phase signature catches a repeated
        // missing-resource set so the oscillating javac<->aapt loop can no longer hide its stall.
        String first = RepairLoopStallPolicy.signature(AAPT_TWO);
        String second = RepairLoopStallPolicy.signature(AAPT_TWO);
        assertTrue(first.contains("string/account_manage"));
        assertTrue(RepairLoopStallPolicy.stalled(first, second));
        assertFalse(RepairLoopStallPolicy.shrank(first, second));
    }

    @Test
    public void reorderedResourceTokensStillStall() {
        // Sorted tokens: re-ordering the same missing set between rounds is not progress.
        String first = RepairLoopStallPolicy.signature(AAPT_TWO);
        String reordered = RepairLoopStallPolicy.signature(
                "/work/x/a.xml:9: error: resource string/account_manage (aka x) not found.\n"
                        + "/work/x/b.xml:5: error: resource string/category_manage (aka x) not found.\n");
        assertEquals(first, reordered);
        assertTrue(RepairLoopStallPolicy.stalled(first, reordered));
    }

    @Test
    public void shrinkingResourceSetIsProgress() {
        String two = RepairLoopStallPolicy.signature(AAPT_TWO);
        String one = RepairLoopStallPolicy.signature(
                "/work/x/b.xml:5: error: resource string/category_manage (aka x) not found.\n");
        assertTrue(RepairLoopStallPolicy.shrank(two, one));
        assertFalse(RepairLoopStallPolicy.stalled(two, one));
    }

    @Test
    public void oscillatingJavacAndAaptDoesNotShareSignature() {
        // The exact failure mode of project-134: round N is javac, round N+1 is aapt. Distinct signatures
        // mean they are not "identical consecutive" — the shrink check then governs the stall counter.
        String javac = RepairLoopStallPolicy.signature(LOG_A);
        String aapt = RepairLoopStallPolicy.signature(AAPT_TWO);
        assertFalse(javac.equals(aapt));
        assertFalse(RepairLoopStallPolicy.stalled(javac, aapt));
    }

    @Test
    public void unclassifiableFailureSignatureIsEmpty() {
        String gradleEnvFailure = "> Task :app:mergeDebugResources\n"
                + "FAILURE: Build failed with an exception.\n"
                + "Could not resolve all files for configuration ':app:debugRuntimeClasspath'.\n";
        assertEquals("", RepairLoopStallPolicy.signature(gradleEnvFailure));
        assertFalse(RepairLoopStallPolicy.stalled("", ""));
    }
}
