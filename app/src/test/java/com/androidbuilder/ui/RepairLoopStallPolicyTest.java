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
}
