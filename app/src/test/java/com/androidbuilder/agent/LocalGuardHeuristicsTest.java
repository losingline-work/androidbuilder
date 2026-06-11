package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.TaskOperations;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LocalGuardHeuristicsTest {
    @Test
    public void preflightCatchesArrowTokenBeforeRunningLlama() {
        TaskOperations operations = new TaskOperations(
                "Add format helper",
                Collections.singletonList(new FileOperation(
                        "write",
                        "app/src/main/java/com/example/FormatUtils.java",
                        "class FormatUtils { /** e.g. 1 -> 2 */ }")));

        LocalGuardResult result = LocalGuardHeuristics.reviewOperations("", operations);

        assertTrue(result.usable);
        assertEquals(LocalGuardResult.Decision.REWRITE, result.decision);
        assertTrue(result.additionalInstruction.contains("FormatUtils.java"));
        assertTrue(result.additionalInstruction.contains("->"));
        assertTrue(result.additionalInstruction.contains("comments"));
    }

    @Test
    public void preflightCatchesMissingDrawableReferenceBeforeWrite() {
        TaskOperations operations = new TaskOperations(
                "Add icon resolver",
                Collections.singletonList(new FileOperation(
                        "write",
                        "app/src/main/java/com/example/IconRes.java",
                        "class IconRes { int id() { return R.drawable.ic_food; } }")));

        LocalGuardResult result = LocalGuardHeuristics.reviewOperations("", operations);

        assertTrue(result.usable);
        assertEquals(LocalGuardResult.Decision.REWRITE, result.decision);
        assertTrue(result.additionalInstruction.contains("R.drawable.ic_food"));
        assertTrue(result.additionalInstruction.contains("app/src/main/res/drawable/ic_food.xml"));
    }

    @Test
    public void preflightCatchesMissingXmlValueResourceBeforeWrite() {
        TaskOperations operations = new TaskOperations(
                "Add tab indicator",
                Collections.singletonList(new FileOperation(
                        "write",
                        "app/src/main/res/drawable/tab_indicator.xml",
                        "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\">"
                                + "<solid android:color=\"@color/tab_selected\"/>"
                                + "</shape>")));

        LocalGuardResult result = LocalGuardHeuristics.reviewOperations("", operations);

        assertTrue(result.usable);
        assertEquals(LocalGuardResult.Decision.REWRITE, result.decision);
        assertTrue(result.additionalInstruction.contains("@color/tab_selected"));
        assertTrue(result.additionalInstruction.contains("tab_indicator.xml"));
        assertTrue(result.additionalInstruction.contains("app/src/main/res/values/colors.xml"));
    }

    @Test
    public void preflightAcceptsXmlValueResourceAddedInSameOperations() {
        TaskOperations operations = new TaskOperations(
                "Add tab indicator",
                Arrays.asList(
                        new FileOperation(
                                "write",
                                "app/src/main/res/drawable/tab_indicator.xml",
                                "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\">"
                                        + "<solid android:color=\"@color/tab_selected\"/>"
                                        + "</shape>"),
                        new FileOperation(
                                "write",
                                "app/src/main/res/values/colors.xml",
                                "<resources><color name=\"tab_selected\">#2196F3</color></resources>")));

        LocalGuardResult result = LocalGuardHeuristics.reviewOperations("", operations);

        assertTrue(result.summary, !result.usable);
    }

    @Test
    public void preflightGroupsManyMissingDrawablesIntoOneShortHint() {
        TaskOperations operations = new TaskOperations(
                "Add icon resolver",
                Collections.singletonList(new FileOperation(
                        "write",
                        "app/src/main/java/com/example/IconRes.java",
                        "class IconRes { int food() { return R.drawable.ic_food; } int traffic() { return R.drawable.ic_traffic; } int other() { return R.drawable.ic_other; } }")));

        LocalGuardResult result = LocalGuardHeuristics.reviewOperations("", operations);

        assertTrue(result.usable);
        assertTrue(result.additionalInstruction.contains("IconRes.java references missing drawable resources"));
        assertTrue(result.additionalInstruction.contains("ic_food, ic_traffic, ic_other"));
        assertTrue(result.additionalInstruction.contains("getIdentifier"));
        assertEquals(1, count(result.additionalInstruction, "IconRes.java references"));
    }

    @Test
    public void preflightCatchesDaoCallWithoutDeclaration() {
        TaskOperations operations = new TaskOperations(
                "Add backup",
                Collections.singletonList(new FileOperation(
                        "write",
                        "app/src/main/java/com/example/JsonBackup.java",
                        "class JsonBackup { void export(RecordDao recordDao) { recordDao.listAll(); } }")));
        String snapshot = "--- app/src/main/java/com/example/db/RecordDao.java ---\n"
                + "class RecordDao { long insert(Record record) { return 1; } }\n";

        LocalGuardResult result = LocalGuardHeuristics.reviewOperations(snapshot, operations);

        assertTrue(result.usable);
        assertEquals(LocalGuardResult.Decision.REWRITE, result.decision);
        assertTrue(result.additionalInstruction.contains("RecordDao.listAll()"));
        assertTrue(result.additionalInstruction.contains("JsonBackup.java"));
    }

    @Test
    public void policyFailureFastHintForMissingDrawableKeepsExactResource() {
        LocalGuardResult result = LocalGuardHeuristics.rewritePolicyFailure(
                "Generated source policy blocked missing drawable resource: R.drawable.ic_food in IconRes.java.");

        assertTrue(result.usable);
        assertEquals(LocalGuardResult.Decision.REWRITE, result.decision);
        assertTrue(result.additionalInstruction.contains("R.drawable.ic_food"));
        assertTrue(result.additionalInstruction.contains("IconRes.java"));
        assertTrue(result.additionalInstruction.contains("app/src/main/res/drawable/ic_food.xml"));
    }

    @Test
    public void policyFailureFastHintForMissingXmlColorKeepsExactResource() {
        LocalGuardResult result = LocalGuardHeuristics.rewritePolicyFailure(
                "Generated source policy blocked missing XML resource reference: @color/primary in styles.xml.");

        assertTrue(result.usable);
        assertEquals(LocalGuardResult.Decision.REWRITE, result.decision);
        assertTrue(result.additionalInstruction.contains("@color/primary"));
        assertTrue(result.additionalInstruction.contains("styles.xml"));
        assertTrue(result.additionalInstruction.contains("app/src/main/res/values/colors.xml"));
    }

    private static int count(String text, String pattern) {
        int count = 0;
        int start = 0;
        while (true) {
            int index = text.indexOf(pattern, start);
            if (index < 0) {
                return count;
            }
            count++;
            start = index + pattern.length();
        }
    }
}
