package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectBuildLogVisibilityPolicyTest {
    @Test
    public void showsSuccessfulFinishedBuildAsTimelineRecord() {
        BuildJobRecord job = new BuildJobRecord(7, 1, "success", "finished", "/tmp/build.log", "/tmp/app.apk", null, 0, 0, 0);

        assertTrue(ProjectBuildLogVisibilityPolicy.shouldShow(job, "Build complete: success. APK is ready."));
    }

    @Test
    public void showsSuccessfulBuildCompletionEvenWhenLogPathIsMissing() {
        BuildJobRecord job = new BuildJobRecord(7, 1, "success", "finished", null, "/tmp/app.apk", null, 0, 0, 0);

        assertTrue(ProjectBuildLogVisibilityPolicy.shouldShow(job, "Build complete: success. APK is ready."));
    }

    @Test
    public void keepsGeneratedSourceMessageOutOfBuildRecords() {
        BuildJobRecord job = new BuildJobRecord(7, 1, "generated", "ready_for_build", "/tmp/build.log", null, null, 0, 0, 0);

        assertFalse(ProjectBuildLogVisibilityPolicy.shouldShow(job, "Generated source for Demo. Tap Build to start the build."));
    }
}
