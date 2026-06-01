package com.androidbuilder.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.androidbuilder.AndroidBuilderApp;
import com.androidbuilder.R;
import com.androidbuilder.agent.AgentService;
import com.androidbuilder.agent.BuildFailureClassifier;
import com.androidbuilder.agent.OpenAiClient;
import com.androidbuilder.backend.BuildBackend;
import com.androidbuilder.backend.BuildBackendFactory;
import com.androidbuilder.backend.BuildBackendSettings;
import com.androidbuilder.data.AppRepository;
import com.androidbuilder.install.ApkInstaller;
import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ChatMessage;
import com.androidbuilder.model.ProjectPlanRecord;
import com.androidbuilder.model.ProjectRecord;
import com.androidbuilder.model.ProjectTaskRecord;
import com.androidbuilder.server.LocalBuildServer;
import com.androidbuilder.util.ActiveWorkRegistry;
import com.androidbuilder.util.AppSettings;
import com.androidbuilder.util.FileUtils;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ProjectActivity extends BaseActivity {
    private static final long MAX_PREVIEW_BYTES = 80 * 1024;
    private static final int BUILD_LOG_INLINE_LIMIT = 9000;
    private static final int BUILD_LOG_CONTEXT_RADIUS = 2500;

    private AppRepository repository;
    private AgentService agentService;
    private LocalBuildServer buildServer;
    private long projectId;
    private final List<ChatMessage> messages = new ArrayList<>();
    private final List<FileItem> fileItems = new ArrayList<>();
    private final List<ProjectTaskRecord> taskItems = new ArrayList<>();
    private TimelineAdapter adapter;
    private FileAdapter fileAdapter;
    private ListView messageList;
    private ListView fileList;
    private MaterialToolbar projectToolbar;
    private TextView status;
    private TextView currentPathText;
    private EditText promptInput;
    private View projectContent;
    private View fileBrowserPanel;
    private View fileDrawerScrim;
    private TextView showFilesButton;
    private boolean buildLogVisible;
    private boolean busy;
    private boolean autoExecutingPlan;
    private long activeTaskStartedAt;
    private String statusSummary = "";
    private String operationStatus = "";
    private BuildJobRecord latestJob;
    private ProjectPlanRecord latestPlan;
    private File sourceRoot;
    private File currentSourceDir;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat fileTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    private final Set<Long> autoRepairing = new HashSet<>();
    private final Handler elapsedHandler = new Handler(Looper.getMainLooper());
    private final Runnable elapsedTicker = new Runnable() {
        @Override
        public void run() {
            refreshLiveState();
            if (shouldTickElapsed()) {
                elapsedHandler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        setContentView(R.layout.activity_project);
        applySystemBarPadding();
        projectToolbar = findViewById(R.id.projectToolbar);
        projectToolbar.setNavigationOnClickListener(v -> finish());
        repository = ((AndroidBuilderApp) getApplication()).repository();
        agentService = new AgentService(this, repository);
        projectId = getIntent().getLongExtra(MainActivity.EXTRA_PROJECT_ID, -1);
        sourceRoot = repository.sourceDir(projectId);
        currentSourceDir = sourceRoot;
        status = findViewById(R.id.statusText);
        promptInput = findViewById(R.id.promptInput);
        projectContent = findViewById(R.id.projectContent);
        fileBrowserPanel = findViewById(R.id.fileBrowserPanel);
        fileDrawerScrim = findViewById(R.id.fileDrawerScrim);
        showFilesButton = findViewById(R.id.showFilesButton);
        currentPathText = findViewById(R.id.currentPathText);
        adapter = new TimelineAdapter();
        messageList = findViewById(R.id.messageList);
        messageList.setAdapter(adapter);
        messageList.setOnItemLongClickListener((parent, view, position, id) -> {
            showTimelineActions(position);
            return true;
        });
        fileAdapter = new FileAdapter();
        fileList = findViewById(R.id.fileList);
        fileList.setAdapter(fileAdapter);
        fileList.setOnItemClickListener((parent, view, position, id) -> openFileItem(fileItems.get(position)));
        findViewById(R.id.sendButton).setOnClickListener(v -> generatePlan());
        showFilesButton.setOnClickListener(v -> toggleFileBrowser());
        fileDrawerScrim.setOnClickListener(v -> closeFileBrowser());
        findViewById(R.id.closeFilesButton).setOnClickListener(v -> closeFileBrowser());
        findViewById(R.id.executePlanButton).setOnClickListener(v -> executePlan());
        findViewById(R.id.buildButton).setOnClickListener(v -> buildLatest());
        findViewById(R.id.installButton).setOnClickListener(v -> installLatest());
        findViewById(R.id.copyLogButton).setOnClickListener(v -> copyText(getString(R.string.build_log), latestJob == null ? "" : readBuildLogPreview(latestJob)));
        buildServer = new LocalBuildServer(repository, (p, j) -> runOnUiThread(() -> {
            refresh();
            maybeAutoRepair(j);
        }));
        try {
            buildServer.start();
        } catch (Exception error) {
            Toast.makeText(this, getString(R.string.local_server_failed, error.getMessage()), Toast.LENGTH_LONG).show();
        }
        recoverInterruptedWorkIfNeeded();
        refresh();
        String initialPrompt = getIntent().getStringExtra(MainActivity.EXTRA_INITIAL_PROMPT);
        if (initialPrompt != null && repository.listMessages(projectId).isEmpty()) {
            promptInput.setText(initialPrompt);
            generatePlan();
        }
    }

    private void recoverInterruptedWorkIfNeeded() {
        if (ActiveWorkRegistry.isActive(projectId)) {
            return;
        }
        if (repository.recoverInterruptedWork(projectId, getString(R.string.interrupted_work_error))) {
            repository.addMessage(projectId, "assistant", getString(R.string.interrupted_work_recovered), null);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (buildServer != null) {
            buildServer.stop();
        }
        clearKeepScreenOn();
        elapsedHandler.removeCallbacks(elapsedTicker);
    }

    @Override
    public void onBackPressed() {
        if (fileBrowserPanel != null && fileBrowserPanel.getVisibility() == View.VISIBLE) {
            if (currentSourceDir != null && !sameFile(currentSourceDir, sourceRoot)) {
                loadSourceDirectory(currentSourceDir.getParentFile(), false);
                return;
            }
            closeFileBrowser();
            return;
        }
        super.onBackPressed();
    }

    private void refresh() {
        ProjectRecord project = repository.getProject(projectId);
        if (project == null) {
            finish();
            return;
        }
        projectToolbar.setTitle(project.name);
        latestJob = repository.latestBuildJob(projectId);
        latestPlan = repository.latestProjectPlan(projectId);
        updateStatusSummary(project);
        messages.clear();
        messages.addAll(repository.listMessages(projectId));
        taskItems.clear();
        taskItems.addAll(repository.listProjectTasks(projectId));
        adapter.notifyDataSetChanged();
        loadSourceDirectory(currentSourceDir == null ? sourceRoot : currentSourceDir, false);
        updateFileBrowserButton();
        updateBuildLogPanel();
        updateActionButtons();
        updateKeepScreenOn();
        if (!messages.isEmpty() || buildLogVisible || shouldShowOperationStatus()) {
            scrollMessagesToBottom();
        }
        updateElapsedTicker();
    }

    private void refreshLiveState() {
        ProjectRecord project = repository.getProject(projectId);
        if (project == null || isFinishing()) {
            return;
        }
        latestJob = repository.latestBuildJob(projectId);
        latestPlan = repository.latestProjectPlan(projectId);
        updateStatusSummary(project);

        messages.clear();
        messages.addAll(repository.listMessages(projectId));
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }

        List<ProjectTaskRecord> latestTasks = repository.listProjectTasks(projectId);
        if (ProjectLiveState.tasksChanged(taskItems, latestTasks)) {
            taskItems.clear();
            taskItems.addAll(latestTasks);
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            scrollToCurrentTask();
        } else if (adapter != null) {
            adapter.notifyDataSetChanged();
        }

        updateBuildLogPanel();
        updateActionButtons();
        updateKeepScreenOn();
        if (!shouldShowOperationStatus()) {
            operationStatus = "";
        }
    }

    private void updateStatusSummary(ProjectRecord project) {
        String planStatus = latestPlan == null ? "idle" : latestPlan.status;
        statusSummary = project.packageName + " · " + project.lastBuildStatus + " · " + getString(R.string.plan_status, planStatusText(planStatus)) + (latestJob == null ? "" : " · job #" + latestJob.id);
        status.setText(statusSummary);
    }

    private void toggleFileBrowser() {
        if (fileBrowserPanel == null) {
            return;
        }
        boolean show = fileBrowserPanel.getVisibility() != View.VISIBLE;
        if (show) {
            openFileBrowser();
        } else {
            closeFileBrowser();
        }
    }

    private void openFileBrowser() {
        if (fileBrowserPanel == null) {
            return;
        }
        loadSourceDirectory(currentSourceDir == null ? sourceRoot : currentSourceDir, false);
        if (fileDrawerScrim != null) {
            fileDrawerScrim.setVisibility(View.VISIBLE);
        }
        fileBrowserPanel.setVisibility(View.VISIBLE);
        fileBrowserPanel.bringToFront();
        updateFileBrowserButton();
    }

    private void closeFileBrowser() {
        if (fileBrowserPanel == null) {
            return;
        }
        fileBrowserPanel.setVisibility(View.GONE);
        if (fileDrawerScrim != null) {
            fileDrawerScrim.setVisibility(View.GONE);
        }
        updateFileBrowserButton();
    }

    private void updateFileBrowserButton() {
        if (showFilesButton == null || fileBrowserPanel == null) {
            return;
        }
        showFilesButton.setText(fileBrowserPanel.getVisibility() == View.VISIBLE ? R.string.hide_source_files : R.string.show_source_files);
    }

    private void showTimelineActions(int position) {
        ProjectTimelinePolicy.Entry entry = adapter == null ? null : adapter.entryAt(position);
        if (entry == null) {
            return;
        }
        if (entry.kind == ProjectTimelinePolicy.Kind.OPERATION_STATUS) {
            copyText(getString(R.string.message), operationStatus);
            return;
        }
        if (entry.kind == ProjectTimelinePolicy.Kind.BUILD_LOG) {
            BuildJobRecord job = latestJob;
            copyText(getString(R.string.build_log), job == null ? "" : readBuildLogPreview(job));
            return;
        }
        int messageIndex = entry.kind == ProjectTimelinePolicy.Kind.MESSAGE ? entry.sourceIndex : -1;
        if (messageIndex >= 0 && messageIndex < messages.size()) {
            ChatMessage message = messages.get(messageIndex);
            new MaterialAlertDialogBuilder(this)
                    .setItems(new CharSequence[]{getString(R.string.copy_message), getString(R.string.delete_message)}, (dialog, which) -> {
                        if (which == 0) {
                            copyText(getString(R.string.message), message.content);
                        } else {
                            confirmDeleteMessage(message);
                        }
                    })
                    .show();
            return;
        }
        if (entry.kind == ProjectTimelinePolicy.Kind.TASK && entry.sourceIndex >= 0 && entry.sourceIndex < taskItems.size()) {
            ProjectTaskRecord task = taskItems.get(entry.sourceIndex);
            String summary = task.resultSummary == null ? "" : task.resultSummary.trim();
            if (!summary.isEmpty()) {
                copyText(getString(R.string.copy_log), summary);
            }
        }
    }

    private void confirmDeleteMessage(ChatMessage message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_message_title)
                .setMessage(R.string.delete_message_message)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    repository.deleteMessage(message.id);
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void copyText(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text == null ? "" : text));
        Toast.makeText(this, getString(R.string.copied, label), Toast.LENGTH_SHORT).show();
    }

    private void generatePlan() {
        String prompt = promptInput.getText().toString().trim();
        if (prompt.isEmpty()) {
            Toast.makeText(this, R.string.write_requirement_first, Toast.LENGTH_SHORT).show();
            promptInput.requestFocus();
            return;
        }
        if (!new OpenAiClient(this).isConfigured()) {
            Toast.makeText(this, R.string.api_required_short, Toast.LENGTH_LONG).show();
            startActivity(new android.content.Intent(this, SettingsActivity.class));
            return;
        }
        promptInput.setText("");
        setBusy(true);
        setOperationStatus(getString(R.string.plan_generating));
        adapter.notifyDataSetChanged();
        agentService.planAsync(projectId, prompt, new AgentService.PlanCallback() {
            @Override
            public void onComplete(String plan) {
                runOnUiThread(() -> {
                    setBusy(false);
                    refresh();
                    Toast.makeText(ProjectActivity.this, R.string.plan_generated, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    repository.addMessage(projectId, "assistant", getString(R.string.plan_failed, error.getMessage()), null);
                    refresh();
                    Toast.makeText(ProjectActivity.this, error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void executePlan() {
        latestPlan = repository.latestProjectPlan(projectId);
        if (latestPlan == null || latestPlan.content.trim().isEmpty() || !"planned".equals(latestPlan.status)) {
            Toast.makeText(this, R.string.plan_required, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!new OpenAiClient(this).isConfigured()) {
            Toast.makeText(this, R.string.api_required_short, Toast.LENGTH_LONG).show();
            startActivity(new android.content.Intent(this, SettingsActivity.class));
            return;
        }
        setBusy(true);
        setOperationStatus(getString(R.string.plan_executing));
        adapter.notifyDataSetChanged();
        autoExecutingPlan = true;
        updateKeepScreenOn();
        executePlanStep();
    }

    private void executePlanStep() {
        activeTaskStartedAt = System.currentTimeMillis();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        scrollToCurrentTask();
        updateElapsedTicker();
        agentService.executePlanAsync(projectId, new AgentService.Callback() {
            @Override
            public void onComplete(BuildJobRecord job) {
                runOnUiThread(() -> {
                    refresh();
                    latestPlan = repository.latestProjectPlan(projectId);
                    if (autoExecutingPlan && latestPlan != null && "planned".equals(latestPlan.status)) {
                        executePlanStep();
                    } else {
                        autoExecutingPlan = false;
                        setBusy(false);
                        Toast.makeText(ProjectActivity.this, R.string.source_generated_from_plan, Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(() -> {
                    autoExecutingPlan = false;
                    setBusy(false);
                    repository.addMessage(projectId, "assistant", getString(R.string.execute_plan_failed, error.getMessage()), null);
                    refresh();
                    Toast.makeText(ProjectActivity.this, error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void scrollToCurrentTask() {
        if (messageList == null || adapter == null || taskItems.isEmpty()) {
            return;
        }
        int index = currentTaskIndex();
        if (index < 0) {
            return;
        }
        int timelinePosition = adapter.positionForTaskIndex(index);
        if (timelinePosition < 0) {
            return;
        }
        messageList.postDelayed(() -> messageList.smoothScrollToPositionFromTop(timelinePosition, dp(8)), 80);
    }

    private int currentTaskIndex() {
        for (int i = 0; i < taskItems.size(); i++) {
            String status = taskItems.get(i).status == null ? "pending" : taskItems.get(i).status;
            if ("running".equals(status)) {
                return i;
            }
        }
        for (int i = 0; i < taskItems.size(); i++) {
            String status = taskItems.get(i).status == null ? "pending" : taskItems.get(i).status;
            if ("failed".equals(status)) {
                return i;
            }
        }
        for (int i = 0; i < taskItems.size(); i++) {
            String status = taskItems.get(i).status == null ? "pending" : taskItems.get(i).status;
            if ("pending".equals(status)) {
                return i;
            }
        }
        return -1;
    }

    private void buildLatest() {
        latestPlan = repository.latestProjectPlan(projectId);
        if (latestPlan == null || !"generated".equals(latestPlan.status)) {
            Toast.makeText(this, R.string.execute_plan_first, Toast.LENGTH_SHORT).show();
            return;
        }
        BuildJobRecord job = repository.latestBuildJob(projectId);
        if (job == null || job.logsPath == null) {
            Toast.makeText(this, R.string.generate_source_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (buildServer == null) {
            Toast.makeText(this, R.string.local_server_not_running, Toast.LENGTH_SHORT).show();
            return;
        }
        BuildBackend backend = BuildBackendFactory.create(this, repository, buildServer);
        buildLogVisible = true;
        setOperationStatus(getString(BuildBackendSettings.EXTERNAL_TERMUX.equals(backend.id()) ? R.string.termux_build_started : R.string.embedded_build_started));
        String logsPath = resetBuildLog(job);
        autoRepairing.remove(job.id);
        repository.updateBuildJob(job.id, "building", backend.id() + "_start", logsPath, null, null, BuildRepairPolicy.retryCountForManualBuild(job));
        BuildJobRecord buildJob = repository.getBuildJob(job.id);
        backend.build(buildJob == null ? job : buildJob, (p, j) -> runOnUiThread(() -> {
            refresh();
            maybeAutoRepair(j);
        }));
        refresh();
        Toast.makeText(this, BuildBackendSettings.EXTERNAL_TERMUX.equals(backend.id()) ? R.string.termux_build_started : R.string.embedded_build_started, Toast.LENGTH_SHORT).show();
    }

    private String resetBuildLog(BuildJobRecord job) {
        File log = new File(repository.jobDir(job.projectId, job.id), "build.log");
        try {
            FileUtils.writeText(log, "");
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
        return log.getAbsolutePath();
    }

    private void installLatest() {
        BuildJobRecord job = repository.latestBuildJob(projectId);
        if (job == null || job.apkPath == null) {
            Toast.makeText(this, R.string.no_apk_yet, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            new ApkInstaller(this).install(new File(job.apkPath));
        } catch (Exception error) {
            Toast.makeText(this, "Install failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadSourceDirectory(File dir, boolean announceEmpty) {
        if (sourceRoot == null || !sourceRoot.exists()) {
            currentPathText.setText(R.string.no_source_files);
            fileItems.clear();
            fileAdapter.notifyDataSetChanged();
            return;
        }
        if (dir == null || !isInside(sourceRoot, dir)) {
            dir = sourceRoot;
        }
        currentSourceDir = dir;
        currentPathText.setText(relativeSourcePath(dir));
        fileItems.clear();
        if (!sameFile(dir, sourceRoot)) {
            fileItems.add(FileItem.parent(dir.getParentFile()));
        }
        File[] children = dir.listFiles();
        if (children != null) {
            List<File> files = new ArrayList<>();
            Collections.addAll(files, children);
            files.sort(Comparator
                    .comparing((File file) -> !file.isDirectory())
                    .thenComparing(file -> file.getName().toLowerCase(Locale.ROOT)));
            for (File file : files) {
                fileItems.add(FileItem.file(file));
            }
        }
        if (announceEmpty && fileItems.isEmpty()) {
            Toast.makeText(this, R.string.empty_directory, Toast.LENGTH_SHORT).show();
        }
        fileAdapter.notifyDataSetChanged();
    }

    private void openFileItem(FileItem item) {
        if (item.parent || item.file.isDirectory()) {
            loadSourceDirectory(item.file, true);
            return;
        }
        previewFile(item.file);
    }

    private void previewFile(File file) {
        if (file.length() > MAX_PREVIEW_BYTES) {
            Toast.makeText(this, R.string.file_too_large, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String text = FileUtils.readText(file);
            if (looksBinary(text)) {
                Toast.makeText(this, R.string.binary_file, Toast.LENGTH_SHORT).show();
                return;
            }
            TextView preview = new TextView(this);
            preview.setText(text);
            preview.setTextColor(getResources().getColor(R.color.ink));
            preview.setTextSize(13);
            preview.setTypeface(android.graphics.Typeface.MONOSPACE);
            preview.setPadding(24, 18, 24, 18);
            preview.setMovementMethod(new ScrollingMovementMethod());
            new MaterialAlertDialogBuilder(this)
                    .setTitle(relativeSourcePath(file))
                    .setView(preview)
                    .setPositiveButton(R.string.close, null)
                    .show();
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean looksBinary(String value) {
        int sample = Math.min(value.length(), 2000);
        for (int i = 0; i < sample; i++) {
            if (value.charAt(i) == 0) {
                return true;
            }
        }
        return false;
    }

    private String relativeSourcePath(File file) {
        if (sameFile(file, sourceRoot)) {
            return "/";
        }
        String root = sourceRoot.getAbsolutePath();
        String path = file.getAbsolutePath();
        return path.startsWith(root) ? path.substring(root.length()) : path;
    }

    private boolean isInside(File root, File file) {
        try {
            String rootPath = root.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
        } catch (Exception error) {
            return false;
        }
    }

    private boolean sameFile(File left, File right) {
        if (left == null || right == null) {
            return false;
        }
        try {
            return left.getCanonicalPath().equals(right.getCanonicalPath());
        } catch (Exception error) {
            return left.equals(right);
        }
    }

    private void maybeAutoRepair(long failedJobId) {
        BuildJobRecord failed = repository.getBuildJob(failedJobId);
        if (failed == null || !"failed".equals(failed.status)) {
            return;
        }
        if (BuildRepairPolicy.reachedAutoRepairLimit(failed)) {
            if (!autoRepairing.contains(failedJobId)) {
                autoRepairing.add(failedJobId);
                repository.addMessage(projectId, "assistant", "自动修复已达到 3 次上限。你可以点击重新构建来开始新一轮自动修复。", failedJobId);
                refresh();
            }
            return;
        }
        boolean repairable = isRepairableFailure(failed);
        if (!repairable && !autoRepairing.contains(failedJobId)) {
            autoRepairing.add(failedJobId);
            repository.addMessage(projectId, "assistant", "构建失败，但这类错误需要先修复本机运行环境或构建后端配置，不自动重试。", failedJobId);
            refresh();
            return;
        }
        if (!BuildRepairPolicy.canAutoRepair(failed, autoRepairing.contains(failedJobId), repairable)) {
            return;
        }
        autoRepairing.add(failedJobId);
        String logs = "";
        if (failed.logsPath != null) {
            try {
                logs = FileUtils.readText(new File(failed.logsPath));
                logs = buildFailureContext(logs);
            } catch (Exception ignored) {
            }
        }
        repository.addMessage(projectId, "assistant", "构建失败，开始自动修复第 " + BuildRepairPolicy.nextRetryCount(failed) + "/3 次。", failedJobId);
        agentService.repairBuildAsync(projectId, logs, new AgentService.Callback() {
            @Override
            public void onComplete(BuildJobRecord job) {
                runOnUiThread(() -> {
                    repository.updateBuildJob(job.id, job.status, job.phase, job.logsPath, job.apkPath, job.errorSummary, BuildRepairPolicy.nextRetryCount(failed));
                    BuildJobRecord retryJob = repository.getBuildJob(job.id);
                    refresh();
                    BuildBackend backend = BuildBackendFactory.create(ProjectActivity.this, repository, buildServer);
                    backend.build(retryJob == null ? job : retryJob, (p, j) -> runOnUiThread(() -> {
                        refresh();
                        maybeAutoRepair(j);
                    }));
                });
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(() -> {
                    repository.addMessage(projectId, "assistant", "自动修复失败：" + error.getMessage(), failedJobId);
                    refresh();
                });
            }
        });
    }

    private boolean isRepairableFailure(BuildJobRecord failed) {
        String error = failed.errorSummary == null ? "" : failed.errorSummary;
        BuildFailureClassifier.Result result = BuildFailureClassifier.classify(failed.phase, error);
        return result.repairableByModel;
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        if (!busy) {
            activeTaskStartedAt = 0;
        }
        updateActionButtons();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateKeepScreenOn();
        updateElapsedTicker();
    }

    private void setOperationStatus(String value) {
        operationStatus = value == null ? "" : value.trim();
        if (status != null) {
            status.setText(operationStatus);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (shouldShowOperationStatus()) {
            scrollMessagesToBottom();
        }
    }

    private boolean shouldShowOperationStatus() {
        return ProjectOperationStatus.shouldShow(operationStatus, busy, autoExecutingPlan, latestJob);
    }

    private void updateKeepScreenOn() {
        boolean keepScreenOn = WorkAwakePolicy.shouldKeepScreenOn(busy, autoExecutingPlan, taskItems, latestJob);
        if (keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        View decorView = getWindow().getDecorView();
        if (decorView != null) {
            decorView.setKeepScreenOn(keepScreenOn);
        }
        if (projectContent != null) {
            projectContent.setKeepScreenOn(keepScreenOn);
        }
    }

    private void clearKeepScreenOn() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View decorView = getWindow().getDecorView();
        if (decorView != null) {
            decorView.setKeepScreenOn(false);
        }
        if (projectContent != null) {
            projectContent.setKeepScreenOn(false);
        }
    }

    private boolean shouldTickElapsed() {
        if (busy || autoExecutingPlan) {
            return true;
        }
        for (ProjectTaskRecord task : taskItems) {
            if ("running".equals(task.status)) {
                return true;
            }
        }
        for (ChatMessage message : messages) {
            if (message.linkedBuildJobId != null) {
                BuildJobRecord job = repository.getBuildJob(message.linkedBuildJobId);
                if (job != null && isRunningJob(job)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void updateElapsedTicker() {
        elapsedHandler.removeCallbacks(elapsedTicker);
        if (shouldTickElapsed()) {
            elapsedHandler.postDelayed(elapsedTicker, 1000);
        }
    }

    private void updateActionButtons() {
        View sendButton = findViewById(R.id.sendButton);
        View executePlanButton = findViewById(R.id.executePlanButton);
        View buildButton = findViewById(R.id.buildButton);
        View installButton = findViewById(R.id.installButton);
        View copyLogButton = findViewById(R.id.copyLogButton);
        boolean canExecutePlan = latestPlan != null && "planned".equals(latestPlan.status) && !latestPlan.content.trim().isEmpty();
        sendButton.setEnabled(!busy);
        executePlanButton.setEnabled(!busy && canExecutePlan);
        buildButton.setEnabled(!busy && latestPlan != null && "generated".equals(latestPlan.status) && latestJob != null && latestJob.logsPath != null);
        if (buildButton instanceof TextView) {
            ((TextView) buildButton).setText(latestJob != null && "failed".equals(latestJob.status) ? R.string.rebuild : R.string.build);
        }
        installButton.setEnabled(!busy && latestJob != null && latestJob.apkPath != null);
        copyLogButton.setEnabled(latestJob != null && latestJob.logsPath != null);
    }

    private void scrollMessagesToBottom() {
        if (messageList == null || adapter == null) {
            return;
        }
        messageList.postDelayed(() -> messageList.setSelection(Math.max(adapter.getCount() - 1, 0)), 120);
    }

    private void updateBuildLogPanel() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private String planStatusText(String value) {
        boolean chinese = AppSettings.isChinese(this);
        if ("planning".equals(value)) {
            return chinese ? "规划中" : "planning";
        }
        if ("planned".equals(value)) {
            return chinese ? "待执行" : "planned";
        }
        if ("coding".equals(value)) {
            return chinese ? "编码中" : "coding";
        }
        if ("generated".equals(value)) {
            return chinese ? "已生成" : "generated";
        }
        return chinese ? "待规划" : "idle";
    }

    private boolean isRunningJob(BuildJobRecord job) {
        if (job == null || job.status == null) {
            return false;
        }
        return "queued".equals(job.status) || "generating".equals(job.status) || "building".equals(job.status);
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0, millis / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes <= 0) {
            return seconds + "s";
        }
        return minutes + "m " + seconds + "s";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private class TimelineAdapter extends BaseAdapter {
        private static final int TYPE_MESSAGE = 0;
        private static final int TYPE_STATUS = 1;
        private static final int TYPE_TASK = 2;
        private static final int TYPE_BUILD_LOG = 3;

        @Override
        public int getCount() {
            return entries().size();
        }

        @Override
        public Object getItem(int position) {
            ProjectTimelinePolicy.Entry entry = entryAt(position);
            if (entry == null) {
                return null;
            }
            if (entry.kind == ProjectTimelinePolicy.Kind.MESSAGE && entry.sourceIndex >= 0 && entry.sourceIndex < messages.size()) {
                return messages.get(entry.sourceIndex);
            }
            if (entry.kind == ProjectTimelinePolicy.Kind.TASK && entry.sourceIndex >= 0 && entry.sourceIndex < taskItems.size()) {
                return taskItems.get(entry.sourceIndex);
            }
            if (entry.kind == ProjectTimelinePolicy.Kind.BUILD_LOG) {
                return latestJob;
            }
            return entry.kind;
        }

        @Override
        public long getItemId(int position) {
            ProjectTimelinePolicy.Entry entry = entryAt(position);
            if (entry == null) {
                return position;
            }
            if (entry.kind == ProjectTimelinePolicy.Kind.MESSAGE && entry.sourceIndex >= 0 && entry.sourceIndex < messages.size()) {
                return messages.get(entry.sourceIndex).id;
            }
            if (entry.kind == ProjectTimelinePolicy.Kind.TASK && entry.sourceIndex >= 0 && entry.sourceIndex < taskItems.size()) {
                return taskItems.get(entry.sourceIndex).id;
            }
            if (entry.kind == ProjectTimelinePolicy.Kind.OPERATION_STATUS) {
                return -1;
            }
            if (entry.kind == ProjectTimelinePolicy.Kind.EMPTY_TASKS) {
                return -2;
            }
            return -3;
        }

        @Override
        public int getViewTypeCount() {
            return 4;
        }

        @Override
        public int getItemViewType(int position) {
            ProjectTimelinePolicy.Entry entry = entryAt(position);
            if (entry == null) {
                return TYPE_MESSAGE;
            }
            if (entry.kind == ProjectTimelinePolicy.Kind.OPERATION_STATUS) {
                return TYPE_STATUS;
            }
            if (entry.kind == ProjectTimelinePolicy.Kind.TASK || entry.kind == ProjectTimelinePolicy.Kind.EMPTY_TASKS) {
                return TYPE_TASK;
            }
            if (entry.kind == ProjectTimelinePolicy.Kind.BUILD_LOG) {
                return TYPE_BUILD_LOG;
            }
            return TYPE_MESSAGE;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ProjectTimelinePolicy.Entry entry = entryAt(position);
            if (entry == null) {
                return new View(ProjectActivity.this);
            }
            if (entry.kind == ProjectTimelinePolicy.Kind.OPERATION_STATUS) {
                return bindOperationStatus(convertView, parent);
            }
            if (entry.kind == ProjectTimelinePolicy.Kind.TASK || entry.kind == ProjectTimelinePolicy.Kind.EMPTY_TASKS) {
                return bindTask(entry, convertView, parent);
            }
            if (entry.kind == ProjectTimelinePolicy.Kind.BUILD_LOG) {
                return bindBuildLog(convertView, parent);
            }
            return bindMessage(entry, convertView, parent);
        }

        ProjectTimelinePolicy.Entry entryAt(int position) {
            List<ProjectTimelinePolicy.Entry> entries = entries();
            return position >= 0 && position < entries.size() ? entries.get(position) : null;
        }

        int positionForTaskIndex(int taskIndex) {
            List<ProjectTimelinePolicy.Entry> entries = entries();
            for (int i = 0; i < entries.size(); i++) {
                ProjectTimelinePolicy.Entry entry = entries.get(i);
                if (entry.kind == ProjectTimelinePolicy.Kind.TASK && entry.sourceIndex == taskIndex) {
                    return i;
                }
            }
            return -1;
        }

        private List<ProjectTimelinePolicy.Entry> entries() {
            return ProjectTimelinePolicy.entries(messages.size(), shouldShowOperationStatus(), latestPlan, taskItems, latestJob, buildLogVisible);
        }

        private View bindOperationStatus(View convertView, ViewGroup parent) {
            View view = convertView == null ? getLayoutInflater().inflate(R.layout.row_operation_status, parent, false) : convertView;
            ((TextView) view.findViewById(R.id.operationStatusContent)).setText(operationStatus);
            return view;
        }

        private View bindMessage(ProjectTimelinePolicy.Entry entry, View convertView, ViewGroup parent) {
            View view = convertView == null ? getLayoutInflater().inflate(R.layout.row_message, parent, false) : convertView;
            ChatMessage message = messages.get(entry.sourceIndex);
            ((TextView) view.findViewById(R.id.messageRole)).setText(message.role.toUpperCase());
            ((TextView) view.findViewById(R.id.messageTime)).setText(messageTimeText(message));
            ((TextView) view.findViewById(R.id.messageContent)).setText(message.content);
            return view;
        }

        private View bindTask(ProjectTimelinePolicy.Entry entry, View convertView, ViewGroup parent) {
            View view = convertView == null ? getLayoutInflater().inflate(R.layout.row_project_task, parent, false) : convertView;
            TextView icon = view.findViewById(R.id.taskStatusIcon);
            TextView title = view.findViewById(R.id.taskTitle);
            TextView steps = view.findViewById(R.id.taskSteps);
            TextView status = view.findViewById(R.id.taskStatus);
            TextView log = view.findViewById(R.id.taskLog);
            View copyLog = view.findViewById(R.id.taskCopyLog);
            copyLog.setVisibility(View.GONE);
            copyLog.setOnClickListener(null);
            if (entry.kind == ProjectTimelinePolicy.Kind.EMPTY_TASKS || taskItems.isEmpty()) {
                icon.setText("-");
                title.setText(R.string.no_plan_tasks);
                steps.setVisibility(View.GONE);
                status.setText("");
                log.setVisibility(View.GONE);
                return view;
            }
            ProjectTaskRecord task = taskItems.get(entry.sourceIndex);
            title.setText((task.sortOrder + 1) + ". " + task.title);
            setTaskSteps(steps, task.instruction);
            String taskStatus = task.status == null ? "pending" : task.status;
            if (TaskRunningDisplayPolicy.shouldShowPredictedRunning(busy, entry.sourceIndex, taskStatus, taskItems)) {
                icon.setText("...");
                long startedAt = activeTaskStartedAt > 0 ? activeTaskStartedAt : System.currentTimeMillis();
                status.setText(getString(R.string.task_running) + " · " + getString(R.string.elapsed_time, formatDuration(System.currentTimeMillis() - startedAt)));
                setTaskLog(log, getString(R.string.task_running_log));
                return view;
            }
            if ("done".equals(taskStatus)) {
                icon.setText("✓");
                status.setText(getString(R.string.task_done) + taskDurationSuffix(task));
                setTaskLog(log, task.resultSummary);
            } else if ("running".equals(taskStatus)) {
                icon.setText("...");
                status.setText(getString(R.string.task_running) + taskDurationSuffix(task));
                setTaskLog(log, task.resultSummary == null || task.resultSummary.trim().isEmpty() ? getString(R.string.task_running_log) : task.resultSummary);
            } else if ("failed".equals(taskStatus)) {
                icon.setText("!");
                status.setText(getString(R.string.task_failed) + taskDurationSuffix(task));
                setTaskLog(log, task.resultSummary);
                configureTaskLogCopy(copyLog, task.resultSummary);
            } else {
                icon.setText(String.valueOf(task.sortOrder + 1));
                status.setText(R.string.task_pending);
                log.setVisibility(View.GONE);
            }
            return view;
        }

        private String messageTimeText(ChatMessage message) {
            String time = timeFormat.format(new Date(message.createdAt));
            if (message.linkedBuildJobId == null) {
                return time;
            }
            BuildJobRecord job = repository.getBuildJob(message.linkedBuildJobId);
            if (job == null) {
                return time;
            }
            long end = isRunningJob(job) ? System.currentTimeMillis() : Math.max(job.updatedAt, job.createdAt);
            return time + " · " + getString(R.string.elapsed_time, formatDuration(Math.max(0, end - job.createdAt)));
        }

        private View bindBuildLog(View convertView, ViewGroup parent) {
            View view = convertView == null ? getLayoutInflater().inflate(R.layout.row_build_log, parent, false) : convertView;
            BuildJobRecord job = latestJob;
            boolean running = job != null && ("building".equals(job.status) || "queued".equals(job.status));
            ProgressBar progress = view.findViewById(R.id.buildProgress);
            TextView content = view.findViewById(R.id.buildLogContent);
            View copyButton = view.findViewById(R.id.buildLogCopyButton);
            progress.setVisibility(running ? View.VISIBLE : View.GONE);
            content.setText(job == null || job.logsPath == null ? getString(R.string.no_build_log) : readBuildLogPreview(job));
            copyButton.setEnabled(job != null && job.logsPath != null);
            copyButton.setOnClickListener(v -> copyText(getString(R.string.build_log), job == null ? "" : readBuildLogPreview(job)));
            return view;
        }

        private String taskDurationSuffix(ProjectTaskRecord task) {
            if (task.startedAt <= 0) {
                return "";
            }
            long end = "running".equals(task.status) || task.completedAt <= 0 ? System.currentTimeMillis() : task.completedAt;
            return " · " + getString(R.string.elapsed_time, formatDuration(Math.max(0, end - task.startedAt)));
        }

        private void setTaskLog(TextView log, String value) {
            String text = value == null ? "" : value.trim();
            if (text.isEmpty()) {
                log.setVisibility(View.GONE);
            } else {
                log.setText(text);
                log.setVisibility(View.VISIBLE);
            }
        }

        private void setTaskSteps(TextView steps, String instruction) {
            String text = taskStepsText(instruction);
            if (text.isEmpty()) {
                steps.setVisibility(View.GONE);
                return;
            }
            steps.setText(text);
            steps.setVisibility(View.VISIBLE);
        }

        private String taskStepsText(String instruction) {
            String raw = instruction == null ? "" : instruction.trim().replace("\r", "");
            if (raw.isEmpty()) {
                return "";
            }
            List<String> parts = new ArrayList<>();
            for (String line : raw.split("\n")) {
                addTaskStep(parts, line);
            }
            if (parts.size() <= 1) {
                parts.clear();
                for (String part : raw.split("[。；;]\\s*")) {
                    addTaskStep(parts, part);
                }
            }
            if (parts.isEmpty()) {
                return "";
            }
            StringBuilder text = new StringBuilder(getString(R.string.task_substeps)).append(":\n");
            int count = Math.min(parts.size(), 6);
            for (int i = 0; i < count; i++) {
                text.append("• ").append(parts.get(i)).append("\n");
            }
            if (parts.size() > count) {
                text.append("• ...");
            }
            return text.toString().trim();
        }

        private void addTaskStep(List<String> parts, String value) {
            String text = value == null ? "" : value.trim()
                    .replaceFirst("^(?:[-*•]+|\\d+[.)、])\\s*", "")
                    .replaceFirst("^(Instruction|步骤|子步骤)[:：]\\s*", "")
                    .trim();
            if (text.isEmpty()) {
                return;
            }
            if (text.length() > 180) {
                text = text.substring(0, 180).trim() + "...";
            }
            parts.add(text);
        }

        private void configureTaskLogCopy(View copyLog, String value) {
            String text = value == null ? "" : value.trim();
            if (text.isEmpty()) {
                copyLog.setVisibility(View.GONE);
                copyLog.setOnClickListener(null);
                return;
            }
            copyLog.setVisibility(View.VISIBLE);
            copyLog.setOnClickListener(v -> copyText(getString(R.string.copy_log), text));
        }
    }

    private class FileAdapter extends BaseAdapter {
        @Override public int getCount() { return fileItems.size(); }
        @Override public Object getItem(int position) { return fileItems.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView == null ? getLayoutInflater().inflate(R.layout.row_file, parent, false) : convertView;
            FileItem item = fileItems.get(position);
            ((TextView) view.findViewById(R.id.fileIcon)).setText(item.parent ? ".." : item.file.isDirectory() ? "/" : "{}");
            ((TextView) view.findViewById(R.id.fileName)).setText(item.parent ? getString(R.string.parent_directory) : item.file.getName());
            ((TextView) view.findViewById(R.id.fileMeta)).setText(item.parent ? relativeSourcePath(item.file) : fileMeta(item.file));
            return view;
        }

        private String fileMeta(File file) {
            String updated = getString(R.string.updated_at, fileTimeFormat.format(new Date(file.lastModified())));
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                return getString(R.string.file_items, children == null ? 0 : children.length) + " · " + updated;
            }
            return file.length() + " bytes · " + updated;
        }
    }

    private static class FileItem {
        final File file;
        final boolean parent;

        private FileItem(File file, boolean parent) {
            this.file = file;
            this.parent = parent;
        }

        static FileItem file(File file) {
            return new FileItem(file, false);
        }

        static FileItem parent(File file) {
            return new FileItem(file, true);
        }
    }

    private String readBuildLogPreview(BuildJobRecord job) {
        String logs = "";
        try {
            logs = FileUtils.readText(new File(job.logsPath));
        } catch (Exception ignored) {
        }
        if (logs.trim().isEmpty()) {
            return getString(R.string.build_waiting);
        }
        if (logs.length() <= 5000) {
            return logs;
        }
        return buildFailureContext(logs);
    }

    private String buildFailureContext(String logs) {
        if (logs == null || logs.trim().isEmpty()) {
            return "";
        }
        if (logs.length() <= BUILD_LOG_INLINE_LIMIT) {
            return logs;
        }
        StringBuilder result = new StringBuilder();
        appendSnippet(result, "First log", logs, 0, Math.min(1600, logs.length()));
        int[] anchors = failureAnchors(logs);
        for (int anchor : anchors) {
            if (anchor >= 0) {
                appendSnippet(result, "Failure context", logs,
                        Math.max(0, anchor - BUILD_LOG_CONTEXT_RADIUS),
                        Math.min(logs.length(), anchor + BUILD_LOG_CONTEXT_RADIUS));
            }
        }
        appendSnippet(result, "Last log", logs, Math.max(0, logs.length() - 3500), logs.length());
        String text = result.toString().trim();
        if (text.length() > 14000) {
            return text.substring(0, 14000).trim() + "\n\n...[truncated]";
        }
        return text;
    }

    private int[] failureAnchors(String logs) {
        return new int[]{
                indexOfAny(logs, "Android resource linking failed", "error: resource", "error: failed linking", "AAPT: error"),
                indexOfAny(logs, "Namespace not specified", "Manifest merger failed", "package=\"", "> Task :app:processDebugResources FAILED", "Execution failed for task ':app:processDebugResources'", "* What went wrong:"),
                indexOfAny(logs, "BUILD FAILED", "Caused by:")
        };
    }

    private int indexOfAny(String text, String... needles) {
        int best = -1;
        for (String needle : needles) {
            int index = text.indexOf(needle);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    private void appendSnippet(StringBuilder result, String label, String text, int start, int end) {
        if (start >= end) {
            return;
        }
        String snippet = text.substring(start, end).trim();
        if (snippet.isEmpty() || result.indexOf(snippet) >= 0) {
            return;
        }
        if (result.length() > 0) {
            result.append("\n\n...\n\n");
        }
        result.append(label).append(":\n").append(snippet);
    }
}
