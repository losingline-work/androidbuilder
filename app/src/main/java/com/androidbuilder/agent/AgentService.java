package com.androidbuilder.agent;

import android.content.Context;

import com.androidbuilder.data.AppRepository;
import com.androidbuilder.backend.BuildBackendSettings;
import com.androidbuilder.model.AppSpec;
import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ChatMessage;
import com.androidbuilder.model.ProjectPlanRecord;
import com.androidbuilder.model.ProjectRecord;
import com.androidbuilder.model.ProjectTaskRecord;
import com.androidbuilder.model.TaskOperations;
import com.androidbuilder.util.FileUtils;
import com.androidbuilder.util.AppSettings;
import com.androidbuilder.util.ActiveWorkRegistry;
import com.androidbuilder.util.NameUtils;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AgentService {
    private static final int SOURCE_SNAPSHOT_LIMIT = 18000;
    private static final int SOURCE_FILE_PREVIEW_LIMIT = 3500;
    private static final int SOURCE_FOCUS_FILE_LIMIT = 12000;
    private static final int BUILD_LOG_PREVIEW_LIMIT = 7000;
    private static final int POLICY_REWRITE_ATTEMPTS = 5;
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

    private final Context context;
    private final AppRepository repository;
    private final OpenAiClient openAiClient;
    private final GeneratedProjectWriter writer = new GeneratedProjectWriter();
    private final FileOperationsWriter operationsWriter;
    private final LocalGuardAssistant localGuardAssistant;

    public AgentService(Context context, AppRepository repository) {
        this(context, repository, null);
    }

    AgentService(Context context, AppRepository repository, LocalGuardAssistant localGuardAssistant) {
        this.context = context.getApplicationContext();
        this.repository = repository;
        this.openAiClient = new OpenAiClient(context);
        this.operationsWriter = new FileOperationsWriter(new DependencyGuard(
                BuildBackendSettings.dependencyMode(this.context),
                BuildBackendSettings.offlineMavenDir(this.context)));
        this.localGuardAssistant = localGuardAssistant == null
                ? new LlamaLocalGuardAssistant(this.context)
                : localGuardAssistant;
    }

    /** Releases the cached local-guard model/engine. Call when the owning screen is destroyed. */
    public void release() {
        localGuardAssistant.close();
    }

    public void setProgressListener(OpenAiClient.ProgressListener listener) {
        openAiClient.setProgressListener(listener);
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
        String planPrompt = prompt + "\n\nProject package/applicationId: " + project.packageName + "\nUse this package and namespace consistently.";
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
        ProjectTaskRecord runningTask = null;
        try {
            repository.updateProjectPlanStatus(projectId, "coding", job.id);
            repository.updateBuildJob(job.id, "generating", "cloud_spec", null, null, null, 0);
            ensureImplementationTasks(projectId, job.id, plan, chinese);
            runningTask = repository.nextPendingProjectTask(projectId);
            if (runningTask == null) {
                repository.updateProjectPlanStatus(projectId, "generated", job.id);
                repository.updateBuildJob(job.id, "generated", "ready_for_build", null, null, null, 0);
                repository.addMessage(projectId, "assistant", chinese ? "所有计划任务已完成。下一步可以构建。" : "All plan tasks are done. Next, build the project.", job.id);
                return repository.getBuildJob(job.id);
            }
            repository.updateProjectTask(runningTask.id, "running", "");
            repository.addMessage(projectId, "assistant", (chinese ? "执行下一步：" : "Executing next step: ") + runningTask.title, job.id);

            File jobDir = repository.jobDir(projectId, job.id);
            File logs = new File(jobDir, "build.log");
            FileUtils.writeText(logs, (chinese ? "正在执行计划任务：" : "Executing plan task: ") + runningTask.title + "\n");
            repository.updateBuildJob(job.id, "generating", "cloud_spec", logs.getAbsolutePath(), null, null, 0);

            File sourceDir = repository.sourceDir(projectId);
            String snapshot = sourceSnapshot(sourceDir);
            TaskOperations operations = createAndApplyTaskOperations(projectId, job.id, sourceDir, plan.content, runningTask.title, runningTask.instruction, snapshot, logs, chinese);
            repository.updateProjectTask(runningTask.id, "done", operations.summary);
            ProjectTaskRecord next = repository.nextPendingProjectTask(projectId);
            repository.updateProjectPlanStatus(projectId, next == null ? "generated" : "planned", job.id);

            File projectZip = new File(jobDir, "project.zip");
            FileUtils.zipDirectory(repository.sourceDir(projectId), projectZip);
            FileUtils.appendText(logs, (chinese ? "计划任务完成：" : "Executed plan task: ") + runningTask.title + "\n" + operations.summary + "\n");

            String message = (chinese ? "已完成：" : "Done: ") + runningTask.title + (next == null ? (chinese ? "。所有任务已完成，可以构建。" : ". All tasks are complete; ready to build.") : (chinese ? "。可以继续执行下一步。" : ". Continue with the next step."));
            repository.addMessage(projectId, "assistant", message, job.id);
            repository.updateBuildJob(job.id, "generated", "ready_for_build", logs.getAbsolutePath(), null, null, 0);
            return repository.getBuildJob(job.id);
        } catch (Exception error) {
            if (runningTask != null) {
                repository.updateProjectTask(runningTask.id, "failed", error.getMessage());
            }
            repository.updateBuildJob(job.id, "failed", "coding_failed", null, null, error.getMessage(), job.retryCount);
            repository.updateProjectPlanStatus(projectId, "planned", job.id);
            throw error;
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

            File sourceDir = repository.sourceDir(projectId);
            String snapshot = sourceSnapshot(sourceDir, buildLog);
            // Local build-log triage: let the on-device model turn the raw log into a focused fix
            // instruction for the cloud model. Falls back to the deterministic instruction if the
            // local model is off, unavailable, times out, or finds no actionable source cause.
            String baseInstruction = repairInstruction(buildLog, chinese);
            LocalGuardResult triage = triageBuildFailureWithLocalGuard(projectId, job.id, buildLog, snapshot, chinese);
            appendLocalGuardLog(logs, chinese ? "本地守卫构建日志分诊" : "Local guard build triage", triage);
            String instruction = triage.usable && triage.decision == LocalGuardResult.Decision.REWRITE
                    && triage.additionalInstruction != null && !triage.additionalInstruction.trim().isEmpty()
                    ? LocalGuardInstructionComposer.forBuildTriage(baseInstruction, triage.additionalInstruction)
                    : baseInstruction;
            TaskOperations operations = createAndApplyTaskOperations(
                    projectId,
                    job.id,
                    sourceDir,
                    planContent,
                    chinese ? "修复构建失败" : "Repair build failure",
                    instruction,
                    snapshot,
                    logs,
                    chinese);

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

    private TaskOperations createAndApplyTaskOperations(long projectId, Long linkedBuildJobId, File sourceDir, String planContent, String taskTitle, String taskInstruction, String snapshot, File logs, boolean chinese) throws Exception {
        String instruction = taskInstruction;
        // Recent user requirements live in chat but may never have reached the plan text; feed them
        // to the coding/repair model so it honors intent the plan dropped.
        String recentRequirements = ConversationContextPolicy.recentUserRequirements(
                repository.listMessages(projectId), CODING_USER_REQUIREMENTS_WINDOW, CODING_USER_REQUIREMENTS_CHARS);
        IllegalArgumentException lastPolicyError = null;
        for (int attempt = 1; attempt <= POLICY_REWRITE_ATTEMPTS; attempt++) {
            String requestLog = taskOperationsRequestForAiLog(planContent, taskTitle, instruction, snapshot, attempt);
            final String attemptInstruction = instruction;
            final String attemptSnapshot = snapshot;
            String operationsJson = recordCloudAiCall(
                    projectId,
                    linkedBuildJobId,
                    (chinese ? "云端 AI · 文件操作生成" : "Cloud AI · task operations") + " #" + attempt,
                    requestLog,
                    () -> openAiClient.createTaskOperations(planContent, taskTitle, attemptInstruction, attemptSnapshot, recentRequirements, chinese));
            try {
                TaskOperations operations = TaskOperationsParser.fromJson(operationsJson);
                LocalGuardResult preflight = reviewOperationsWithLocalGuard(projectId, linkedBuildJobId, planContent, taskTitle, instruction, snapshot, operations, chinese);
                appendLocalGuardLog(logs, chinese ? "本地守卫预审" : "Local guard preflight", preflight);
                if (shouldRetryFromLocalGuard(preflight, attempt)) {
                    instruction = LocalGuardInstructionComposer.forPreflightRewrite(instruction, preflight.additionalInstruction);
                    snapshot = sourceSnapshot(sourceDir);
                    continue;
                }
                operationsWriter.apply(sourceDir, operations);
                return operations;
            } catch (IllegalArgumentException policyError) {
                if (!isRewriteablePolicyError(policyError) || attempt == POLICY_REWRITE_ATTEMPTS) {
                    throw policyError;
                }
                lastPolicyError = policyError;
                // Refocus the snapshot on the files/types named in the rejection so the next attempt
                // sees the offending caller and the real class declarations in full (untruncated).
                snapshot = sourceSnapshot(sourceDir, policyError.getMessage());
                String policyInstruction = PolicyRewriteInstruction.create(instruction, policyError.getMessage(), attempt + 1);
                LocalGuardResult localRewrite = rewritePolicyFailureWithLocalGuard(projectId, linkedBuildJobId, instruction, policyError.getMessage(), snapshot, attempt + 1, chinese);
                appendLocalGuardLog(logs, chinese ? "本地守卫策略错误提示" : "Local guard policy-error hint", localRewrite);
                instruction = localRewrite.usable && localRewrite.decision == LocalGuardResult.Decision.REWRITE
                        ? LocalGuardInstructionComposer.forPolicyRewrite(policyInstruction, localRewrite.additionalInstruction)
                        : policyInstruction;
            }
        }
        throw lastPolicyError == null ? new IllegalStateException("Task operation generation failed.") : lastPolicyError;
    }

    private boolean shouldRetryFromLocalGuard(LocalGuardResult result, int attempt) {
        return result != null
                && result.usable
                && result.decision == LocalGuardResult.Decision.REWRITE
                && result.additionalInstruction != null
                && !result.additionalInstruction.trim().isEmpty()
                && attempt < POLICY_REWRITE_ATTEMPTS;
    }

    private LocalGuardResult reviewOperationsWithLocalGuard(long projectId, Long linkedBuildJobId, String planContent, String taskTitle, String taskInstruction, String snapshot, TaskOperations operations, boolean chinese) {
        String prompt = LocalGuardPromptBuilder.reviewOperationsPrompt(planContent, taskTitle, taskInstruction, snapshot, operations);
        try {
            LocalGuardResult result = localGuardAssistant.reviewOperations(planContent, taskTitle, taskInstruction, snapshot, operations);
            recordLocalGuardAi(projectId, linkedBuildJobId, chinese ? "本地 AI · 源码写入前预审" : "Local AI · source guard preflight", prompt, result);
            return result;
        } catch (Exception error) {
            LocalGuardResult result = LocalGuardResult.unusable("Local guard preflight failed: " + localGuardErrorMessage(error));
            recordLocalGuardAi(projectId, linkedBuildJobId, chinese ? "本地 AI · 源码写入前预审" : "Local AI · source guard preflight", prompt, result);
            return result;
        }
    }

    private LocalGuardResult rewritePolicyFailureWithLocalGuard(long projectId, Long linkedBuildJobId, String taskInstruction, String policyError, String focusedSnapshot, int attempt, boolean chinese) {
        String prompt = LocalGuardPromptBuilder.policyFailurePrompt(taskInstruction, policyError, focusedSnapshot, attempt);
        try {
            LocalGuardResult result = localGuardAssistant.rewritePolicyFailure(taskInstruction, policyError, focusedSnapshot, attempt);
            recordLocalGuardAi(projectId, linkedBuildJobId, (chinese ? "本地 AI · 策略错误提示优化" : "Local AI · policy-error hint") + " #" + attempt, prompt, result);
            return result;
        } catch (Exception error) {
            LocalGuardResult result = LocalGuardResult.unusable("Local guard policy-error rewrite failed: " + localGuardErrorMessage(error));
            recordLocalGuardAi(projectId, linkedBuildJobId, (chinese ? "本地 AI · 策略错误提示优化" : "Local AI · policy-error hint") + " #" + attempt, prompt, result);
            return result;
        }
    }

    private LocalGuardResult triageBuildFailureWithLocalGuard(long projectId, Long linkedBuildJobId, String buildLog, String focusedSnapshot, boolean chinese) {
        String prompt = LocalGuardPromptBuilder.triageBuildFailurePrompt(buildLog, focusedSnapshot);
        try {
            LocalGuardResult result = localGuardAssistant.triageBuildFailure(buildLog, focusedSnapshot);
            recordLocalGuardAi(projectId, linkedBuildJobId, chinese ? "本地 AI · 构建日志分诊" : "Local AI · build-log triage", prompt, result);
            return result;
        } catch (Exception error) {
            LocalGuardResult result = LocalGuardResult.unusable("Local guard build triage failed: " + localGuardErrorMessage(error));
            recordLocalGuardAi(projectId, linkedBuildJobId, chinese ? "本地 AI · 构建日志分诊" : "Local AI · build-log triage", prompt, result);
            return result;
        }
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
                FileUtils.appendText(logs, "Local guard instruction: " + result.additionalInstruction.trim() + "\n");
            }
        } catch (Exception ignored) {
            // Local guard logs are diagnostic only and must never block code generation.
        }
    }

    private String recordCloudAiCall(long projectId, Long linkedBuildJobId, String title, String requestText, AiTextCall call) throws Exception {
        try {
            String response = call.run();
            recordAiConversationSafely(
                    projectId,
                    "cloud",
                    title,
                    requestText,
                    response,
                    "success",
                    cloudAiMetadata(),
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
                    cloudAiMetadata(),
                    linkedBuildJobId);
            throw error;
        }
    }

    private void recordLocalGuardAi(long projectId, Long linkedBuildJobId, String title, String requestText, LocalGuardResult result) {
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
                "local",
                title,
                requestText,
                response,
                status,
                localGuardMetadata(),
                linkedBuildJobId);
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
        return "provider=" + openAiClient.currentProvider()
                + "\nmodel=" + openAiClient.currentModel()
                + "\nendpoint=" + openAiClient.currentEndpoint();
    }

    private String localGuardMetadata() {
        File model = LocalGuardSettings.modelFile(context);
        return "provider=local-llama.cpp"
                + "\nmodel=" + LocalGuardSettings.modelName(context)
                + "\npath=" + model.getAbsolutePath()
                + "\nmode=" + LocalGuardSettings.mode(context).name();
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

    private String taskOperationsRequestForAiLog(String planContent, String taskTitle, String instruction, String snapshot, int attempt) {
        return "Attempt: " + attempt
                + "\n\nApproved engineering plan:\n" + truncateForInlineLog(planContent, 12000)
                + "\n\nTask title:\n" + taskTitle
                + "\n\nTask instruction:\n" + instruction
                + "\n\nCurrent source tree:\n" + truncateForInlineLog(snapshot, 24000);
    }

    private String truncateAiLog(String value) {
        String text = value == null ? "" : value;
        if (text.length() <= AI_LOG_TEXT_LIMIT) {
            return text;
        }
        return text.substring(0, AI_LOG_TEXT_LIMIT) + "\n...[ai log truncated]";
    }

    private String truncateForInlineLog(String value, int limit) {
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
        if (!repository.listProjectTasks(projectId).isEmpty()) {
            return;
        }
        String tasksJson = recordCloudAiCall(
                projectId,
                linkedBuildJobId,
                chinese ? "云端 AI · 执行任务拆分" : "Cloud AI · implementation task split",
                "Approved engineering plan:\n\n" + plan.content,
                () -> openAiClient.createImplementationTasks(plan.content, chinese));
        List<ProjectTaskRecord> tasks = ImplementationTaskParser.fromJson(tasksJson);
        repository.replaceProjectTasks(projectId, tasks);
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
        StringBuilder snapshot = new StringBuilder();
        Set<String> seen = new HashSet<>();
        appendFocusedSourceFiles(sourceDir, focusText, snapshot, seen);
        // General tree, ordered most-relevant first so that, if the budget runs out, the files
        // dropped are the least relevant (assets/configs) rather than Java/layouts.
        List<File> candidates = new java.util.ArrayList<>();
        collectSourceFiles(sourceDir, candidates);
        sortByRelevance(sourceDir, candidates);
        for (File file : candidates) {
            appendSourceFile(sourceDir, file, snapshot, seen, false);
        }
        // Tell the model exactly which source files it cannot see, so it stops aligning against
        // files it has not been shown (a common cause of cross-file API mismatches).
        appendOmittedFilesNote(sourceDir, candidates, seen, snapshot);
        return snapshot.length() == 0 ? "(empty)" : snapshot.toString();
    }

    private void collectSourceFiles(File file, List<File> out) {
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

    private void sortByRelevance(File root, List<File> files) {
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

    private String relativePath(File root, File file) {
        return root.toURI().relativize(file.toURI()).getPath();
    }

    private void appendOmittedFilesNote(File root, List<File> candidates, Set<String> seen, StringBuilder snapshot) {
        List<String> omitted = new java.util.ArrayList<>();
        for (File file : candidates) {
            String path = relativePath(root, file);
            if (!seen.contains(path)) {
                omitted.add(path);
            }
        }
        if (omitted.isEmpty()) {
            return;
        }
        int shown = Math.min(omitted.size(), 40);
        snapshot.append("\n--- context note ---\n");
        snapshot.append("The project is larger than the context budget, so ").append(omitted.size())
                .append(" source file(s) below were omitted from this snapshot. Do not assume their contents; only reference or modify files shown above, and keep every change consistent with the APIs you can actually see. Omitted files:\n");
        for (int i = 0; i < shown; i++) {
            snapshot.append("- ").append(omitted.get(i)).append('\n');
        }
        if (omitted.size() > shown) {
            snapshot.append("- ...and ").append(omitted.size() - shown).append(" more\n");
        }
    }

    private void appendFocusedSourceFiles(File root, String focusText, StringBuilder snapshot, Set<String> seen) {
        if (focusText == null || focusText.trim().isEmpty()) {
            return;
        }
        // Files that the failure points at are appended in full (not preview-truncated) and first,
        // so the model can see and fix the whole offending file, e.g. every synthetic view access.
        Matcher pathMatcher = Pattern.compile("(?:^|/)(app/src/[^\\s:]+\\.(?:kt|java|xml|gradle|kts))").matcher(focusText);
        while (pathMatcher.find() && snapshot.length() <= SOURCE_SNAPSHOT_LIMIT) {
            File file = new File(root, pathMatcher.group(1));
            appendSourceFile(root, file, snapshot, seen, true);
        }
        // Guard/compiler messages often name only the bare file ("... in AddTransactionActivity.java."),
        // so also resolve plain file names by searching the tree.
        Matcher nameMatcher = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*\\.(?:kt|java|xml))\\b").matcher(focusText);
        while (nameMatcher.find() && snapshot.length() <= SOURCE_SNAPSHOT_LIMIT) {
            appendSourceFilesNamed(root, root, nameMatcher.group(1), snapshot, seen, true);
        }
        for (String type : BuildLogContextExtractor.referencedJavaTypes(focusText)) {
            appendSourceFilesNamed(root, root, type + ".java", snapshot, seen, true);
        }
        // Capitalized type identifiers named in the failure (e.g. "CategoryDao", "DBHelper" from a
        // constructor-mismatch message) are pulled in full so the model can reconcile caller and class.
        Matcher typeMatcher = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\b").matcher(focusText);
        int typeBudget = 16;
        Set<String> triedTypes = new HashSet<>();
        while (typeMatcher.find() && typeBudget > 0 && snapshot.length() <= SOURCE_SNAPSHOT_LIMIT) {
            String typeName = typeMatcher.group(1);
            if (!triedTypes.add(typeName)) {
                continue;
            }
            typeBudget--;
            appendSourceFilesNamed(root, root, typeName + ".java", snapshot, seen, true);
        }
        if (focusText.contains("Unresolved reference") || focusText.contains("findViewById") || focusText.contains("R.id")) {
            appendSourceSnapshot(root, new File(root, "app/src/main/res/layout"), snapshot, seen);
        }
    }

    private void appendSourceFilesNamed(File root, File file, String fileName, StringBuilder snapshot, Set<String> seen, boolean full) {
        if (file == null || !file.exists() || snapshot.length() > SOURCE_SNAPSHOT_LIMIT) {
            return;
        }
        if (file.isFile()) {
            if (file.getName().equals(fileName)) {
                appendSourceFile(root, file, snapshot, seen, full);
            }
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            appendSourceFilesNamed(root, child, fileName, snapshot, seen, full);
        }
    }

    private void appendSourceSnapshot(File root, File file, StringBuilder snapshot, Set<String> seen) {
        if (file == null || !file.exists() || snapshot.length() > SOURCE_SNAPSHOT_LIMIT) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                appendSourceSnapshot(root, child, snapshot, seen);
            }
            return;
        }
        appendSourceFile(root, file, snapshot, seen);
    }

    private void appendSourceFile(File root, File file, StringBuilder snapshot, Set<String> seen) {
        appendSourceFile(root, file, snapshot, seen, false);
    }

    private void appendSourceFile(File root, File file, StringBuilder snapshot, Set<String> seen, boolean full) {
        if (file == null || !file.exists() || snapshot.length() > SOURCE_SNAPSHOT_LIMIT) {
            return;
        }
        try {
            String path = root.toURI().relativize(file.toURI()).getPath();
            if (!seen.add(path)) {
                return;
            }
            if (isLikelyBinaryPath(path)) {
                snapshot.append(path).append(" [binary]\n");
                return;
            }
            snapshot.append("\n--- ").append(path).append(" ---\n");
            String text = FileUtils.readText(file);
            int limit = full ? SOURCE_FOCUS_FILE_LIMIT : SOURCE_FILE_PREVIEW_LIMIT;
            if (text.length() > limit) {
                text = text.substring(0, limit) + "\n...[truncated]";
            }
            snapshot.append(text).append("\n");
        } catch (Exception ignored) {
            // Best-effort prompt context only; unreadable files should not block generation.
        }
    }

    private boolean isLikelyBinaryPath(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".apk") ||
                lower.endsWith(".jar") || lower.endsWith(".zip");
    }

}
