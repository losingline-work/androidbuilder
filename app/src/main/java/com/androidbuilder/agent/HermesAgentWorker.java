package com.androidbuilder.agent;

import com.androidbuilder.data.AppRepository;
import com.androidbuilder.model.HermesAgentRunRecord;
import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.ProjectPlanRecord;
import com.androidbuilder.model.ProjectTaskRecord;
import com.androidbuilder.model.TaskOperations;
import com.androidbuilder.util.FileUtils;

import org.json.JSONArray;

import java.io.File;
import java.util.List;

final class HermesAgentWorker {
    private final AppRepository repository;
    private final TaskOperationExecutor executor;

    HermesAgentWorker(AppRepository repository, TaskOperationExecutor executor) {
        this.repository = repository;
        this.executor = executor;
    }

    HermesAgentResult runTask(
            long projectId,
            long linkedBuildJobId,
            long executionRunId,
            ProjectPlanRecord plan,
            ProjectTaskRecord task,
            int batchIndex,
            int agentIndex,
            File canonicalSource,
            File scratchRoot,
            boolean chinese) {
        HermesAgentRunRecord run = null;
        File agentRoot = new File(scratchRoot, "task-" + task.id + "-agent-" + agentIndex);
        File scratchSource = new File(agentRoot, "source");
        File logs = new File(agentRoot, "agent.log");
        HermesTaskContract contract = HermesTaskContractCodec.extractFromInstruction(task.instruction);
        List<String> locks = HermesFileLockPolicy.locksFor(task.title, task.instruction, contract);
        String baseHash = "";
        try {
            baseHash = SourceTreeHashPolicy.hash(canonicalSource);
            FileUtils.deleteRecursively(agentRoot);
            if (canonicalSource.exists()) {
                FileUtils.copyRecursively(canonicalSource, scratchSource);
            } else if (!scratchSource.mkdirs()) {
                throw new IllegalStateException("Cannot create scratch source: " + scratchSource);
            }
            FileUtils.writeText(logs, (chinese ? "子 Agent 执行任务：" : "Sub-agent executing task: ") + task.title + "\n");
            repository.updateProjectTask(task.id, "running", "");
            run = repository.createHermesAgentRun(
                    executionRunId,
                    task.id,
                    batchIndex,
                    agentIndex,
                    "running",
                    agentRoot.getAbsolutePath(),
                    baseHash,
                    locksJson(locks));
            TaskOperations operations = executor.execute(
                    projectId,
                    linkedBuildJobId,
                    scratchSource,
                    plan.content,
                    task,
                    logs,
                    chinese,
                    "failed".equals(task.status) ? task.resultSummary : "",
                    false);
            String summary = operations.summary == null ? "" : operations.summary;
            String mergedHash = SourceTreeHashPolicy.hash(scratchSource);
            repository.updateHermesAgentRun(run.id, "merge_pending", mergedHash, summary, "");
            return new HermesAgentResult(task, repository.getHermesAgentRun(run.id), contract, operations, locks, summary, null);
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.toString() : error.getMessage();
            if (run != null) {
                repository.updateHermesAgentRun(run.id, "failed", "", "", message);
                run = repository.getHermesAgentRun(run.id);
            }
            repository.updateProjectTask(task.id, "failed", message);
            return new HermesAgentResult(task, run, contract, null, locks, "", error);
        }
    }

    private static String locksJson(List<String> locks) {
        JSONArray array = new JSONArray();
        if (locks != null) {
            for (String lock : locks) {
                array.put(lock);
            }
        }
        return array.toString();
    }
}
