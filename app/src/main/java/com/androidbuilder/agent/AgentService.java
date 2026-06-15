package com.androidbuilder.agent;

import android.content.Context;

import com.androidbuilder.data.AppRepository;
import com.androidbuilder.backend.BuildBackendSettings;
import com.androidbuilder.model.AppSpec;
import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ChatMessage;
import com.androidbuilder.model.ContextNegotiation;
import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.HermesExecutionRunRecord;
import com.androidbuilder.model.HermesRunEvent;
import com.androidbuilder.model.HermesReview;
import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.ProjectPlanRecord;
import com.androidbuilder.model.ProjectRecord;
import com.androidbuilder.model.ProjectTaskRecord;
import com.androidbuilder.model.TaskManifest;
import com.androidbuilder.model.TaskOperations;
import com.androidbuilder.util.FileUtils;
import com.androidbuilder.util.AppSettings;
import com.androidbuilder.util.ActiveWorkRegistry;
import com.androidbuilder.util.NameUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AgentService {
    private static final int SOURCE_SNAPSHOT_LIMIT = 24000;
    private static final int SOURCE_FULL_TEXT_LAYER_LIMIT = 14000;
    // Stage 1: the Java API digest carries the signatures of every non-focused class - the model's
    // only window onto what methods a callee actually declares. At 6000 chars a 24+ file app's digest
    // was truncated, so callers invented method names that did not exist (the project-83 method web:
    // BudgetCalculator.calculate, TransactionRepository.sumExpenseByCategory, ...). The digest is
    // appended in full after full-text in SourceSnapshotComposer.compose (never squeezed by full-text);
    // raising this cap lets ~35 classes' complete signatures through, and the total snapshot stays
    // bounded by SOURCE_SNAPSHOT_LIMIT because full-text absorbs the shift.
    private static final int SOURCE_API_DIGEST_LIMIT = 14000;
    private static final int SOURCE_RESOURCE_INDEX_LIMIT = 3000;
    private static final int SOURCE_CONTEXT_NOTE_RESERVE = 2500;
    private static final int SOURCE_FILE_PREVIEW_LIMIT = 3500;
    private static final int BUILD_LOG_PREVIEW_LIMIT = 7000;
    private static final int POLICY_REWRITE_ATTEMPTS = 5;
    // Pre-apply structural rewrites are capped separately so
    // they cannot starve the policy-error retry budget; after this many they yield to the real guard.
    private static final int PREFLIGHT_REWRITE_BUDGET = 2;
    // After this many DISTINCT methods of one DAO are flagged "not declared" across a dispatch, the
    // family is treated as stuck and the next attempt reconciles the whole DAO + callers cluster at
    // once (RC3), instead of letting mutating method names defeat the same-error fuse.
    private static final int STUCK_FAMILY_THRESHOLD = 2;
    private static final int CONTEXT_NEGOTIATION_ROUNDS = ContextNegotiationPolicy.MAX_NEGOTIATION_ROUNDS;
    private static final int AI_LOG_TEXT_LIMIT = 80000;
    // Conversation context sent to the cloud: drop status chatter, keep a recent window.
    private static final int PLANNING_HISTORY_WINDOW = 16;
    private static final int CODING_USER_REQUIREMENTS_WINDOW = 6;
    private static final int CODING_USER_REQUIREMENTS_CHARS = 1500;

    public interface Callback {
        void onComplete(BuildJobRecord job);
        void onError(Exception error);
    }

    public interface PlanCallback {
        void onComplete(String plan);
        void onError(Exception error);
    }

    private interface AiTextCall {
        String run() throws Exception;
    }

    private static class ParallelRepairMergeException extends Exception {
        ParallelRepairMergeException(String message) {
            super(message);
        }
    }

    private static class BatchGenerationException extends IllegalArgumentException {
        final TaskOperations partialDraft;

        BatchGenerationException(String message, TaskOperations partialDraft) {
            super(message);
            this.partialDraft = partialDraft;
        }

        BatchGenerationException(String message, TaskOperations partialDraft, Throwable cause) {
            super(message, cause);
            this.partialDraft = partialDraft;
        }
    }

    private static class FailureStreak {
        String lastErrorSignature = "";
        int sameErrorStreak = 0;

        void remember(String errorMessage) {
            String signature = DraftCorrectionPolicy.errorSignature(errorMessage);
            if (signature.isEmpty()) {
                return;
            }
            if (signature.equals(lastErrorSignature)) {
                sameErrorStreak++;
            } else {
                lastErrorSignature = signature;
                sameErrorStreak = 1;
            }
        }
    }

    private final Context context;
    private final AppRepository repository;
    private final OpenAiClient openAiClient;
    private final GeneratedProjectWriter writer = new GeneratedProjectWriter();
    private final FileOperationsWriter operationsWriter;
    private final TaskOperationExecutor taskOperationExecutor;
    private final StreamProgressRegistry streamProgressRegistry = new StreamProgressRegistry();
    private volatile OpenAiClient.ProgressListener progressListener;

    public AgentService(Context context, AppRepository repository) {
        this.context = context.getApplicationContext();
        this.repository = repository;
        this.openAiClient = new OpenAiClient(context);
        this.openAiClient.setProgressListener((callTag, answerChars, reasoningChars) -> {
            streamProgressRegistry.updateCounts(callTag, answerChars, reasoningChars);
            OpenAiClient.ProgressListener listener = progressListener;
            if (listener != null) {
                listener.onProgress(callTag, answerChars, reasoningChars);
            }
        });
        this.operationsWriter = new FileOperationsWriter(new DependencyGuard(
                BuildBackendSettings.dependencyMode(this.context),
                BuildBackendSettings.offlineMavenDir(this.context)));
        this.taskOperationExecutor = new TaskOperationExecutor((projectId, linkedBuildJobId, sourceDir, planContent, task, logs, chinese, initialFailureContext, initialDraft, deleteDraftOnApplySuccess, repairFlow) ->
                createAndApplyTaskOperationsInternal(
                        projectId,
                        linkedBuildJobId,
                        task.id,
                        sourceDir,
                        planContent,
                        task.title,
                        task.instruction,
                        sourceSnapshot(sourceDir),
                        logs,
                        chinese,
                        initialFailureContext,
                        initialDraft,
                        deleteDraftOnApplySuccess,
                        repairFlow));
    }

    public void setProgressListener(OpenAiClient.ProgressListener listener) {
        this.progressListener = listener;
    }

    public Map<String, StreamProgressRegistry.StreamProgress> streamProgressSnapshot() {
        return streamProgressRegistry.snapshot();
    }

    public void generateAsync(long projectId, String prompt, Callback callback) {
        new Thread(() -> {
            ActiveWorkRegistry.begin(context, projectId, context.getString(com.androidbuilder.R.string.foreground_work_coding));
            try {
                BuildJobRecord job = generate(projectId, prompt, true, true);
                callback.onComplete(job);
            } catch (Exception error) {
                callback.onError(error);
            } finally {
                ActiveWorkRegistry.end(context, projectId);
            }
        }, "agent-generate").start();
    }

    public void generateRepairAsync(long projectId, String prompt, Callback callback) {
        new Thread(() -> {
            ActiveWorkRegistry.begin(context, projectId, context.getString(com.androidbuilder.R.string.foreground_work_coding));
            try {
                BuildJobRecord job = generate(projectId, prompt, false, false);
                callback.onComplete(job);
            } catch (Exception error) {
                callback.onError(error);
            } finally {
                ActiveWorkRegistry.end(context, projectId);
            }
        }, "agent-generate").start();
    }

    public void planAsync(long projectId, String prompt, PlanCallback callback) {
        new Thread(() -> {
            ActiveWorkRegistry.begin(context, projectId, context.getString(com.androidbuilder.R.string.foreground_work_planning));
            try {
                String plan = plan(projectId, prompt);
                callback.onComplete(plan);
            } catch (Exception error) {
                repository.updateProjectPlanStatus(projectId, "idle", null);
                callback.onError(error);
            } finally {
                ActiveWorkRegistry.end(context, projectId);
            }
        }, "agent-plan").start();
    }

    public void executePlanAsync(long projectId, Callback callback) {
        new Thread(() -> {
            ActiveWorkRegistry.begin(context, projectId, context.getString(com.androidbuilder.R.string.foreground_work_coding));
            try {
                BuildJobRecord job = executePlan(projectId);
                callback.onComplete(job);
            } catch (Exception error) {
                callback.onError(error);
            } finally {
                ActiveWorkRegistry.end(context, projectId);
            }
        }, "agent-execute-plan").start();
    }

    public void repairBuildAsync(long projectId, String buildLog, Callback callback) {
        new Thread(() -> {
            ActiveWorkRegistry.begin(context, projectId, context.getString(com.androidbuilder.R.string.foreground_work_repairing));
            try {
                BuildJobRecord job = repairBuild(projectId, buildLog);
                callback.onComplete(job);
            } catch (Exception error) {
                callback.onError(error);
            } finally {
                ActiveWorkRegistry.end(context, projectId);
            }
        }, "agent-repair-build").start();
    }

    public BuildJobRecord generate(long projectId, String prompt) throws Exception {
        return generate(projectId, prompt, true, true);
    }

    private BuildJobRecord generate(long projectId, String prompt, boolean recordUserMessage, boolean announceGenerated) throws Exception {
        ProjectRecord project = repository.getProject(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }
        BuildJobRecord job = repository.createBuildJob(projectId);
        if (recordUserMessage) {
            repository.addMessage(projectId, "user", prompt, job.id);
        }
        repository.updateBuildJob(job.id, "generating", "cloud_spec", null, null, null, 0);

        List<ChatMessage> history = ConversationContextPolicy.planningHistory(repository.listMessages(projectId), PLANNING_HISTORY_WINDOW);
        boolean chinese = AppSettings.isChinese(context);
        String specRequest = "Latest approved implementation request: " + prompt + "\n\nConversation history:\n" + historyForAiLog(history);
        String specJson = recordCloudAiCall(
                projectId,
                job.id,
                chinese ? "云端 AI · 项目规格生成" : "Cloud AI · project spec",
                specRequest,
                () -> openAiClient.createSpecJson(history, prompt, chinese));
        AppSpec spec = AppSpecParser.fromJson(specJson, prompt, project.name, chinese);
        String packageName = NameUtils.isPackageName(project.packageName) ? project.packageName : spec.packageName;
        if (!NameUtils.isPackageName(packageName)) {
            packageName = NameUtils.packageNameFromProject(project.name);
        }
        spec = new AppSpec(spec.appName, packageName, spec.description, spec.entityName, spec.primaryField, spec.secondaryField, spec.language);

        repository.updateProjectMetadata(projectId, spec.packageName, spec.description);
        writer.write(repository.sourceDir(projectId), spec);

        File jobDir = repository.jobDir(projectId, job.id);
        File projectZip = new File(jobDir, "project.zip");
        FileUtils.zipDirectory(repository.sourceDir(projectId), projectZip);
        File logs = new File(jobDir, "build.log");
        FileUtils.writeText(logs, "Generated project for " + spec.appName + "\nWaiting for build.\n");

        if (announceGenerated) {
            repository.addMessage(projectId, "assistant", chinese ? "已生成项目源码：" + spec.appName + "。可以点击 Build 开始构建。" : "Generated source for " + spec.appName + ". Tap Build to start the build.", job.id);
        }
        repository.updateBuildJob(job.id, "generated", "ready_for_build", logs.getAbsolutePath(), null, null, 0);
        return repository.getBuildJob(job.id);
    }

    private String plan(long projectId, String prompt) throws Exception {
        ProjectRecord project = repository.getProject(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }
        repository.addMessage(projectId, "user", prompt, null);
        repository.saveProjectPlan(projectId, "", "planning", null);
        List<ChatMessage> history = ConversationContextPolicy.planningHistory(repository.listMessages(projectId), PLANNING_HISTORY_WINDOW);
        boolean chinese = AppSettings.isChinese(context);
        String basePlanPrompt = prompt + "\n\nProject package/applicationId: " + project.packageName + "\nUse this package and namespace consistently.";
        String planPrompt = PlanConstraintComposer.withPlanningConstraints(
                basePlanPrompt,
                BuildBackendSettings.dependencyMode(context),
                PlanConstraintComposer.offlineCacheAvailable(BuildBackendSettings.offlineMavenDir(context)),
                BuildBackendSettings.confirmRiskyPlanChoices(context),
                chinese);
        String plan = recordCloudAiCall(
                projectId,
                null,
                chinese ? "云端 AI · 工程计划生成" : "Cloud AI · engineering plan",
                "Latest requirement or plan change:\n" + planPrompt + "\n\nConversation history:\n" + historyForAiLog(history),
                () -> openAiClient.createEngineeringPlan(history, planPrompt, chinese)).trim();
        if (chinese && !plan.startsWith("# 工程计划")) {
            plan = "# 工程计划\n\n" + plan;
        } else if (!chinese && !plan.startsWith("# Engineering Plan")) {
            plan = "# Engineering Plan\n\n" + plan;
        }
        repository.saveProjectPlan(projectId, plan, "planned", null);
        repository.clearProjectTasks(projectId);
        deleteAllTaskDraftsSafely(projectId);
        repository.addMessage(projectId, "assistant", plan, null);
        CapabilityAssessment assessment = assessCapability(plan + "\n" + prompt);
        if (assessment.hasRisks()) {
            repository.addMessage(projectId, "assistant", assessment.message(chinese), null);
        }
        return plan;
    }

    private BuildJobRecord executePlan(long projectId) throws Exception {
        ProjectRecord project = repository.getProject(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }
        ProjectPlanRecord plan = repository.latestProjectPlan(projectId);
        if (plan == null || plan.content.trim().isEmpty() || !"planned".equals(plan.status)) {
            throw new IllegalStateException(AppSettings.isChinese(context) ? "请先生成工程计划。" : "Generate an engineering plan first.");
        }
        boolean chinese = AppSettings.isChinese(context);
        CapabilityAssessment assessment = assessCapability(plan.content);
        if (assessment.blocksExecution()) {
            throw new IllegalStateException(assessment.message(chinese));
        }
        BuildJobRecord job = repository.createBuildJob(projectId);
        File jobDir = repository.jobDir(projectId, job.id);
        File logs = new File(jobDir, "build.log");
        File agentsRoot = new File(jobDir, "agents");
        HermesExecutionRunRecord executionRun = null;
        List<ProjectTaskRecord> runningTasks = new ArrayList<>();
        try {
            repository.updateProjectPlanStatus(projectId, "coding", job.id);
            repository.updateBuildJob(job.id, "generating", "cloud_spec", null, null, null, 0);
            ensureImplementationTasks(projectId, job.id, plan, chinese);
            File sourceDir = repository.sourceDir(projectId);
            int maxParallel = BuildBackendSettings.parallelAgentLimit(context);
            String baseSourceHash = safeSourceHash(sourceDir);
            executionRun = repository.createHermesExecutionRun(
                    projectId,
                    job.id,
                    maxParallel <= 1 ? "serial" : "parallel",
                    maxParallel,
                    baseSourceHash);
            Map<Long, Integer> dispatchCounts = new HashMap<>();
            List<String> completedTitles = new ArrayList<>();
            boolean anyMerged = false;
            boolean buildRequiredAfter = false;
            int batchIndex = 0;

            FileUtils.writeText(logs, (chinese ? "构建版本：" : "Build: ") + com.androidbuilder.BuildStamp.text() + "\n"
                    + (chinese ? "开始执行计划任务。\n" : "Starting plan execution.\n"));
            repository.updateBuildJob(job.id, "generating", "cloud_spec", logs.getAbsolutePath(), null, null, 0);

            while (true) {
                List<ProjectTaskRecord> tasks = repository.listProjectTasks(projectId);
                List<ProjectTaskRecord> allowedTasks = HermesDispatchBudget.allowedTasks(tasks, dispatchCounts);
                allowedTasks = SequentialFailureGate.filter(
                        tasks,
                        allowedTasks,
                        dispatchCounts,
                        SequentialFailureGate.doneProducesForTasks(tasks));
                HermesParallelBatch scheduledBatch = HermesParallelScheduler.nextBatch(
                        allowedTasks,
                        repository.listActiveHermesAgentRuns(projectId),
                        maxParallel);
                if (scheduledBatch.tasks.isEmpty()) {
                    break;
                }
                batchIndex++;
                HermesParallelBatch batch = new HermesParallelBatch(batchIndex, scheduledBatch.tasks, scheduledBatch.exclusiveReason);
                for (ProjectTaskRecord task : batch.tasks) {
                    HermesDispatchBudget.markDispatched(dispatchCounts, task.id);
                }
                runningTasks.addAll(batch.tasks);
                String dispatchMessage = batch.tasks.size() == 1
                        ? (chinese ? "执行下一步：" : "Executing next step: ") + batch.tasks.get(0).title
                        : (chinese ? "并行执行下一批：" : "Executing next parallel batch: ") + taskTitles(batch.tasks);
                repository.addMessage(projectId, "assistant", dispatchMessage, job.id);
                recordHermesRunEvent(projectId, job.id, new HermesRunEvent(
                        job.id + ":batch-" + executionRun.id + "-" + batchIndex,
                        "parallel_batch",
                        "orchestrator",
                        "dispatch",
                        batch.exclusiveReason.isEmpty() ? "Dispatch safe parallel batch." : batch.exclusiveReason,
                        "Tasks:\n" + taskTitles(batch.tasks),
                        "maxParallel=" + maxParallel + "\nbaseSourceHash=" + baseSourceHash,
                        batchIndex));

                FileUtils.appendText(logs, (chinese ? "正在执行计划任务批次：" : "Executing plan task batch: ")
                        + taskTitles(batch.tasks) + "\n");
                repository.updateBuildJob(job.id, "generating", "cloud_spec", logs.getAbsolutePath(), null, null, 0);

                List<HermesAgentResult> results = executeParallelBatch(projectId, job, executionRun, plan, batch, sourceDir, jobDir, chinese);
                HermesMergeCoordinator.MergeResult merge = HermesMergeCoordinator.merge(sourceDir, results);
                if (!merge.success) {
                    String summary = mergeFailureSummary(merge, firstAgentError(results));
                    repository.updateHermesExecutionRun(executionRun.id, "failed", baseSourceHash);
                    throw new IllegalStateException(summary);
                }
                for (HermesAgentResult result : merge.mergedResults) {
                    String summary = result.summary.isEmpty()
                            ? (result.operations == null ? "" : result.operations.summary)
                            : result.summary;
                    String mergedHash = safeSourceHash(sourceDir, result.touchedPaths);
                    if (result.run != null) {
                        repository.updateHermesAgentRun(result.run.id, "done", mergedHash, summary, "");
                    }
                    repository.updateProjectTask(result.task.id, "done", summary);
                    deleteTaskDraftSafely(projectId, result.task.id);
                    clearStreamProgressForTask(result.task);
                    completedTitles.add(result.task.title);
                    anyMerged = true;
                    FileUtils.appendText(logs, (chinese ? "计划任务完成：" : "Executed plan task: ") + result.task.title + "\n" + summary + "\n");
                }
                markMergeFailedResults(merge.failedResults, logs, chinese);
                Exception failedAgentError = firstAgentError(results);
                int failedCount = merge.failedResults.size();
                if (merge.mergedResults.isEmpty() && !batch.tasks.isEmpty()) {
                    if (!anyMerged) {
                        repository.updateHermesExecutionRun(executionRun.id, "failed", baseSourceHash);
                        throw new IllegalStateException(mergeFailureSummary(merge, null), failedAgentError);
                    }
                    FileUtils.appendText(logs, chinese
                            ? "本批次未合并任何任务；保留失败任务并继续检查其它可执行任务。\n"
                            : "No tasks merged in this batch; keeping failed tasks and checking other ready work.\n");
                }
                if (failedCount > 0) {
                    FileUtils.appendText(logs, (chinese ? "本批次失败任务数：" : "Failed tasks in this batch: ") + failedCount + "\n");
                }
                buildRequiredAfter = buildRequiredAfter || batchRequiresBuild(batch);
                HermesScratchCleanup.afterMerge(agentsRoot, failedCount == 0);
            }

            ProjectTaskRecord next = repository.nextPendingProjectTask(projectId);
            List<ProjectTaskRecord> finalTasks = repository.listProjectTasks(projectId);
            int failedTasks = countTasksWithStatus(finalTasks, "failed");
            ProjectTaskRecord exhaustedFailure = SequentialFailureGate.firstExhaustedFailure(finalTasks, dispatchCounts);
            boolean complete = next == null && failedTasks == 0;
            repository.updateProjectPlanStatus(projectId, complete ? "generated" : "planned", job.id);
            repository.updateHermesExecutionRun(executionRun.id, failedTasks == 0 ? "done" : "failed", safeSourceHash(sourceDir));
            HermesScratchCleanup.afterMerge(agentsRoot, failedTasks == 0);

            File projectZip = new File(jobDir, "project.zip");
            FileUtils.zipDirectory(repository.sourceDir(projectId), projectZip);

            String message;
            if (exhaustedFailure != null) {
                message = exhaustedFailurePauseMessage(exhaustedFailure.title, chinese);
            } else if (completedTitles.isEmpty()) {
                message = chinese ? "所有计划任务已完成。下一步可以构建。" : "All plan tasks are done. Next, build the project.";
            } else {
                String completedTitle = executionCompletionTitle(completedTitles, chinese);
                message = taskCompletionMessage(completedTitle, next != null && failedTasks == 0, buildRequiredAfter, chinese);
            }
            if (failedTasks > 0 && exhaustedFailure == null) {
                message += chinese
                        ? "；仍有 " + failedTasks + " 个任务失败，可查看日志后再次执行计划重试。"
                        : "; " + failedTasks + " task(s) still failed. Review the logs and run the plan again to retry.";
            }
            repository.addMessage(projectId, "assistant", message, job.id);
            repository.updateBuildJob(job.id, "generated", "ready_for_build", logs.getAbsolutePath(), null, null, 0);
            return repository.getBuildJob(job.id);
        } catch (Exception error) {
            HermesScratchCleanup.afterMerge(agentsRoot, false);
            String rawErrorMessage = error.getMessage() == null ? error.toString() : error.getMessage();
            String errorMessage = HermesParallelExecutionPolicy.userMessageForBatchFailure(rawErrorMessage, chinese);
            for (ProjectTaskRecord runningTask : runningTasks) {
                ProjectTaskRecord latest = repository.getProjectTask(runningTask.id);
                if (latest != null && "running".equals(latest.status)) {
                    repository.updateProjectTask(runningTask.id, "failed", errorMessage);
                }
                clearStreamProgressForTask(runningTask);
            }
            if (executionRun != null) {
                repository.updateHermesExecutionRun(executionRun.id, "failed", executionRun.baseSourceHash);
            }
            try {
                FileUtils.appendText(logs, (chinese ? "执行计划失败：" : "Plan execution failed: ") + errorMessage + "\n");
            } catch (Exception ignored) {
            }
            repository.updateBuildJob(job.id, "failed", "coding_failed", logs.getAbsolutePath(), null, errorMessage, job.retryCount);
            repository.updateProjectPlanStatus(projectId, "planned", job.id);
            if (errorMessage.equals(rawErrorMessage)) {
                throw error;
            }
            throw new IllegalStateException(errorMessage, error);
        }
    }

    private BuildJobRecord repairBuild(long projectId, String buildLog) throws Exception {
        ProjectRecord project = repository.getProject(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }
        boolean chinese = AppSettings.isChinese(context);
        ProjectPlanRecord plan = repository.latestProjectPlan(projectId);
        String planContent = plan == null || plan.content.trim().isEmpty()
                ? fallbackRepairPlan(chinese)
                : plan.content;
        BuildJobRecord job = repository.createBuildJob(projectId);
        File jobDir = repository.jobDir(projectId, job.id);
        File logs = new File(jobDir, "build.log");
        try {
            FileUtils.writeText(logs, chinese ? "开始根据构建日志修复当前源码。\n" : "Repairing current source from build log.\n");
            repository.updateBuildJob(job.id, "generating", "repairing_build_failure", logs.getAbsolutePath(), null, null, 0);
            repository.addMessage(projectId, "assistant", chinese ? "正在根据构建日志修复当前源码。" : "Repairing the current source from the build log.", job.id);
            recordHermesRunEvent(projectId, job.id, new HermesRunEvent(
                    job.id + ":repair",
                    "repair",
                    "orchestrator",
                    "repair",
                    "Build repair requested from a failed Gradle/AAPT/javac log.",
                    "Build log:\n" + truncateForInlineLog(buildLog, 4000),
                    "Triage the build log, focus the source snapshot, generate repair operations, and guard before applying.",
                    1));

            File sourceDir = repository.sourceDir(projectId);
            String snapshot = sourceSnapshot(sourceDir, buildLog);
            String baseInstruction = repairInstruction(buildLog, chinese);
            LocalGuardResult triage = triageBuildFailureWithCloudGuard(projectId, job.id, buildLog, snapshot, chinese);
            appendLocalGuardLog(logs, chinese ? "云端守卫构建日志分诊" : "Cloud guard build triage", triage);
            String instruction = triage.usable && triage.decision == LocalGuardResult.Decision.REWRITE
                    && triage.additionalInstruction != null && !triage.additionalInstruction.trim().isEmpty()
                    ? LocalGuardInstructionComposer.forBuildTriage(baseInstruction, triage.additionalInstruction)
                    : baseInstruction;
            List<HermesRepairShard> repairShards = HermesRepairShardingPolicy.shards(buildLog);
            TaskOperations operations = null;
            if (canUseParallelRepair(repairShards)) {
                try {
                    operations = repairBuildInParallel(
                            projectId,
                            job,
                            sourceDir,
                            jobDir,
                            planContent,
                            instruction,
                            buildLog,
                            repairShards,
                            chinese);
                } catch (Exception parallelError) {
                    if (parallelError instanceof ParallelRepairMergeException) {
                        throw parallelError;
                    }
                    String message = parallelError.getMessage() == null ? parallelError.toString() : parallelError.getMessage();
                    FileUtils.appendText(logs, (chinese ? "并行修复回退到单 Agent：" : "Parallel repair fell back to single Agent: ") + message + "\n");
                    recordHermesRunEvent(projectId, job.id, new HermesRunEvent(
                            job.id + ":repair-parallel-fallback",
                            "repair",
                            "orchestrator",
                            "fallback",
                            "Parallel repair failed before committing a successful merge; falling back to single repair.",
                            message,
                            "Single repair path remains the source of truth.",
                            1));
                    operations = null;
                }
            }
            if (operations == null) {
                operations = createAndApplyTaskOperations(
                        projectId,
                        job.id,
                        sourceDir,
                        planContent,
                        chinese ? "修复构建失败" : "Repair build failure",
                        instruction,
                        snapshot,
                        logs,
                        chinese,
                        buildLog,
                        true);
            }

            File projectZip = new File(jobDir, "project.zip");
            FileUtils.zipDirectory(repository.sourceDir(projectId), projectZip);
            FileUtils.appendText(logs, (chinese ? "修复完成：\n" : "Repair complete:\n") + operations.summary + "\n" + (chinese ? "等待重新构建。\n" : "Waiting for build.\n"));

            repository.updateProjectPlanStatus(projectId, "generated", job.id);
            repository.addMessage(projectId, "assistant", (chinese ? "已完成构建修复：" : "Build repair complete: ") + operations.summary, job.id);
            repository.updateBuildJob(job.id, "generated", "ready_for_build", logs.getAbsolutePath(), null, null, 0);
            return repository.getBuildJob(job.id);
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.toString() : error.getMessage();
            try {
                FileUtils.appendText(logs, (chinese ? "修复失败：" : "Repair failed: ") + message + "\n");
            } catch (Exception ignored) {
            }
            repository.updateBuildJob(job.id, "failed", "repair_failed", logs.getAbsolutePath(), null, message, job.retryCount);
            repository.addMessage(projectId, "assistant", context.getString(com.androidbuilder.R.string.repair_build_failed, message), job.id);
            throw error;
        }
    }

    private boolean canUseParallelRepair(List<HermesRepairShard> shards) {
        if (BuildBackendSettings.parallelAgentLimit(context) <= 1 || shards == null || shards.size() <= 1) {
            return false;
        }
        List<String> focusPaths = new ArrayList<>();
        for (HermesRepairShard shard : shards) {
            if (shard == null || shard.exclusive || shard.focusPath.isEmpty()) {
                return false;
            }
            for (String existing : focusPaths) {
                if (HermesFileLockPolicy.conflicts(
                        Collections.singletonList(existing),
                        Collections.singletonList(shard.focusPath))) {
                    return false;
                }
            }
            focusPaths.add(shard.focusPath);
        }
        return true;
    }

    private TaskOperations repairBuildInParallel(
            long projectId,
            BuildJobRecord job,
            File sourceDir,
            File jobDir,
            String planContent,
            String baseInstruction,
            String buildLog,
            List<HermesRepairShard> shards,
            boolean chinese) throws Exception {
        int maxParallel = Math.min(BuildBackendSettings.parallelAgentLimit(context), shards.size());
        String baseSourceHash = safeSourceHash(sourceDir);
        HermesExecutionRunRecord executionRun = repository.createHermesExecutionRun(
                projectId,
                job.id,
                "repair_parallel",
                maxParallel,
                baseSourceHash);
        File agentsRoot = new File(jobDir, "repair-agents");
        FileUtils.deleteRecursively(agentsRoot);
        FileUtils.appendText(new File(jobDir, "build.log"),
                (chinese ? "尝试并行修复分片：" : "Trying parallel repair shards: ") + repairShardTitles(shards) + "\n");
        repository.addMessage(projectId, "assistant",
                chinese ? "检测到多个独立构建错误，正在尝试并行修复。" : "Detected multiple independent build errors; trying parallel repair.",
                job.id);
        recordHermesRunEvent(projectId, job.id, new HermesRunEvent(
                job.id + ":repair-parallel",
                "parallel_repair",
                "orchestrator",
                "dispatch",
                "Dispatch independent build repair shards.",
                repairShardTitles(shards),
                "maxParallel=" + maxParallel + "\nbaseSourceHash=" + baseSourceHash,
                1));

        List<HermesAgentResult> results = executeParallelRepairShards(
                projectId,
                job,
                planContent,
                baseInstruction,
                buildLog,
                shards,
                sourceDir,
                agentsRoot,
                maxParallel,
                chinese);
        Exception failedAgentError = firstAgentError(results);
        if (failedAgentError != null) {
            repository.updateHermesExecutionRun(executionRun.id, "failed", baseSourceHash);
            throw failedAgentError;
        }

        HermesMergeCoordinator.MergePlan plan = HermesMergeCoordinator.plan(results);
        if (!plan.canMergeAll) {
            List<HermesAgentResult> safeResults = nonConflictingRepairResults(results);
            if (safeResults.isEmpty()) {
                repository.updateHermesExecutionRun(executionRun.id, "failed", baseSourceHash);
                throw new IllegalStateException("Parallel repair merge blocked by conflicts: " + joinLines(plan.conflicts));
            }
            HermesMergeCoordinator.MergeResult partialMerge = HermesMergeCoordinator.merge(sourceDir, safeResults);
            if (!partialMerge.success) {
                repository.updateHermesExecutionRun(executionRun.id, "failed", baseSourceHash);
                throw new ParallelRepairMergeException(partialMerge.summary);
            }
            repository.updateHermesExecutionRun(executionRun.id, "failed", safeSourceHash(sourceDir));
            String message = chinese
                    ? "并行修复中有冲突；已合并无冲突修复，未合并的剩余问题建议再用单 Agent 修复。"
                    : "Parallel repair had conflicts; merged non-conflicting fixes and left the remaining fixes unmerged. Use single Agent repair for the rest.";
            repository.addMessage(projectId, "assistant", message, job.id);
            return aggregateRepairOperations(partialMerge.mergedResults, message + "\n" + joinLines(plan.conflicts));
        }

        HermesMergeCoordinator.MergeResult merge = HermesMergeCoordinator.merge(sourceDir, results);
        if (!merge.success) {
            repository.updateHermesExecutionRun(executionRun.id, "failed", baseSourceHash);
            throw new ParallelRepairMergeException(merge.summary);
        }
        repository.updateHermesExecutionRun(executionRun.id, "done", safeSourceHash(sourceDir));
        return aggregateRepairOperations(merge.mergedResults,
                chinese ? "并行构建修复完成。" : "Parallel build repair complete.");
    }

    private List<HermesAgentResult> executeParallelRepairShards(
            long projectId,
            BuildJobRecord job,
            String planContent,
            String baseInstruction,
            String buildLog,
            List<HermesRepairShard> shards,
            File sourceDir,
            File agentsRoot,
            int maxParallel,
            boolean chinese) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(maxParallel);
        try {
            List<Future<HermesAgentResult>> futures = new ArrayList<>();
            for (int i = 0; i < shards.size(); i++) {
                final int shardIndex = i;
                final HermesRepairShard shard = shards.get(i);
                futures.add(executor.submit(new Callable<HermesAgentResult>() {
                    @Override
                    public HermesAgentResult call() {
                        return runRepairShard(
                                projectId,
                                job.id,
                                planContent,
                                baseInstruction,
                                buildLog,
                                shard,
                                shardIndex,
                                sourceDir,
                                agentsRoot,
                                chinese);
                    }
                }));
            }
            List<HermesAgentResult> results = new ArrayList<>();
            for (Future<HermesAgentResult> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private HermesAgentResult runRepairShard(
            long projectId,
            long linkedBuildJobId,
            String planContent,
            String baseInstruction,
            String buildLog,
            HermesRepairShard shard,
            int shardIndex,
            File canonicalSource,
            File agentsRoot,
            boolean chinese) {
        File agentRoot = new File(agentsRoot, "repair-" + shardIndex);
        File scratchSource = new File(agentRoot, "source");
        File logs = new File(agentRoot, "agent.log");
        ProjectTaskRecord task = new ProjectTaskRecord(
                shardIndex + 1,
                projectId,
                shardIndex,
                "Repair build failure: " + shard.focusPath,
                focusedRepairInstruction(baseInstruction, shard),
                "running",
                "",
                0,
                0,
                0,
                0);
        try {
            FileUtils.deleteRecursively(agentRoot);
            if (canonicalSource.exists()) {
                FileUtils.copyRecursively(canonicalSource, scratchSource);
            } else if (!scratchSource.mkdirs()) {
                throw new IllegalStateException("Cannot create scratch source: " + scratchSource);
            }
            FileUtils.writeText(logs, (chinese ? "并行修复分片：" : "Parallel repair shard: ") + shard.focusPath + "\n");
            TaskOperations operations = taskOperationExecutor.execute(
                    projectId,
                    linkedBuildJobId,
                    scratchSource,
                    planContent,
                    task,
                    logs,
                    chinese,
                    shard.logExcerpt.isEmpty() ? buildLog : shard.logExcerpt,
                    null,
                    false,
                    true);
            String summary = operations.summary == null ? "" : operations.summary;
            return new HermesAgentResult(task, null, HermesTaskContract.empty(), operations,
                    Collections.<String>emptyList(), summary, null);
        } catch (Exception error) {
            return new HermesAgentResult(task, null, HermesTaskContract.empty(), null,
                    Collections.singletonList(shard.focusPath), "", error);
        }
    }

    private String focusedRepairInstruction(String baseInstruction, HermesRepairShard shard) {
        return baseInstruction
                + "\n\nRepair only diagnostics related to this focus path:\n"
                + shard.focusPath
                + "\n\nRelevant log excerpt:\n"
                + shard.logExcerpt;
    }

    private List<HermesAgentResult> nonConflictingRepairResults(List<HermesAgentResult> results) {
        Map<String, HermesAgentResult> owners = new HashMap<>();
        Set<HermesAgentResult> blocked = new HashSet<>();
        for (HermesAgentResult result : results) {
            if (result == null || !result.success()) {
                continue;
            }
            for (String path : result.touchedPaths) {
                if (path == null || path.trim().isEmpty()) {
                    continue;
                }
                if (path.contains("*")) {
                    blocked.add(result);
                    continue;
                }
                String normalized;
                try {
                    normalized = PathValidator.normalizeGeneratedPath(path);
                } catch (IllegalArgumentException error) {
                    blocked.add(result);
                    continue;
                }
                HermesAgentResult owner = owners.get(normalized);
                if (owner != null && owner != result) {
                    blocked.add(owner);
                    blocked.add(result);
                } else {
                    owners.put(normalized, result);
                }
            }
        }
        List<HermesAgentResult> safe = new ArrayList<>();
        for (HermesAgentResult result : results) {
            if (result != null && result.success() && !blocked.contains(result)) {
                safe.add(result);
            }
        }
        return safe;
    }

    private TaskOperations aggregateRepairOperations(List<HermesAgentResult> results, String fallbackSummary) {
        List<FileOperation> operations = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        if (fallbackSummary != null && !fallbackSummary.trim().isEmpty()) {
            summary.append(fallbackSummary.trim());
        }
        if (results != null) {
            for (HermesAgentResult result : results) {
                if (result == null || result.operations == null) {
                    continue;
                }
                if (result.operations.operations != null) {
                    operations.addAll(result.operations.operations);
                }
                String resultSummary = result.summary.isEmpty() ? result.operations.summary : result.summary;
                if (resultSummary != null && !resultSummary.trim().isEmpty()) {
                    if (summary.length() > 0) {
                        summary.append('\n');
                    }
                    summary.append(resultSummary.trim());
                }
            }
        }
        return new TaskOperations(summary.toString(), operations);
    }

    private String repairShardTitles(List<HermesRepairShard> shards) {
        if (shards == null || shards.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (HermesRepairShard shard : shards) {
            if (shard == null || shard.focusPath.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(shard.focusPath);
        }
        return builder.toString();
    }

    private List<HermesAgentResult> executeParallelBatch(
            long projectId,
            BuildJobRecord job,
            HermesExecutionRunRecord executionRun,
            ProjectPlanRecord plan,
            HermesParallelBatch batch,
            File sourceDir,
            File jobDir,
            boolean chinese) throws Exception {
        File agentsRoot = new File(jobDir, "agents");
        HermesAgentWorker worker = new HermesAgentWorker(repository, taskOperationExecutor);
        if (batch.tasks.size() <= 1) {
            List<HermesAgentResult> results = new ArrayList<>();
            results.add(worker.runTask(
                    projectId,
                    job.id,
                    executionRun.id,
                    plan,
                    batch.tasks.get(0),
                    batch.batchIndex,
                    0,
                    sourceDir,
                    agentsRoot,
                    chinese));
            return results;
        }
        ExecutorService executor = Executors.newFixedThreadPool(batch.tasks.size());
        try {
            List<Future<HermesAgentResult>> futures = new ArrayList<>();
            for (int i = 0; i < batch.tasks.size(); i++) {
                final int agentIndex = i;
                final ProjectTaskRecord task = batch.tasks.get(i);
                futures.add(executor.submit(new Callable<HermesAgentResult>() {
                    @Override
                    public HermesAgentResult call() {
                        return worker.runTask(
                                projectId,
                                job.id,
                                executionRun.id,
                                plan,
                                task,
                                batch.batchIndex,
                                agentIndex,
                                sourceDir,
                                agentsRoot,
                                chinese);
                    }
                }));
            }
            List<HermesAgentResult> results = new ArrayList<>();
            for (Future<HermesAgentResult> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private void markMergeFailedResults(List<HermesMergeCoordinator.FailedResult> failedResults, File logs, boolean chinese) throws Exception {
        if (failedResults == null) {
            return;
        }
        for (HermesMergeCoordinator.FailedResult failed : failedResults) {
            if (failed == null || failed.result == null) {
                continue;
            }
            HermesAgentResult result = failed.result;
            String reason = failed.reason == null || failed.reason.isEmpty() ? "Merge failed." : failed.reason;
            if (result.run != null) {
                repository.updateHermesAgentRun(result.run.id, "failed", "", "", reason);
            }
            if (result.task != null) {
                repository.updateProjectTask(result.task.id, "failed", reason);
                clearStreamProgressForTask(result.task);
                FileUtils.appendText(logs, (chinese ? "计划任务合并失败：" : "Plan task merge failed: ")
                        + result.task.title + "\n" + reason + "\n");
            }
        }
    }

    private Exception firstAgentError(List<HermesAgentResult> results) {
        if (results == null) {
            return null;
        }
        for (HermesAgentResult result : results) {
            if (result != null && result.error != null) {
                return result.error;
            }
        }
        return null;
    }

    private String mergeFailureSummary(HermesMergeCoordinator.MergeResult merge, Exception failedAgentError) {
        StringBuilder summary = new StringBuilder();
        if (merge != null && merge.summary != null && !merge.summary.trim().isEmpty()) {
            summary.append(merge.summary.trim());
        }
        if (merge != null && merge.failedResults != null) {
            for (HermesMergeCoordinator.FailedResult failed : merge.failedResults) {
                if (failed == null || failed.reason.isEmpty()) {
                    continue;
                }
                if (summary.length() > 0) {
                    summary.append('\n');
                }
                summary.append(failed.reason);
            }
        }
        if (failedAgentError != null) {
            if (summary.length() > 0) {
                summary.append('\n');
            }
            summary.append(failedAgentError.getMessage() == null ? failedAgentError.toString() : failedAgentError.getMessage());
        }
        return summary.length() == 0 ? "Hermes merge failed." : summary.toString();
    }

    private boolean batchRequiresBuild(HermesParallelBatch batch) {
        if (batch == null || batch.tasks == null) {
            return false;
        }
        for (ProjectTaskRecord task : batch.tasks) {
            if (HermesTaskScheduler.decide(
                    HermesTaskContractCodec.extractFromInstruction(task.instruction),
                    "",
                    0,
                    false).requiresBuildAfter) {
                return true;
            }
        }
        return false;
    }

    private String safeSourceHash(File sourceDir) {
        try {
            return SourceTreeHashPolicy.hash(sourceDir);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String safeSourceHash(File sourceDir, List<String> relativePaths) {
        try {
            return SourceTreeHashPolicy.hash(sourceDir, relativePaths);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String taskTitles(List<ProjectTaskRecord> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ProjectTaskRecord task : tasks) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(task.title);
        }
        return builder.toString();
    }

    private int countTasksWithStatus(List<ProjectTaskRecord> tasks, String status) {
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ProjectTaskRecord task : tasks) {
            if (task != null && task.status != null && task.status.equalsIgnoreCase(status)) {
                count++;
            }
        }
        return count;
    }

    private String executionCompletionTitle(List<String> completedTitles, boolean chinese) {
        if (completedTitles == null || completedTitles.isEmpty()) {
            return chinese ? "本轮执行" : "this execution";
        }
        if (completedTitles.size() == 1) {
            return completedTitles.get(0);
        }
        return chinese ? completedTitles.size() + " 个任务" : completedTitles.size() + " tasks";
    }

    private String streamCallTag(long taskId, Long linkedBuildJobId, boolean repairFlow) {
        if (taskId > 0) {
            return "task:" + taskId;
        }
        if (repairFlow && linkedBuildJobId != null) {
            return "repair:" + linkedBuildJobId;
        }
        return "";
    }

    private void updateStreamPhase(String callTag, String phase, int attempt) {
        streamProgressRegistry.updatePhase(callTag, phase, attempt, POLICY_REWRITE_ATTEMPTS);
    }

    private void updateStreamBatch(String callTag, int batchNumber, int batchTotal) {
        streamProgressRegistry.updateBatch(callTag, batchNumber, batchTotal);
    }

    // Mirror the human-readable narration line onto the running task card (where the user is looking),
    // in addition to the build log. Trim to one line and a reasonable width for the card.
    private void narrate(String callTag, File logs, String line) throws java.io.IOException {
        FileUtils.appendText(logs, line);
        String oneLine = line == null ? "" : line.replace("\n", " ").trim();
        if (oneLine.length() > 140) {
            oneLine = oneLine.substring(0, 140) + "…";
        }
        streamProgressRegistry.updateNarration(callTag, oneLine);
    }

    public String taskNarration(String callTag) {
        return streamProgressRegistry.narration(callTag);
    }

    private void clearStreamProgressForTask(ProjectTaskRecord task) {
        if (task != null) {
            streamProgressRegistry.clear("task:" + task.id);
        }
    }

    private String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line.trim());
        }
        return builder.toString();
    }

    private TaskOperations createAndApplyTaskOperations(long projectId, Long linkedBuildJobId, File sourceDir, String planContent, String taskTitle, String taskInstruction, String snapshot, File logs, boolean chinese, String initialFailureContext, boolean repairFlow) throws Exception {
        ProjectTaskRecord task = new ProjectTaskRecord(
                0,
                projectId,
                0,
                taskTitle,
                taskInstruction,
                "running",
                "",
                0,
                0,
                0,
                0);
        return createAndApplyTaskOperationsInternal(
                projectId,
                linkedBuildJobId,
                0,
                sourceDir,
                planContent,
                task.title,
                task.instruction,
                snapshot == null || snapshot.trim().isEmpty() ? sourceSnapshot(sourceDir) : snapshot,
                logs,
                chinese,
                initialFailureContext,
                null,
                true,
                repairFlow);
    }

    private TaskOperations createAndApplyTaskOperationsInternal(long projectId, Long linkedBuildJobId, long taskId, File sourceDir, String planContent, String taskTitle, String taskInstruction, String snapshot, File logs, boolean chinese, String initialFailureContext, TaskOperations initialDraft, boolean deleteDraftOnApplySuccess, boolean repairFlow) throws Exception {
        String instruction = taskInstruction;
        String callTag = streamCallTag(taskId, linkedBuildJobId, repairFlow);
        // Recent user requirements live in chat but may never have reached the plan text; feed them
        // to the coding/repair model so it honors intent the plan dropped.
        String recentRequirements = ConversationContextPolicy.recentUserRequirements(
                repository.listMessages(projectId), CODING_USER_REQUIREMENTS_WINDOW, CODING_USER_REQUIREMENTS_CHARS);
        IllegalArgumentException lastPolicyError = null;
        String previousFailure = initialFailureContext == null ? "" : initialFailureContext.trim();
        TaskOperations previousDraft = initialDraft;
        FailureStreak draftFailureStreak = new FailureStreak();
        draftFailureStreak.remember(previousFailure);
        HermesTaskDecision taskDecision = HermesTaskScheduler.decide(
                HermesTaskContractCodec.extractFromInstruction(taskInstruction),
                previousFailure,
                previousFailure.isEmpty() ? 0 : 1,
                repairFlow);
        ContextNegotiation negotiation = null;
        String retryContext = "";
        int negotiationRounds = 0;
        int preflightRewrites = 0;
        int cloudReviewsUsed = 0;
        boolean scopeExpanded = false;
        boolean scopeExpandedRetryPending = false;
        List<FailureFingerprint> failureFingerprints = new ArrayList<>();
        TaskAttemptJournal journal = new TaskAttemptJournal();
        for (int attempt = 1; attempt <= POLICY_REWRITE_ATTEMPTS; attempt++) {
            if ((repairFlow || !previousFailure.isEmpty()) && retryContext.isEmpty()) {
                retryContext = ContextNegotiationPolicy.retryContext(previousFailure, null);
            }
            boolean forceContextScout = taskDecision.requiresContextScout && attempt == 1;
            if ((forceContextScout || ContextNegotiationPolicy.shouldNegotiate(repairFlow || !previousFailure.isEmpty(), attempt, previousFailure, ""))
                    && negotiationRounds < CONTEXT_NEGOTIATION_ROUNDS) {
                do {
                    negotiationRounds++;
                    updateStreamPhase(callTag, "scouting", attempt);
                    negotiation = negotiateTaskContextWithFallback(
                            projectId,
                            linkedBuildJobId,
                            callTag,
                            planContent,
                            taskTitle,
                            instruction,
                            snapshot,
                            recentRequirements,
                            previousFailure,
                            repairFlow,
                            negotiationRounds,
                            chinese);
                    if (negotiation != null) {
                        retryContext = ContextNegotiationPolicy.retryContext(previousFailure, negotiation, missingNeededFiles(sourceDir, negotiation));
                        String focusText = ContextNegotiationPolicy.focusText(negotiation, previousFailure);
                        if (!focusText.isEmpty()) {
                            snapshot = sourceSnapshot(sourceDir, focusText);
                        }
                    }
                } while (ContextNegotiationPolicy.shouldContinueNegotiation(negotiation, negotiationRounds));
            }
            boolean correctionMode = DraftCorrectionPolicy.shouldCorrect(
                    previousDraft != null,
                    previousFailure,
                    draftFailureStreak.sameErrorStreak);
            String previousDraftSection = correctionMode
                    ? TaskDraftContextPolicy.correctionSection(previousDraft, previousFailure, TaskDraftContextPolicy.DRAFT_SECTION_LIMIT)
                    : "";
            if (previousDraftSection.isEmpty()) {
                correctionMode = false;
            }
            // RC3: family-level stuck detector. A DAO whose callers keep failing to reconcile cycles
            // through many method names; each looks like a new error, so the digit-only same-error fuse
            // never fires and the retry budget drains re-discovering one method per round. When a DAO
            // family is stuck (>= STUCK_FAMILY_THRESHOLD distinct methods flagged "not declared" this
            // dispatch), inject a one-pass reconcile directive listing every method the callers need and
            // refocus the snapshot on that DAO + its callers, so the model declares them all and aligns
            // the call-sites together. Derived fresh each attempt from the persistent fingerprint
            // history so the directive never stacks. Strategy/context only - the merge-time
            // AndroidSourceGuard remains the sole authority on the assembled tree.
            StuckFamilyPolicy.Family stuckFamily = StuckFamilyPolicy.detect(failureFingerprints, STUCK_FAMILY_THRESHOLD);
            String effectiveInstruction = instruction;
            if (stuckFamily != null) {
                // Stage 5: cite the callee's REAL declared methods (from disk + the in-flight draft) so
                // the model reconciles against the actual API instead of guessing.
                List<String> declaredMethods = declaredMethodsForClass(sourceDir, previousDraft, stuckFamily.className);
                effectiveInstruction = LocalGuardInstructionComposer.forPreflightRewrite(
                        instruction, StuckFamilyPolicy.reconcileDirective(stuckFamily, declaredMethods));
                snapshot = sourceSnapshot(sourceDir, stuckFamily.className);
            }
            String requestMode = scopeExpandedRetryPending
                    ? BlockedTaskPolicy.MODE_SCOPE_EXPANDED
                    : taskOperationMode(correctionMode);
            String requestLog = taskOperationsRequestForAiLog(planContent, taskTitle, effectiveInstruction, snapshot, retryContext, previousDraftSection, requestMode, attempt);
            final String attemptInstruction = effectiveInstruction;
            final String attemptSnapshot = snapshot;
            final String attemptRetryContext = retryContext;
            final String attemptPreviousDraftSection = previousDraftSection;
            final boolean attemptCorrectionMode = correctionMode;
            final HermesTaskContract taskContract = HermesTaskContractCodec.extractFromInstruction(instruction);
            final TaskStreamInspectionPolicy[] singleStreamInspection = new TaskStreamInspectionPolicy[1];
            scopeExpandedRetryPending = false;
            updateStreamPhase(callTag, "coding", attempt);
            TaskOperations generatedOperations;
            TaskOperations batchedDraftForResume = null;
            try {
                if (shouldUseBatchedGeneration(previousDraft, attemptCorrectionMode)) {
                    generatedOperations = createTaskOperationsInBatches(
                            projectId,
                            linkedBuildJobId,
                            taskId,
                            sourceDir,
                            planContent,
                            taskTitle,
                            attemptInstruction,
                            attemptSnapshot,
                            recentRequirements,
                            attemptRetryContext,
                            requestLog,
                            callTag,
                            attempt,
                            taskContract,
                            previousDraft,
                            journal,
                            logs,
                            chinese);
                    batchedDraftForResume = generatedOperations;
                } else {
                    TaskStreamInspectionPolicy streamInspection = new TaskStreamInspectionPolicy(taskContract);
                    singleStreamInspection[0] = streamInspection;
                    String operationsJson = recordCloudAiCall(
                            projectId,
                            linkedBuildJobId,
                            (chinese ? "云端 AI · 文件操作生成" : "Cloud AI · task operations") + " #" + attempt,
                            requestLog,
                            () -> openAiClient.createTaskOperations(planContent, taskTitle, attemptInstruction, attemptSnapshot, recentRequirements, attemptRetryContext, attemptPreviousDraftSection, chinese, callTag, streamInspection),
                            taskId);
                    generatedOperations = JavaImportNormalizer.normalize(
                            CanonicalPathPolicy.canonicalizeAll(TaskOperationsParser.fromJson(operationsJson)),
                            attemptSnapshot);
                }
            } catch (BatchGenerationException batchError) {
                previousFailure = batchError.getMessage() == null ? batchError.toString() : batchError.getMessage();
                // Merge the salvaged partial onto the accumulated draft rather than replacing it: a
                // file written in an earlier correction round (e.g. a DAO gaining listInRange) must
                // survive an abort that only salvaged the caller, or the merged tree the guard
                // validates becomes internally inconsistent (caller present, callee's method gone).
                previousDraft = hasDraftProgress(batchError.partialDraft)
                        ? TaskOperationsMergePolicy.merge(previousDraft, batchError.partialDraft)
                        : previousDraft;
                saveTaskDraftSafely(projectId, taskId, previousDraft);
                recordAttemptTermination(logs, journal, attempt, "batch-generation", previousFailure);
                draftFailureStreak.remember(previousFailure);
                retryContext = mergeRetryContext(retryContext, previousFailure);
                snapshot = sourceSnapshot(sourceDir, previousFailure);
                if (attempt == POLICY_REWRITE_ATTEMPTS) {
                    emitExecutionSummary(logs, journal, "failed", chinese);
                    throw batchError;
                }
                continue;
            } catch (OpenAiClient.StreamAbortException streamAbort) {
                previousFailure = streamAbort.getMessage() == null ? streamAbort.toString() : streamAbort.getMessage();
                TaskStreamInspectionPolicy streamInspection = singleStreamInspection[0];
                TaskOperations salvaged = streamInspection == null
                        ? new TaskOperations("partial draft salvaged from aborted stream", Collections.<FileOperation>emptyList())
                        : streamInspection.partialDraft("partial draft salvaged from aborted stream");
                // Merge onto the accumulated draft (see BatchGenerationException above); never null
                // out work the model already produced in earlier rounds.
                previousDraft = hasDraftProgress(salvaged)
                        ? TaskOperationsMergePolicy.merge(previousDraft, salvaged)
                        : previousDraft;
                saveTaskDraftSafely(projectId, taskId, previousDraft);
                recordAttemptTermination(logs, journal, attempt, "stream-abort", previousFailure);
                draftFailureStreak.remember(previousFailure);
                retryContext = mergeRetryContext(retryContext, previousFailure);
                snapshot = sourceSnapshot(sourceDir, previousFailure);
                if (attempt == POLICY_REWRITE_ATTEMPTS) {
                    emitExecutionSummary(logs, journal, "failed", chinese);
                    throw new IllegalArgumentException(previousFailure, streamAbort);
                }
                continue;
            } catch (IllegalArgumentException generationError) {
                previousFailure = generationError.getMessage() == null ? generationError.toString() : generationError.getMessage();
                TaskStreamInspectionPolicy streamInspection = singleStreamInspection[0];
                TaskOperations salvaged = streamInspection == null
                        ? new TaskOperations("partial draft salvaged from failed generation", Collections.<FileOperation>emptyList())
                        : streamInspection.partialDraft("partial draft salvaged from failed generation");
                // Merge onto the accumulated draft (see BatchGenerationException above) instead of
                // replacing it with the salvage alone.
                previousDraft = hasDraftProgress(salvaged)
                        ? TaskOperationsMergePolicy.merge(previousDraft, salvaged)
                        : previousDraft;
                saveTaskDraftSafely(projectId, taskId, previousDraft);
                recordAttemptTermination(logs, journal, attempt, "generation", previousFailure);
                draftFailureStreak.remember(previousFailure);
                retryContext = mergeRetryContext(retryContext, previousFailure);
                snapshot = sourceSnapshot(sourceDir, previousFailure);
                if (attempt == POLICY_REWRITE_ATTEMPTS) {
                    emitExecutionSummary(logs, journal, "failed", chinese);
                    throw generationError;
                }
                continue;
            }
            try {
                TaskOperations operations = generatedOperations;
                if (operations.blocked) {
                    if (ManifestResumePolicy.hasManifest(operations)) {
                        previousDraft = new TaskOperations(
                                operations.summary,
                                operations.operations,
                                false,
                                "",
                                "",
                                operations.manifestJson,
                                operations.acceptedPaths);
                    }
                    String blockedSummary = BlockedTaskPolicy.blockedSummary(operations);
                    if (BlockedTaskPolicy.shouldExpandScope(operations, scopeExpanded)) {
                        scopeExpanded = true;
                        scopeExpandedRetryPending = true;
                        previousFailure = blockedSummary;
                        saveTaskDraftSafely(projectId, taskId, previousDraft);
                        recordAttemptTermination(logs, journal, attempt, "blocked-scope-expand", previousFailure);
                        draftFailureStreak.remember(previousFailure);
                        retryContext = mergeRetryContext(retryContext, blockedSummary);
                        instruction = BlockedTaskPolicy.scopeExpandedInstruction(instruction, operations);
                        snapshot = sourceSnapshot(sourceDir, BlockedTaskPolicy.snapshotFocus(operations));
                        FileUtils.appendText(logs, (chinese ? "任务前置缺失，扩展一次执行边界：" : "Task reported missing prerequisites; expanding scope once: ")
                                + blockedSummary + "\n");
                        attempt--;
                        continue;
                    }
                    saveTaskDraftSafely(projectId, taskId, previousDraft);
                    recordAttemptTermination(logs, journal, attempt, "blocked", blockedSummary);
                    emitExecutionSummary(logs, journal, "blocked", chinese);
                    throw new IllegalStateException(blockedSummary);
                }
                if (attemptCorrectionMode) {
                    operations = TaskOperationsMergePolicy.merge(previousDraft, operations);
                } else {
                    operations = TaskOperationsMergePolicy.stripDrops(operations);
                }
                operations = JavaImportNormalizer.normalize(operations, snapshot);
                previousDraft = operations;
                String reviewedOperationsJson = taskOperationsJson(operations);
                HermesReview contractReview = HermesTaskContractGuard.review(taskContract, operations);
                String contractTitle = (chinese ? "Hermes · 任务契约预检" : "Hermes · task contract preflight") + " #" + attempt;
                if (taskContract.hasSignals()) {
                    appendHermesReviewLog(logs, contractTitle, contractReview);
                    recordHermesReviewAi(projectId, linkedBuildJobId, contractTitle,
                            deterministicPreflightRequest(taskTitle, instruction, reviewedOperationsJson), contractReview, taskId);
                }
                if (contractReview.decision == HermesReview.Decision.REWRITE
                        && contractReview.rewriteInstruction != null
                        && !contractReview.rewriteInstruction.trim().isEmpty()
                        && preflightRewrites < PREFLIGHT_REWRITE_BUDGET) {
                    preflightRewrites++;
                    journal.recordRewrite();
                    previousFailure = mergeRetryContext(
                            HermesReviewerPolicy.rewriteContext(contractReview),
                            GuardFindingPolicy.retryContext(GuardFindingPolicy.fromHermesReview("hermes-contract-guard", contractReview)));
                    previousFailure = mergeRetryContext(previousFailure,
                            rememberFailure(failureFingerprints, FailureFingerprintPolicy.fromHermesReview("hermes-contract-guard", contractReview)));
                    draftFailureStreak.remember(previousFailure);
                    retryContext = mergeRetryContext(retryContext, previousFailure);
                    instruction = LocalGuardInstructionComposer.forPreflightRewrite(instruction, previousFailure);
                    snapshot = sourceSnapshot(sourceDir, previousFailure);
                    continue;
                }
                HermesReview deterministicReview = TaskOperationsPreflight.review(operations, snapshot);
                String deterministicTitle = (chinese ? "确定性预检" : "Deterministic preflight") + " #" + attempt;
                appendHermesReviewLog(logs, deterministicTitle, deterministicReview);
                recordHermesReviewAi(projectId, linkedBuildJobId, deterministicTitle,
                        deterministicPreflightRequest(taskTitle, instruction, reviewedOperationsJson), deterministicReview, taskId);
                // Pre-apply structural rewrites are capped and never throw: they must not starve the
                // policy-error retry budget, and AndroidSourceGuard + the real build are the final authority.
                if (deterministicReview.decision == HermesReview.Decision.REWRITE
                        && deterministicReview.rewriteInstruction != null
                        && !deterministicReview.rewriteInstruction.trim().isEmpty()
                        && preflightRewrites < PREFLIGHT_REWRITE_BUDGET) {
                    preflightRewrites++;
                    journal.recordRewrite();
                    previousFailure = mergeRetryContext(
                            HermesReviewerPolicy.rewriteContext(deterministicReview),
                            GuardFindingPolicy.retryContext(GuardFindingPolicy.fromHermesReview(deterministicTitle, deterministicReview)));
                    previousFailure = mergeRetryContext(previousFailure,
                            rememberFailure(failureFingerprints, FailureFingerprintPolicy.fromHermesReview(deterministicTitle, deterministicReview)));
                    draftFailureStreak.remember(previousFailure);
                    retryContext = mergeRetryContext(retryContext, previousFailure);
                    instruction = LocalGuardInstructionComposer.forPreflightRewrite(instruction, previousFailure);
                    snapshot = sourceSnapshot(sourceDir, previousFailure);
                    continue;
                }
                // `operations` here is the merged accumulated draft (post TaskOperationsMergePolicy.merge
                // at the top of this attempt), not just the latest correction's re-sent files. The local
                // guard's declaration resolution depends on seeing every file the merge will write - keep
                // this call after the merge so a caller-only correction does not trigger phantom hints.
                LocalGuardResult preflight = reviewOperationsWithLocalRules(projectId, linkedBuildJobId, snapshot, operations, chinese);
                appendLocalGuardLog(logs, chinese ? "确定性规则预审" : "Deterministic rule preflight", preflight);
                if (shouldRetryFromLocalGuard(preflight) && preflightRewrites < PREFLIGHT_REWRITE_BUDGET) {
                    preflightRewrites++;
                    journal.recordRewrite();
                    previousFailure = GuardFindingPolicy.retryContext(GuardFindingPolicy.fromLocalGuardResult("local-guard-preflight", preflight));
                    if (previousFailure.isEmpty()) {
                        previousFailure = preflight.additionalInstruction;
                    }
                    previousFailure = mergeRetryContext(previousFailure,
                            rememberFailure(failureFingerprints, FailureFingerprintPolicy.fromLocalGuardResult("local-guard-preflight", preflight)));
                    draftFailureStreak.remember(previousFailure);
                    retryContext = mergeRetryContext(retryContext, previousFailure);
                    instruction = LocalGuardInstructionComposer.forPreflightRewrite(instruction, preflight.additionalInstruction);
                    snapshot = sourceSnapshot(sourceDir, previousFailure);
                    continue;
                }
                updateStreamPhase(callTag, "reviewing", attempt);
                narrate(callTag, logs, BatchNarrationPolicy.reviewingLine(chinese));
                HermesReview hermesReview = reviewOperationsWithHermes(
                        projectId,
                        linkedBuildJobId,
                        callTag,
                        taskTitle,
                        instruction,
                        snapshot,
                        reviewedOperationsJson,
                        operations,
                        negotiation,
                        repairFlow || !previousFailure.isEmpty(),
                        attempt,
                        cloudReviewsUsed,
                        POLICY_REWRITE_ATTEMPTS,
                        preflightRewrites < PREFLIGHT_REWRITE_BUDGET,
                        logs,
                        chinese);
                boolean deterministicOkThisAttempt = deterministicReview.decision == HermesReview.Decision.OK;
                if (HermesReviewerPolicy.isStaleOrDuplicate(hermesReview, deterministicOkThisAttempt)) {
                    hermesReview = null;
                }
                if (HermesReviewerPolicy.isValidCloudDecision(hermesReview)) {
                    cloudReviewsUsed++;
                    journal.recordReview();
                }
                if (HermesReviewerPolicy.shouldRetry(hermesReview, attempt, POLICY_REWRITE_ATTEMPTS)
                        && preflightRewrites < PREFLIGHT_REWRITE_BUDGET) {
                    preflightRewrites++;
                    journal.recordRewrite();
                    previousFailure = mergeRetryContext(
                            HermesReviewerPolicy.rewriteContext(hermesReview),
                            GuardFindingPolicy.retryContext(GuardFindingPolicy.fromHermesReview("hermes-review", hermesReview)));
                    previousFailure = mergeRetryContext(previousFailure,
                            rememberFailure(failureFingerprints, FailureFingerprintPolicy.fromHermesReview("hermes-review", hermesReview)));
                    draftFailureStreak.remember(previousFailure);
                    retryContext = mergeRetryContext(retryContext, previousFailure);
                    instruction = LocalGuardInstructionComposer.forPreflightRewrite(instruction, previousFailure);
                    snapshot = sourceSnapshot(sourceDir, previousFailure);
                    continue;
                }
                updateStreamPhase(callTag, "merging", attempt);
                narrate(callTag, logs, BatchNarrationPolicy.mergingLine(chinese));
                operationsWriter.apply(sourceDir, operations);
                if (deleteDraftOnApplySuccess) {
                    deleteTaskDraftSafely(projectId, taskId);
                }
                List<String> stubs = operationsWriter.lastStubReconciliations();
                if (stubs != null && !stubs.isEmpty()) {
                    // Surface the unfinished-behaviour debt: the build succeeds, but these members are
                    // stubs that throw until filled (greppable via // ANDROIDBUILDER-STUB in the source).
                    narrate(callTag, logs, (chinese ? "🩹 自动补桩 " : "🩹 auto-stubbed ") + stubs.size()
                            + (chinese ? " 处以让其编译通过（待回填）：" : " member(s) so it compiles (TODO): ")
                            + String.join("; ", stubs));
                }
                recordAttemptTermination(logs, journal, attempt, "apply", "success");
                String executionSummary = (chinese ? "执行流摘要：" : "Execution flow: ")
                        + journal.renderSummary("success");
                FileUtils.appendText(logs, (chinese ? "任务执行摘要：" : "Task execution summary: ")
                        + executionSummary + "\n");
                return withExecutionSummary(operations, executionSummary);
            } catch (IllegalArgumentException policyError) {
                if (!isRewriteablePolicyError(policyError) || attempt == POLICY_REWRITE_ATTEMPTS) {
                    saveTaskDraftSafely(projectId, taskId, previousDraft);
                    recordAttemptTermination(logs, journal, attempt, "policy-error", policyError.getMessage());
                    emitExecutionSummary(logs, journal, "failed", chinese);
                    throw policyError;
                }
                lastPolicyError = policyError;
                recordAttemptTermination(logs, journal, attempt, "policy-error-retry", policyError.getMessage());
                // P2: if a batched generation was rejected by the whole-tree merge guard, resume the
                // manifest with only the rejected files evicted. The next attempt regenerates just
                // those callers against the frozen, already-accepted foundation (correct DbHelper /
                // DAO signatures in context) instead of dropping to single-shot full mode that
                // truncates the foundation and re-rolls every signature - the loop that burned a week.
                TaskOperations resumeDraft = resumeDraftEvictingRejectedFiles(batchedDraftForResume, policyError.getMessage());
                if (resumeDraft != null) {
                    previousDraft = resumeDraft;
                    saveTaskDraftSafely(projectId, taskId, resumeDraft);
                    FileUtils.appendText(logs, (chinese ? "保留已通过的基础，仅重生成被拒文件后续批。\n"
                            : "Froze accepted foundation; resuming batches to regenerate only the rejected files.\n"));
                }
                // Refocus the snapshot on the files/types named in the rejection so the next attempt
                // sees the offending caller and the real class declarations in full (untruncated).
                snapshot = sourceSnapshot(sourceDir, policyError.getMessage());
                previousFailure = policyError.getMessage();
                draftFailureStreak.remember(previousFailure);
                retryContext = mergeRetryContext(retryContext,
                        rememberFailure(failureFingerprints, FailureFingerprintPolicy.fromPolicyError(policyError.getMessage())));
                String policyInstruction = PolicyRewriteInstruction.create(instruction, policyError.getMessage(), attempt + 1);
                // Inject the REAL API of every class the merge guard named, built from disk + the
                // in-flight draft. The guard says "missing method: RecurringDao.findById" but not what
                // RecurringDao actually declares; the model keeps re-inventing the same call from the
                // digest it has demonstrably ignored. Listing the authoritative members lets it
                // reconcile against the truth. Advisory only - the merge guard stays the authority.
                String calleeApi = CalleeApiHintPolicy.hint(
                        policyError.getMessage(),
                        new AndroidSourceGuard().symbolTableOf(sourceDir, draftJavaContents(previousDraft)));
                if (!calleeApi.isEmpty()) {
                    policyInstruction = policyInstruction + "\n\n" + calleeApi;
                }
                LocalGuardResult rewriteHint = rewritePolicyFailureWithLocalRules(projectId, linkedBuildJobId, policyError.getMessage(), chinese);
                appendLocalGuardLog(logs, chinese ? "本地规则策略错误提示" : "Local rule policy-error hint", rewriteHint);
                instruction = rewriteHint.usable && rewriteHint.decision == LocalGuardResult.Decision.REWRITE
                        ? LocalGuardInstructionComposer.forPolicyRewrite(policyInstruction, rewriteHint.additionalInstruction)
                        : policyInstruction;
                if (rewriteHint.usable && rewriteHint.decision == LocalGuardResult.Decision.REWRITE) {
                    String findingContext = GuardFindingPolicy.retryContext(GuardFindingPolicy.fromLocalGuardResult("policy-error-guard", rewriteHint));
                    retryContext = mergeRetryContext(retryContext, findingContext.isEmpty() ? rewriteHint.additionalInstruction : findingContext);
                }
            }
        }
        saveTaskDraftSafely(projectId, taskId, previousDraft);
        String finalFailure = previousFailure == null || previousFailure.trim().isEmpty()
                ? "Task operation generation failed."
                : "Task operation generation failed. Last failure: " + previousFailure.trim();
        emitExecutionSummary(logs, journal, "exhausted", chinese);
        throw lastPolicyError == null ? new IllegalStateException(finalFailure) : lastPolicyError;
    }

    private boolean shouldUseBatchedGeneration(TaskOperations previousDraft, boolean correctionMode) {
        if (!OpenAiClient.batchedGenerationEnabled(context.getSharedPreferences(OpenAiClient.PREFS, Context.MODE_PRIVATE))) {
            return false;
        }
        if (ManifestResumePolicy.shouldResume(previousDraft)) {
            return true;
        }
        return previousDraft == null && !correctionMode;
    }

    private static boolean hasDraftProgress(TaskOperations draft) {
        return draft != null
                && ((draft.operations != null && !draft.operations.isEmpty())
                || ManifestResumePolicy.hasManifest(draft));
    }

    /**
     * Stage 5: the methods the stuck callee class currently declares, resolved from the on-disk tree
     * plus the in-flight draft (the callee may be a same-task file not yet written to disk). Best-effort
     * and read-only - used only to enrich the reconcile directive.
     */
    private static List<String> declaredMethodsForClass(File sourceDir, TaskOperations draft, String className) {
        SymbolTable table = new AndroidSourceGuard().symbolTableOf(sourceDir, draftJavaContents(draft));
        List<String> names = new ArrayList<>(table.declaredMethodNames(className));
        Collections.sort(names);
        return names;
    }

    /** The .java write contents of a draft, for building a SymbolTable over disk + in-flight work. */
    private static List<String> draftJavaContents(TaskOperations draft) {
        List<String> javaContents = new ArrayList<>();
        if (draft != null && draft.operations != null) {
            for (FileOperation operation : draft.operations) {
                if (operation != null && operation.path != null && operation.path.endsWith(".java")
                        && operation.content != null && "write".equals(operation.action)) {
                    javaContents.add(operation.content);
                }
            }
        }
        return javaContents;
    }

    private static TaskOperations withExecutionSummary(TaskOperations operations, String executionSummary) {
        if (operations == null || executionSummary == null || executionSummary.trim().isEmpty()) {
            return operations;
        }
        String summary = operations.summary == null ? "" : operations.summary.trim();
        summary = summary.isEmpty() ? executionSummary.trim() : summary + "\n" + executionSummary.trim();
        return new TaskOperations(
                summary,
                operations.operations,
                operations.blocked,
                operations.blockedReason,
                operations.prerequisiteWork,
                operations.manifestJson,
                operations.acceptedPaths);
    }

    private TaskOperations createTaskOperationsInBatches(
            long projectId,
            Long linkedBuildJobId,
            long taskId,
            File sourceDir,
            String planContent,
            String taskTitle,
            String taskInstruction,
            String snapshot,
            String recentRequirements,
            String retryContext,
            String requestLog,
            String callTag,
            int attempt,
            HermesTaskContract taskContract,
            TaskOperations previousDraft,
            TaskAttemptJournal journal,
            File logs,
            boolean chinese) throws Exception {
        updateStreamPhase(callTag, "manifest", attempt);
        String manifestJson;
        TaskManifest manifest;
        List<FileOperation> accepted = new ArrayList<>();
        if (ManifestResumePolicy.hasManifest(previousDraft)) {
            manifestJson = previousDraft.manifestJson;
            manifest = ManifestResumePolicy.manifest(previousDraft);
            accepted.addAll(ManifestResumePolicy.acceptedOperations(previousDraft));
            FileUtils.appendText(logs, chinese
                    ? "复用任务清单草稿，继续剩余批次。\n"
                    : "Resuming task manifest draft for remaining batches.\n");
        } else {
            manifestJson = recordCloudAiCall(
                    projectId,
                    linkedBuildJobId,
                    (chinese ? "云端 AI · 任务文件清单" : "Cloud AI · task file manifest") + " #" + attempt,
                    requestLog,
                    () -> openAiClient.createTaskManifest(planContent, taskTitle, taskInstruction, snapshot, recentRequirements, retryContext, chinese, callTag),
                    taskId);
            if (journal != null) {
                journal.recordManifest();
            }
            manifest = TaskManifestParser.fromJson(manifestJson);
        }
        if (manifest.blocked) {
            return manifest.toBlockedOperations();
        }
        List<List<TaskManifest.Entry>> allBatches = ManifestBatchPolicy.batches(manifest.files);
        List<List<TaskManifest.Entry>> batches = ManifestResumePolicy.hasManifest(previousDraft)
                ? ManifestResumePolicy.remainingBatches(manifest, previousDraft.acceptedPaths)
                : allBatches;
        int completedBeforeResume = allBatches.size() - batches.size();
        if (journal != null) {
            journal.recordBatchProgress(completedBeforeResume, allBatches.size());
        }
        if (batches.isEmpty()) {
            return new TaskOperations(manifest.summary, accepted);
        }
        FileUtils.appendText(logs, (chinese ? "任务清单：" : "Task manifest: ")
                + manifest.files.size() + (chinese ? " 个文件，分 " : " file(s), ")
                + allBatches.size() + (chinese ? " 批，剩余 " : " batch(es), remaining ")
                + batches.size() + "\n");
        narrate(callTag, logs, BatchNarrationPolicy.manifestLine(
                manifest.summary, manifest.files.size(), allBatches.size(), chinese));
        for (int i = 0; i < batches.size(); i++) {
            List<TaskManifest.Entry> batch = batches.get(i);
            int batchNumber = completedBeforeResume + i + 1;
            narrate(callTag, logs, BatchNarrationPolicy.batchLine(batchNumber, allBatches.size(), batch, chinese));
            String batchRetryContext = "";
            boolean acceptedBatch = false;
            for (int batchAttempt = 1; batchAttempt <= 2; batchAttempt++) {
                TaskStreamInspectionPolicy streamInspection = new TaskStreamInspectionPolicy(taskContract);
                String completedContext = CompletedBatchContextPolicy.context(accepted, CompletedBatchContextPolicy.DEFAULT_MAX_CHARS);
                String effectiveRetryContext = mergeRetryContext(retryContext, batchRetryContext);
                String batchRequest = requestLog
                        + "\n\nBatch " + batchNumber + "/" + allBatches.size() + " files:\n" + batchFileListForLog(batch)
                        + "\n\nCompleted files context:\n" + truncateForInlineLog(completedContext, 20000);
                try {
                    updateStreamPhase(callTag, "coding", attempt);
                    updateStreamBatch(callTag, batchNumber, allBatches.size());
                    String batchJson = recordCloudAiCall(
                            projectId,
                            linkedBuildJobId,
                            (chinese ? "云端 AI · 文件操作生成批次 " : "Cloud AI · task operations batch ")
                                    + batchNumber + "/" + allBatches.size() + " #" + attempt + "." + batchAttempt,
                            batchRequest,
                            () -> openAiClient.createTaskOperationsBatch(
                                    planContent,
                                    taskTitle,
                                    taskInstruction,
                                    snapshot,
                                    recentRequirements,
                                    effectiveRetryContext,
                                    batch,
                                    completedContext,
                                    chinese,
                                    callTag,
                                    streamInspection),
                            taskId);
                    TaskOperations batchOperations = JavaImportNormalizer.normalize(
                            CanonicalPathPolicy.canonicalizeAll(TaskOperationsParser.fromJson(batchJson)),
                            snapshot);
                    if (batchOperations.blocked) {
                        TaskOperations partial = partialBatchDraft(accepted, null, manifestJson);
                        saveTaskDraftSafely(projectId, taskId, partial);
                        return new TaskOperations(
                                batchOperations.summary,
                                partial.operations,
                                true,
                                batchOperations.blockedReason,
                                batchOperations.prerequisiteWork,
                                manifestJson,
                                partial.acceptedPaths);
                    }
                    String validationError = BatchValidationPolicy.review(
                            batchOperations.operations,
                            manifestPaths(batch),
                            taskContract);
                    if (validationError == null) {
                        accepted.addAll(batchOperations.operations);
                        saveTaskDraftSafely(projectId, taskId, partialBatchDraft(accepted, null, manifestJson));
                        if (journal != null) {
                            journal.recordBatchProgress(batchNumber, allBatches.size());
                        }
                        acceptedBatch = true;
                        break;
                    }
                    batchRetryContext = validationError;
                    FileUtils.appendText(logs, (chinese ? "批次校验失败：" : "Batch validation failed: ") + validationError + "\n");
                } catch (OpenAiClient.StreamAbortException streamAbort) {
                    TaskOperations partial = partialBatchDraft(accepted, streamInspection.partialDraft("partial draft salvaged from aborted stream"), manifestJson);
                    String message = streamAbort.getMessage() == null ? streamAbort.toString() : streamAbort.getMessage();
                    throw new BatchGenerationException(message, partial, streamAbort);
                } catch (IllegalArgumentException parseOrPolicyError) {
                    batchRetryContext = parseOrPolicyError.getMessage() == null ? parseOrPolicyError.toString() : parseOrPolicyError.getMessage();
                    FileUtils.appendText(logs, (chinese ? "批次生成失败：" : "Batch generation failed: ") + batchRetryContext + "\n");
                }
            }
            if (!acceptedBatch) {
                throw new BatchGenerationException(
                        batchRetryContext.isEmpty() ? "Batch generation failed." : batchRetryContext,
                        partialBatchDraft(accepted, null, manifestJson));
            }
        }
        // Carry the manifest + accepted paths so that, if the whole-tree merge guard later rejects
        // a caller, the next attempt can RESUME this manifest and regenerate only the rejected files
        // against the frozen, already-accepted foundation - instead of re-emitting all 30 files in
        // single-shot mode (which truncates the foundation and re-rolls every signature).
        return new TaskOperations(
                manifest.summary,
                accepted,
                false,
                "",
                "",
                manifestJson,
                ManifestResumePolicy.acceptedPathsFor(accepted));
    }

    private TaskOperations partialBatchDraft(List<FileOperation> accepted, TaskOperations currentPartial, String manifestJson) {
        List<FileOperation> operations = new ArrayList<>();
        if (accepted != null) {
            operations.addAll(accepted);
        }
        if (currentPartial != null && currentPartial.operations != null) {
            operations.addAll(currentPartial.operations);
        }
        TaskOperations canonical = CanonicalPathPolicy.canonicalizeAll(new TaskOperations("partial draft salvaged from aborted batch", operations));
        return new TaskOperations(
                canonical.summary,
                canonical.operations,
                false,
                "",
                "",
                manifestJson,
                ManifestResumePolicy.acceptedPathsFor(accepted));
    }

    private static List<String> manifestPaths(List<TaskManifest.Entry> batch) {
        List<String> paths = new ArrayList<>();
        if (batch != null) {
            for (TaskManifest.Entry entry : batch) {
                if (entry != null) {
                    paths.add(entry.path);
                }
            }
        }
        return paths;
    }

    private static String batchFileListForLog(List<TaskManifest.Entry> batch) {
        StringBuilder builder = new StringBuilder();
        if (batch != null) {
            for (TaskManifest.Entry entry : batch) {
                if (entry == null) {
                    continue;
                }
                builder.append("- ")
                        .append(entry.action)
                        .append(' ')
                        .append(entry.path);
                if (entry.intent != null && !entry.intent.isEmpty()) {
                    builder.append(": ").append(entry.intent);
                }
                builder.append('\n');
            }
        }
        return builder.toString().trim();
    }

    private boolean shouldRetryFromLocalGuard(LocalGuardResult result) {
        return result != null
                && result.usable
                && result.decision == LocalGuardResult.Decision.REWRITE
                && result.additionalInstruction != null
                && !result.additionalInstruction.trim().isEmpty();
    }

    private ContextNegotiation negotiateTaskContextWithFallback(long projectId, Long linkedBuildJobId, String callTag, String planContent, String taskTitle, String taskInstruction, String snapshot, String recentRequirements, String previousFailure, boolean repairFlow, int round, boolean chinese) {
        String title = hermesContextTitle(repairFlow, round, chinese);
        String request = "Round: " + round
                + "\n\nTask title:\n" + taskTitle
                + "\n\nTask instruction:\n" + truncateForInlineLog(taskInstruction, 12000)
                + "\n\nPrevious failure summary:\n" + truncateForInlineLog(previousFailure, 12000)
                + "\n\nRecent user requirements:\n" + truncateForInlineLog(recentRequirements, 4000)
                + "\n\nCurrent source snapshot:\n" + truncateForInlineLog(snapshot, 24000);
        try {
            String response = recordCloudAiCall(
                    projectId,
                    linkedBuildJobId,
                    title,
                    request,
                    () -> openAiClient.negotiateTaskContext(planContent, taskTitle, taskInstruction, snapshot, recentRequirements, previousFailure, chinese, callTag),
                    taskIdFromCallTag(callTag));
            return ContextNegotiationParser.fromJson(response);
        } catch (Exception error) {
            recordAiConversationSafely(
                    projectId,
                    "cloud",
                    title,
                    request,
                    localGuardErrorMessage(error),
                    "fallback",
                    cloudAiMetadata(),
                    linkedBuildJobId);
            return null;
        }
    }

    private String mergeRetryContext(String existing, String addition) {
        return RetryContextPolicy.merge(existing, addition);
    }

    // Phase 7.1: record one attempt's terminal outcome in the journal AND write the structured
    // per-attempt line to the job log, so every failed attempt's reason survives (the journal used
    // to keep only the last) and the next diagnosis does not need a multi-megabyte raw log.
    private void recordAttemptTermination(File logs, TaskAttemptJournal journal, int attempt, String phase, String reason) {
        String line = journal.recordAttempt(attempt, phase, reason);
        appendLogSafely(logs, "attempt-termination: " + line + "\n");
    }

    // Phase 7.2: emit the end-of-task execution summary card on every termination path, not just
    // success and policy-error, so a batch/stream/generation/blocked failure also leaves the card.
    private void emitExecutionSummary(File logs, TaskAttemptJournal journal, String reason, boolean chinese) {
        appendLogSafely(logs, (chinese ? "任务执行摘要：" : "Task execution summary: ")
                + journal.renderSummary(reason) + "\n");
    }

    // Observability writes are best-effort: a log-append failure must never mask the real task
    // error these helpers are called alongside (often right before a throw).
    private void appendLogSafely(File logs, String text) {
        try {
            FileUtils.appendText(logs, text);
        } catch (Exception ignored) {
        }
    }

    private TaskOperations resumeDraftEvictingRejectedFiles(TaskOperations batchedDraft, String guardMessage) {
        return BatchResumePolicy.resumeDraftEvicting(batchedDraft, guardMessage);
    }

    private void saveTaskDraftSafely(long projectId, long taskId, TaskOperations draft) {
        try {
            new TaskDraftStore(repository.projectRoot(projectId)).save(taskId, draft);
        } catch (Exception ignored) {
            // Draft memory is an optimization for retries and must never mask the real task error.
        }
    }

    private void deleteTaskDraftSafely(long projectId, long taskId) {
        try {
            new TaskDraftStore(repository.projectRoot(projectId)).delete(taskId);
        } catch (Exception ignored) {
            // Best-effort cleanup only.
        }
    }

    private void deleteAllTaskDraftsSafely(long projectId) {
        try {
            new TaskDraftStore(repository.projectRoot(projectId)).deleteAll();
        } catch (Exception ignored) {
            // Best-effort cleanup only.
        }
    }

    private String rememberFailure(List<FailureFingerprint> history, FailureFingerprint fingerprint) {
        if (history == null || fingerprint == null) {
            return "";
        }
        history.add(fingerprint);
        return FailureFingerprintPolicy.repeatedRetryContext(history, fingerprint, 2);
    }

    private String hermesContextTitle(boolean repairFlow, int round, boolean chinese) {
        if (repairFlow) {
            return (chinese ? "Hermes · 修复上下文侦察" : "Hermes · Repair Context Scout") + " #" + round;
        }
        return (chinese ? "Hermes · 上下文侦察" : "Hermes · Context Scout") + " #" + round;
    }

    private HermesReview reviewOperationsWithHermes(long projectId, Long linkedBuildJobId, String callTag, String taskTitle, String taskInstruction, String snapshot, String operationsJson, TaskOperations operations, ContextNegotiation negotiation, boolean retryOrRepairFlow, int attempt, int cloudReviewsUsed, int maxAttempts, boolean rewriteBudgetAvailable, File logs, boolean chinese) {
        if (!HermesReviewerPolicy.shouldReviewOperations(retryOrRepairFlow, attempt, negotiation, operations, cloudReviewsUsed, maxAttempts, rewriteBudgetAvailable)) {
            return null;
        }
        String title = (chinese ? "Hermes · 文件操作审查" : "Hermes · file operation review") + " #" + attempt;
        String contextScoutNotes = HermesReviewerPolicy.contextScoutNotes(negotiation);
        String request = hermesReviewRequestForAiLog(taskTitle, taskInstruction, snapshot, operationsJson, contextScoutNotes);
        try {
            String response = recordCloudAiCall(
                    projectId,
                    linkedBuildJobId,
                    title,
                    request,
                    () -> openAiClient.reviewTaskOperations(taskTitle, taskInstruction, snapshot, operationsJson, contextScoutNotes, chinese, callTag),
                    taskIdFromCallTag(callTag));
            HermesReview review = HermesReviewParser.fromJson(response);
            appendHermesReviewLog(logs, title, review);
            return review;
        } catch (Exception error) {
            HermesReview fallback = new HermesReview(
                    HermesReview.Decision.FALLBACK,
                    (chinese ? "Hermes 审查不可用：" : "Hermes review unavailable: ") + localGuardErrorMessage(error),
                    "");
            appendHermesReviewLog(logs, title, fallback);
            return fallback;
        }
    }

    private LocalGuardResult reviewOperationsWithLocalRules(long projectId, Long linkedBuildJobId, String snapshot, TaskOperations operations, boolean chinese) {
        LocalGuardResult result = LocalGuardHeuristics.reviewOperations(snapshot, operations);
        if (result.usable) {
            recordDeterministicGuard(projectId, linkedBuildJobId,
                    chinese ? "确定性规则 · 源码写入前预审" : "Deterministic rules · source preflight",
                    "Generated operations were checked against the current source snapshot.",
                    result);
        }
        return result;
    }

    private LocalGuardResult rewritePolicyFailureWithLocalRules(long projectId, Long linkedBuildJobId, String policyError, boolean chinese) {
        LocalGuardResult playbook = playbookResult(FailureFingerprintPolicy.fromPolicyError(policyError));
        if (playbook.usable) {
            recordDeterministicGuard(projectId, linkedBuildJobId,
                    chinese ? "Hermes 策略库 · 策略错误提示" : "Hermes playbook · policy-error hint",
                    policyError,
                    playbook);
            return playbook;
        }
        LocalGuardResult result = LocalGuardHeuristics.rewritePolicyFailure(policyError);
        if (result.usable) {
            recordDeterministicGuard(projectId, linkedBuildJobId,
                    chinese ? "确定性规则 · 策略错误提示" : "Deterministic rules · policy-error hint",
                    policyError,
                    result);
        }
        return result;
    }

    private LocalGuardResult triageBuildFailureWithCloudGuard(long projectId, Long linkedBuildJobId, String buildLog, String focusedSnapshot, boolean chinese) {
        LocalGuardResult playbook = playbookResult(FailureFingerprintPolicy.fromBuildLog(buildLog));
        if (playbook.usable) {
            recordDeterministicGuard(projectId, linkedBuildJobId,
                    chinese ? "Hermes 策略库 · 构建日志分诊" : "Hermes playbook · build-log triage",
                    buildLog,
                    playbook);
            return playbook;
        }
        String request = "Build log:\n" + truncateForInlineLog(buildLog, 24000)
                + "\n\nFocused source snapshot:\n" + truncateForInlineLog(focusedSnapshot, 24000);
        try {
            String hint = recordCloudAiCall(
                    projectId,
                    linkedBuildJobId,
                    chinese ? "云端 AI · 构建日志分诊" : "Cloud AI · build-log triage",
                    request,
                    () -> openAiClient.createBuildFailureTriageHint(buildLog, focusedSnapshot, chinese));
            return cloudHintResult(chinese ? "云端模型已生成构建修复提示。" : "Cloud model produced a build repair hint.", hint);
        } catch (Exception error) {
            return LocalGuardResult.unusable((chinese ? "云端构建分诊不可用：" : "Cloud build triage unavailable: ") + localGuardErrorMessage(error));
        }
    }

    private LocalGuardResult cloudHintResult(String summary, String hint) {
        String text = hint == null ? "" : hint.trim();
        if (text.isEmpty()) {
            return LocalGuardResult.unusable("Cloud guard returned an empty hint.");
        }
        if (text.length() > 2000) {
            text = text.substring(0, 2000) + "\n...[truncated]";
        }
        return LocalGuardResult.rewrite(summary, text);
    }

    private LocalGuardResult playbookResult(FailureFingerprint fingerprint) {
        String hint = RepairPlaybookPolicy.retryHint(fingerprint);
        if (hint.isEmpty()) {
            return LocalGuardResult.unusable("");
        }
        return LocalGuardResult.rewrite("Hermes repair playbook produced a deterministic retry hint.", hint);
    }

    private String localGuardErrorMessage(Exception error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    private void appendLocalGuardLog(File logs, String prefix, LocalGuardResult result) {
        if (logs == null || result == null || result.summary == null || result.summary.trim().isEmpty()) {
            return;
        }
        try {
            String status = result.usable ? result.decision.name().toLowerCase(java.util.Locale.ROOT) : "fallback";
            FileUtils.appendText(logs, prefix + " [" + status + "]: " + result.summary.trim() + "\n");
            if (result.usable && result.decision == LocalGuardResult.Decision.REWRITE
                    && result.additionalInstruction != null
                    && !result.additionalInstruction.trim().isEmpty()) {
                FileUtils.appendText(logs, "Guard instruction: " + result.additionalInstruction.trim() + "\n");
            }
        } catch (Exception ignored) {
            // Guard logs are diagnostic only and must never block code generation.
        }
    }

    private void appendHermesReviewLog(File logs, String prefix, HermesReview review) {
        if (logs == null || review == null || review.summary == null || review.summary.trim().isEmpty()) {
            return;
        }
        try {
            String status = review.decision.name().toLowerCase(java.util.Locale.ROOT);
            FileUtils.appendText(logs, prefix + " [" + status + "]: " + review.summary.trim() + "\n");
            if (review.decision == HermesReview.Decision.REWRITE
                    && review.rewriteInstruction != null
                    && !review.rewriteInstruction.trim().isEmpty()) {
                FileUtils.appendText(logs, "Hermes reviewer instruction: " + review.rewriteInstruction.trim() + "\n");
            }
        } catch (Exception ignored) {
            // Hermes logs are diagnostic only and must never block code generation.
        }
    }

    private String recordCloudAiCall(long projectId, Long linkedBuildJobId, String title, String requestText, AiTextCall call) throws Exception {
        return recordCloudAiCall(projectId, linkedBuildJobId, title, requestText, call, 0);
    }

    private String recordCloudAiCall(long projectId, Long linkedBuildJobId, String title, String requestText, AiTextCall call, long taskId) throws Exception {
        long startedAt = System.currentTimeMillis();
        try {
            String response = call.run();
            recordAiConversationSafely(
                    projectId,
                    "cloud",
                    title,
                    requestText,
                    response,
                    "success",
                    cloudAiMetadata(elapsedMs(startedAt), taskId),
                    linkedBuildJobId);
            return response;
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.toString() : error.getMessage();
            recordAiConversationSafely(
                    projectId,
                    "cloud",
                    title,
                    requestText,
                    message,
                    "failed",
                    cloudAiMetadata(elapsedMs(startedAt), taskId),
                    linkedBuildJobId);
            throw error;
        }
    }

    private void recordDeterministicGuard(long projectId, Long linkedBuildJobId, String title, String requestText, LocalGuardResult result) {
        if (result == null || ((result.summary == null || result.summary.trim().isEmpty())
                && (result.additionalInstruction == null || result.additionalInstruction.trim().isEmpty()))) {
            return;
        }
        String status = result.usable ? result.decision.name().toLowerCase(java.util.Locale.ROOT) : "fallback";
        String response = "decision: " + status
                + "\nsummary: " + (result.summary == null ? "" : result.summary)
                + "\nadditionalInstruction: " + (result.additionalInstruction == null ? "" : result.additionalInstruction);
        recordAiConversationSafely(
                projectId,
                "deterministic",
                title,
                requestText,
                response,
                status,
                deterministicPreflightMetadata(),
                linkedBuildJobId);
    }

    private void recordHermesRunEvent(long projectId, Long linkedBuildJobId, HermesRunEvent event) {
        if (event == null) {
            return;
        }
        String status = event.decision.isEmpty() ? "event" : event.decision;
        recordAiConversationSafely(
                projectId,
                "hermes",
                "Hermes · " + event.role + " · " + event.phase,
                HermesRunEventFormatter.requestText(event),
                HermesRunEventFormatter.responseText(event),
                status,
                HermesRunEventFormatter.metadata(event),
                linkedBuildJobId);
    }

    private void recordHermesReviewAi(long projectId, Long linkedBuildJobId, String title, String requestText, HermesReview review) {
        recordHermesReviewAi(projectId, linkedBuildJobId, title, requestText, review, 0);
    }

    private void recordHermesReviewAi(long projectId, Long linkedBuildJobId, String title, String requestText, HermesReview review, long taskId) {
        if (review == null || ((review.summary == null || review.summary.trim().isEmpty())
                && (review.rewriteInstruction == null || review.rewriteInstruction.trim().isEmpty()))) {
            return;
        }
        String status = review.decision.name().toLowerCase(java.util.Locale.ROOT);
        recordAiConversationSafely(
                projectId,
                "deterministic",
                title,
                requestText,
                hermesReviewResponseForAiLog(review),
                status,
                deterministicPreflightMetadata(taskId),
                linkedBuildJobId);
    }

    private String deterministicPreflightRequest(String taskTitle, String taskInstruction, String operationsJson) {
        return "Task title:\n" + taskTitle
                + "\n\nTask instruction:\n" + truncateForInlineLog(taskInstruction, 12000)
                + "\n\nGenerated operations JSON:\n" + truncateForInlineLog(operationsJson, 24000);
    }

    private static String taskOperationsJson(TaskOperations operations) throws Exception {
        JSONObject json = new JSONObject();
        json.put("summary", operations == null || operations.summary == null ? "" : operations.summary);
        JSONArray array = new JSONArray();
        if (operations != null && operations.operations != null) {
            for (FileOperation operation : operations.operations) {
                JSONObject item = new JSONObject();
                item.put("action", operation.action == null ? "" : operation.action);
                item.put("path", operation.path == null ? "" : operation.path);
                item.put("content", operation.content == null ? "" : operation.content);
                if ("edit".equals(operation.action)) {
                    item.put("find", operation.find == null ? "" : operation.find);
                    item.put("replace", operation.replace == null ? "" : operation.replace);
                }
                array.put(item);
            }
        }
        json.put("operations", array);
        return json.toString();
    }

    private String hermesReviewRequestForAiLog(String taskTitle, String taskInstruction, String snapshot, String operationsJson, String contextScoutNotes) {
        return "Task title:\n" + taskTitle
                + "\n\nTask instruction:\n" + truncateForInlineLog(taskInstruction, 12000)
                + "\n\nContext Scout notes:\n" + truncateForInlineLog(contextScoutNotes, 4000)
                + "\n\nCurrent source snapshot:\n" + truncateForInlineLog(snapshot, 24000)
                + "\n\nGenerated operations JSON:\n" + truncateForInlineLog(operationsJson, 24000);
    }

    static String hermesReviewResponseForAiLogForTest(HermesReview review) {
        return hermesReviewResponseForAiLog(review);
    }

    private static String hermesReviewResponseForAiLog(HermesReview review) {
        String status = review == null ? "fallback" : review.decision.name().toLowerCase(java.util.Locale.ROOT);
        return "decision: " + status
                + "\nsummary: " + (review == null || review.summary == null ? "" : review.summary)
                + "\nrewriteInstruction: " + (review == null || review.rewriteInstruction == null ? "" : review.rewriteInstruction);
    }

    private void recordAiConversationSafely(long projectId, String source, String title, String requestText, String responseText, String status, String metadata, Long linkedBuildJobId) {
        try {
            repository.addAiConversation(
                    projectId,
                    source,
                    title,
                    truncateAiLog(requestText),
                    truncateAiLog(responseText),
                    status,
                    metadata,
                    linkedBuildJobId);
        } catch (Exception ignored) {
            // AI conversation logs are diagnostic and must never block generation, repair, or build.
        }
    }

    private String cloudAiMetadata() {
        return cloudAiMetadata(-1);
    }

    private String cloudAiMetadata(long durationMs) {
        return cloudAiMetadata(durationMs, 0);
    }

    private String cloudAiMetadata(long durationMs, long taskId) {
        return appendTaskId(
                cloudAiMetadata(openAiClient.currentProvider(), openAiClient.currentModel(), openAiClient.currentEndpoint(), durationMs),
                taskId);
    }

    static String cloudAiMetadataForTest(String provider, String model, String endpoint, long durationMs) {
        return cloudAiMetadata(provider, model, endpoint, durationMs);
    }

    private static String cloudAiMetadata(String provider, String model, String endpoint, long durationMs) {
        StringBuilder builder = new StringBuilder();
        builder.append("provider=").append(provider == null ? "" : provider)
                .append("\nmodel=").append(model == null ? "" : model)
                .append("\nendpoint=").append(endpoint == null ? "" : endpoint);
        if (durationMs >= 0) {
            builder.append("\ndurationMs=").append(durationMs);
        }
        return builder.toString();
    }

    private static long elapsedMs(long startedAt) {
        return Math.max(0, System.currentTimeMillis() - startedAt);
    }

    private String deterministicPreflightMetadata() {
        return deterministicPreflightMetadata(0);
    }

    private String deterministicPreflightMetadata(long taskId) {
        return appendTaskId("provider=deterministic-preflight"
                + "\nmodel=java-rules"
                + "\nmode=before-hermes", taskId);
    }

    private static String appendTaskId(String metadata, long taskId) {
        if (taskId <= 0) {
            return metadata == null ? "" : metadata;
        }
        String text = metadata == null ? "" : metadata;
        return text.isEmpty() ? "taskId=" + taskId : text + "\ntaskId=" + taskId;
    }

    private static long taskIdFromCallTag(String callTag) {
        String tag = callTag == null ? "" : callTag.trim();
        if (!tag.startsWith("task:")) {
            return 0;
        }
        try {
            return Long.parseLong(tag.substring("task:".length()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String historyForAiLog(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "(empty)";
        }
        StringBuilder text = new StringBuilder();
        int start = Math.max(0, history.size() - 12);
        if (start > 0) {
            text.append("...[older messages omitted]\n");
        }
        for (int i = start; i < history.size(); i++) {
            ChatMessage message = history.get(i);
            text.append(message.role == null ? "message" : message.role)
                    .append(": ")
                    .append(truncateForInlineLog(message.content, 4000))
                    .append("\n\n");
        }
        return text.toString().trim();
    }

    private String taskOperationsRequestForAiLog(String planContent, String taskTitle, String instruction, String snapshot, String retryContext, String previousDraftSection, String mode, int attempt) {
        return taskOperationsRequestForAiLogText(planContent, taskTitle, instruction, snapshot, retryContext, previousDraftSection, mode, attempt);
    }

    private static String taskOperationsRequestForAiLogText(String planContent, String taskTitle, String instruction, String snapshot, String retryContext, int attempt) {
        return taskOperationsRequestForAiLogText(planContent, taskTitle, instruction, snapshot, retryContext, "", taskOperationMode(false), attempt);
    }

    private static String taskOperationsRequestForAiLogText(String planContent, String taskTitle, String instruction, String snapshot, String retryContext, String previousDraftSection, boolean correctionMode, int attempt) {
        return taskOperationsRequestForAiLogText(planContent, taskTitle, instruction, snapshot, retryContext, previousDraftSection, taskOperationMode(correctionMode), attempt);
    }

    private static String taskOperationsRequestForAiLogText(String planContent, String taskTitle, String instruction, String snapshot, String retryContext, String previousDraftSection, String mode, int attempt) {
        String retrySection = retryContext == null || retryContext.trim().isEmpty()
                ? ""
                : "\n\nAdditional retry/repair context:\n" + truncateForInlineLogText(retryContext, 12000);
        String draftSection = previousDraftSection == null || previousDraftSection.trim().isEmpty()
                ? ""
                : "\n\n" + truncateForInlineLogText(previousDraftSection, TaskDraftContextPolicy.DRAFT_SECTION_LIMIT);
        String cleanInstruction = HermesTaskContractCodec.stripFromInstruction(instruction);
        String contractContext = HermesTaskContractCodec.promptContextFromInstruction(instruction);
        String contractSection = contractContext.isEmpty()
                ? ""
                : "\n\n" + contractContext;
        return "Attempt: " + attempt
                + "\nMode: " + cleanTaskOperationMode(mode)
                + "\n\nApproved engineering plan:\n" + truncateForInlineLogText(planContent, 12000)
                + "\n\nTask title:\n" + taskTitle
                + "\n\nTask instruction:\n" + cleanInstruction
                + contractSection
                + retrySection
                + draftSection
                + "\n\nCurrent source tree:\n" + truncateForInlineLogText(snapshot, 24000);
    }

    private String truncateAiLog(String value) {
        String text = value == null ? "" : value;
        if (text.length() <= AI_LOG_TEXT_LIMIT) {
            return text;
        }
        return text.substring(0, AI_LOG_TEXT_LIMIT) + "\n...[ai log truncated]";
    }

    private String truncateForInlineLog(String value, int limit) {
        return truncateForInlineLogText(value, limit);
    }

    private static String truncateForInlineLogText(String value, int limit) {
        String text = value == null ? "" : value;
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "\n...[truncated]";
    }

    private boolean isRewriteablePolicyError(IllegalArgumentException error) {
        return TaskOperationErrorPolicy.shouldRequestRewrite(error);
    }

    static int policyRewriteAttemptsForTest() {
        return POLICY_REWRITE_ATTEMPTS;
    }

    static int contextNegotiationRoundsForTest() {
        return CONTEXT_NEGOTIATION_ROUNDS;
    }

    static String taskOperationsRequestForAiLogForTest(String planContent, String taskTitle, String instruction, String snapshot, String retryContext, int attempt) {
        return taskOperationsRequestForAiLogText(planContent, taskTitle, instruction, snapshot, retryContext, attempt);
    }

    static String taskOperationsRequestForAiLogForTest(String planContent, String taskTitle, String instruction, String snapshot, String retryContext, String previousDraftSection, boolean correctionMode, int attempt) {
        return taskOperationsRequestForAiLogText(planContent, taskTitle, instruction, snapshot, retryContext, previousDraftSection, correctionMode, attempt);
    }

    static String taskOperationsRequestForAiLogForTest(String planContent, String taskTitle, String instruction, String snapshot, String retryContext, String previousDraftSection, boolean correctionMode, int attempt, String mode) {
        return taskOperationsRequestForAiLogText(planContent, taskTitle, instruction, snapshot, retryContext, previousDraftSection, mode, attempt);
    }

    private static String taskOperationMode(boolean correctionMode) {
        return correctionMode ? "correction" : "full";
    }

    private static String cleanTaskOperationMode(String mode) {
        String text = mode == null ? "" : mode.trim();
        return text.isEmpty() ? "full" : text;
    }

    static String taskCompletionMessageForTest(String taskTitle, boolean hasNextTask, boolean buildRequiredAfter, boolean chinese) {
        return taskCompletionMessage(taskTitle, hasNextTask, buildRequiredAfter, chinese);
    }

    static String exhaustedFailurePauseMessageForTest(String taskTitle, boolean chinese) {
        return exhaustedFailurePauseMessage(taskTitle, chinese);
    }

    private static String exhaustedFailurePauseMessage(String taskTitle, boolean chinese) {
        String title = taskTitle == null || taskTitle.trim().isEmpty() ? "未知任务" : taskTitle.trim();
        if (chinese) {
            return "任务「" + title + "」已失败且重试耗尽，其后续任务已暂停以避免在缺失前置产物上空跑；请点击执行下一步重试，或调整需求后重新生成计划。";
        }
        return "Task \"" + title + "\" failed and exhausted its retries; downstream tasks are paused to avoid running without missing prerequisites. Run the next step to retry, or adjust the requirements and regenerate the plan.";
    }

    private static String taskCompletionMessage(String taskTitle, boolean hasNextTask, boolean buildRequiredAfter, boolean chinese) {
        String title = taskTitle == null ? "" : taskTitle;
        String base = (chinese ? "已完成：" : "Done: ") + title;
        if (buildRequiredAfter) {
            return base + (chinese
                    ? "。此任务要求构建验证，请现在构建确认。"
                    : ". This task requires build verification; build now to confirm.");
        }
        if (hasNextTask) {
            return base + (chinese ? "。可以继续执行下一步。" : ". Continue with the next step.");
        }
        return base + (chinese ? "。所有任务已完成，可以构建。" : ". All tasks are complete; ready to build.");
    }

    private CapabilityAssessment assessCapability(String text) {
        return CapabilityAnalyzer.assess(
                text,
                BuildBackendSettings.dependencyMode(context),
                hasOfflineMavenCache());
    }

    private boolean hasOfflineMavenCache() {
        File dir = BuildBackendSettings.offlineMavenDir(context);
        return dir.exists() && containsFile(dir);
    }

    private boolean containsFile(File file) {
        if (file == null || !file.exists()) {
            return false;
        }
        if (file.isFile()) {
            return true;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (containsFile(child)) {
                return true;
            }
        }
        return false;
    }

    private String repairInstruction(String buildLog, boolean chinese) {
        String log = buildLog == null ? "" : buildLog.trim();
        if (log.length() > BUILD_LOG_PREVIEW_LIMIT) {
            log = log.substring(0, BUILD_LOG_PREVIEW_LIMIT) + "\n...[truncated]";
        }
        if (chinese) {
            return "根据下面的构建失败日志修复当前源码。只做最小必要改动，不要重新生成整个项目，不要删除无关功能。"
                    + "如果错误来自依赖策略，请改用当前依赖模式允许的 Android SDK/Java/XML 实现。"
                    + "如果日志包含 Could not find <group:artifact:version>，说明该依赖在配置仓库中不存在：删除它，或替换为已验证依赖（" + DependencyCatalog.coordinatesSummary() + "）。"
                    + "如果是 javac 编译错误，必须逐条处理所有 diagnostics，不要只修第一条；"
                    + "所有方法调用必须存在且参数个数/类型匹配；所有 DTO/model 字段访问必须真实存在且可见，例如 item.total 要求 CategorySum 声明 total 字段或提供对应 getter；private 字段要改用 getter/setter 或同步调整 DTO 字段可见性；"
                    + "统计/汇总 DTO 给 Adapter 展示时，不要访问未声明的 item.categoryName、item.percent 等字段；要么补齐 DTO 字段/getter，要么把 Adapter 改为真实存在的字段。DAO/helper 构造函数必须按声明传参，例如 CategoryDAO(Context) 不能传 DBHelper，必须改传 context 或同步修改构造函数与所有调用点。"
                    + "修改 Activity/Adapter 时必须同步更新 DAO、model、helper 中对应的方法签名和字段。"
                    + "如果计划或需求涉及版本升级、发版、测试版、APK 迭代或构建号，必须保持或修正 app/build.gradle 中的 versionCode/versionName：versionCode 必须大于当前旧值，versionName 按需求指定版本或递增补丁号，禁止降级。"
                    + "不要使用 Java lambda 或箭头语法，监听器必须写成匿名内部类；注释、Javadoc、字符串示例里也不要写箭头式示例；"
                    + "如果是 Java 引用错误：Fragment 中不要直接调用 findViewById，要使用 inflated root view.findViewById 或 requireView().findViewById；"
                    + "不要使用 Kotlin synthetic 视图变量（例如 btn_save.setOnClickListener），必须先从 dialog/root view 中 findViewById 声明局部变量；"
                    + "所有 R.* 代码引用和 XML 中的 @mipmap/@style/@drawable/@string/@color/@layout 引用都必须有对应资源，缺失时补资源或改用已有资源。\n\n构建日志：\n" + log;
        }
        return "Repair the current source based on the build failure log below. Make the smallest necessary changes, do not regenerate the whole project, and do not remove unrelated features. "
                + "If the log contains 'Could not find <group:artifact:version>', that dependency does not exist in the configured repositories: remove it or replace it with a verified catalog library (" + DependencyCatalog.coordinatesSummary() + "). "
                + "If the error comes from dependency policy, rewrite the implementation using Android SDK/Java/XML APIs allowed by the active dependency mode. "
                + "If these are javac diagnostics, fix every diagnostic, not just the first one. Every method call must have an existing declaration with matching argument count and types. Every direct DTO/model field access must actually exist and be visible; for example item.total requires CategorySum to declare total or expose a matching getter. Use getters/setters or adjust DTO field visibility consistently. When changing an Activity or Adapter, update the corresponding DAO, model, and helper APIs in the same repair. "
                + "For aggregate/statistics DTOs used by adapters, do not access undeclared item.categoryName, item.percent, or similar fields; either add DTO fields/getters or update the adapter to real fields. DAO/helper constructor calls must pass the declared type, e.g. CategoryDAO(Context) cannot receive DBHelper; pass context or update the constructor and every caller consistently. "
                + VersionUpgradePolicy.prompt() + " "
                + "Do not use Java lambdas or arrow syntax; listeners must use anonymous inner classes, and comments/Javadocs/string examples must not include arrow-style examples. "
                + "For Java reference errors: in Fragments, do not call findViewById directly; use the inflated root view.findViewById or requireView().findViewById. "
                + "Do not use Kotlin synthetic view variables such as btn_save.setOnClickListener; first declare local variables from the dialog/root view using findViewById. "
                + "Every R.* code reference and every XML @mipmap/@style/@drawable/@string/@color/@layout reference must have a matching resource; add the resource or use an existing one.\n\nBuild log:\n" + log;
    }

    private String fallbackRepairPlan(boolean chinese) {
        if (chinese) {
            return "# 工程计划\n\n修复当前 Android 项目，使它保持现有功能并能够成功构建。";
        }
        return "# Engineering Plan\n\nRepair the current Android project, preserve existing behavior, and make it build successfully.";
    }

    private void ensureImplementationTasks(long projectId, Long linkedBuildJobId, ProjectPlanRecord plan, boolean chinese) throws Exception {
        List<ProjectTaskRecord> existingTasks = repository.listProjectTasks(projectId);
        if (!existingTasks.isEmpty()) {
            List<ProjectTaskRecord> normalizedExisting = ImplementationTaskNormalizer.normalize(existingTasks);
            if (ImplementationTaskNormalizer.canReplaceExistingTasks(existingTasks)
                    && ImplementationTaskNormalizer.changed(existingTasks, normalizedExisting)) {
                repository.replaceProjectTasks(projectId, normalizedExisting);
                deleteAllTaskDraftsSafely(projectId);
                repository.addMessage(projectId, "assistant", taskListMessage(normalizedExisting, chinese), null);
            }
            return;
        }
        String tasksJson = recordCloudAiCall(
                projectId,
                linkedBuildJobId,
                chinese ? "云端 AI · 执行任务拆分" : "Cloud AI · implementation task split",
                "Approved engineering plan:\n\n" + plan.content,
                () -> openAiClient.createImplementationTasks(plan.content, chinese));
        List<ProjectTaskRecord> tasks = ImplementationTaskNormalizer.normalize(ImplementationTaskParser.fromJson(tasksJson));
        repository.replaceProjectTasks(projectId, tasks);
        deleteAllTaskDraftsSafely(projectId);
        repository.addMessage(projectId, "assistant", taskListMessage(tasks, chinese), null);
    }

    private String taskListMessage(List<ProjectTaskRecord> tasks, boolean chinese) {
        StringBuilder message = new StringBuilder(chinese ? "已拆分为执行任务：\n" : "Split into implementation tasks:\n");
        for (int i = 0; i < tasks.size(); i++) {
            message.append(i + 1).append(". ").append(tasks.get(i).title).append("\n");
        }
        return message.toString().trim();
    }

    private String sourceSnapshot(File sourceDir) {
        return sourceSnapshot(sourceDir, "");
    }

    private String sourceSnapshot(File sourceDir, String focusText) {
        return buildSourceSnapshot(sourceDir, focusText);
    }

    static String sourceSnapshotForTest(File sourceDir, String focusText) {
        return buildSourceSnapshot(sourceDir, focusText);
    }

    private static String buildSourceSnapshot(File sourceDir, String focusText) {
        List<SourceSnapshotComposer.TextSection> fullTextSections = new ArrayList<>();
        Set<String> fullTextPaths = new HashSet<>();
        appendFocusedSourceFiles(sourceDir, focusText, fullTextSections, fullTextPaths);
        appendSourceFile(sourceDir, new File(sourceDir, "app/src/main/AndroidManifest.xml"), fullTextSections, fullTextPaths, true);
        appendSourceFile(sourceDir, new File(sourceDir, "app/build.gradle"), fullTextSections, fullTextPaths, true);
        // General tree, ordered most-relevant first so that, if the budget runs out, the files
        // dropped are the least relevant (assets/configs) rather than Java/layouts.
        List<File> candidates = new java.util.ArrayList<>();
        collectSourceFiles(sourceDir, candidates);
        sortByRelevance(sourceDir, candidates);
        for (File file : candidates) {
            String path = relativePath(sourceDir, file);
            if (fullTextPaths.contains(path) || isJavaSourcePath(path)) {
                continue;
            }
            appendSourceFile(sourceDir, file, fullTextSections, fullTextPaths, false);
        }
        String resourceIndex = resourceIndex(sourceDir);
        SourceSnapshotComposer.Composition composition = composeSourceSnapshot(
                sourceDir,
                fullTextSections,
                resourceIndex,
                fullTextPaths);
        String contextNote = snapshotCoverageNote(sourceDir, candidates, fullTextPaths, composition);
        return SourceSnapshotComposer.appendContextNote(composition.text, contextNote, SOURCE_SNAPSHOT_LIMIT);
    }

    private static SourceSnapshotComposer.Composition composeSourceSnapshot(
            File sourceDir,
            List<SourceSnapshotComposer.TextSection> fullTextSections,
            String resourceIndex,
            Set<String> initiallyExcludedJavaPaths) {
        Set<String> excludedJavaPaths = new HashSet<>(initiallyExcludedJavaPaths);
        SourceSnapshotComposer.Composition composition = null;
        for (int i = 0; i < 4; i++) {
            String javaApiDigest = javaApiDigest(sourceDir, excludedJavaPaths);
            composition = SourceSnapshotComposer.compose(
                    fullTextSections,
                    javaApiDigest,
                    resourceIndex,
                    SOURCE_FULL_TEXT_LAYER_LIMIT,
                    SOURCE_SNAPSHOT_LIMIT - SOURCE_CONTEXT_NOTE_RESERVE);
            Set<String> nextExcluded = new HashSet<>(composition.fullyIncludedPaths);
            if (composition.partiallyIncludedPath != null) {
                nextExcluded.add(composition.partiallyIncludedPath);
            }
            if (nextExcluded.equals(excludedJavaPaths)) {
                return composition;
            }
            excludedJavaPaths = nextExcluded;
        }
        return composition == null
                ? SourceSnapshotComposer.compose(fullTextSections, "", resourceIndex, SOURCE_FULL_TEXT_LAYER_LIMIT, SOURCE_SNAPSHOT_LIMIT - SOURCE_CONTEXT_NOTE_RESERVE)
                : composition;
    }

    private static String javaApiDigest(File sourceDir, Set<String> fullTextPaths) {
        try {
            return JavaApiDigest.digestTree(sourceDir, fullTextPaths, SOURCE_API_DIGEST_LIMIT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String resourceIndex(File sourceDir) {
        try {
            return ResourceIndexDigest.digest(sourceDir, SOURCE_RESOURCE_INDEX_LIMIT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static List<String> missingNeededFiles(File sourceDir, ContextNegotiation negotiation) {
        List<String> missing = new ArrayList<>();
        if (sourceDir == null || negotiation == null) {
            return missing;
        }
        for (String path : negotiation.neededFiles) {
            if (path == null || path.trim().isEmpty()) {
                continue;
            }
            if (!new File(sourceDir, path).exists()) {
                missing.add(path);
            }
        }
        return missing;
    }

    private static boolean isJavaSourcePath(String path) {
        return path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".java");
    }

    private static void collectSourceFiles(File file, List<File> out) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                collectSourceFiles(child, out);
            }
            return;
        }
        if (isTextSourceFile(file.getName())) {
            out.add(file);
        }
    }

    static boolean isTextSourceFile(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".xml")
                || lower.endsWith(".gradle") || lower.endsWith(".kts")
                || lower.endsWith(".properties") || lower.endsWith(".json") || lower.endsWith(".pro");
    }

    private static void sortByRelevance(File root, List<File> files) {
        java.util.Collections.sort(files, (a, b) -> {
            int byScore = Integer.compare(relevanceScore(relativePath(root, a)), relevanceScore(relativePath(root, b)));
            return byScore != 0 ? byScore : relativePath(root, a).compareTo(relativePath(root, b));
        });
    }

    static int relevanceScore(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".java") || lower.endsWith(".kt")) {
            return 0;
        }
        if (lower.contains("/res/layout") && lower.endsWith(".xml")) {
            return 1;
        }
        if (lower.endsWith("androidmanifest.xml")) {
            return 2;
        }
        if (lower.endsWith(".xml")) {
            return 3;
        }
        if (lower.endsWith(".gradle") || lower.endsWith(".kts") || lower.endsWith(".properties")) {
            return 4;
        }
        return 5;
    }

    private static String relativePath(File root, File file) {
        return root.toURI().relativize(file.toURI()).getPath();
    }

    private static String snapshotCoverageNote(File root, List<File> candidates, Set<String> enqueuedFullTextPaths, SourceSnapshotComposer.Composition composition) {
        Set<String> fullyIncluded = new HashSet<>(composition.fullyIncludedPaths);
        String partial = composition.partiallyIncludedPath;
        List<String> digestOnly = new java.util.ArrayList<>();
        List<String> notShown = new java.util.ArrayList<>();
        for (File file : candidates) {
            String path = relativePath(root, file);
            if (fullyIncluded.contains(path)) {
                continue;
            }
            if (path.equals(partial)) {
                continue;
            }
            if (isJavaSourcePath(path)) {
                digestOnly.add(path);
            } else if (enqueuedFullTextPaths.contains(path)) {
                notShown.add(path);
            }
        }
        StringBuilder note = new StringBuilder();
        note.append("This inventory is COMPLETE. Every existing project file appears above in exactly one category (full text, truncated, API digest, or not-shown), and every existing XML resource name appears in the resource index. A file path that appears in NONE of these lists does not exist in the project yet - if your task requires it, CREATE it; that is expected work, not invention.\n");
        if (partial != null) {
            note.append("\nTruncated mid-file:\n");
            note.append("- ").append(partial).append(" (treat the unseen remainder as unknown)\n");
        }
        appendPathList(note, "Shown only in the Java API digest:", digestOnly);
        appendPathList(note, "Not shown at all (budget):", notShown);
        return note.toString();
    }

    private static void appendPathList(StringBuilder note, String title, List<String> paths) {
        if (paths.isEmpty()) {
            return;
        }
        note.append('\n').append(title).append('\n');
        int shown = Math.min(paths.size(), 40);
        for (int i = 0; i < shown; i++) {
            note.append("- ").append(paths.get(i)).append('\n');
        }
        if (paths.size() > shown) {
            note.append("- +").append(paths.size() - shown).append(" more\n");
        }
    }

    private static void appendFocusedSourceFiles(File root, String focusText, List<SourceSnapshotComposer.TextSection> sections, Set<String> seen) {
        if (focusText == null || focusText.trim().isEmpty()) {
            return;
        }
        // Files that the failure points at are appended in full (not preview-truncated) and first,
        // so the model can see and fix the whole offending file, e.g. every synthetic view access.
        Matcher pathMatcher = Pattern.compile("(?:^|/)(app/src/[^\\s:]+\\.(?:kt|java|xml|gradle|kts))").matcher(focusText);
        while (pathMatcher.find()) {
            File file = new File(root, pathMatcher.group(1));
            appendSourceFile(root, file, sections, seen, true);
        }
        // Guard/compiler messages often name only the bare file ("... in AddTransactionActivity.java."),
        // so also resolve plain file names by searching the tree.
        Matcher nameMatcher = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*\\.(?:kt|java|xml))\\b").matcher(focusText);
        while (nameMatcher.find()) {
            appendSourceFilesNamed(root, root, nameMatcher.group(1), sections, seen, true);
        }
        for (String type : BuildLogContextExtractor.referencedJavaTypes(focusText)) {
            appendSourceFilesNamed(root, root, type + ".java", sections, seen, true);
        }
        // Capitalized type identifiers named in the failure (e.g. "CategoryDao", "DBHelper" from a
        // constructor-mismatch message) are pulled in full so the model can reconcile caller and class.
        Matcher typeMatcher = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\b").matcher(focusText);
        int typeBudget = 16;
        Set<String> triedTypes = new HashSet<>();
        while (typeMatcher.find() && typeBudget > 0) {
            String typeName = typeMatcher.group(1);
            if (!triedTypes.add(typeName)) {
                continue;
            }
            typeBudget--;
            appendSourceFilesNamed(root, root, typeName + ".java", sections, seen, true);
        }
        if (focusText.contains("Unresolved reference") || focusText.contains("findViewById") || focusText.contains("R.id")) {
            appendSourceSnapshot(root, new File(root, "app/src/main/res/layout"), sections, seen);
        }
    }

    private static void appendSourceFilesNamed(File root, File file, String fileName, List<SourceSnapshotComposer.TextSection> sections, Set<String> seen, boolean full) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isFile()) {
            if (file.getName().equals(fileName)) {
                appendSourceFile(root, file, sections, seen, full);
            }
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            appendSourceFilesNamed(root, child, fileName, sections, seen, full);
        }
    }

    private static void appendSourceSnapshot(File root, File file, List<SourceSnapshotComposer.TextSection> sections, Set<String> seen) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                appendSourceSnapshot(root, child, sections, seen);
            }
            return;
        }
        appendSourceFile(root, file, sections, seen);
    }

    private static void appendSourceFile(File root, File file, List<SourceSnapshotComposer.TextSection> sections, Set<String> seen) {
        appendSourceFile(root, file, sections, seen, false);
    }

    private static void appendSourceFile(File root, File file, List<SourceSnapshotComposer.TextSection> sections, Set<String> seen, boolean full) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            String path = root.toURI().relativize(file.toURI()).getPath();
            if (!seen.add(path)) {
                return;
            }
            if (isLikelyBinaryPath(path)) {
                sections.add(SourceSnapshotComposer.textSection(path, "[binary]\n"));
                return;
            }
            String text = FileUtils.readText(file);
            if (!full && text.length() > SOURCE_FILE_PREVIEW_LIMIT) {
                text = text.substring(0, SOURCE_FILE_PREVIEW_LIMIT) + "\n...[truncated]";
            }
            sections.add(SourceSnapshotComposer.textSection(path, text));
        } catch (Exception ignored) {
            // Best-effort prompt context only; unreadable files should not block generation.
        }
    }

    private static boolean isLikelyBinaryPath(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".apk") ||
                lower.endsWith(".jar") || lower.endsWith(".zip");
    }

}
