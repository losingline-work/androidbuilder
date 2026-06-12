package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectBuildLogExpansionPolicyTest {
    @Test
    public void collapsesFailedBuildLogsByDefault() {
        BuildJobRecord job = new BuildJobRecord(1, 1, "failed", "embedded_runtime_finished", "/tmp/build.log", null, "failed", 0, 0, 0);

        assertFalse(ProjectBuildLogExpansionPolicy.shouldShowContent(job, false));
    }

    @Test
    public void supportsExplicitlyShowingFailedBuildLogWhenRequested() {
        BuildJobRecord job = new BuildJobRecord(1, 1, "failed", "embedded_runtime_finished", "/tmp/build.log", null, "failed", 0, 0, 0);

        assertTrue(ProjectBuildLogExpansionPolicy.shouldShowContent(job, true));
    }

    @Test
    public void collapsesFinishedBuildLogsByDefault() {
        BuildJobRecord success = new BuildJobRecord(1, 1, "success", "finished", "/tmp/build.log", "/tmp/app.apk", null, 0, 0, 0);

        assertFalse(ProjectBuildLogExpansionPolicy.shouldShowContent(success, false));
        assertTrue(ProjectBuildLogExpansionPolicy.shouldShowContent(success, true));
    }

    @Test
    public void keepsRunningBuildLogsExpandedForLiveFeedback() {
        BuildJobRecord running = new BuildJobRecord(2, 1, "building", "embedded_runtime", "/tmp/build.log", null, null, 0, 0, 0);

        assertTrue(ProjectBuildLogExpansionPolicy.shouldShowContent(running, false));
    }
}
