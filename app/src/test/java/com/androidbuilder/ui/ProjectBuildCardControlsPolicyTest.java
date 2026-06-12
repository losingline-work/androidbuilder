package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectBuildCardControlsPolicyTest {
    @Test
    public void failedBuildShowsFailureContextBesideFailureMessageWithoutExportOrToggle() {
        BuildJobRecord failed = new BuildJobRecord(
                1, 1, "failed", "compileDebugJavaWithJavac", "/tmp/build.log", null,
                "cannot find symbol", 0, 0, 0);

        ProjectBuildCardControlsPolicy.Controls controls =
                ProjectBuildCardControlsPolicy.controls(failed, true, true);

        assertFalse(controls.showCopyLog);
        assertTrue(controls.showFailureContext);
        assertFalse(controls.showExport);
        assertFalse(controls.showToggle);
        assertTrue(controls.showCardAction);
    }

    @Test
    public void successfulBuildShowsInstallOnly() {
        BuildJobRecord success = new BuildJobRecord(
                1, 1, "success", "finished", "/tmp/build.log", "/tmp/app.apk",
                null, 0, 0, 0);

        ProjectBuildCardControlsPolicy.Controls controls =
                ProjectBuildCardControlsPolicy.controls(success, true, true);

        assertFalse(controls.showCopyLog);
        assertFalse(controls.showFailureContext);
        assertFalse(controls.showExport);
        assertFalse(controls.showToggle);
        assertTrue(controls.showCardAction);
    }
}
