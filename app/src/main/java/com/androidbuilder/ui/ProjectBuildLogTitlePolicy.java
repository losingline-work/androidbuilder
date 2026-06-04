package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

import java.util.Locale;

public final class ProjectBuildLogTitlePolicy {
    public enum Title {
        BUILD_LOG,
        BUILD_RUNNING,
        BUILD_SUCCESS,
        BUILD_FAILED,
        REPAIR_RECORD
    }

    private ProjectBuildLogTitlePolicy() {
    }

    public static Title titleFor(BuildJobRecord job) {
        if (job == null) {
            return Title.BUILD_LOG;
        }
        String phase = job.phase == null ? "" : job.phase.toLowerCase(Locale.ROOT);
        if (phase.contains("repair")) {
            return Title.REPAIR_RECORD;
        }
        String status = job.status == null ? "" : job.status.toLowerCase(Locale.ROOT);
        if ("success".equals(status) || "built".equals(status)) {
            return Title.BUILD_SUCCESS;
        }
        if ("failed".equals(status)) {
            return Title.BUILD_FAILED;
        }
        if ("queued".equals(status) || "generating".equals(status) || "building".equals(status)) {
            return Title.BUILD_RUNNING;
        }
        return Title.BUILD_LOG;
    }
}
