package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ProjectBuildCardActionPolicyTest {
    @Test
    public void showsInstallForSuccessfulJobWithApk() {
        BuildJobRecord job = new BuildJobRecord(1, 1, "success", "finished", "/tmp/build.log", "/tmp/app.apk", null, 0, 0, 0);

        assertEquals(ProjectBuildCardActionPolicy.Action.INSTALL, ProjectBuildCardActionPolicy.action(job, false));
    }

    @Test
    public void showsRepairForRepairableFailure() {
        BuildJobRecord job = new BuildJobRecord(1, 1, "failed", "compileDebugJavaWithJavac", "/tmp/build.log", null, "cannot find symbol", 0, 0, 0);

        assertEquals(ProjectBuildCardActionPolicy.Action.REPAIR, ProjectBuildCardActionPolicy.action(job, true));
    }

    @Test
    public void hidesActionForRunningOrUnrepairableJobs() {
        BuildJobRecord running = new BuildJobRecord(1, 1, "building", "embedded_runtime", "/tmp/build.log", null, null, 0, 0, 0);
        BuildJobRecord failed = new BuildJobRecord(2, 1, "failed", "dependency_network", "/tmp/build.log", null, "network", 0, 0, 0);

        assertEquals(ProjectBuildCardActionPolicy.Action.NONE, ProjectBuildCardActionPolicy.action(running, false));
        assertEquals(ProjectBuildCardActionPolicy.Action.NONE, ProjectBuildCardActionPolicy.action(failed, false));
    }
}
