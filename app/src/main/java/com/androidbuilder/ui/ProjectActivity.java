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
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.androidbuilder.AndroidBuilderApp;
import com.androidbuilder.R;
import com.androidbuilder.agent.AgentService;
import com.androidbuilder.agent.BuildFailureClassifier;
import com.androidbuilder.agent.BuildLogContextExtractor;
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
    private final Set<Long> expandedBuildLogJobIds = new HashSet<>();
    private boolean tasksCollapsed;
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
    private boolean busy;
    private boolean autoExecutingPlan;
    private long repairSourceBuildJobId = -1;
    private long operationStartedAt;
    private long activeTaskStartedAt;
    private String statusSummary = "";
    private String operationStatus = "";
    private String operationProgress = "";
    private long lastStreamProgressAt;
    private BuildJobRecord latestJob;
    private ProjectPlanRecord latestPlan;
    private File sourceRoot;
    private File currentSourceDir;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat fileTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
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
        projectToolbar.inflateMenu(R.menu.menu_project);
        projectToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_source_files) {
                toggleFileBrowser();
                return true;
            }
            return false;
        });
        repository = ((AndroidBuilderApp) getApplication()).repository();
        agentService = new AgentService(this, repository);
        agentService.setProgressListener(this::onModelStreamProgress);
        projectId = getIntent().getLongExtra(MainActivity.EXTRA_PROJECT_ID, -1);
        sourceRoot = repository.sourceDir(projectId);
        currentSourceDir = sourceRoot;
        status = null;
        promptInput = findViewById(R.id.promptInput);
        projectContent = findViewById(R.id.projectContent);
        fileBrowserPanel = findViewById(R.id.fileBrowserPanel);
        fileDrawerScrim = findViewById(R.id.fileDrawerScrim);
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
        findViewById(R.id.sendButton).setOnClickListener(v -> {
            hideKeyboard();
            generatePlan();
        });
        fileDrawerScrim.setOnClickListener(v -> closeFileBrowser());
        findViewById(R.id.closeFilesButton).setOnClickListener(v -> closeFileBrowser());
        findViewById(R.id.executePlanButton).setOnClickListener(v -> executePlan());
        findViewById(R.id.buildButton).setOnClickListener(v -> buildLatest());
        findViewById(R.id.repairButton).setOnClickListener(v -> repairLatest());
        findViewById(R.id.installButton).setOnClickListener(v -> installLatest());
        buildServer = new LocalBuildServer(repository, (p, j) -> runOnUiThread(() -> {
            refresh();
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
        if (!messages.isEmpty() || shouldShowOperationStatus()) {
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

        List<ProjectTaskRecord> latestTasks = repository.listProjectTasks(projectId);
        boolean tasksChanged = ProjectLiveState.tasksChanged(taskItems, latestTasks);
        if (tasksChanged) {
            taskItems.clear();
            taskItems.addAll(latestTasks);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (tasksChanged) {
            scrollToCurrentTask();
        }

        updateBuildLogPanel();
        updateActionButtons();
        updateKeepScreenOn();
        if (!shouldShowOperationStatus()) {
            operationStatus = "";
            operationStartedAt = 0;
        }
    }

    private void updateStatusSummary(ProjectRecord project) {
        String planStatus = latestPlan == null ? "idle" : latestPlan.status;
        statusSummary = project.packageName + " · " + project.lastBuildStatus + " · " + getString(R.string.plan_status, planStatusText(planStatus)) + (latestJob == null ? "" : " · job #" + latestJob.id);
        if (status != null) {
            status.setText(statusSummary);
        }
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
        if (projectToolbar == null || fileBrowserPanel == null) {
            return;
        }
        android.view.MenuItem item = projectToolbar.getMenu().findItem(R.id.action_source_files);
        if (item != null) {
            item.setTitle(fileBrowserPanel.getVisibility() == View.VISIBLE ? R.string.hide_source_files : R.string.show_source_files);
        }
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
            BuildJobRecord job = buildLogJob(entry);
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
        if (entry.kind == ProjectTimelinePolicy.Kind.TASK_GROUP && !taskItems.isEmpty()) {
            copyText(getString(R.string.plan_tasks), taskGroupCopyText());
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

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) {
            focused = promptInput;
        }
        InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null && focused != null) {
            inputMethodManager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        if (promptInput != null) {
            promptInput.clearFocus();
        }
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

    private String taskGroupCopyText() {
        StringBuilder text = new StringBuilder(getString(R.string.plan_tasks));
        for (ProjectTaskRecord task : taskItems) {
            text.append("\n\n")
                    .append(task.sortOrder + 1)
                    .append(". ")
                    .append(task.title)
                    .append(" [")
                    .append(task.status == null ? "pending" : task.status)
                    .append("]");
            String summary = task.resultSummary == null ? "" : task.resultSummary.trim();
            if (!summary.isEmpty()) {
                text.append("\n").append(summary);
            }
        }
        return text.toString();
    }

    private void buildLatest() {
        if (!hasSourceFiles()) {
            Toast.makeText(this, R.string.generate_source_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (buildServer == null) {
            Toast.makeText(this, R.string.local_server_not_running, Toast.LENGTH_SHORT).show();
            return;
        }
        BuildJobRecord job = repository.createBuildJob(projectId);
        BuildBackend backend = BuildBackendFactory.create(this, repository, buildServer);
        repairSourceBuildJobId = -1;
        operationStartedAt = System.currentTimeMillis();
        setOperationStatus(getString(BuildBackendSettings.EXTERNAL_TERMUX.equals(backend.id()) ? R.string.termux_build_started : R.string.embedded_build_started));
        String logsPath = resetBuildLog(job);
        repository.updateBuildJob(job.id, "building", backend.id() + "_start", logsPath, null, null, 0);
        repository.addMessage(projectId, "assistant", getString(BuildBackendSettings.EXTERNAL_TERMUX.equals(backend.id()) ? R.string.termux_build_started : R.string.embedded_build_started), job.id);
        BuildJobRecord buildJob = repository.getBuildJob(job.id);
        backend.build(buildJob == null ? job : buildJob, (p, j) -> runOnUiThread(this::refresh));
        refresh();
        Toast.makeText(this, BuildBackendSettings.EXTERNAL_TERMUX.equals(backend.id()) ? R.string.termux_build_started : R.string.embedded_build_started, Toast.LENGTH_SHORT).show();
    }

    private boolean hasSourceFiles() {
        return containsSourceFile(sourceRoot);
    }

    private boolean containsSourceFile(File file) {
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
            if (containsSourceFile(child)) {
                return true;
            }
        }
        return false;
    }

    private void repairLatest() {
        latestJob = repository.latestBuildJob(projectId);
        BuildJobRecord failed = repairTargetJob();
        boolean repairable = failed != null && isRepairableFailure(failed);
        if (!ProjectBuildActionPolicy.canRepair(busy, failed, repairable)) {
            int message = failed != null && "failed".equals(failed.status) && !repairable
                    ? R.string.repair_build_not_model_repairable
                    : R.string.repair_build_required;
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            return;
        }
        String logs;
        try {
            logs = FileUtils.readText(new File(failed.logsPath));
            logs = buildFailureContext(logs);
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        repairSourceBuildJobId = failed.id;
        setBusy(true);
        setOperationStatus(getString(R.string.repair_build_started));
        refresh();
        Toast.makeText(this, R.string.repair_build_started, Toast.LENGTH_SHORT).show();
        agentService.repairBuildAsync(projectId, logs, new AgentService.Callback() {
            @Override
            public void onComplete(BuildJobRecord job) {
                runOnUiThread(() -> {
                    setBusy(false);
                    refresh();
                    Toast.makeText(ProjectActivity.this, R.string.repair_build_done, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    refresh();
                    Toast.makeText(ProjectActivity.this, error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
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

    private BuildJobRecord repairTargetJob() {
        return ProjectRepairFlowPolicy.repairTargetJob(latestJob, repository.latestFailedBuildJobWithLog(projectId));
    }

    private BuildJobRecord buildLogJob(ProjectTimelinePolicy.Entry entry) {
        if (adapter != null) {
            BuildJobRecord cached = adapter.cachedBuildLogJob(entry);
            if (cached != null) {
                return cached;
            }
        }
        if (entry == null || entry.sourceIndex < 0 || entry.sourceIndex >= messages.size()) {
            return null;
        }
        Long jobId = messages.get(entry.sourceIndex).linkedBuildJobId;
        return jobId == null ? null : repository.getBuildJob(jobId);
    }

    private void installLatest() {
        BuildJobRecord job = repository.latestBuildJobWithApk(projectId);
        if (job == null || job.apkPath == null) {
            Toast.makeText(this, R.string.no_apk_yet, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            new ApkInstaller(this).install(new File(job.apkPath));
        } catch (Exception error) {
            Toast.makeText(this, getString(R.string.install_failed, error.getMessage()), Toast.LENGTH_LONG).show();
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

    private boolean isRepairableFailure(BuildJobRecord failed) {
        String error = failed.errorSummary == null ? "" : failed.errorSummary;
        BuildFailureClassifier.Result result = BuildFailureClassifier.classify(failed.phase, error);
        return result.repairableByModel;
    }

    private void setBusy(boolean busy) {
        if (busy && !this.busy) {
            operationStartedAt = System.currentTimeMillis();
        }
        this.busy = busy;
        if (!busy) {
            activeTaskStartedAt = 0;
            operationStartedAt = 0;
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
        operationProgress = "";
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

    private String operationStatusWithProgress() {
        if (operationProgress.isEmpty()) {
            return operationStatus;
        }
        if (operationStatus.isEmpty()) {
            return operationProgress;
        }
        return operationStatus + " · " + operationProgress;
    }

    // Called on a worker thread by OpenAiClient while a model response is streaming.
    private void onModelStreamProgress(int answerChars, int reasoningChars) {
        long now = System.currentTimeMillis();
        if (now - lastStreamProgressAt < 300) {
            return;
        }
        lastStreamProgressAt = now;
        runOnUiThread(() -> {
            if (!busy && !autoExecutingPlan) {
                return;
            }
            if (answerChars > 0) {
                operationProgress = getString(R.string.streaming_writing, formatStreamCount(answerChars));
            } else if (reasoningChars > 0) {
                operationProgress = getString(R.string.streaming_thinking, formatStreamCount(reasoningChars));
            } else {
                return;
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
    }

    private String formatStreamCount(int chars) {
        if (chars >= 1000) {
            return (chars / 100) / 10.0 + "k";
        }
        return String.valueOf(chars);
    }

    private boolean shouldShowOperationStatus() {
        return ProjectOperationStatus.shouldShow(operationStatus, busy, autoExecutingPlan, latestJob);
    }

    private String operationElapsedText() {
        long startedAt = operationStartedAt;
        if (startedAt <= 0 && latestJob != null && isRunningJob(latestJob)) {
            startedAt = latestJob.createdAt;
        }
        if (startedAt <= 0 || !(busy || autoExecutingPlan || isRunningJob(latestJob))) {
            return "";
        }
        return getString(R.string.elapsed_time, formatDuration(System.currentTimeMillis() - startedAt));
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
                BuildJobRecord job = adapter == null ? null : adapter.cachedJobForMessage(message);
                if (job == null) {
                    job = repository.getBuildJob(message.linkedBuildJobId);
                }
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
        View repairButton = findViewById(R.id.repairButton);
        View installButton = findViewById(R.id.installButton);
        boolean canExecutePlan = latestPlan != null && "planned".equals(latestPlan.status) && !latestPlan.content.trim().isEmpty();
        boolean hasSourceFiles = hasSourceFiles();
        BuildJobRecord repairTarget = repairTargetJob();
        boolean repairable = repairTarget != null && isRepairableFailure(repairTarget);
        boolean showRepairAction = (busy && repairSourceBuildJobId > 0) ||
                ProjectBuildActionPolicy.primaryAction(repairTarget, repairable) == ProjectBuildActionPolicy.PrimaryAction.REPAIR;
        sendButton.setEnabled(!busy);
        executePlanButton.setEnabled(!busy && canExecutePlan);
        buildButton.setVisibility(showRepairAction ? View.GONE : View.VISIBLE);
        repairButton.setVisibility(showRepairAction ? View.VISIBLE : View.GONE);
        buildButton.setEnabled(ProjectBuildActionPolicy.canBuild(busy, hasSourceFiles));
        repairButton.setEnabled(ProjectBuildActionPolicy.canRepair(busy, repairTarget, repairable));
        if (buildButton instanceof TextView) {
            ((TextView) buildButton).setText(latestJob != null && "failed".equals(latestJob.status) ? R.string.rebuild : R.string.build);
        }
        installButton.setEnabled(!busy && repository.latestBuildJobWithApk(projectId) != null);
    }

    private void scrollMessagesToBottom() {
        if (messageList == null || adapter == null) {
            return;
        }
        messageList.postDelayed(() -> messageList.setSelection(Math.max(adapter.getCount() - 1, 0)), 120);
    }

    private void updateBuildLogPanel() {
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
        private ProjectTimelineSnapshot snapshot = ProjectTimelineSnapshot.empty();

        TimelineAdapter() {
            rebuildSnapshot();
        }

        @Override
        public void notifyDataSetChanged() {
            rebuildSnapshot();
            super.notifyDataSetChanged();
        }

        private void rebuildSnapshot() {
            snapshot = ProjectTimelineSnapshot.create(
                    messages,
                    shouldShowOperationStatus(),
                    latestPlan,
                    taskItems,
                    latestJob,
                    id -> repository == null ? null : repository.getBuildJob(id));
        }

        @Override
        public int getCount() {
            return snapshot.size();
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
            if (entry.kind == ProjectTimelinePolicy.Kind.TASK_GROUP) {
                return taskItems;
            }
            if (entry.kind == ProjectTimelinePolicy.Kind.BUILD_LOG) {
                return cachedBuildLogJob(entry);
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
            if (entry.kind == ProjectTimelinePolicy.Kind.TASK_GROUP) {
                return -2;
            }
            if (entry.kind == ProjectTimelinePolicy.Kind.OPERATION_STATUS) {
                return -1;
            }
            if (entry.kind == ProjectTimelinePolicy.Kind.BUILD_LOG) {
                return -1000 - entry.sourceIndex;
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
            if (entry.kind == ProjectTimelinePolicy.Kind.TASK_GROUP) {
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
            if (entry.kind == ProjectTimelinePolicy.Kind.TASK_GROUP) {
                return bindTaskGroup(convertView, parent);
            }
            if (entry.kind == ProjectTimelinePolicy.Kind.BUILD_LOG) {
                return bindBuildLog(entry, convertView, parent);
            }
            return bindMessage(entry, convertView, parent);
        }

        ProjectTimelinePolicy.Entry entryAt(int position) {
            return snapshot.entryAt(position);
        }

        int positionForTaskIndex(int taskIndex) {
            return snapshot.positionForTaskIndex(taskIndex);
        }

        BuildJobRecord cachedBuildLogJob(ProjectTimelinePolicy.Entry entry) {
            return snapshot.buildLogJob(entry);
        }

        BuildJobRecord cachedJobForMessage(ChatMessage message) {
            return snapshot.jobForMessage(message);
        }

        private View bindOperationStatus(View convertView, ViewGroup parent) {
            View view = convertView == null ? getLayoutInflater().inflate(R.layout.row_operation_status, parent, false) : convertView;
            ((TextView) view.findViewById(R.id.operationStatusContent)).setText(ProjectOperationStatus.displayText(operationStatusWithProgress(), operationElapsedText()));
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

        private View bindTaskGroup(View convertView, ViewGroup parent) {
            View view = convertView == null ? getLayoutInflater().inflate(R.layout.row_project_tasks_card, parent, false) : convertView;
            TextView icon = view.findViewById(R.id.tasksStatusIcon);
            TextView title = view.findViewById(R.id.tasksTitle);
            TextView summary = view.findViewById(R.id.tasksSummary);
            TextView toggle = view.findViewById(R.id.tasksToggle);
            LinearLayout container = view.findViewById(R.id.tasksContainer);

            title.setText(R.string.task_card_title);
            icon.setText(taskGroupIcon());
            summary.setText(taskGroupSummary());
            toggle.setText(tasksCollapsed ? R.string.expand_tasks : R.string.collapse_tasks);
            View.OnClickListener toggleListener = v -> {
                tasksCollapsed = !tasksCollapsed;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            };
            toggle.setOnClickListener(toggleListener);
            view.setOnClickListener(toggleListener);

            container.removeAllViews();
            container.setVisibility(tasksCollapsed ? View.GONE : View.VISIBLE);
            if (!tasksCollapsed) {
                for (int i = 0; i < taskItems.size(); i++) {
                    container.addView(taskRowView(taskItems.get(i), i));
                }
            }
            return view;
        }

        private View taskRowView(ProjectTaskRecord task, int index) {
            LinearLayout row = new LinearLayout(ProjectActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.TOP);
            row.setPadding(0, index == 0 ? 0 : dp(10), 0, 0);

            TextView icon = new TextView(ProjectActivity.this);
            icon.setGravity(android.view.Gravity.CENTER);
            icon.setText(taskIcon(task, index));
            icon.setTextSize(13);
            icon.setTextColor(getResources().getColor(R.color.colorOnPrimaryContainer));
            icon.setBackgroundResource(R.drawable.bg_status);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(28), dp(28));
            row.addView(icon, iconParams);

            LinearLayout body = new LinearLayout(ProjectActivity.this);
            body.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            bodyParams.setMarginStart(dp(10));
            row.addView(body, bodyParams);

            TextView taskTitle = new TextView(ProjectActivity.this);
            taskTitle.setText((task.sortOrder + 1) + ". " + task.title);
            taskTitle.setTextColor(getResources().getColor(R.color.colorOnSurface));
            taskTitle.setTextSize(14);
            taskTitle.setTypeface(taskTitle.getTypeface(), android.graphics.Typeface.BOLD);
            body.addView(taskTitle);

            TextView status = new TextView(ProjectActivity.this);
            status.setText(taskStatusText(task, index));
            status.setTextSize(13);
            body.addView(status);

            String stepsText = taskStepsText(task.instruction);
            if (!stepsText.isEmpty()) {
                TextView steps = new TextView(ProjectActivity.this);
                steps.setText(stepsText);
                steps.setTextSize(13);
                LinearLayout.LayoutParams stepsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                stepsParams.setMargins(0, dp(4), 0, 0);
                body.addView(steps, stepsParams);
            }

            String logText = taskLogText(task, index);
            if (!logText.isEmpty()) {
                TextView log = new TextView(ProjectActivity.this);
                log.setText(logText);
                log.setTextSize(12);
                log.setTypeface(android.graphics.Typeface.MONOSPACE);
                log.setPadding(dp(8), dp(6), dp(8), dp(6));
                log.setBackgroundResource(R.drawable.bg_log_md3);
                log.setTextColor(getResources().getColor(R.color.colorInverseOnSurface));
                log.setOnLongClickListener(v -> {
                    copyText(getString(R.string.copy_log), logText);
                    return true;
                });
                LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                logParams.setMargins(0, dp(6), 0, 0);
                body.addView(log, logParams);
            }
            return row;
        }

        private String taskGroupIcon() {
            if (taskItems.isEmpty()) {
                return "-";
            }
            int failed = 0;
            int running = 0;
            int done = 0;
            for (ProjectTaskRecord task : taskItems) {
                String status = task.status == null ? "pending" : task.status;
                if ("failed".equals(status)) {
                    failed++;
                } else if ("running".equals(status)) {
                    running++;
                } else if ("done".equals(status)) {
                    done++;
                }
            }
            if (failed > 0) {
                return "!";
            }
            if (running > 0 || (autoExecutingPlan && currentTaskIndex() >= 0)) {
                return "...";
            }
            if (done == taskItems.size()) {
                return "✓";
            }
            return String.valueOf(taskItems.size());
        }

        private String taskGroupSummary() {
            int done = 0;
            int failed = 0;
            int runningIndex = -1;
            for (int i = 0; i < taskItems.size(); i++) {
                String status = taskItems.get(i).status == null ? "pending" : taskItems.get(i).status;
                if ("done".equals(status)) {
                    done++;
                } else if ("failed".equals(status)) {
                    failed++;
                } else if ("running".equals(status) || TaskRunningDisplayPolicy.shouldShowPredictedRunning(autoExecutingPlan, i, status, taskItems)) {
                    runningIndex = i;
                }
            }
            if (runningIndex >= 0) {
                return getString(R.string.task_card_summary_running, taskItems.size(), done, failed, runningIndex + 1);
            }
            return getString(R.string.task_card_summary, taskItems.size(), done, failed);
        }

        private String taskIcon(ProjectTaskRecord task, int index) {
            String status = task.status == null ? "pending" : task.status;
            if (TaskRunningDisplayPolicy.shouldShowPredictedRunning(autoExecutingPlan, index, status, taskItems)) {
                return "...";
            }
            if ("done".equals(status)) {
                return "✓";
            }
            if ("running".equals(status)) {
                return "...";
            }
            if ("failed".equals(status)) {
                return "!";
            }
            return String.valueOf(task.sortOrder + 1);
        }

        private String taskStatusText(ProjectTaskRecord task, int index) {
            String status = task.status == null ? "pending" : task.status;
            if (TaskRunningDisplayPolicy.shouldShowPredictedRunning(autoExecutingPlan, index, status, taskItems)) {
                long startedAt = activeTaskStartedAt > 0 ? activeTaskStartedAt : System.currentTimeMillis();
                return getString(R.string.task_running) + " · " + getString(R.string.elapsed_time, formatDuration(System.currentTimeMillis() - startedAt));
            }
            if ("done".equals(status)) {
                return getString(R.string.task_done) + taskDurationSuffix(task);
            }
            if ("running".equals(status)) {
                return getString(R.string.task_running) + taskDurationSuffix(task);
            }
            if ("failed".equals(status)) {
                return getString(R.string.task_failed) + taskDurationSuffix(task);
            }
            return getString(R.string.task_pending);
        }

        private String taskLogText(ProjectTaskRecord task, int index) {
            String status = task.status == null ? "pending" : task.status;
            if (TaskRunningDisplayPolicy.shouldShowPredictedRunning(autoExecutingPlan, index, status, taskItems)) {
                return getString(R.string.task_running_log);
            }
            String summary = task.resultSummary == null ? "" : task.resultSummary.trim();
            if ("running".equals(status) && summary.isEmpty()) {
                return getString(R.string.task_running_log);
            }
            return summary;
        }

        private String messageTimeText(ChatMessage message) {
            String time = timeFormat.format(new Date(message.createdAt));
            if (message.linkedBuildJobId == null) {
                return time;
            }
            BuildJobRecord job = cachedJobForMessage(message);
            if (job == null) {
                return time;
            }
            long end = isRunningJob(job) ? System.currentTimeMillis() : Math.max(job.updatedAt, job.createdAt);
            return time + " · " + getString(R.string.elapsed_time, formatDuration(Math.max(0, end - job.createdAt)));
        }

        private View bindBuildLog(ProjectTimelinePolicy.Entry entry, View convertView, ViewGroup parent) {
            View view = convertView == null ? getLayoutInflater().inflate(R.layout.row_build_log, parent, false) : convertView;
            BuildJobRecord job = cachedBuildLogJob(entry);
            boolean running = job != null && ("building".equals(job.status) || "queued".equals(job.status));
            ProgressBar progress = view.findViewById(R.id.buildProgress);
            TextView title = view.findViewById(R.id.buildLogTitle);
            TextView content = view.findViewById(R.id.buildLogContent);
            View copyButton = view.findViewById(R.id.buildLogCopyButton);
            TextView toggleButton = view.findViewById(R.id.buildLogToggleButton);
            boolean expanded = job != null && expandedBuildLogJobIds.contains(job.id);
            boolean showContent = ProjectBuildLogExpansionPolicy.shouldShowContent(job, expanded);
            boolean showToggle = ProjectBuildLogExpansionPolicy.shouldShowToggle(job);
            title.setText(buildLogTitleText(job));
            progress.setVisibility(running ? View.VISIBLE : View.GONE);
            content.setText(showContent ? (job == null || job.logsPath == null ? getString(R.string.no_build_log) : readBuildLogPreview(job)) : "");
            content.setVisibility(showContent ? View.VISIBLE : View.GONE);
            copyButton.setEnabled(job != null && job.logsPath != null);
            copyButton.setOnClickListener(v -> copyText(getString(R.string.build_log), job == null ? "" : readBuildLogPreview(job)));
            toggleButton.setVisibility(showToggle ? View.VISIBLE : View.GONE);
            toggleButton.setText(expanded ? R.string.collapse_log : R.string.expand_log);
            toggleButton.setOnClickListener(v -> {
                if (job == null) {
                    return;
                }
                if (expandedBuildLogJobIds.contains(job.id)) {
                    expandedBuildLogJobIds.remove(job.id);
                } else {
                    expandedBuildLogJobIds.add(job.id);
                }
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
            return view;
        }

        private int buildLogTitle(BuildJobRecord job) {
            ProjectBuildLogTitlePolicy.Title title = ProjectBuildLogTitlePolicy.titleFor(job);
            if (title == ProjectBuildLogTitlePolicy.Title.BUILD_RUNNING) {
                return R.string.build_log_running;
            }
            if (title == ProjectBuildLogTitlePolicy.Title.BUILD_SUCCESS) {
                return R.string.build_log_success;
            }
            if (title == ProjectBuildLogTitlePolicy.Title.BUILD_FAILED) {
                return R.string.build_log_failed;
            }
            if (title == ProjectBuildLogTitlePolicy.Title.REPAIR_RECORD) {
                return R.string.repair_record;
            }
            return R.string.build_log;
        }

        private String buildLogTitleText(BuildJobRecord job) {
            String title = getString(buildLogTitle(job));
            if (job == null) {
                return title;
            }
            long end = isRunningJob(job) ? System.currentTimeMillis() : Math.max(job.updatedAt, job.createdAt);
            long elapsed = Math.max(0, end - job.createdAt);
            if (job.createdAt <= 0 || elapsed <= 0) {
                return title;
            }
            return title + " · " + getString(R.string.elapsed_time, formatDuration(elapsed));
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
        String missingFieldHints = BuildLogContextExtractor.missingFieldHints(logs);
        if (!missingFieldHints.isEmpty()) {
            appendSnippet(result, "Java API consistency hints", missingFieldHints, 0, missingFieldHints.length());
        }
        String javaDiagnostics = BuildLogContextExtractor.javaCompileDiagnostics(logs, 9000);
        if (!javaDiagnostics.isEmpty()) {
            appendSnippet(result, "Java compile diagnostics", javaDiagnostics, 0, javaDiagnostics.length());
        }
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
                indexOfAny(logs, ".java:", "error: cannot find symbol", "has private access", "cannot be applied to given types", "actual and formal argument lists differ"),
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
