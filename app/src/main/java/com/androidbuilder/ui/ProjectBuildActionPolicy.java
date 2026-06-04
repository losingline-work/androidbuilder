package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

final class ProjectBuildActionPolicy {
    enum PrimaryAction {
        BUILD,
        REPAIR
    }

    private ProjectBuildActionPolicy() {
    }

    static boolean canBuild(boolean busy, boolean hasSourceFiles) {
        return !busy && hasSourceFiles;
    }

    static boolean canRepair(boolean busy, BuildJobRecord latestJob, boolean repairableByModel) {
        return !busy &&
                latestJob != null &&
                "failed".equals(latestJob.status) &&
                latestJob.logsPath != null &&
                repairableByModel;
    }

    static PrimaryAction primaryAction(BuildJobRecord repairTarget, boolean repairableByModel) {
        return canRepair(false, repairTarget, repairableByModel) ? PrimaryAction.REPAIR : PrimaryAction.BUILD;
    }
}
