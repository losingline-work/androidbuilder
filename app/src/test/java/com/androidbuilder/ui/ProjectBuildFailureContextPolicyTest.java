package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectBuildFailureContextPolicyTest {
    @Test
    public void copiesFailureContextOnlyForFailedJobsWithLogs() {
        assertTrue(ProjectBuildFailureContextPolicy.canCopyFailureContext(
                new BuildJobRecord(1, 1, "failed", "embedded_runtime_finished", "/tmp/build.log", null, "javac failed", 0, 0, 0)));
        assertFalse(ProjectBuildFailureContextPolicy.canCopyFailureContext(
                new BuildJobRecord(2, 1, "success", "finished", "/tmp/build.log", "/tmp/app.apk", null, 0, 0, 0)));
        assertFalse(ProjectBuildFailureContextPolicy.canCopyFailureContext(
                new BuildJobRecord(3, 1, "failed", "embedded_runtime_finished", "", null, "javac failed", 0, 0, 0)));
    }

    @Test
    public void failureContextIncludesSummaryAnchoredContextAndTail() {
        String logs = repeat("startup line\n", 120)
                + "before failure context\n"
                + "app/src/main/java/MainActivity.java:42: error: cannot find symbol\n"
                + "after failure context\n"
                + repeat("middle line\n", 120)
                + "BUILD FAILED in 12s\n"
                + "last diagnostic line\n";

        String context = ProjectBuildFailureContextPolicy.copyText(
                new BuildJobRecord(1, 1, "failed", "embedded_runtime_finished", "/tmp/build.log", null, "Cannot find symbol", 0, 0, 0),
                logs);

        assertTrue(context.contains("Job #1"));
        assertTrue(context.contains("Error summary"));
        assertTrue(context.contains("Cannot find symbol"));
        assertTrue(context.contains("before failure context"));
        assertTrue(context.contains("error: cannot find symbol"));
        assertTrue(context.contains("after failure context"));
        assertTrue(context.contains("BUILD FAILED"));
        assertTrue(context.contains("last diagnostic line"));
    }

    @Test
    public void failureContextIsBoundedForVeryLargeLogs() {
        String logs = repeat("line before\n", 4000)
                + "error: resource color/missing not found\n"
                + repeat("line after\n", 4000);

        String context = ProjectBuildFailureContextPolicy.copyText(
                new BuildJobRecord(1, 1, "failed", "embedded_runtime_finished", "/tmp/build.log", null, "", 0, 0, 0),
                logs);

        assertTrue(context.contains("error: resource color/missing not found"));
        assertTrue(context.length() <= 18050);
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
