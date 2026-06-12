package com.androidbuilder.ui;

import com.androidbuilder.model.BuildJobRecord;

final class ProjectBuildCardControlsPolicy {
    private ProjectBuildCardControlsPolicy() {
    }

    static Controls controls(BuildJobRecord job, boolean canCopyFailureContext, boolean showCardAction) {
        boolean failed = job != null && "failed".equals(job.status);
        return new Controls(
                false,
                failed && canCopyFailureContext,
                false,
                false,
                showCardAction);
    }

    static final class Controls {
        final boolean showCopyLog;
        final boolean showFailureContext;
        final boolean showExport;
        final boolean showToggle;
        final boolean showCardAction;

        Controls(boolean showCopyLog, boolean showFailureContext, boolean showExport, boolean showToggle, boolean showCardAction) {
            this.showCopyLog = showCopyLog;
            this.showFailureContext = showFailureContext;
            this.showExport = showExport;
            this.showToggle = showToggle;
            this.showCardAction = showCardAction;
        }
    }
}
