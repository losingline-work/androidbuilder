package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectBuildLogContentPolicyTest {
    @Test
    public void failedPlanExecutionCardShowsMergedFailureSummaryInsteadOfNeedingMessageCard() {
        String error = "Merged 0 Hermes agent result(s), failed 1.\n"
                + "Task 637/2 agent failed: Generated source policy blocked missing XML id: R.id.toolbar in BaseActivity.java.";
        BuildJobRecord job = new BuildJobRecord(7, 1, "failed", "coding_failed", "/tmp/build.log", null, error, 0, 0, 0);

        ProjectBuildLogContentPolicy.Content content =
                ProjectBuildLogContentPolicy.content(job, false, "full log", "No build log yet.", true);

        assertTrue(content.visible);
        assertEquals("执行计划失败：" + error, content.text);
    }

    @Test
    public void completedSuccessfulBuildStaysCollapsedWhenLogIsNotExplicitlyShown() {
        BuildJobRecord job = new BuildJobRecord(7, 1, "success", "finished", "/tmp/build.log", "/tmp/app.apk", null, 0, 0, 0);

        ProjectBuildLogContentPolicy.Content content =
                ProjectBuildLogContentPolicy.content(job, false, "full log", "No build log yet.", false);

        assertFalse(content.visible);
        assertEquals("", content.text);
    }
}
