package com.androidbuilder.agent;

import com.androidbuilder.model.ProjectTaskRecord;
import com.androidbuilder.model.TaskOperations;

import java.io.File;

public final class TaskOperationExecutor {
    interface Runner {
        TaskOperations execute(
                long projectId,
                Long linkedBuildJobId,
                File sourceDir,
                String planContent,
                ProjectTaskRecord task,
                File logs,
                boolean chinese,
                String initialFailureContext,
                TaskOperations initialDraft,
                boolean deleteDraftOnApplySuccess,
                boolean repairFlow) throws Exception;
    }

    private final Runner runner;

    TaskOperationExecutor(Runner runner) {
        this.runner = runner;
    }

    public TaskOperations execute(
            long projectId,
            Long linkedBuildJobId,
            File sourceDir,
            String planContent,
            ProjectTaskRecord task,
            File logs,
            boolean chinese,
            String initialFailureContext,
            TaskOperations initialDraft,
            boolean deleteDraftOnApplySuccess,
            boolean repairFlow) throws Exception {
        return runner.execute(
                projectId,
                linkedBuildJobId,
                sourceDir,
                planContent,
                task,
                logs,
                chinese,
                initialFailureContext,
                initialDraft,
                deleteDraftOnApplySuccess,
                repairFlow);
    }
}
